# Jenkins DevSecOps Shared Library

A production-grade Jenkins shared library that automates the complete DevSecOps lifecycle — from multi-language builds and security scanning to GitOps-driven Kubernetes deployments.

---

## Table of Contents

1. [Overview](#overview)
2. [Pipeline Stages](#pipeline-stages)
3. [Shared Library Reference](#shared-library-reference)
4. [Quick Start](#quick-start)
5. [Jenkins One-Time Setup](#jenkins-one-time-setup)
6. [Kubernetes Manifest Management](#kubernetes-manifest-management)
7. [Dependency Scanning](#dependency-scanning)
8. [SBOM & Dependency-Track](#sbom--dependency-track)
9. [DefectDojo Integration](#defectdojo-integration)
10. [Disk Management](#disk-management)
11. [Configuration Reference](#configuration-reference)
12. [Security & Compliance](#security--compliance)
13. [Best Practices](#best-practices)

---

## Overview

This library provides a standardized DevSecOps pipeline template where **only the `environment{}` block changes per project** — stages, scanning logic, and notifications all live in the shared library.

- **Multi-language builds**: Maven, NPM/Node/Next.js, Go, Gradle, .NET — auto-detected or explicit.
- **Shift-left security**: SAST (SonarQube), SCA (OWASP/npm audit/govulncheck), container CVE scanning (Trivy), secrets detection (Gitleaks), and policy enforcement (OPA Conftest).
- **SBOM generation**: Syft generates a full Software Bill of Materials for every Docker image and uploads it to Dependency-Track for continuous CVE monitoring between builds.
- **GitOps**: Automated K8s manifest updates with pre-flight verification, pipeline pause on missing files, and OPA scan before push.
- **Disk hygiene**: Docker images removed after push, build cache capped at 2 GB, workspaces cleaned after success.

---

## Pipeline Stages

| # | Stage | What it does |
|---|-------|-------------|
| 1 | **Checkout & Git Info** | Clones source repo, extracts commit SHA, author, and branch |
| 2 | **Notify Start** | Sends email with trigger info and build link |
| 3 | **Build Artifact** | Compiles app (`mvn`/`npm ci`/`go build`/etc.) — no `archiveArtifacts` |
| 4 | **Unit Tests** *(optional)* | Uncomment `unitTests()` when tests exist |
| 5 | **SonarQube SAST** | Static analysis — code smells, vulnerabilities, coverage |
| 6 | **Dependency Check** | CVE scan of all dependencies (language-specific tool) + OSV Scanner |
| 7 | **Vuln Scan — Dockerfile** | Trivy base image CVE scan + OPA Dockerfile policies + Gitleaks secrets detection |
| 8 | **Docker Build & Push** | Builds image, pushes to Harbor, keeps local copy for stages 9–10 |
| 9 | **Generate & Upload SBOM** | Syft generates CycloneDX SBOM from local image → uploaded to Dependency-Track |
| 10 | **Vuln Scan — App Image** | Trivy full image (all layers) + OPA K8s manifest policies; removes local image |
| 11 | **Publish Security Results** | Uploads all scan reports to DefectDojo; sends results email |
| 12 | **K8s Manifest Update** | Updates image tag in manifest repo (+ optional OPA scan), commits & pushes |

> **Stage 11 runs before Stage 12 by design** — scan results reach DefectDojo even if the K8s manifest update fails.
>
> **Stage 9 must run before Stage 10** — Syft scans the local Docker image; Stage 10 removes it after scanning.

---

## Shared Library Reference

### `vars/` functions

| Function | Description |
|:---------|:------------|
| `buildArtifact` | Multi-language build orchestration. Auto-detects tool from project files; supports `buildTool`, `command`, and per-tool overrides. No `archiveArtifacts` — Harbor is the artifact store. |
| `buildDockerImageAndPush` | Builds Docker image with `--build-arg` metadata (git commit, author, version). Pushes to Harbor, keeps local image for SBOM and vulnerability scanning, then removes it after Stage 10. Caps Docker build cache at 2 GB. |
| `generateSbom` | Runs Syft against the local Docker image to produce a CycloneDX JSON SBOM. Uploads to Dependency-Track API (`autoCreate=true` — project is created on first run). Non-blocking: failure marks build UNSTABLE but does not halt delivery. Must run after `buildDockerImageAndPush` and before `vulnScanApplicationImage`. |
| `owaspDependencyCheck` | Language-aware CVE scanning: OWASP Maven plugin, `npm audit`, `govulncheck` (Go), OWASP Gradle plugin, `dotnet list package`. `failOnCVSS` controls blocking vs reporting. |
| `osvScanner` | Google OSV Scanner — complements OWASP, covers Python and all languages via OSV.dev database. No credentials required. |
| `vulnScanDocker` | Parallel: Trivy base image CVE scan (HTML report + email) + OPA Conftest Dockerfile policies + Gitleaks hardcoded secrets detection. Runs **before** `buildDockerImageAndPush`. |
| `vulnScanApplicationImage` | Parallel: Trivy full image scan (2 rounds: informational HIGH+CRITICAL, then blocking CRITICAL) + OPA K8s manifest scan. Removes local Docker image after scanning. |
| `publishToDefectDojo` | Uploads all scan reports to DefectDojo at Stage 11 — before the K8s manifest update so findings are always captured. Supports Trivy, OWASP, npm audit, Gitleaks, govulncheck. Skips missing files — safe for all project types. |
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

    NOTIFICATION_EMAIL = 'team@softnet.co.tz'

    GIT_REPO_URL       = 'http://192.168.15.85/group/my-service.git'
    GIT_CREDENTIALS_ID = 'lsaid'
    BRANCH_NAME        = 'main'

    K8S_MANIFEST_REPO_URL       = 'http://192.168.15.85/k8s/my-service.git'
    K8S_MANIFEST_CREDENTIALS_ID = 'lsaid'
    K8S_MANIFEST_BRANCH         = 'main'
    K8S_MANIFEST_PATHS          = '04-deployment.yaml'

    BUILD_TOOL   = 'maven'   // or: npm | go | gradle | dotnet | remove to auto-detect
    APP_TIMEZONE = 'Africa/Dar_es_Salaam'

    // DefectDojo — create one Engagement per service in DefectDojo and paste the ID
    DEFECTDOJO_URL           = 'https://defectdojo.devops.softnethq.co.tz'
    DEFECTDOJO_ENGAGEMENT_ID = '1'   // unique per service

    // Dependency-Track — credential 'dependency-track-api-key' in Jenkins
    DEPENDENCY_TRACK_URL     = 'https://dependencytrack.devops.softnethq.co.tz'
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
| Docker Pipeline | Stages 7, 8, 9, 10 |
| Workspace Cleanup | `cleanWs()` in `post{}` |

### Credentials *(Manage Jenkins → Credentials → Global)*

| ID | Type | Used for |
|:---|:-----|:---------|
| `lsaid` | Username/Password | GitLab source and manifest repos |
| `robot-jenkins` | Username/Password | Harbor robot account |
| `nvd-api-key` | Secret text | NVD API key for OWASP scans — [register free](https://nvd.nist.gov/developers/request-an-api-key) |
| `defectdojo-api-token` | Secret text | DefectDojo API token (Stage 11) |
| `dependency-track-api-key` | Secret text | Dependency-Track API key (Stage 9 — SBOM upload) |

### How to get the Dependency-Track API key

1. Login to `https://dependencytrack.devops.softnethq.co.tz`
2. Go to **Administration → Access Management → Teams**
3. Click **Automation** team
4. Click **+** next to API Keys → copy the key
5. Add to Jenkins as Secret text with ID `dependency-track-api-key`

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
2. The pipeline **pauses for up to 30 minutes**.
3. A human can fix the file, push it, then click **Proceed** in Jenkins.
4. The function **re-validates** the file before continuing.
5. If no action is taken within the timeout → **automatic failure**.

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
| npm / Node / Next | `npm audit` | `npm-audit-report.json` |
| Go | `govulncheck` | `govulncheck-report.txt` |
| Gradle | OWASP Dependency-Check Gradle plugin | `build/reports/dependency-check-report.xml` |
| .NET | `dotnet list package --vulnerable` | `dotnet-vuln-report.txt` |
| All (incl. Python) | OSV Scanner (`osvScanner`) | Runs in parallel with OWASP |

### `failOnCVSS` values

| Value | Behaviour |
|:------|:----------|
| `0` | Report only — never fails build (start here to see baseline) |
| `7` | Fails on HIGH + CRITICAL CVEs |
| `9` | Fails on CRITICAL only (recommended once team is ready) |

---

## SBOM & Dependency-Track

### What is an SBOM?

A Software Bill of Materials (SBOM) is a complete inventory of every component inside a Docker image — OS packages, language dependencies, transitive dependencies, and their exact versions.

### How it works

Stage 9 (`generateSbom`) runs after Docker build and before the local image is removed:

```
Stage 8: buildDockerImageAndPush  → image pushed to Harbor, local copy kept
Stage 9: generateSbom             → Syft scans local image → CycloneDX JSON → Dependency-Track
Stage 10: vulnScanApplicationImage → Trivy scans local image → image removed
```

### Dependency-Track auto-creates projects

On the first pipeline run for any service, Dependency-Track automatically creates a project named after `IMAGE_NAME` with version `APP_VERSION`. Subsequent builds add new versions — DT tracks the entire version history.

### Continuous CVE monitoring (the key value)

Once an SBOM is uploaded, Dependency-Track **continuously monitors it against the NVD, OSV, and GitHub Advisory databases**. If a new CVE is published tomorrow against a library you shipped last month, Dependency-Track alerts you — **without running a new pipeline build**.

This is the difference from Trivy (which scans at build time only).

### Accessing Dependency-Track

URL: `https://dependencytrack.devops.softnethq.co.tz`

After login: **Projects** → select service → **Components** tab shows every dependency with CVE status.

### `generateSbom` parameters

| Parameter | Default | Description |
|:----------|:--------|:------------|
| `dtUrl` | `env.DEPENDENCY_TRACK_URL` | Dependency-Track base URL |
| `imageName` | `env.FINAL_IMAGE_NAME` | Local Docker image to scan (set by `buildDockerImageAndPush`) |
| `projectName` | `env.IMAGE_NAME` | Project name in Dependency-Track |
| `projectVersion` | `env.APP_VERSION` | Version string |
| `credentialsId` | `dependency-track-api-key` | Jenkins credential ID for DT API key |

### Failure behaviour

SBOM upload failure marks the build **UNSTABLE** but does not stop delivery. The pipeline continues to Stage 10 and the K8s deploy proceeds. Check Dependency-Track connectivity if this happens repeatedly.

---

## DefectDojo Integration

All scan reports are automatically uploaded to **DefectDojo** by `publishToDefectDojo()` at Stage 11, before the K8s manifest update (Stage 12).

### What gets uploaded

| Report file | Scanner | Produced by |
|:------------|:--------|:------------|
| `trivy-report.json` | Trivy Scan | `vulnScanApplicationImage` |
| `trivy-base-report.json` | Trivy Scan | `vulnScanDocker` |
| `target/dependency-check-report.xml` | Dependency Check | `owaspDependencyCheck` (Maven) |
| `npm-audit-report.json` | NPM Audit | `owaspDependencyCheck` (npm) |
| `gitleaks-report.json` | Gitleaks | `vulnScanDocker` |
| `govulncheck-report.txt` | Govulncheck | `owaspDependencyCheck` (Go) |

### One-time DefectDojo setup per service

1. Create a **Product** in DefectDojo for the service.
2. Create an **Engagement** inside the Product (e.g. "CI/CD").
3. Copy the **Engagement ID** from the URL: `/engagement/<id>`.
4. Set `DEFECTDOJO_ENGAGEMENT_ID = '<id>'` in the Jenkinsfile `environment{}`.

### DefectDojo vs Dependency-Track

| Tool | Purpose |
|:-----|:--------|
| **DefectDojo** | Tracks findings per build — manages remediation, deduplicates, assigns tickets |
| **Dependency-Track** | Continuously monitors components — alerts when new CVEs affect already-shipped versions |

Both tools work together — DefectDojo handles what was found in this build; Dependency-Track handles what becomes vulnerable after this build.

---

## Disk Management

| Mechanism | Effect |
|:----------|:-------|
| `buildDiscarder(logRotator(numToKeepStr: '5', artifactNumToKeepStr: '0'))` | Keeps last 5 builds, zero archived JARs |
| Local image removed after Stage 10 | Syft and Trivy both scan the local image; it is removed only after both complete |
| `docker builder prune -f --keep-storage=2gb` | Caps Docker build cache at 2 GB |
| `cleanWs(cleanWhenSuccess: true, cleanWhenFailure: false)` | Removes workspace on success; keeps on failure for debugging |
| No `archiveArtifacts` | JARs/dist go into the Docker image; Harbor is the artifact store |

---

## Configuration Reference

### Full environment variable list

| Variable | Description | Required |
|:---------|:------------|:---------|
| `PROJECT_NAME` | Human-readable project name | Yes |
| `IMAGE_NAME` | Docker image name (no registry prefix) | Yes |
| `HARBOR_PROJECT` | Harbor project namespace | Yes |
| `REGISTRY_URL` | Docker registry base URL | Yes |
| `REGISTRY_CREDENTIALS_ID` | Jenkins credential ID for registry | Yes |
| `NOTIFICATION_EMAIL` | Comma-separated recipient list | Yes |
| `GIT_REPO_URL` | Source code repository URL | Yes |
| `GIT_CREDENTIALS_ID` | Jenkins credential ID for source repo | Yes |
| `BRANCH_NAME` | Branch to build | Yes |
| `K8S_MANIFEST_REPO_URL` | GitOps manifest repository URL | Yes |
| `K8S_MANIFEST_CREDENTIALS_ID` | Jenkins credential ID for manifest repo | Yes |
| `K8S_MANIFEST_BRANCH` | Manifest repo branch | Yes |
| `K8S_MANIFEST_PATHS` | Comma-separated manifest file paths | Yes |
| `BUILD_TOOL` | Build tool override (`maven`/`npm`/`go`/`gradle`/`dotnet`) | No — auto-detected |
| `APP_TIMEZONE` | Timezone baked into Docker image | No |
| `DEFECTDOJO_URL` | DefectDojo instance base URL | Yes |
| `DEFECTDOJO_ENGAGEMENT_ID` | DefectDojo engagement ID (unique per service) | Yes |
| `DEPENDENCY_TRACK_URL` | Dependency-Track base URL | Yes |

### Auto-populated (do not edit)

| Variable | Value |
|:---------|:------|
| `GIT_COMMIT` | `git rev-parse HEAD` |
| `GIT_AUTHOR` | `git log -1 --pretty=format:"%an"` |
| `APP_VERSION` | `1.0.${env.BUILD_NUMBER}` |
| `BUILD_DATE_UTC` | ISO-8601 UTC timestamp |
| `FINAL_IMAGE_NAME` | Set by `buildDockerImageAndPush` — used by `generateSbom` and `vulnScanApplicationImage` |

---

## Security & Compliance

### Scanning coverage

| Layer | Tool | Stage | Findings go to |
|:------|:-----|:------|:--------------|
| Source code (SAST) | SonarQube | 5 | SonarQube dashboard |
| Dependencies (SCA) | OWASP / npm audit / govulncheck / OSV | 6 | DefectDojo |
| Dockerfile | Trivy base image + OPA Conftest | 7 | DefectDojo + email |
| Secrets in source | Gitleaks | 7 | DefectDojo |
| Software Bill of Materials | Syft (CycloneDX) | 9 | Dependency-Track |
| Application image (all layers) | Trivy | 10 | DefectDojo |
| K8s manifests (IaC) | OPA Conftest | 10 + 12 | Pipeline log |
| Security findings aggregation | DefectDojo | 11 | DefectDojo dashboard |
| Continuous CVE monitoring | Dependency-Track | Always (between builds) | Email / DT dashboard |

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
- **Use `k8sManifestScanAndUpdate()`** over `updateK8sManifest()` in production — prevents non-compliant manifests reaching the cluster.
- **Immutable tags**: Always use `env.BUILD_NUMBER` for image tags, never `latest`.
- **Separate repositories**: Keep K8s manifests in a dedicated repo, separate from application code.
- **Check Dependency-Track weekly** — new CVEs are published daily; DT surfaces them against your shipped versions without requiring a rebuild.
- **NVD API key**: Register at [nvd.nist.gov](https://nvd.nist.gov/developers/request-an-api-key) to speed up OWASP dependency-check DB downloads.
- **`waitForQualityGate: false`**: Start non-blocking for SonarQube; switch to `true` only when the team is ready to enforce gate failures.
- **One Engagement per service in DefectDojo**: Keeps findings scoped per service, not mixed across the platform.

---

**Last Updated**: 2026-05-29
**Library Version**: 4.0
**Maintained by**: DevOps Engineering Team — softnethq.co.tz
