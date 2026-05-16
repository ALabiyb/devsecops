# Jenkins DevSecOps Shared Library

A production-grade Jenkins shared library that automates the complete DevSecOps lifecycle — from multi-language builds and security scanning to GitOps-driven Kubernetes deployments.

---

## 📚 Table of Contents

1. [Overview](#overview)
2. [Pipeline Stages](#pipeline-stages)
3. [Shared Library Reference](#shared-library-reference)
4. [Quick Start](#quick-start)
5. [Jenkins One-Time Setup](#jenkins-one-time-setup)
6. [Kubernetes Manifest Management](#kubernetes-manifest-management)
7. [Dependency Scanning](#dependency-scanning)
8. [Disk Management](#disk-management)
9. [Configuration Reference](#configuration-reference)
10. [Security & Compliance](#security--compliance)
11. [Best Practices](#best-practices)

---

## Overview

This library provides a standardized DevSecOps pipeline template where **only the `environment{}` block changes per project** — stages, scanning logic, and notifications all live in the shared library.

- **Multi-language builds**: Maven, NPM/Node/Next.js, Go, Gradle, .NET — auto-detected or explicit.
- **Shift-left security**: SAST (SonarQube), SCA (OWASP/npm audit/govulncheck), container CVE scanning (Trivy), secrets detection (Gitleaks), and policy enforcement (OPA Conftest).
- **GitOps**: Automated K8s manifest updates with pre-flight verification, pipeline pause on missing files, and OPA scan before push.
- **Disk hygiene**: Docker images removed after push, build cache capped at 2 GB, workspaces cleaned after success.

---

## Pipeline Stages

| # | Stage | What it does |
|---|-------|-------------|
| 1 | **Checkout & Git Info** | Clones source repo, extracts commit SHA and author |
| 2 | **Notify Start** | Sends email with trigger info and build link |
| 3 | **Build Artifact** | Compiles app (`mvn`/`npm ci`/`go build`/etc.) — no `archiveArtifacts` |
| 4 | **Unit Tests** *(optional)* | Uncomment `unitTests()` when tests exist |
| 5 | **SonarQube SAST** | Static analysis — code smells, vulnerabilities, coverage |
| 6 | **Dependency Check** | CVE scan of all dependencies (language-specific tool) |
| 7 | **Vuln Scan — Dockerfile** | Trivy base image + OPA Dockerfile policies + Gitleaks secrets detection |
| 8 | **Docker Build & Push** | Builds image, pushes to Harbor, removes local copy |
| 9 | **Vuln Scan — App Image** | Trivy full image (all layers) + OPA K8s manifest policies |
| 10 | **K8s Manifest Update** | Updates image tag in manifest repo, commits & pushes |
| 11 | **Publish Security Results** | Uploads all scan reports to DefectDojo; sends results email |

---

## Shared Library Reference

### `vars/` functions

| Function | Description |
|:---------|:------------|
| `buildArtifact` | Multi-language build orchestration. Auto-detects tool from project files; supports `buildTool`, `command`, and per-tool overrides. No `archiveArtifacts` — Harbor is the artifact store. |
| `buildDockerImageAndPush` | Builds Docker image with `--build-arg` metadata (git commit, author, version). Pushes to Harbor, then removes both local and registry-tagged images. Caps Docker build cache at 2 GB. |
| `owaspDependencyCheck` | Language-aware CVE scanning: OWASP Maven plugin, `npm audit`, `govulncheck` (Go), OWASP Gradle plugin, `dotnet list package`. `failOnCVSS` controls blocking vs reporting. |
| `vulnScanDocker` | Parallel: Trivy base image CVE scan (HTML report + email) + OPA Conftest Dockerfile policies + Gitleaks hardcoded secrets detection. Runs **before** `buildDockerImageAndPush`. |
| `vulnScanApplicationImage` | Parallel: Trivy full image scan (2 rounds: informational HIGH+CRITICAL, then blocking CRITICAL) + OPA K8s manifest scan. Auto-detects `k8s/` directory. |
| `publishToDefectDojo` | **New.** Uploads all scan reports to DefectDojo at the end of every build. Supports Trivy image, Trivy base image, OWASP, npm audit, Gitleaks, and govulncheck. Skips files that don't exist — safe for all project types. Sends an HTML results email with a direct link to the DefectDojo engagement. |
| `updateK8sManifest` | Clones manifest repo, pre-flight verifies files exist (pauses pipeline + emails team if missing), updates image tags, verifies update, commits & pushes. Never silently succeeds. |
| `k8sManifestScanAndUpdate` | Same as above **plus** OPA Conftest scan of updated manifests before push. Recommended for production. |
| `sonarSast` | SonarQube analysis with optional quality gate wait. `projectKey`/`projectName` default to `IMAGE_NAME`/`PROJECT_NAME`. |
| `checkoutAndGitInfo` | Standardized checkout with repo, branch, and credentials params. |
| `detectBuildTrigger` | Returns human-readable trigger string (SCM poll, manual, timer, etc.) for notifications. |
| `sendStartNotification` | HTML email on pipeline start. |
| `sendSuccessNotification` | HTML email on success with build summary. |
| `sendFailureNotification` | HTML email on failure with failed stage and reason. |
| `sshRemoteDeploy` | SSH-based deployment to remote servers (alternative to GitOps). |
| `unitTests` | Placeholder for per-language unit test execution. |

---

## Quick Start

### 1. Add policy files to your application repo

| File | Purpose |
|:-----|:--------|
| `Dockerfile` | Container definition |
| `opa-docker-security.rego` | Dockerfile policy (no root, no `latest`, no secrets in ENV, etc.) |
| `opa-k8s-security.rego` | K8s manifest policy (non-root, resource limits, no privileged, etc.) |
| `trivy-docker-image-scan.sh` | Trivy base image scan script |

### 2. Copy the Jenkinsfile template

Copy [`examples/Jenkinsfile.example`](examples/Jenkinsfile.example) to your project as `Jenkinsfile`.

**Change only the `environment{}` block** — everything else stays the same across all projects.

```groovy
environment {
    PROJECT_NAME            = 'My Service'
    IMAGE_NAME              = 'my-service'
    HARBOR_PROJECT          = 'softaml'
    REGISTRY_URL            = 'harbor.devops.softnethq.co.tz'
    REGISTRY_CREDENTIALS_ID = 'robot-jenkins'

    NOTIFICATION_EMAIL = 'team@example.com'

    GIT_REPO_URL       = 'http://gitlab.example.com/group/my-service.git'
    GIT_CREDENTIALS_ID = 'lsaid'
    BRANCH_NAME        = 'main'

    K8S_MANIFEST_REPO_URL       = 'http://gitlab.example.com/k8s/my-service.git'
    K8S_MANIFEST_CREDENTIALS_ID = 'lsaid'
    K8S_MANIFEST_BRANCH         = 'main'
    K8S_MANIFEST_PATHS          = '04-deployment.yaml'

    BUILD_TOOL   = 'maven'   // or: npm | go | gradle | dotnet | remove to auto-detect
    APP_TIMEZONE = 'Africa/Dar_es_Salaam'

    // DefectDojo — create one Engagement per service in DefectDojo and paste the ID
    DEFECTDOJO_URL           = 'https://defectdojo.devops.softnethq.co.tz'
    DEFECTDOJO_ENGAGEMENT_ID = '1'   // unique number per service
}
```

---

## Jenkins One-Time Setup

### Plugins *(Manage Jenkins → Plugins)*

| Plugin | Required for |
|:-------|:------------|
| Pipeline, Git, Credentials Binding | Core (usually pre-installed) |
| Email Extension (`emailext`) | All notifications |
| SonarQube Scanner | Stage 5 |
| OWASP Dependency-Check | Stage 6 (Maven/Gradle projects) |
| SSH Agent | `sshRemoteDeploy` |
| Docker Pipeline | Stages 7, 8, 9 |
| Workspace Cleanup | `cleanWs()` in `post{}` |

### Credentials *(Manage Jenkins → Credentials → Global)*

| ID | Type | Used for |
|:---|:-----|:---------|
| `lsaid` | Username/Password | GitLab source and manifest repos |
| `robot-jenkins` | Username/Password | Harbor robot account |
| `nvd-api-key` | Secret text | NVD API key for OWASP scans — [register free](https://nvd.nist.gov/developers/request-an-api-key) |
| `defectdojo-api-token` | Secret text | DefectDojo API token (stage 11) |

### Global Tool Configuration *(Manage Jenkins → Global Tool Configuration)*

| Tool | Name |
|:-----|:-----|
| Maven | `mave-3.9.15` |
| JDK | `jdk21` |
| SonarQube Scanner | `SonarScanner` |

### System Configuration *(Manage Jenkins → Configure System)*

- **SonarQube Servers**: name = `SonarQube Server`
- **Extended Email**: SMTP host, port, and credentials

---

## Kubernetes Manifest Management

### Two options — choose one per project

```groovy
// Option A: update only (faster)
stage('k8s Manifest Update') {
    steps { script { updateK8sManifest() } }
}

// Option B: update + OPA scan before push (recommended for production)
stage('k8s Manifest Update') {
    steps { script { k8sManifestScanAndUpdate() } }
}
```

### Pre-flight behaviour (missing file protection)

Both functions check that **all files listed in `K8S_MANIFEST_PATHS` exist** in the cloned repo before making any changes.

If a file is missing:
1. A **warning email** is sent immediately to `NOTIFICATION_EMAIL`.
2. The pipeline **pauses for up to 30 minutes** (configurable via `waitMinutes`).
3. A human can fix the file, push it, then click **Proceed** in Jenkins.
4. The function **re-validates** the file before continuing.
5. If no action is taken within the timeout → **automatic failure**.

This prevents partial updates and ensures the pipeline never silently succeeds.

### Updating multiple files

```groovy
K8S_MANIFEST_PATHS = '03-deployment.yaml,06-ingress.yaml'
```

---

## Dependency Scanning

`owaspDependencyCheck()` auto-detects the project language and runs the appropriate scanner:

| Language | Tool | Report |
|:---------|:-----|:-------|
| Maven | OWASP Dependency-Check Maven plugin | `target/dependency-check-report.xml` (published in Jenkins UI) |
| npm / Node / Next | `npm audit` | `npm-audit-report.json` (build artifact) |
| Go | `govulncheck` | `govulncheck-report.txt` (build artifact) |
| Gradle | OWASP Dependency-Check Gradle plugin | `build/reports/dependency-check-report.xml` |
| .NET | `dotnet list package --vulnerable` | `dotnet-vuln-report.txt` (build artifact) |

### `failOnCVSS` values

| Value | Behaviour |
|:------|:----------|
| `0` | Report only — never fails build (**start here** to see baseline) |
| `7` | Fails on HIGH + CRITICAL CVEs |
| `9` | Fails on CRITICAL only (**recommended** once team is ready) |

The Maven XML report is published automatically by `dependencyCheckPublisher` in `post { always {} }` and creates a **trend graph** on the Jenkins job page.

---

## Disk Management

The pipeline is designed to keep Jenkins disk usage under control:

| Mechanism | Effect |
|:----------|:-------|
| `buildDiscarder(logRotator(numToKeepStr: '5', artifactNumToKeepStr: '0'))` | Keeps last 5 builds, zero archived JARs |
| `removeAfterPush: true` in `buildDockerImageAndPush` | Removes both local and registry-tagged images after push |
| `docker builder prune -f --keep-storage=2gb` | Caps Docker build cache at 2 GB |
| `cleanWs(cleanWhenSuccess: true, cleanWhenFailure: false)` | Removes workspace on success; keeps on failure for debugging |
| No `archiveArtifacts` for build outputs | JARs/dist go into the Docker image; Harbor is the artifact store |

---

## Configuration Reference

### Full environment variable list

| Variable | Description | Default |
|:---------|:------------|:--------|
| `PROJECT_NAME` | Human-readable project name | — |
| `IMAGE_NAME` | Docker image name (no registry prefix) | `env.JOB_NAME` |
| `HARBOR_PROJECT` | Harbor project namespace | — |
| `REGISTRY_URL` | Docker registry base URL | `docker.io` |
| `REGISTRY_CREDENTIALS_ID` | Jenkins credential ID for registry | — |
| `NOTIFICATION_EMAIL` | Comma-separated recipient list | — |
| `GIT_REPO_URL` | Source code repository URL | — |
| `GIT_CREDENTIALS_ID` | Jenkins credential ID for source repo | — |
| `BRANCH_NAME` | Branch to build | `main` |
| `K8S_MANIFEST_REPO_URL` | GitOps manifest repository URL | **Required** |
| `K8S_MANIFEST_CREDENTIALS_ID` | Jenkins credential ID for manifest repo | **Required** |
| `K8S_MANIFEST_BRANCH` | Manifest repo branch | `main` |
| `K8S_MANIFEST_PATHS` | Comma-separated manifest file paths | `deployment.yaml` |
| `BUILD_TOOL` | Build tool override | auto-detect |
| `APP_TIMEZONE` | Timezone baked into Docker image | — |
| `DEFECTDOJO_URL` | DefectDojo instance base URL | `http://192.168.15.85:8090` |
| `DEFECTDOJO_ENGAGEMENT_ID` | DefectDojo engagement ID (unique per service) | **Required for stage 11** |

### Auto-populated (do not edit)

| Variable | Value |
|:---------|:------|
| `GIT_COMMIT` | `git rev-parse HEAD` |
| `GIT_AUTHOR` | `git log -1 --pretty=format:"%an"` |
| `APP_VERSION` | `1.0.${env.BUILD_NUMBER}` |
| `BUILD_DATE_UTC` | ISO-8601 UTC timestamp |

---

## DefectDojo Integration

All scan reports are automatically uploaded to **DefectDojo** at the end of every pipeline run (stage 11 — `publishToDefectDojo`).

### What gets uploaded

| Report file | Scanner | Produced by |
|:------------|:--------|:------------|
| `trivy-report.json` | Trivy Scan | `vulnScanApplicationImage` |
| `trivy-base-report.json` | Trivy Scan | `vulnScanDocker` |
| `target/dependency-check-report.xml` | Dependency Check Scan | `owaspDependencyCheck` (Maven) |
| `npm-audit-report.json` | NPM Audit Scan | `owaspDependencyCheck` (npm) |
| `gitleaks-report.json` | Gitleaks Scan | `vulnScanDocker` |
| `govulncheck-report.txt` | Govulncheck Scanner | `owaspDependencyCheck` (Go) |

Only files that exist are uploaded — safe to call on any project type.

### One-time DefectDojo setup

1. **Create a Product** in DefectDojo for each microservice.
2. **Create an Engagement** inside the Product (e.g. "CI/CD").
3. Copy the **Engagement ID** (visible in the URL: `/engagement/<id>`).
4. Add `DEFECTDOJO_ENGAGEMENT_ID = '<id>'` to the Jenkinsfile `environment{}` block.
5. Add the API token credential in Jenkins:
   - **Manage Jenkins → Credentials → Add → Secret text**
   - **ID**: `defectdojo-api-token`
   - **Value**: DefectDojo → top-right menu → *API v2* → Authorize → copy token

### Behaviour

- **Deduplication**: DefectDojo merges findings — the same CVE found again updates the existing ticket rather than creating a duplicate.
- **Auto-close**: `close_old_findings=true` automatically closes fixed CVEs on the next build.
- **Non-blocking**: Upload failures emit a warning but do not fail the pipeline.
- **Email notification**: After a successful upload, the team receives an HTML email with a direct link to the DefectDojo engagement.

---

## Security & Compliance

### Scanning coverage

| Layer | Tool | Stage |
|:------|:-----|:------|
| Source code (SAST) | SonarQube | 5 |
| Dependencies (SCA) | OWASP / npm audit / govulncheck / dotnet | 6 |
| Dockerfile | Trivy base image + OPA Conftest | 7 |
| Secrets in source | Gitleaks | 7 |
| Application image (all layers) | Trivy | 9 |
| K8s manifests (IaC) | OPA Conftest | 9 (and optionally 10) |

### OPA Dockerfile policies (`opa-docker-security.rego`)

- No secrets in `ENV` variables
- No `:latest` tag on base images
- No `curl | bash` (pipe to shell)
- No `apt-get upgrade` / `apk upgrade`
- `COPY` instead of `ADD`
- Non-root `USER` defined
- No `sudo` usage

### OPA K8s manifest policies (`opa-k8s-security.rego`)

- Namespace required on all resources
- No root containers (`runAsUser: 0` forbidden)
- `runAsNonRoot: true` required
- No privileged containers
- No `allowPrivilegeEscalation`
- `readOnlyRootFilesystem: true`
- CPU and memory resource limits defined
- No `hostPID` / `hostIPC` sharing
- No dangerous `hostPath` mounts (`/proc`, `/sys`, `/`)
- No `:latest` image tags
- Services must be `NodePort`

---

## Best Practices

- **Start with `failOnCVSS: 0`** — see your vulnerability baseline before enforcing failures.
- **Use `k8sManifestScanAndUpdate()`** over `updateK8sManifest()` in production — it prevents non-compliant manifests reaching the cluster.
- **Immutable tags**: Always use `env.BUILD_NUMBER` for image tags, never `latest`.
- **Separate repositories**: Keep K8s manifests in a dedicated repo, separate from application code.
- **Least privilege**: Ensure Jenkins credentials for manifest repos have write access only to specific GitOps repos.
- **NVD API key**: Register at [nvd.nist.gov](https://nvd.nist.gov/developers/request-an-api-key) to speed up OWASP dependency-check DB downloads.
- **`waitForQualityGate: false`**: Start non-blocking for SonarQube; switch to `true` only when the team is ready to enforce gate failures.

---

**Last Updated**: 2026-05-16
**Library Version**: 3.1
**Maintained by**: DevOps Engineering Team
