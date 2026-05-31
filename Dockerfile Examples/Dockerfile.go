# =============================================================================
# Production Dockerfile — Go Microservice
# Multi-stage: build stage compiles binary, runtime stage has nothing else
# Runs as a numeric non-root user — satisfies OPA + Kubernetes runAsNonRoot
#
# WHY MULTI-STAGE FOR GO?
#   Go compiles to a single static binary. The final image only needs that
#   binary — no Go toolchain, no source code, no build tools.
#   Build stage: ~300MB (golang image with full toolchain)
#   Final image:  ~10MB (alpine + binary only)
#
# Follows Sysdig Dockerfile best practices: multi-stage, pinned minimal base,
# numeric non-root user, COPY --chown, .dockerignore (see template), no ADD.
# =============================================================================

# ── STAGE 1: BUILD ────────────────────────────────────────────────────────────
FROM golang:1.22-alpine AS builder

# Install git — needed if your go modules are fetched from private git repos
RUN apk add --no-cache git ca-certificates tzdata

WORKDIR /app

# Copy go.mod and go.sum first — better layer caching
# go mod download only re-runs when dependencies change
COPY go.mod go.sum ./
RUN go mod download

# Copy source and build
COPY . .

# CGO_ENABLED=0  → static binary (no C library dependencies)
# GOOS=linux     → target OS
# -ldflags       → strip debug info and symbol table (smaller binary)
RUN CGO_ENABLED=0 GOOS=linux go build \
    -ldflags="-w -s" \
    -o app .

# ── STAGE 2: RUNTIME ─────────────────────────────────────────────────────────
# alpine:3.19 is tiny (~5MB) — only what is needed to run the binary
# Alternative: use 'scratch' for absolute minimum but loses shell + wget
FROM alpine:3.19

ARG GIT_AUTHOR=unknown
ARG GIT_COMMIT=unknown
ARG BUILD_DATE=unknown
ARG VERSION=unknown
ARG APP_TIMEZONE=Africa/Dar_es_Salaam

LABEL org.opencontainers.image.title="Go Microservice" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.revision="${GIT_COMMIT}" \
      org.opencontainers.image.authors="${GIT_AUTHOR}"

# ca-certificates: needed for HTTPS calls to external APIs
# tzdata: needed for timezone support
RUN apk add --no-cache ca-certificates tzdata

ENV TZ=${APP_TIMEZONE}
RUN cp /usr/share/zoneinfo/${APP_TIMEZONE} /etc/localtime && \
    echo "${APP_TIMEZONE}" > /etc/timezone

# Create numeric non-root user
RUN addgroup -g 10001 -S appgroup && adduser -u 10001 -S appuser -G appgroup

WORKDIR /app

# Copy only the compiled binary from the build stage, owned by the non-root user.
# Nothing else — no source, no go toolchain, no build artifacts.
COPY --from=builder --chown=appuser:appgroup /app/app .

USER 10001

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=15s --retries=3 \
    CMD wget -qO- http://localhost:8080/health || exit 1

CMD ["./app"]
