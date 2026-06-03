# Shared Library Functions - Complete Documentation

This document provides exhaustive documentation for all shared library functions, including their purpose, parameters (with environment variable fallbacks), and usage examples.

---

## 📋 Table of Contents

1. [buildArtifact](#buildartifact)
2. [buildDockerImageAndPush](#builddockerimageandpush)
3. [updateK8sManifest](#updatek8smanifest)
4. [k8sManifestScanAndUpdate](#k8smanifestscanandupdate)
5. [productionApproval](#productionapproval)
6. [sonarSast](#sonarsast)
7. [vulnScanDocker](#vulnscandocker)
8. [checkoutAndGitInfo](#checkoutandgitinfo)
9. [Notification Functions](#notification-functions)

---

## buildArtifact

**File**: `vars/buildArtifact.groovy`

### Purpose
Builds application artifacts with automatic build tool detection. Supports Maven, NPM, Go, Gradle, and .NET.

### Parameters

| Parameter | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `buildTool` | String | `env.BUILD_TOOL` | Build tool to use: `maven`, `npm`, `go`, `gradle`, `dotnet`. |
| `command` | String | Tool-specific | Custom build command. |
| `artifacts` | String | Tool-specific | Artifact path pattern for archiving. |

### Auto-Detection Logic
The function automatically detects the build tool if `buildTool` is not provided:
- `pom.xml` → **Maven**
- `package.json` → **NPM**
- `go.mod` → **Go**
- `build.gradle` → **Gradle**

---

## buildDockerImageAndPush

**File**: `vars/buildDockerImageAndPush.groovy`

### Purpose
Builds and tags Docker images, then pushes them to a remote registry.

### Parameters

| Parameter | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `imageName` | String | `env.IMAGE_NAME` | The name of the Docker image. |
| `imageTag` | String | `env.BUILD_NUMBER` | The version/tag for the image. |
| `registryUrl` | String | `env.REGISTRY_URL` | The base URL of the Docker registry. |
| `pushToRegistry` | Boolean | `true` | Whether to push the image after building. |
| `buildArgs` | Map | `[:]` | Dictionary of build-time variables. |

---

## updateK8sManifest

**File**: `vars/updateK8sManifest.groovy`

### Purpose
Updates one or more Kubernetes manifest files in a GitOps repository with a newly built image tag. This is a direct update without security scanning.

### Parameters (Automatic Env Fallbacks)

| Parameter | Env Variable | Default | Description |
| :--- | :--- | :--- | :--- |
| `repoUrl` | `K8S_MANIFEST_REPO_URL` | **Required** | URL of the manifest repository. |
| `manifestPath` | `K8S_MANIFEST_PATHS` | `deployment.yaml` | Comma-separated list of files to update. |
| `credentialsId` | `K8S_MANIFEST_CREDENTIALS_ID` | **Required** | Jenkins credential ID for Git access. |
| `imageName` | `IMAGE_NAME` | **Required** | Image name to look for in the manifest. |
| `imageTag` | `BUILD_NUMBER` | `latest` | The new tag to apply. |

### Usage Example
```groovy
// In Jenkinsfile
updateK8sManifest() // Uses all defaults from environment variables
```

---

## k8sManifestScanAndUpdate

**File**: `vars/k8sManifestScanAndUpdate.groovy`

### Purpose
Provides a secure GitOps update flow. It clones the repository, updates the tags, and then runs an **OPA security scan (Conftest)**. The push is only executed if the scan passes.

### Requirements
*   Requires `opa-k8s-security.rego` to be present in the root of the application repository.

### Parameters
Inherits all parameters from `updateK8sManifest`.

### Security Workflow
1.  **Clone**: Pulls the latest manifests.
2.  **Modify**: Updates the image tags in memory/locally.
3.  **Scan**: Executes `docker run conftest` against the modified manifests.
4.  **Confirm**: If the scan fails, the pipeline errors out and **no changes are pushed**.

---

## productionApproval

**File**: `vars/productionApproval.groovy`

### Purpose
Production deploy gate for a `prod` branch workflow. Sends a rich HTML approval email to the team with **Approve / Reject** buttons, pauses the pipeline waiting for a human decision, then on approval calls `k8sManifestScanAndUpdate()` using a **release version tag** (e.g. `1.2.0`) instead of the build number. Handles rejection and timeout with separate notification emails.

### Parameters

| Parameter | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `releaseVersion` | String | `env.RELEASE_VERSION` | **Required.** Semantic version to stamp on the image, e.g. `1.2.0` or `v1.2.0`. Leading `v` is stripped automatically for the image tag. |
| `recipients` | String | `env.NOTIFICATION_EMAIL` | Email address(es) to notify — comma-separated for multiple. |
| `timeoutMinutes` | Integer | `30` | Minutes to wait for approval before auto-abort. |
| `approverMessage` | String | Auto-generated | Custom message shown on the Jenkins input prompt. |
| `skipManifest` | Boolean | `false` | Set `true` to send the email and gate only, without updating the manifest (useful for testing). |

### How the release version is resolved

Add a `VERSION` file to your repo root containing the semver string (e.g. `1.2.0`). The Jenkinsfile reads it automatically:

```
# repo root
echo "1.2.0" > VERSION
git add VERSION && git commit -m "chore: bump version to 1.2.0"
git push origin prod
```

If no `VERSION` file exists it falls back to `APP_VERSION` (`1.0.<build_number>`).

### Jenkinsfile usage

Add these two stages to replace the single `k8s Manifest Update` stage:

```groovy
// ── Production gate (prod branch only) ───────────────────────────────
stage('Production Approval') {
    when { branch 'prod' }
    steps {
        script {
            productionApproval(
                releaseVersion: env.RELEASE_VERSION,
                recipients:     env.NOTIFICATION_EMAIL,
                timeoutMinutes: 30
            )
        }
    }
}

// ── Regular manifest update (all branches except prod) ────────────────
stage('k8s Manifest Update') {
    when { not { branch 'prod' } }
    steps {
        script {
            k8sManifestScanAndUpdate()
        }
    }
}
```

Also add to the `environment` block:

```groovy
// Reads VERSION file from repo root; falls back to APP_VERSION.
RELEASE_VERSION = sh(script: "cat VERSION 2>/dev/null || echo ${env.APP_VERSION}", returnStdout: true).trim()
```

### Workflow diagram

```
prod branch push
       │
       ▼
  Build & scan (same as main)
       │
       ▼
  ┌─ productionApproval() ─────────────────────────────┐
  │  1. Sends approval email with Approve/Reject button │
  │  2. Pauses pipeline (up to 30 min)                  │
  │  3a. Approved → k8sManifestScanAndUpdate(v1.2.0)    │
  │  3b. Rejected → sends rejection email, FAILURE      │
  │  3c. Timeout  → sends timeout email, ABORTED        │
  └─────────────────────────────────────────────────────┘
       │
       ▼
  Manifest updated with harbor.../image:1.2.0
```

### Emails sent

| Event | Subject |
| :--- | :--- |
| Approval request | `⏳ [Approval Required] <image> v<tag> → PRODUCTION` |
| Rejected | `❌ [Rejected] <image> v<tag> production deploy cancelled` |
| Timed out | `⏰ [Timed Out] <image> v<tag> production deploy aborted` |

---

## sonarSast

**File**: `vars/sonarSast.groovy`

### Purpose
Executes Static Application Security Testing (SAST) via SonarQube.

### Parameters

| Parameter | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `projectKey` | String | `env.JOB_NAME` | Unique key for the SonarQube project. |
| `waitForQualityGate` | Boolean | `true` | Whether to wait for the analysis status. |

---

## vulnScanDocker

**File**: `vars/vulnScanDocker.groovy`

### Purpose
Runs parallel security scans on the Dockerfile and project dependencies before the image is built.

### Scans Included
1.  **OWASP Dependency-Check**: Scans third-party libraries for known vulnerabilities.
2.  **Trivy Static Scan**: Scans the base image defined in your Dockerfile.
3.  **OPA Conftest**: Validates your Dockerfile against best practices (e.g., no root user).

---

## checkoutAndGitInfo

**File**: `vars/checkoutAndGitInfo.groovy`

### Purpose
Standardizes the SCM checkout process and extracts Git metadata into environment variables for use in later stages.

### Environment Variables Set
*   `GIT_COMMIT`: The current short SHA.
*   `GIT_AUTHOR`: The name of the last committer.
*   `GIT_BRANCH`: The current branch name.

---

## Notification Functions

### `sendStartNotification` / `sendSuccessNotification` / `sendFailureNotification`

These functions send rich HTML emails to the recipients defined in `env.NOTIFICATION_EMAIL`. They include build details, commit information, and direct links to the Jenkins build log.

---

**Last Updated**: 2026-06-03  
**Documentation Version**: 2.1
