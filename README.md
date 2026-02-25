# Jenkins DevSecOps Multi-Branch Pipeline

Complete guide for understanding, configuring, and running the DevSecOps CI/CD pipeline.

---

## 📚 Table of Contents

1. [Overview](#overview)
2. [Pipeline Architecture](#pipeline-architecture)
3. [Quick Start](#quick-start)
4. [Requirements](#requirements)
5. [Configuration Guide](#configuration-guide)
6. [Pipeline Stages Explained](#pipeline-stages-explained)
7. [Missing Components & Recommendations](#missing-components--recommendations)
8. [Troubleshooting](#troubleshooting)
9. [Best Practices](#best-practices)

## 📖 Additional Documentation

- **[Multibranch Usage Guide](docs/multibranch_usage_guide.md)** - How to set up and use multibranch pipelines
- **[Function Documentation](docs/function_documentation.md)** - Detailed function parameters and usage
- **[Pipeline Requirements](docs/pipeline_requirements.md)** - Complete setup requirements
- **[Setup Checklist](docs/setup_checklist.md)** - Quick verification checklist
- **[Missing Components Analysis](docs/missing_components_analysis.md)** - Gap analysis and recommendations

---

## Overview

This is a **comprehensive DevSecOps pipeline** that automates:
- ✅ Multi-language builds (Maven, NPM, Go, Gradle, .NET)
- ✅ Security scanning (SAST, dependency scanning, container scanning)
- ✅ Docker image building and publishing
- ✅ Kubernetes manifest updates
- ✅ Email notifications

### Pipeline Flow

```
Checkout → Build → Test → Security Scans → Docker Build → K8s Update → Notify
```

---

## Pipeline Architecture

### Shared Library Structure

```
devsecops/
├── vars/                          # Reusable pipeline functions
│   ├── buildArtifact.groovy      # Multi-tool build support
│   ├── buildDockerImageAndPush.groovy
│   ├── checkoutAndGitInfo.groovy
│   ├── sonarSast.groovy          # SonarQube SAST
│   ├── vulnScanDocker.groovy     # Pre-build security scans
│   ├── vulnScanApplicationImage.groovy
│   ├── k8sManifestScanAndUpdate.groovy
│   ├── send*Notification.groovy  # Email notifications
│   └── detectBuildTrigger.groovy
├── lib/                           # Additional libraries
└── resources/                     # Templates and configs
```

### Key Features

- **Auto-detection**: Automatically detects build tool (Maven, NPM, Go, etc.)
- **Parallel Scanning**: Runs security scans in parallel for speed
- **Flexible Configuration**: Environment variables for easy customization
- **GitOps Ready**: Updates K8s manifests in separate repository

---

## Quick Start

### 1. Prerequisites Checklist

- [ ] Jenkins server with required plugins installed
- [ ] Jenkins agent with label `docker-server`
- [ ] Docker installed on agent
- [ ] All credentials configured
- [ ] SonarQube server accessible
- [ ] Application repository with required files

### 2. Create Multi-Branch Pipeline

1. **In Jenkins**: New Item → Multibranch Pipeline
2. **Branch Sources**: Add your Git repository
3. **Credentials**: Select `github-personal-access-token`
4. **Build Configuration**: Script Path = `Jenkinsfile`
5. **Scan Triggers**: Configure as needed
6. **Save** and run "Scan Multibranch Pipeline Now"

### 3. Configure Environment Variables

Edit the `Jenkinsfile` environment section:

```groovy
environment {
    // Update these for your project
    PROJECT_NAME = 'Your Project Name'
    IMAGE_NAME = 'your-image-name'
    HARBOR_PROJECT = 'your-registry-project'
    REGISTRY_URL = 'docker.io'
    NOTIFICATION_EMAIL = 'your-email@example.com'
    GIT_REPO_URL = 'https://github.com/your-org/your-repo.git'
    K8S_MANIFEST_REPO_URL = 'https://github.com/your-org/k8s-manifests.git'
}
```

### 4. Add Required Files to Your Repository

```
your-app-repo/
├── Dockerfile                      # Required
├── trivy-docker-image-scan.sh     # Required for security scanning
├── opa-docker-security.rego       # Required for Dockerfile validation
├── opa-k8s-security.rego          # Required for K8s validation
├── pom.xml (or package.json, etc.) # Build file
└── Jenkinsfile                     # Pipeline definition
```

---

## Requirements

### Jenkins Configuration

#### Required Plugins

| Plugin | Purpose |
|--------|---------|
| Pipeline | Core pipeline functionality |
| Git | Source code management |
| GitHub/GitLab Branch Source | Multi-branch support |
| Docker Pipeline | Docker integration |
| Maven Integration | Maven builds |
| SonarQube Scanner | SAST analysis |
| OWASP Dependency-Check | Dependency scanning |
| Email Extension | Notifications |
| Credentials Binding | Secure credential handling |

**Install via**: Manage Jenkins → Manage Plugins → Available

#### Tool Configuration

**Manage Jenkins → Global Tool Configuration**:

1. **JDK**
   - Name: `jdk21`
   - Install automatically: ✅
   - Version: JDK 21

2. **Maven**
   - Name: `maven-3.8.7`
   - Install automatically: ✅ (Recommended)
   - Version: 3.8.7 or later

> **Note**: Jenkins can auto-install Maven - no manual installation needed on agents!

#### SonarQube Server

**Manage Jenkins → Configure System → SonarQube servers**:
- Name: `SonarQube Server` (must match exactly)
- Server URL: `http://your-sonarqube:9000`
- Server authentication token: (from SonarQube)

### Jenkins Agent Requirements

#### Agent Label
Your pipeline requires: **`docker-server`**

#### Agent Capabilities
- Docker installed and running
- Docker socket accessible (`/var/run/docker.sock`)
- Jenkins user in docker group:
  ```bash
  sudo usermod -aG docker jenkins
  sudo systemctl restart jenkins
  ```

#### Network Access
- GitHub/GitLab
- Docker Hub/Harbor registry
- SonarQube server
- NVD API (nvd.nist.gov)

### Required Credentials

**Manage Jenkins → Credentials → Add Credentials**:

#### 1. Git Access
- **ID**: `github-personal-access-token`
- **Type**: Username with password
- **Username**: Your GitHub username
- **Password**: Personal Access Token (PAT)
- **Scope**: `repo` permission required

#### 2. Docker Registry
- **ID**: `registry-credentials`
- **Type**: Username with password
- **Username**: Docker Hub/Harbor username
- **Password**: Docker Hub/Harbor password or token

#### 3. NVD API Key
- **ID**: `nvd-api-key`
- **Type**: Secret text
- **Secret**: Get from https://nvd.nist.gov/developers/request-an-api-key

### Required Scripts & Policies

#### 1. Trivy Scan Script

**File**: `trivy-docker-image-scan.sh` (in app repo root)

```bash
#!/bin/bash
# Scan base image for vulnerabilities
docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  aquasec/trivy:latest \
  image --severity HIGH,CRITICAL \
  openjdk:21-jdk-slim
```

Make executable: `chmod +x trivy-docker-image-scan.sh`

#### 2. Dockerfile Security Policy

**File**: `opa-docker-security.rego` (in app repo root)

```rego
package main

deny[msg] {
  input[i].Cmd == "from"
  val := input[i].Value
  contains(val[i], "latest")
  msg = "Do not use 'latest' tag for base images"
}

deny[msg] {
  input[i].Cmd == "run"
  val := input[i].Value
  contains(val[_], "sudo")
  msg = "Avoid using 'sudo' in Dockerfile"
}

deny[msg] {
  input[i].Cmd == "user"
  val := input[i].Value
  val[i] == "root"
  msg = "Do not run as root user"
}
```

#### 3. Kubernetes Security Policy

**File**: `opa-k8s-security.rego` (in app repo root)

```rego
package main

deny[msg] {
  input.kind == "Deployment"
  not input.spec.template.spec.securityContext.runAsNonRoot
  msg = "Containers must not run as root"
}

deny[msg] {
  input.kind == "Deployment"
  container := input.spec.template.spec.containers[_]
  not container.securityContext.readOnlyRootFilesystem
  msg = sprintf("Container '%s' should have readOnlyRootFilesystem", [container.name])
}

deny[msg] {
  input.kind == "Deployment"
  container := input.spec.template.spec.containers[_]
  container.securityContext.privileged
  msg = sprintf("Container '%s' should not run in privileged mode", [container.name])
}
```

---

## Configuration Guide

### Environment Variables Reference

| Variable | Description | Example |
|----------|-------------|---------|
| `PROJECT_NAME` | Human-readable project name | `Landlord Management API` |
| `IMAGE_NAME` | Docker image name | `landlord-management-api` |
| `HARBOR_PROJECT` | Registry project/namespace | `munimdevops` |
| `REGISTRY_URL` | Docker registry URL | `docker.io` |
| `REGISTRY_CREDENTIALS_ID` | Jenkins credential ID | `registry-credentials` |
| `NOTIFICATION_EMAIL` | Email recipients (comma-separated) | `dev@example.com` |
| `GIT_REPO_URL` | Application repository URL | `https://github.com/org/repo.git` |
| `GIT_CREDENTIALS_ID` | Git credential ID | `github-personal-access-token` |
| `BRANCH_NAME` | Branch to build | `main` |
| `K8S_MANIFEST_REPO_URL` | K8s manifest repository URL | `https://github.com/org/k8s.git` |
| `K8S_MANIFEST_CREDENTIALS_ID` | K8s repo credential ID | `github-personal-access-token` |
| `K8S_MANIFEST_BRANCH` | K8s manifest branch | `main` |
| `BUILD_TOOL` | Build tool (auto-detected if empty) | `maven`, `npm`, `go` |

### Customizing Build Behavior

#### Override Build Tool
```groovy
environment {
    BUILD_TOOL = 'npm'  // Force NPM instead of auto-detect
}
```

#### Custom Build Commands
```groovy
buildArtifact(
    buildTool: 'maven',
    command: 'mvn clean package -Pproduction'
)
```

#### Skip Quality Gate
```groovy
sonarSast(
    waitForQualityGate: false  // Don't wait for SonarQube
)
```

---

## Pipeline Stages Explained

### 1. Checkout and Git Info
- Clones application repository
- Extracts Git metadata (commit hash, author)
- Sets up workspace

### 2. Send Start Notification
- Sends email notification
- Includes build trigger information
- Notifies configured recipients

### 3. Build Artifact
**Auto-detects** build tool based on files:
- `pom.xml` → Maven
- `package.json` → NPM
- `go.mod` → Go
- `build.gradle` → Gradle

Runs appropriate build command and archives artifacts.

### 4. Unit Tests ⚠️
**Status**: Placeholder (not implemented)

**Needs**: Implementation with JUnit and JaCoCo

### 5. Mutation Tests ⚠️
**Status**: Placeholder (not implemented)

**Needs**: Implementation with PIT

### 6. SonarQube Analysis ✅
- Runs SAST (Static Application Security Testing)
- Uploads results to SonarQube
- Waits for Quality Gate (configurable)
- Fails build if Quality Gate fails

### 7. Vulnerability Scan - Docker ✅
Runs **in parallel**:

1. **Dependency Check**: OWASP scans dependencies
2. **Trivy Scan**: Scans base Docker image
3. **OPA Conftest**: Validates Dockerfile security

### 8. Build Docker Image and Publish ✅
- Builds Docker image with build args
- Tags with build number
- Pushes to registry
- Optionally removes local image

**Build Args Passed**:
- `GIT_AUTHOR`
- `GIT_COMMIT`
- `BUILD_DATE`
- `VERSION`
- `APP_TIMEZONE`

### 9. K8s Manifest Cloning & Update ✅
- Clones K8s manifest repository
- Updates image tags
- Commits and pushes changes

> **⚠️ Known Issue**: K8s security scan runs in wrong stage (see [Missing Components](#missing-components--recommendations))

---

## Missing Components & Recommendations

### 🐛 Critical Bug: K8s Security Scan

**Issue**: The K8s OPA security scan is in the wrong stage and scans the wrong directory.

**Current Code** (in `vulnScanApplicationImage.groovy`):
```groovy
"k8s security check OPA": {
    sh """
    docker run --rm -v "\$(pwd)":/project openpolicyagent/conftest test --policy opa-k8s-security.rego k8s-manifest
    """
}
```

**Problem**: 
- Runs during "Build Docker Image" stage
- Scans application repo, but K8s manifests are in a **separate repository**
- Runs **before** K8s manifests are cloned

**Fix**: Move scan to `k8sManifestScanAndUpdate.groovy` after cloning manifests.

### Missing Security Components

| Component | Priority | Status |
|-----------|----------|--------|
| **Unit Tests** | 🔴 High | Placeholder only |
| **Mutation Tests** | 🟡 Medium | Placeholder only |
| **Secret Scanning** | 🔴 High | Missing |
| **License Compliance** | 🟡 Medium | Missing |
| **DAST** | 🟢 Low | Missing |
| **Container Signing** | 🟡 Medium | Missing |

### Recommendations

#### 1. Implement Unit Tests (High Priority)

```groovy
stage('Unit Tests - JUnit and Jacoco') {
    steps {
        script {
            sh 'mvn test jacoco:report'
            junit '**/target/surefire-reports/*.xml'
            jacoco(
                execPattern: '**/target/jacoco.exec',
                classPattern: '**/target/classes',
                sourcePattern: '**/src/main/java'
            )
        }
    }
}
```

#### 2. Add Secret Scanning (High Priority)

```groovy
stage('Secret Scanning') {
    steps {
        script {
            sh '''
                docker run --rm -v $(pwd):/path \
                    zricethezav/gitleaks:latest \
                    detect --source /path --verbose --no-git
            '''
        }
    }
}
```

Insert after "Checkout and Git Info" stage.

#### 3. Fix K8s Security Scan (Critical)

Move the OPA scan from `vulnScanApplicationImage.groovy` to `k8sManifestScanAndUpdate.groovy`:

```groovy
// After cloning K8s manifests
sh """
    docker run --rm \
        -v ${tempDir}:/project \
        -v ${env.WORKSPACE}/opa-k8s-security.rego:/opa-k8s-security.rego \
        openpolicyagent/conftest test \
        --policy /opa-k8s-security.rego \
        /project/*.yaml /project/**/*.yaml
"""
```

#### 4. Add License Compliance Scanning

```groovy
"License Compliance": {
    sh 'mvn license:add-third-party'
    sh 'mvn license:download-licenses'
}
```

Add to parallel vulnerability scan stage.

---

## Troubleshooting

### Common Issues

#### "Agent with label 'docker-server' not found"
**Solution**: Create Jenkins agent with label `docker-server`
- Manage Jenkins → Manage Nodes → New Node

#### "Docker command not found"
**Solution**: Install Docker on agent and add to PATH
```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker jenkins
sudo systemctl restart jenkins
```

#### "Permission denied accessing Docker socket"
**Solution**: Add Jenkins user to docker group
```bash
sudo usermod -aG docker jenkins
sudo systemctl restart jenkins
```

#### "SonarQube server not found"
**Solution**: Configure SonarQube with exact name `SonarQube Server`
- Manage Jenkins → Configure System → SonarQube servers

#### "Credential 'xyz' not found"
**Solution**: Verify credential IDs match exactly (case-sensitive)
- Manage Jenkins → Credentials

#### "NVD API rate limit exceeded"
**Solution**: Get API key from https://nvd.nist.gov/developers/request-an-api-key

#### "Quality Gate timeout"
**Solution**: Increase timeout or disable Quality Gate wait
```groovy
sonarSast(
    waitForQualityGate: false
    // or
    timeoutMinutes: 10
)
```

#### Scripts not found (trivy-docker-image-scan.sh, *.rego)
**Solution**: Add required files to application repository root

### Debug Tips

1. **Check Console Output**: Click on build → Console Output
2. **Verify Credentials**: Test Git clone and Docker login manually
3. **Test Docker**: Run `docker ps` on agent
4. **Check Network**: Verify connectivity to external services
5. **Review Logs**: Check Jenkins system logs for errors

---

## Best Practices

### Security

- ✅ Use credential IDs, never hardcode secrets
- ✅ Enable Quality Gate enforcement
- ✅ Review security scan results regularly
- ✅ Keep base images updated
- ✅ Implement secret scanning
- ✅ Sign container images (recommended)

### Performance

- ✅ Use parallel stages where possible
- ✅ Cache dependencies (Maven local repo, npm cache)
- ✅ Use shallow Git clones (`--depth 1`)
- ✅ Remove Docker images after push
- ✅ Limit build history retention

### Maintenance

- ✅ Keep Jenkins and plugins updated
- ✅ Review and update security policies regularly
- ✅ Monitor build times and optimize slow stages
- ✅ Document custom configurations
- ✅ Use semantic versioning for images

### GitOps

- ✅ Keep K8s manifests in separate repository
- ✅ Use meaningful commit messages for manifest updates
- ✅ Implement approval process for production deployments
- ✅ Tag releases in Git

---

## Additional Resources

- **Jenkins Pipeline**: https://www.jenkins.io/doc/book/pipeline/
- **SonarQube**: https://docs.sonarqube.org/
- **OWASP Dependency-Check**: https://jeremylong.github.io/DependencyCheck/
- **Trivy**: https://aquasecurity.github.io/trivy/
- **OPA Conftest**: https://www.conftest.dev/
- **Docker Best Practices**: https://docs.docker.com/develop/dev-best-practices/

---

## Support

For issues or questions:
1. Review this documentation
2. Check troubleshooting section
3. Review console output for specific errors
4. Consult Jenkins and tool-specific documentation

---

**Last Updated**: 2026-02-09  
**Pipeline Version**: 1.0  
**Maintained by**: DevOps Team
