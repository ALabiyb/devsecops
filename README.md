# Jenkins DevSecOps Shared Library

A comprehensive, production-grade Jenkins shared library designed to automate the complete DevSecOps lifecycle—from multi-language builds and security scanning to GitOps-driven Kubernetes deployments.

---

## 📚 Table of Contents

1. [Overview](#overview)
2. [Pipeline Architecture](#pipeline-architecture)
3. [Quick Start](#quick-start)
4. [Kubernetes Manifest Management](#kubernetes-manifest-management)
5. [Full Pipeline Example](#full-pipeline-example)
6. [Configuration Reference](#configuration-reference)
7. [Security & Compliance](#security--compliance)
8. [Best Practices](#best-practices)

---

## Overview

This pipeline provides a standardized approach to DevSecOps by integrating industry-standard tools into a cohesive CI/CD workflow:

*   **Multi-Language Support**: Automated build tool detection (Maven, NPM, Go, Gradle, .NET).
*   **Deep Security Scanning**: SAST (SonarQube), Dependency Analysis (OWASP), and Container Security (Trivy, OPA).
*   **Infrastructure as Code**: Automated GitOps updates for Kubernetes manifests with built-in security policy enforcement.
*   **Extensible Design**: Modular shared library functions that support both zero-configuration and granular overrides.

---

## Pipeline Architecture

### Shared Library Components (`vars/`)

| Function | Purpose |
| :--- | :--- |
| `buildArtifact` | Intelligent build orchestration with auto-detection. |
| `buildDockerImageAndPush` | Production image building and registry management. |
| `updateK8sManifest` | Direct image tag updates for GitOps repositories. |
| `k8sManifestScanAndUpdate` | Secure manifest updates with integrated OPA scanning. |
| `sonarSast` | Static Application Security Testing (SAST). |
| `vulnScanDocker` | Pre-build security analysis of the environment. |
| `checkoutAndGitInfo` | Standardized SCM checkout and metadata extraction. |
| `send*Notification` | Rich email notifications for build status. |

---

## Quick Start

### 1. Repository Configuration
Ensure your application repository contains the following required policy files:
*   `Dockerfile`: Your container definition.
*   `opa-docker-security.rego`: Policy for Dockerfile validation.
*   `opa-k8s-security.rego`: Policy for Kubernetes manifest validation.

### 2. Jenkinsfile Definition
Basic integration requires minimal configuration. See the [Full Pipeline Example](#full-pipeline-example) below for a production-ready template.

---

## Kubernetes Manifest Management

The library supports a secure, GitOps-compliant manifest update flow.

### Supported Functions

1.  **`updateK8sManifest()`**: Best for internal or trusted environments where speed is prioritized.
2.  **`k8sManifestScanAndUpdate()`**: Best for production environments. It performs an OPA security scan (Conftest) on the modified manifests before allowing the push to the manifest repository.

### Updating Multiple Files
If your deployment consists of multiple manifests (e.g., `deployment.yaml`, `ingress.yaml`), list them in the `K8S_MANIFEST_PATHS` environment variable:

```groovy
environment {
    K8S_MANIFEST_PATHS = 'k8s/03-deployment.yaml,k8s/06-ingress.yaml'
}
```

---

## Full Pipeline Example

A complete, production-ready `Jenkinsfile` template is available to help you get started quickly. It demonstrates the full DevSecOps flow, including parallel security scans and GitOps manifest updates.

👉 **View the template: [Jenkinsfile.example](file:///d:/DevSecOps%20Pipeline/examples/Jenkinsfile.example)**

This example shows how to:
- Load the shared library.
- Configure project and GitOps environment variables.
- Orchestrate builds, SAST, and container security.
- Perform automated manifest updates.

---

## Configuration Reference

### Common Environment Variables

| Variable | Description | Default |
| :--- | :--- | :--- |
| `IMAGE_NAME` | The logic name of the application image. | `env.JOB_NAME` |
| `K8S_MANIFEST_REPO_URL` | The URL of the GitOps repository. | **Required** |
| `K8S_MANIFEST_PATHS` | Comma-separated list of manifest files to update. | `deployment.yaml` |
| `K8S_MANIFEST_BRANCH` | The branch to update in the manifest repository. | `main` |
| `REGISTRY_URL` | The base URL of your Docker registry. | `docker.io` |

---

## Security & Compliance

### Integrated Scanning Tools
*   **SAST**: SonarQube analysis for code quality and security.
*   **SCA**: OWASP Dependency-Check for vulnerable libraries.
*   **Container**: Trivy for OS-level vulnerabilities.
*   **IaaC Policy**: Open Policy Agent (OPA) for validating Dockerfiles and K8s manifests.

### Compliance Enforcement
The `k8sManifestScanAndUpdate` function ensures that **no insecure manifest is ever pushed**. If the manifests violate the `opa-k8s-security.rego` policy (e.g., trying to run as root), the push is automatically aborted.

---

## Best Practices

*   **Immutable Tags**: Always use `env.BUILD_NUMBER` for image tags.
*   **Separation of Concerns**: Keep manifests in a separate repository from application code.
*   **Policy as Code**: Regularly update your `.rego` files to reflect organizational security standards.
*   **Least Privilege**: Ensure the Jenkins credentials used for manifest updates only have write access to specific GitOps repositories.

---

**Last Updated**: 2026-04-22  
**Library Version**: 2.0  
**Maintained by**: DevOps Engineering Team
