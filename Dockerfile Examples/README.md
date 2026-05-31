# Dockerfile Standards — DevSecOps Guide

## Overview

This document explains every Dockerfile we use across all services — what each instruction does, why it is written that way, and how it fits into our pipeline security requirements. Read this before writing or modifying any Dockerfile.

---

## Standards We Follow

These Dockerfiles follow the [Sysdig Dockerfile best practices](https://sysdig.com/blog/dockerfile-best-practices/):
multi-stage builds, minimal pinned base images (no `:latest`), a least-privilege
**numeric** non-root user, `COPY --chown` (not `RUN chown`), `COPY` not `ADD`, a
`.dockerignore` to keep secrets and bloat out of the build context, no secrets in
`ENV`, and image scanning (Trivy) in the pipeline. The OPA policy below enforces
the subset of these that can be checked automatically.

## Why These Rules Exist

Our pipeline runs OPA Conftest on every Dockerfile before building. If your Dockerfile violates any of the following rules, the build fails immediately and nothing gets deployed.

| Rule | Reason |
|---|---|
| No `:latest` tag on base images | Latest changes unpredictably. A build that works today may break tomorrow if the upstream image updates. Pinned versions give reproducible, auditable builds. |
| Non-root USER required | If a process inside a container is exploited, running as root gives the attacker root access to the container filesystem and potentially the host. Non-root limits the blast radius. |
| No secrets in ENV variables | Environment variables are visible in `docker inspect`, container logs, and Kubernetes pod specs. Secrets must come from Kubernetes Secrets or a vault, not baked into the image. |
| COPY not ADD | ADD has hidden behaviour — it auto-extracts tar files and can fetch remote URLs. COPY does exactly one thing: copy files. Predictable and auditable. |
| No `apt-get upgrade` or `apk upgrade` | Package upgrades during build make the image non-reproducible. If a vulnerability exists in the base image, fix it by updating to a newer pinned base image tag, not by running upgrade at build time. |
| No `curl \| bash` | Piping curl output directly to bash is a supply chain attack vector. The script could change between the time you audit it and the time it runs. |
| No sudo | Containers should not need to escalate privileges. If a step needs root, it should happen before the USER instruction, not at runtime. |

---

## How Our Pipeline Uses Dockerfiles

```
Developer pushes code
        ↓
Stage 7 — Vulnerability Scan - Docker (BEFORE build)
  ├── Trivy scans the FROM base image for CVEs
  ├── OPA enforces Dockerfile security rules   ← fails here if Dockerfile is wrong
  └── Gitleaks scans for hardcoded secrets
        ↓
Stage 8 — Docker Build and Push
  ├── docker build (uses your Dockerfile)
  ├── docker push to Harbor
  └── local image kept for scanning
        ↓
Stage 9 — Vulnerability Scan - Application Image (AFTER build)
  ├── Trivy scans ALL layers of the built image
  ├── OPA scans your k8s/ manifest files
  └── local image removed after scan
```

---

## Base Image Selection Rationale

All our base images use Alpine Linux variants. Here is why:

**Alpine vs Debian/Ubuntu:**
- Alpine is ~5MB, Debian is ~120MB. Smaller image = fewer packages = fewer CVEs.
- Alpine uses musl libc instead of glibc. This means some packages behave differently, but for our stack (JVM, Node, Python, Go, nginx) Alpine works perfectly.
- Trivy finds significantly fewer vulnerabilities in Alpine-based images.

**JRE vs JDK for Java:**
- The JDK includes the compiler (`javac`), debugging tools (`jdb`), and profiling tools. None of these are needed at runtime.
- JRE is the runtime only. Smaller image, smaller attack surface.
- Our Maven build stage compiles the code. By the time it gets into Docker, it is already a JAR — only the JRE is needed to run it.

**Why `eclipse-temurin` for Java:**
- Eclipse Temurin is the official build of OpenJDK from the Adoptium project.
- It is the most widely used, best maintained, and most frequently patched OpenJDK distribution.
- The Alpine variant (`eclipse-temurin:21-jre-alpine`) is small (~180MB) and well-maintained.

**Why `nginxinc/nginx-unprivileged` for frontend:**
- The standard `nginx:alpine` runs the master process as root to bind to port 80, then drops to a worker user.
- `nginx-unprivileged` is the official nginx image designed to run entirely without root. It listens on port 8080 instead.
- This is the correct approach — not trying to work around root with `chmod` tricks on a root-based image.

---

## Understanding Multi-Stage Builds

Multi-stage builds use multiple `FROM` instructions in one Dockerfile. Each stage is a separate environment. The final image only contains what is explicitly copied from earlier stages — everything else is discarded.

```
Stage 1 (builder):   Full SDK + source code + build tools  →  ~700MB
                              ↓  COPY --from=builder /app/target/*.jar
Stage 2 (runtime):   JRE + compiled JAR only               →  ~180MB
```

We use multi-stage for: React/Next.js, Go, and .NET.

We do NOT use multi-stage for Java Spring Boot because the JAR is already compiled by Maven in the Jenkins build stage before Docker runs. The Dockerfile only needs to copy the pre-built JAR.

---

## The Non-Root Pattern Explained

Every Dockerfile follows this pattern for running as non-root:

```dockerfile
# 1. Create the group and user with an explicit NUMERIC UID/GID
#    (as root — this is fine, it happens at build time)
RUN addgroup -g 10001 -S appgroup && adduser -u 10001 -S appuser -G appgroup

# 2. Copy your application files AND set ownership in one step (--chown)
COPY --chown=appuser:appgroup target/*.jar app.jar

# 3. Switch to non-root using the NUMERIC UID for everything that follows
USER 10001

# 4. All runtime commands (EXPOSE, HEALTHCHECK, CMD, ENTRYPOINT)
#    now run as appuser, not root
```

The `-S` flags create a *system* user/group (no login shell, no home directory,
no password) — more restrictive than a regular user, which is what we want for a
service account.

**Why a NUMERIC UID in `USER` (Sysdig best practice):** Kubernetes
`securityContext.runAsNonRoot: true` can only *verify* that a container is
non-root when the image declares a numeric UID. If you write `USER appuser`,
Kubernetes cannot resolve the name to a UID at admission time and the check is
unreliable. `USER 10001` is unambiguous and provably non-zero.

**Why `COPY --chown` instead of a separate `RUN chown -R`:** a `RUN chown -R`
duplicates every copied file into a new image layer (doubling the app's size on
disk). `COPY --chown` sets ownership in the same layer — smaller image, one
fewer layer.

---

## The .dockerignore File (do not skip this)

Every Dockerfile here uses `COPY . .` at some point. Without a `.dockerignore` in
your project root, that copies your **entire repository** into the build context
and image — including `.git/` history, local `.env` secrets, and `node_modules`.
This bloats the image, slows the build, and can **bake secrets into a layer** that
ships to Harbor.

A ready-made template is provided: **`dockerignore.example`**. Copy it into your
project root and rename it:

```bash
cp dockerignore.example /path/to/your/project/.dockerignore
```

Then adjust per project. One important exception: the **Spring Boot** image needs
the Maven output, so do **not** ignore `target/` for Java services (Jenkins builds
`target/*.jar` on the host and the Dockerfile copies it).

---

---

## Dockerfile Reference — All Languages

---

### Java Spring Boot (Maven)

**File:** `Dockerfile.java-springboot`
**Used by:** TCE, UAA Service, API Gateway, Customer Service, Config Server, Case Management, SoftID API, NidaAPI, Soft-guarantee-api, Branding Service

**How the build works:**
1. Jenkins runs `mvn clean package -DskipTests=true -B`
2. Maven outputs `target/branding-service-1.0.0.jar`
3. Docker runs and finds that JAR via `COPY target/*.jar app.jar`
4. The JAR is a Spring Boot fat JAR — it contains the app + all dependencies + embedded Tomcat

**Key decisions:**

`FROM eclipse-temurin:21-jre-alpine`
JRE not JDK. Alpine for small size. 21 matches the Java version in your `pom.xml` and Jenkins tool configuration. Pinned to the minor version tag, not `:latest`.

`RUN apk add --no-cache tzdata && ... && apk del tzdata`
Installs tzdata to copy the timezone file, then removes it. After this, the timezone is set but tzdata is not in the final image. The `--no-cache` flag prevents Alpine from caching the package index, saving space.

`COPY target/*.jar app.jar`
The wildcard `*.jar` picks up whatever the Maven build produces regardless of the version number in the filename. Renamed to `app.jar` for a consistent, version-independent name.

`-XX:+UseContainerSupport`
Tells the JVM to respect container CPU and memory limits set by Kubernetes. Without this, the JVM reads the host machine's memory and sets heap size based on that, which causes the container to get OOMKilled.

`-XX:MaxRAMPercentage=75.0`
Use 75% of the container's memory limit for heap. Leaves 25% for the JVM's non-heap memory (metaspace, thread stacks, etc.) and the OS.

`-Djava.security.egd=file:/dev/./urandom`
Java's SecureRandom can block waiting for entropy from `/dev/random`. This flag redirects it to `/dev/urandom` which is non-blocking. Significantly speeds up startup in containers.

`-Dspring.profiles.active=prod`
Activates the `application-prod.yml` profile in Spring Boot. Your production configuration (database URLs, etc.) should be in this profile, loaded from Kubernetes ConfigMaps and Secrets.

`HEALTHCHECK CMD wget -qO- http://localhost:8080/actuator/health`
Spring Boot Actuator exposes `/actuator/health`. Kubernetes uses this for liveness and readiness probes. Requires `spring-boot-starter-actuator` in `pom.xml`.

---

### React / Next.js / Vite (Frontend)

**File:** `Dockerfile.react-nginx`
**nginx config:** `nginx.conf`
**Used by:** SoftAML Portal, CBS, SoftPaperless Portal, SoftID web-verifier

**How the build works:**
1. Jenkins runs `npm ci` then `npm run build`
2. Vite/CRA outputs `dist/` or `build/`
3. Docker Stage 1 (builder) runs the same `npm ci` + `npm run build`
4. Docker Stage 2 copies only `dist/` into an nginx image
5. Result: an image containing only nginx + static HTML/CSS/JS files. No Node.js.

**Why two stages:**
The `node_modules` directory used during build can be 200-500MB. None of it is needed at runtime — nginx just serves static files. Multi-stage drops it entirely.

**Key decisions:**

`FROM nginxinc/nginx-unprivileged:1.27-alpine`
The standard `nginx:alpine` requires root to bind to port 80. `nginx-unprivileged` is the official nginx image that runs entirely as a non-root user on port 8080. This is the correct solution — not patching a root-based image.

`listen 8080` in nginx.conf
Non-root processes cannot bind to ports below 1024. Port 8080 is used throughout. Your Kubernetes Service should target port 8080.

`try_files $uri $uri/ /index.html`
SPA routing fallback. Without this, refreshing on `/dashboard` returns 404 because nginx looks for a file called `dashboard` which does not exist. This rule says: try the exact path, try it as a directory, fall back to `index.html` and let React Router handle it.

`location /healthz`
A lightweight health endpoint that returns 200 without hitting the React app. Used by Dockerfile HEALTHCHECK and Kubernetes probes.

`expires 1y` for static assets
Vite and CRA generate filenames with content hashes (e.g. `main.3f7a9c.js`). When the content changes, the filename changes, so the browser always gets fresh files. For unchanged files, the browser can cache for a year — reduces server load and speeds up repeat visits.

Security headers in nginx.conf:
- `X-Frame-Options SAMEORIGIN` — prevents clickjacking (your page cannot be embedded in an iframe on another domain)
- `X-Content-Type-Options nosniff` — browser will not try to guess content type, only use what the server declares
- `X-XSS-Protection` — enables browser's built-in XSS filter (legacy but harmless)
- `server_tokens off` — hides the nginx version from response headers

---

### Python (FastAPI / Flask)

**File:** `Dockerfile.python`
**Used by:** PEP Sanctions Connector

**How the build works:**
1. Jenkins runs `pip install` (via buildArtifact — Python auto-detected from `requirements.txt`)
2. Docker copies `requirements.txt`, installs dependencies, then copies source code

**Key decisions:**

`FROM python:3.12-alpine`
Alpine-based Python image. Significantly smaller than `python:3.12` (Debian-based).
Note: Some Python packages with C extensions may fail to compile on Alpine due to musl libc. If you encounter build errors, switch to `python:3.12-slim` (Debian slim).

`COPY requirements.txt . && RUN pip install ... && COPY . .`
Requirements are copied and installed BEFORE the source code. This is intentional. Docker caches each layer. If you copy all files first, any code change invalidates the pip install layer and forces a full reinstall. This order means `pip install` only re-runs when `requirements.txt` changes.

`pip install --no-cache-dir`
Prevents pip from storing the download cache inside the image. Saves space.

`CMD ["uvicorn", "app.main:app", "--workers", "2"]`
For Flask use: `CMD ["gunicorn", "app:app", "--workers", "2", "--bind", "0.0.0.0:8000"]`
Workers should be set based on your container's CPU limit. A common formula is `(2 × CPU cores) + 1`.

---

### Go

**File:** `Dockerfile.go`

**How the build works:**
1. Stage 1 uses the full Go toolchain to compile a static binary
2. Stage 2 copies only that binary into a minimal Alpine image
3. Result: image is ~15MB total

**Key decisions:**

`CGO_ENABLED=0`
Disables CGO (C Go interface). Produces a statically linked binary with no external C library dependencies. The binary runs on any Linux system including Alpine's musl libc environment.

`-ldflags="-w -s"`
`-w` strips the DWARF debug information. `-s` strips the symbol table. Together they reduce the binary size by 30-40% with no effect on production functionality.

`FROM alpine:3.19` for runtime
Go produces a static binary. The runtime image just needs an OS to run it. Alpine at ~5MB is sufficient. We add `ca-certificates` so HTTPS calls to external APIs work, and `tzdata` for timezone support.

`COPY --from=builder /app/app .`
Only the compiled binary is copied. No source code, no Go toolchain, no intermediate build files. The final image has only Alpine + your binary.

---

### .NET ASP.NET Core

**File:** `Dockerfile.dotnet`

**How the build works:**
1. Stage 1 uses the full .NET SDK to restore packages and publish
2. Stage 2 uses only the ASP.NET runtime — no SDK, no compiler
3. `dotnet publish` produces a self-contained output directory

**Key decisions:**

`FROM mcr.microsoft.com/dotnet/sdk:8.0-alpine AS builder`
Microsoft's official Alpine-based .NET SDK. Used only in the build stage — not in the final image.

`FROM mcr.microsoft.com/dotnet/aspnet:8.0-alpine`
ASP.NET runtime only. Contains the .NET runtime and ASP.NET Core libraries but not the SDK, compiler, or Roslyn. About 200MB vs 700MB for the full SDK.

`COPY *.csproj ./ && RUN dotnet restore`
Same layer caching principle as Python: restore packages before copying source code so restores only re-run when the project file changes.

`ENV ASPNETCORE_URLS=http://+:8080`
Tells ASP.NET Core to listen on port 8080. Must match the EXPOSE instruction. Using 8080 instead of 80 because non-root cannot bind below port 1024.

`/p:UseAppHost=false`
Prevents generating a native executable wrapper. Results in a smaller publish output. The app is started with `dotnet YourApp.dll` instead.

---

### Node.js Express / NestJS API

**File:** `Dockerfile.nodejs-api`
**Note:** This is for backend Node.js APIs. For React/Next.js frontends use `Dockerfile.react-nginx`.

**Key decisions:**

`ENV NODE_ENV=production`
Setting this before `npm ci` tells npm to skip devDependencies. It also enables production optimisations in Express (disables detailed error messages, enables response caching).

`npm ci --omit=dev --frozen-lockfile`
`--omit=dev` skips devDependencies (Jest, TypeScript compiler, ESLint, etc.). These are only needed during development and testing, not at runtime. `--frozen-lockfile` ensures the exact versions in `package-lock.json` are installed.

`npm cache clean --force`
Removes the npm cache after installing. Saves space in the final image.

`CMD ["node", "src/main.js"]`
Starts the app with `node` directly, not `npm start`. When Kubernetes sends a SIGTERM to stop the container, `node` receives it directly and can shut down gracefully. If you use `npm start`, npm receives the signal but does not always forward it to the child process, causing a forced kill after the timeout.

---

## Common Mistakes and How to Fix Them

**Build fails with "no matching files" for COPY target/*.jar**
Maven has not run yet or ran in a different directory. Ensure the Jenkins pipeline runs `buildArtifact()` before `buildDockerImageAndPush()`. The JAR must exist in `target/` before Docker runs.

**OPA fails with "image uses latest tag"**
Change your FROM line from `FROM eclipse-temurin:latest` to `FROM eclipse-temurin:21-jre-alpine`. Always use a specific version tag.

**OPA fails with "container must not run as root"**
Add a `USER` instruction before `CMD`/`ENTRYPOINT`. Create the user with `addgroup` and `adduser` first, and use the NUMERIC UID in `USER` (e.g. `USER 10001`).

**Kubernetes rejects the pod with "container has runAsNonRoot and image will run as root"**
Your `USER` uses a name, not a number. Kubernetes cannot verify a username is non-root. Change `USER appuser` to `USER 10001` (the numeric UID you created).

**Trivy fails with CRITICAL CVEs in base image**
Update to a newer patch version of your base image. For example, change `eclipse-temurin:21.0.1-jre-alpine` to `eclipse-temurin:21.0.5-jre-alpine`. Check https://hub.docker.com for the latest patch version.

**Frontend app shows 404 on page refresh**
Your `nginx.conf` is missing `try_files $uri $uri/ /index.html`. See the nginx.conf provided with this guide.

**Container exits immediately with "permission denied"**
The `USER` instruction was placed before `chown`. The user cannot read files owned by root. Always `chown` before switching to the non-root `USER`.

**Spring Boot starts but Kubernetes marks pod as Unhealthy**
Add `spring-boot-starter-actuator` to `pom.xml` and ensure the actuator health endpoint is accessible. Check `management.endpoints.web.exposure.include=health` is set in `application.yml`.

---

## Quick Reference — Image Sizes

| Service Type | Base Image | Approximate Final Size |
|---|---|---|
| Java Spring Boot | eclipse-temurin:21-jre-alpine | ~180MB |
| React/Vite (nginx) | nginxinc/nginx-unprivileged:1.27-alpine | ~25MB |
| Python FastAPI | python:3.12-alpine | ~80MB |
| Go | alpine:3.19 | ~15MB |
| .NET ASP.NET Core | mcr.microsoft.com/dotnet/aspnet:8.0-alpine | ~210MB |
| Node.js API | node:20-alpine | ~120MB |

---

## Questions

If your Dockerfile is failing OPA policies or Trivy scans and you are not sure how to fix it, contact the DevOps team. Share the failing stage output from the Jenkins build and we will help you resolve it.

DevOps Team — Softnet