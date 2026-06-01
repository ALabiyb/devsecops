// Package api exposes the HTTP endpoints that the frontend dashboard consumes.
// It also owns the background polling loop that keeps data fresh.
//
// Endpoints:
//
//	GET /              → login-protected HTML dashboard
//	GET /login         → login page  (POST processes credentials)
//	GET /logout        → clears session and redirects to /login
//	GET /api/summary   → current cluster health as JSON  (auth required)
//	GET /api/mode      → mock/real flag for the UI banner  (auth required)
//	GET /api/me        → current user's username and role  (auth required)
//	GET /api/export    → download health snapshot as JSON or CSV  (admin only)
//	                     ?format=json (default) or ?format=csv
//
// Auth: HMAC-signed cookie session. Credentials via env vars:
//
//	ADMIN_USER / ADMIN_PASS   (default: admin / admin)
//	VIEWER_USER / VIEWER_PASS (default: viewer / viewer)
//	DASHBOARD_SECRET          (default: random — sessions lost on restart)
package api

import (
	"context"
	"encoding/csv"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"sync"
	"time"

	"github.com/yourorg/k8s-dashboard/config"
	"github.com/yourorg/k8s-dashboard/internal/aggregator"
	"github.com/yourorg/k8s-dashboard/internal/auth"
	"github.com/yourorg/k8s-dashboard/internal/collector"
	"github.com/yourorg/k8s-dashboard/internal/mock"
	"github.com/yourorg/k8s-dashboard/internal/notifier"
)

// Server holds everything the HTTP handlers need.
type Server struct {
	cfg        *config.Config
	collector  *collector.Collector // nil in mock mode
	mockCol    *mock.Collector      // nil in real mode
	aggregator *aggregator.Aggregator
	notifier   *notifier.Notifier
	mockMode   bool

	users  []auth.User
	secret string

	// mu protects the cached summary so the poll goroutine and HTTP handlers
	// can access it concurrently without data races.
	mu      sync.RWMutex
	summary aggregator.Summary
}

// New wires up all the dependencies and returns a ready-to-run Server.
//
// If useMock is true (or if the real k8s client fails to initialise),
// the server automatically falls back to mock mode — no cluster needed.
func New(cfg *config.Config, useMock bool) (*Server, error) {
	agg := aggregator.New(cfg.Thresholds)
	not := notifier.New(cfg.Notifications.Email)

	// ── Auth setup ───────────────────────────────────────────────────────
	secret := getenv("DASHBOARD_SECRET", "")
	if secret == "" {
		secret = auth.GenerateSecret()
		fmt.Println("[auth] WARNING: DASHBOARD_SECRET not set — sessions will not survive restarts")
	}

	adminUser := getenv("ADMIN_USER", "admin")
	adminPass := getenv("ADMIN_PASS", "admin")
	viewerUser := getenv("VIEWER_USER", "viewer")
	viewerPass := getenv("VIEWER_PASS", "viewer")

	if adminPass == "admin" || viewerPass == "viewer" {
		fmt.Println("[auth] WARNING: default credentials in use — set ADMIN_PASS / VIEWER_PASS env vars")
	}

	users := []auth.User{
		{Username: adminUser, Password: adminPass, Role: auth.RoleAdmin},
		{Username: viewerUser, Password: viewerPass, Role: auth.RoleViewer},
	}
	fmt.Printf("[auth] accounts: admin=%q viewer=%q\n", adminUser, viewerUser)

	// ── Mock mode ────────────────────────────────────────────────────────
	if useMock {
		fmt.Println("[server] ⚠  MOCK MODE — using fake data, no k8s cluster needed")
		return &Server{
			cfg:        cfg,
			mockCol:    mock.New(),
			aggregator: agg,
			notifier:   not,
			mockMode:   true,
			users:      users,
			secret:     secret,
		}, nil
	}

	// ── Real mode — try to connect to k8s ────────────────────────────────
	col, err := collector.New()
	if err != nil {
		fmt.Printf("[server] ⚠  k8s unavailable (%v) — falling back to MOCK MODE\n", err)
		return &Server{
			cfg:        cfg,
			mockCol:    mock.New(),
			aggregator: agg,
			notifier:   not,
			mockMode:   true,
			users:      users,
			secret:     secret,
		}, nil
	}

	fmt.Println("[server] connected to Kubernetes cluster ✓")
	return &Server{
		cfg:        cfg,
		collector:  col,
		aggregator: agg,
		notifier:   not,
		mockMode:   false,
		users:      users,
		secret:     secret,
	}, nil
}

