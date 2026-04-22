# Shared Library Functions - Complete Documentation

This document provides exhaustive documentation for all shared library functions, including their purpose, parameters (with environment variable fallbacks), and usage examples.

---

## 📋 Table of Contents

1. [buildArtifact](#buildartifact)
2. [buildDockerImageAndPush](#builddockerimageandpush)
3. [updateK8sManifest](#updatek8smanifest)
4. [k8sManifestScanAndUpdate](#k8smanifestscanandupdate)
5. [sonarSast](#sonarsast)
6. [vulnScanDocker](#vulnscandocker)
7. [checkoutAndGitInfo](#checkoutandgitinfo)
8. [Notification Functions](#notification-functions)

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

**Last Updated**: 2026-04-22  
**Documentation Version**: 2.0
