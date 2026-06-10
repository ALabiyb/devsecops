# =============================================================================
# Production Dockerfile — Go Microservice (multi-stage)
# Build stage compiles a static binary; runtime stage has nothing else.
# Final image: ~10 MB (alpine + binary only — no Go toolchain, no source).
#
# Best practices: multi-stage, pinned minimal base, numeric non-root user,
# COPY --chown, layer-cached module download, static binary (-ldflags -w -s).
# =============================================================================

# ── Stage 1: Build ─────────────────────────────────────────────────────────
FROM golang:1.22-alpine AS builder

RUN apk add --no-cache git ca-certificates tzdata

WORKDIR /app

# Cache module download — only re-runs when go.mod/go.sum change
COPY go.mod go.sum ./
RUN go mod download

COPY . .

# CGO_ENABLED=0 → fully static binary (no glibc dependency)
# -ldflags "-w -s" → strip debug info and symbol table (smaller binary)
RUN CGO_ENABLED=0 GOOS=linux go build \
    -ldflags="-w -s" \
    -o app .

# ── Stage 2: Runtime ────────────────────────────────────────────────────────
FROM alpine:3.19

ARG APP_NAME=app
ARG GIT_AUTHOR=unknown
ARG GIT_COMMIT=unknown
ARG BUILD_DATE=unknown
ARG VERSION=unknown
ARG APP_TIMEZONE=Africa/Dar_es_Salaam

LABEL org.opencontainers.image.title="${APP_NAME}" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.revision="${GIT_COMMIT}" \
      org.opencontainers.image.authors="${GIT_AUTHOR}" \
      org.opencontainers.image.vendor="Softnet"

# ca-certificates: needed for outbound HTTPS calls
# tzdata: needed for time.LoadLocation / timezone support
RUN apk add --no-cache ca-certificates tzdata

ENV TZ=${APP_TIMEZONE}
RUN cp /usr/share/zoneinfo/${APP_TIMEZONE} /etc/localtime && \
    echo "${APP_TIMEZONE}" > /etc/timezone

# Numeric UID — required by OPA policy and Kubernetes runAsNonRoot validation
RUN addgroup -g 10001 -S appgroup && adduser -u 10001 -S appuser -G appgroup

WORKDIR /app

COPY --from=builder --chown=appuser:appgroup /app/app .

USER 10001

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=15s --retries=3 \
    CMD wget -qO- http://localhost:8080/health || exit 1

CMD ["./app"]
