// Package mock provides a fake Collector that returns realistic dummy data.
// It is used when:
//   - You run locally with no kubeconfig (pure UI testing)
//   - You pass the -mock flag at startup
//
// The mock simulates real-world chaos: most services are healthy, but a few
// are in CrashLoop or Pending so you can see all three health states in the UI.
// It also randomly flips one service every 2 minutes so email alerts fire.
package mock

import (
	"math/rand"
	"time"

	"github.com/yourorg/k8s-dashboard/internal/collector"
)

// unstableServices is the pool of services that randomly break/recover.
// This makes the dashboard feel alive during a demo.
var unstableServices = []string{"ingestion-worker", "ml-pipeline", "oauth-proxy"}

// seed the random generator once at startup
var rng = rand.New(rand.NewSource(time.Now().UnixNano()))

// products defines the mock namespace/service structure.
// Edit this to match your real product names for a more realistic preview.
var products = []struct {
	namespace string
	deploys   []string // Deployments (app services)
	sts       []string // StatefulSets (databases, kafka, redis, etc.)
}{
	{
		namespace: "ecommerce",
		deploys:   []string{"api-gateway", "order-service", "payment-service", "cart-service", "notification-worker"},
		sts:       []string{"postgres", "redis", "kafka"},
	},
	{
		namespace: "analytics",
		deploys:   []string{"api-gateway", "ingestion-worker", "report-service", "ml-pipeline", "scheduler"},
		sts:       []string{"clickhouse", "minio", "kafka"},
	},
	{
		namespace: "auth",
		deploys:   []string{"auth-service", "token-service", "oauth-proxy", "session-manager"},
		sts:       []string{"postgres", "redis"},
	},
	{
		namespace: "logistics",
		deploys:   []string{"tracking-service", "route-optimizer", "notification-worker", "webhook-handler"},
		sts:       []string{"postgres", "kafka"},
	},
	{
		namespace: "payments",
		deploys:   []string{"payment-gateway", "fraud-detector", "invoice-service", "reconciler"},
		sts:       []string{"postgres", "redis"},
	},
	{
		namespace: "notifications",
		deploys:   []string{"email-worker", "sms-worker", "push-worker", "template-service"},
		sts:       []string{"redis", "postgres"},
	},
}

// Collector is the mock implementation — same interface as the real collector.
type Collector struct {
	// brokenService is randomly picked each call to simulate flapping
	brokenService string
}

// New creates a mock Collector with one randomly broken service.
func New() *Collector {
	return &Collector{
		brokenService: unstableServices[rng.Intn(len(unstableServices))],
	}
}

// CollectAll returns fake namespace snapshots that look like real k8s data.
// Call this in place of the real collector.CollectAll during mock mode.
func (c *Collector) CollectAll() []collector.NamespaceSnapshot {
	// Randomly rotate the broken service every call (simulates flapping)
	// In a real demo this makes one product occasionally go amber/red
	if rng.Float32() < 0.15 { // 15% chance to flip on each poll
		c.brokenService = unstableServices[rng.Intn(len(unstableServices))]
	}

	var snapshots []collector.NamespaceSnapshot

	for _, p := range products {
		snap := collector.NamespaceSnapshot{Namespace: p.namespace}

		// Add Deployments
		for _, name := range p.deploys {
			snap.Services = append(snap.Services, c.makeService(name, "Deployment"))
		}
		// Add StatefulSets
		for _, name := range p.sts {
			snap.Services = append(snap.Services, c.makeService(name, "StatefulSet"))
		}

		snapshots = append(snapshots, snap)
	}

	return snapshots
}

// makeService builds a realistic ServiceState.
// Most services are healthy; the brokenService gets a CrashLoopBackOff.
func (c *Collector) makeService(name, kind string) collector.ServiceState {
	// The one "broken" service gets an unhealthy state
	if name == c.brokenService {
		// Randomly pick between different failure modes so it looks realistic
		failures := []struct{ status, reason string }{
			{"Unhealthy", "CrashLoopBackOff"},
			{"Unhealthy", "OOMKilled"},
			{"Degraded", "Pending"},
		}
		f := failures[rng.Intn(len(failures))]
		return collector.ServiceState{
			Name:      name,
			Kind:      kind,
			Namespace: "",
			Status:    f.status,
			Reason:    f.reason,
			Ready:     0,
			Desired:   2,
		}
	}

	// Everything else is healthy
	return collector.ServiceState{
		Name:      name,
		Kind:      kind,
		Namespace: "",
		Status:    "Healthy",
		Reason:    "2/2 pods ready",
		Ready:     2,
		Desired:   2,
	}
}