// Start begins the background poll loop and starts listening on the configured port.
func (s *Server) Start() error {
	s.poll()
	go s.pollLoop()

	mux := http.NewServeMux()

	// Public routes (handled before the auth middleware short-circuits)
	mux.HandleFunc("/login", auth.HandleLogin(s.users, s.secret))
	mux.HandleFunc("/logout", auth.HandleLogout)

	// Protected routes (all roles)
	mux.HandleFunc("/api/summary", s.handleSummary)
	mux.HandleFunc("/api/mode", s.handleMode)
	mux.HandleFunc("/api/me", s.handleMe)
	// Admin-only routes
	mux.HandleFunc("/api/export", auth.RequireAdmin(s.handleExport))
	mux.HandleFunc("/favicon.svg", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "image/svg+xml")
		http.ServeFile(w, r, "web/favicon.svg")
	})
	mux.HandleFunc("/", s.handleIndex)

	// Wrap the entire mux with the auth middleware
	handler := auth.Middleware(mux, s.secret)

	addr := fmt.Sprintf(":%d", s.cfg.Server.Port)
	fmt.Printf("[server] listening on http://localhost%s\n", addr)
	return http.ListenAndServe(addr, handler)
}

// pollLoop runs forever, calling poll() on the configured interval.
func (s *Server) pollLoop() {
	ticker := time.NewTicker(s.cfg.Server.PollInterval)
	defer ticker.Stop()
	for range ticker.C {
		s.poll()
	}
}

// poll fetches fresh data (real or mock), aggregates it, and checks for alerts.
func (s *Server) poll() {
	var snapshots []collector.NamespaceSnapshot

	if s.mockMode {
		snapshots = s.mockCol.CollectAll()
	} else {
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		defer cancel()

		var err error
		snapshots, err = s.collector.CollectAll(ctx, s.cfg.ExcludedNS)
		if err != nil {
			fmt.Printf("[poll] error collecting from k8s: %v\n", err)
			return
		}
	}

	summary := s.aggregator.Aggregate(snapshots)

	if !s.mockMode {
		s.notifier.CheckAndNotify(summary)
	}

	s.mu.Lock()
	s.summary = summary
	s.mu.Unlock()

	mode := "real"
	if s.mockMode {
		mode = "mock"
	}
	fmt.Printf("[poll:%s] %d products, %d/%d services healthy\n",
		mode, len(summary.Products), summary.HealthyServices, summary.TotalServices)
}

// handleSummary serves the current health summary as JSON.
func (s *Server) handleSummary(w http.ResponseWriter, r *http.Request) {
	s.mu.RLock()
	summary := s.summary
	s.mu.RUnlock()

	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "max-age=15")

	if err := json.NewEncoder(w).Encode(summary); err != nil {
		http.Error(w, "failed to encode response", http.StatusInternalServerError)
	}
}

// handleMode tells the frontend whether we're in mock or real mode.
func (s *Server) handleMode(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]bool{"mock": s.mockMode})
}

// handleMe returns the authenticated user's username and role as JSON.
func (s *Server) handleMe(w http.ResponseWriter, r *http.Request) {
	claims := auth.GetClaims(r)
	if claims == nil {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{
		"username": claims.Username,
		"role":     claims.Role,
	})
}

// handleIndex serves the single-page HTML dashboard.
func (s *Server) handleIndex(w http.ResponseWriter, r *http.Request) {
	http.ServeFile(w, r, "web/index.html")
}

// handleExport downloads the current health snapshot as JSON or CSV.
// Protected by RequireAdmin — viewers receive HTTP 403.
func (s *Server) handleExport(w http.ResponseWriter, r *http.Request) {
	s.mu.RLock()
	summary := s.summary
	s.mu.RUnlock()

	switch r.URL.Query().Get("format") {
	case "csv":
		w.Header().Set("Content-Type", "text/csv; charset=utf-8")
		w.Header().Set("Content-Disposition", `attachment; filename="k8s-health.csv"`)
		cw := csv.NewWriter(w)
		cw.Write([]string{"namespace", "health", "score_pct", "healthy", "total",
			"service", "kind", "status", "reason", "ready", "desired"})
		for _, p := range summary.Products {
			for _, svc := range p.Services {
				cw.Write([]string{
					p.Namespace, string(p.Health),
					fmt.Sprintf("%d", p.ScorePercent),
					fmt.Sprintf("%d", p.HealthyCount),
					fmt.Sprintf("%d", p.TotalCount),
					svc.Name, svc.Kind, svc.Status, svc.Reason,
					fmt.Sprintf("%d", svc.Ready),
					fmt.Sprintf("%d", svc.Desired),
				})
			}
		}
		cw.Flush()
	default:
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Content-Disposition", `attachment; filename="k8s-health.json"`)
		enc := json.NewEncoder(w)
		enc.SetIndent("", "  ")
		enc.Encode(summary)
	}
}

// getenv returns the value of the environment variable key, or def if unset/empty.
func getenv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}