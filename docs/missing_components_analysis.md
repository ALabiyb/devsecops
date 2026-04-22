# DevSecOps Pipeline - Gap Analysis & Recommendations

This document outlines the current security posture of the pipeline, identifies resolved issues, and provides a roadmap for future DevSecOps enhancements.

---

## ✅ Resolved Issues

### 1. K8s Manifest Security Scan (Fixed)
**Previous Issue**: The Open Policy Agent (OPA) scan for Kubernetes manifests was running in the wrong stage and scanning the application repository instead of the manifest repository.

**Resolution**:
*   The logic was moved into the GitOps update flow.
*   The `k8sManifestScanAndUpdate` function now clones the manifest repository, applies updates, and then scans the manifests using `conftest` before pushing any changes.
*   This ensures that only compliant, valid manifests are ever committed to infrastructure.

---

## 📋 Future roadmap (Next Steps)

### 1. Secret Scanning (High Priority)
**Recommendation**: Implement automated secret scanning to prevent API keys and tokens from being committed to the repository.
**Tool**: `gitleaks` or `trufflehog`.

### 2. Software Bill of Materials (SBOM)
**Recommendation**: Automatically generate an SBOM for every build to provide transparency into the software supply chain.
**Tool**: `syft` or `cyclonedx`.

### 3. Container Signing
**Recommendation**: Sign container images to ensure integrity and authenticity.
**Tool**: `cosign`.

### 4. Dynamic Analysis (DAST)
**Recommendation**: Run baseline security scans against the deployed application in a staging environment.
**Tool**: `OWASP ZAP`.

---

**Last Updated**: 2026-04-22
