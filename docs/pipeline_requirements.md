# Jenkins DevSecOps Pipeline - Complete Requirements Guide

## Overview
This document outlines all requirements, dependencies, and configurations needed to run your Jenkins multi-branch DevSecOps pipeline successfully.

---

## 1. Jenkins Configuration

### Jenkins Version
- **Minimum**: Jenkins 2.387+ (LTS recommended)
- **Recommended**: Latest LTS version

### Required Jenkins Plugins

#### Core Plugins
- **Pipeline** (workflow-aggregator)
- **Git Plugin** (git)
- **GitHub Branch Source** (github-branch-source) or **GitLab Branch Source** (gitlab-branch-source)
- **Credentials Binding** (credentials-binding)
- **Docker Pipeline** (docker-workflow)
- **Pipeline: Stage View** (pipeline-stage-view)

#### Build & Test Plugins
- **Maven Integration** (maven-plugin)
- **JUnit** (junit)
- **JaCoCo** (jacoco)

#### Security & Quality Plugins
- **SonarQube Scanner** (sonar)
- **OWASP Dependency-Check** (dependency-check-jenkins-plugin)

#### Notification Plugins
- **Email Extension** (email-ext)
- **Mailer** (mailer)

#### Shared Library Support
- **Pipeline: Shared Groovy Libraries** (workflow-cps-global-lib)

---

## 2. Jenkins Agent/Node Requirements

### Agent Label
Your pipeline requires an agent with label: **`docker-server`**

```groovy
agent {
    label 'docker-server'
}
```

### Node Capabilities Required
The agent must have:
- Docker daemon installed and running
- Docker socket accessible (`/var/run/docker.sock`)
- Network access to:
  - GitHub (or your Git server)
  - Docker Hub / Harbor registry
  - SonarQube server
  - NVD API (for vulnerability scanning)

---

## 3. Tool Installations (Jenkins Global Tool Configuration)

Configure these tools in **Manage Jenkins → Global Tool Configuration**:

### Java (JDK)
- **Name**: `jdk21`
- **Version**: JDK 21
- **Installation**: Automatic installer or manual path

### Maven
- **Name**: `maven-3.8.7`
- **Version**: Maven 3.8.7+
- **Installation**: Automatic installer or manual path

### Additional Tools (Based on Build Type)
Your pipeline auto-detects build tools, so you may need:

#### For Node.js/NPM Projects
- **Node.js**: Latest LTS version
- **NPM**: Comes with Node.js

#### For Go Projects
- **Go**: Latest stable version

#### For Gradle Projects
- **Gradle**: 7.x or 8.x

#### For .NET Projects
- **.NET SDK**: 6.0 or later

---

## 4. Jenkins Credentials

Configure these credentials in **Manage Jenkins → Credentials**:

### Git Access
- **ID**: `github-personal-access-token`
- **Type**: Username with password
- **Username**: Your GitHub username
- **Password**: Personal Access Token (PAT) with `repo` scope
- **Scope**: Global

### Docker Registry
- **ID**: `registry-credentials`
- **Type**: Username with password
- **Username**: Docker Hub / Harbor username
- **Password**: Docker Hub / Harbor password or token
- **Scope**: Global

### NVD API Key (for Dependency Check)
- **ID**: `nvd-api-key`
- **Type**: Secret text
- **Secret**: Your NVD API key from https://nvd.nist.gov/developers/request-an-api-key
- **Scope**: Global

---

## 5. External Service Configurations

### SonarQube Server
Configure in **Manage Jenkins → Configure System → SonarQube servers**:
- **Name**: `SonarQube Server` (must match pipeline config)
- **Server URL**: Your SonarQube instance URL
- **Server authentication token**: SonarQube token (created in SonarQube)

### Email Notifications
Configure in **Manage Jenkins → Configure System → Extended E-mail Notification**:
- **SMTP server**: Your SMTP server
- **SMTP port**: Usually 587 (TLS) or 465 (SSL)
- **Credentials**: Email account credentials
- **Default recipients**: Can be overridden in pipeline

---

## 6. Docker & Container Requirements

### On Jenkins Agent

#### Docker Installation
```bash
# Verify Docker is installed
docker --version

# Verify Docker daemon is running
docker ps
```

#### Required Docker Images
The pipeline will pull these automatically, but pre-pulling can speed up builds:

```bash
# Trivy scanner
docker pull aquasec/trivy:latest

# OPA Conftest
docker pull openpolicyagent/conftest:latest
```

#### Docker Socket Access
Ensure Jenkins user has access to Docker socket:
```bash
sudo usermod -aG docker jenkins
# Restart Jenkins after this change
```

---

## 7. Security Scanning Tools & Scripts

### Required Files in Your Application Repository

#### Trivy Script
- **File**: `trivy-docker-image-scan.sh`
- **Purpose**: Scans base Docker images for vulnerabilities
- **Location**: Root of your application repository

**Example script**:
```bash
#!/bin/bash
# trivy-docker-image-scan.sh
docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  aquasec/trivy:latest \
  image --severity HIGH,CRITICAL \
  openjdk:21-jdk-slim
```

#### OPA Policies
You need two OPA policy files:

1. **File**: `opa-docker-security.rego`
   - **Purpose**: Validates Dockerfile security best practices
   - **Location**: Root of your application repository

2. **File**: `opa-k8s-security.rego`
   - **Purpose**: Validates Kubernetes manifest security
   - **Location**: Root of your application repository

**Example Dockerfile policy** (`opa-docker-security.rego`):
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
```

**Example K8s policy** (`opa-k8s-security.rego`):
```rego
package main

deny[msg] {
  input.kind == "Deployment"
  not input.spec.template.spec.securityContext.runAsNonRoot
  msg = "Containers must not run as root"
}
```

---

## 8. Kubernetes Manifest Repository

### Separate K8s Repository
Your pipeline updates Kubernetes manifests in a separate repository:

- **Repository URL**: Configured in `K8S_MANIFEST_REPO_URL`
- **Credentials**: Uses same Git credentials (`github-personal-access-token`)
- **Branch**: `main` (configurable via `K8S_MANIFEST_BRANCH`)

### Required Structure
The K8s repository should contain:
- Deployment manifests
- Service manifests
- ConfigMaps, Secrets, etc.

---

## 9. Environment Variables to Configure

Update these in your [Jenkinsfile](file:///d:/DevSecOps%20Pipeline/Jenkinsfile):

### Project-Specific
```groovy
PROJECT_NAME = 'Your Project Name'
IMAGE_NAME = 'your-image-name'
HARBOR_PROJECT = 'your-harbor-project'
REGISTRY_URL = 'docker.io' // or your registry
```

### Notifications
```groovy
NOTIFICATION_EMAIL = 'your-email@example.com'
```

### Git Configuration
```groovy
GIT_REPO_URL = 'https://github.com/your-org/your-repo.git'
BRANCH_NAME = 'main'
```

### K8s Manifest Repository
```groovy
K8S_MANIFEST_REPO_URL = 'https://github.com/your-org/k8s-manifests.git'
K8S_MANIFEST_BRANCH = 'main'
K8S_MANIFEST_PATHS = 'k8s/deployment.yaml,k8s/ingress.yaml' // Comma-separated paths
```

---

## 10. Shared Library Configuration

### Library Setup
Your pipeline loads the shared library from:
```groovy
library identifier: 'jenkins-shared-library@main', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/ALabiyb/devsecops.git',
    traits: [[$class: 'jenkins.plugins.git.traits.BranchDiscoveryTrait']]
])
```

### Library Structure
```
devsecops/
├── vars/
│   ├── buildArtifact.groovy
│   ├── buildDockerImageAndPush.groovy
│   ├── checkoutAndGitInfo.groovy
│   ├── detectBuildTrigger.groovy
│   ├── updateK8sManifest.groovy
│   ├── k8sManifestScanAndUpdate.groovy
│   ├── sendFailureNotification.groovy
│   ├── sendStartNotification.groovy
│   ├── sendSuccessNotification.groovy
│   ├── sonarSast.groovy
│   ├── vulnScanDocker.groovy
│   └── vulnScanApplicationImage.groovy
└── resources/
```

---

## 11. Pipeline Stages Breakdown

### Stage 1: Checkout and Git Info
- Clones your application repository
- Extracts Git metadata (commit, author, etc.)

### Stage 2: Send Start Notification
- Sends email notification that build started
- Includes trigger information

### Stage 3: Build Artifact
- **Auto-detects** build tool (Maven, NPM, Go, Gradle, .NET)
- Builds application artifact
- Archives artifacts

### Stage 4: Unit Tests
- Currently placeholder (you need to implement)

### Stage 5: Mutation Tests
- Currently placeholder (you need to implement)

### Stage 6: SonarQube Analysis
- Runs SAST analysis
- Waits for Quality Gate (configurable)
- **Requires**: SonarQube server configured

### Stage 7: Vulnerability Scan - Docker
Runs in parallel:
1. **Dependency Check**: Scans dependencies using OWASP
2. **Trivy Scan**: Scans base Docker image
3. **OPA Conftest**: Validates Dockerfile against security policies

### Stage 8: Build Docker Image and Publish
- Builds Docker image with build args
- Tags with build number
- Pushes to registry
- Optionally removes local image

### Stage 9: K8s Manifest Cloning & Update
- Clones K8s manifest repository
- Updates image tags
- Commits and pushes changes

---

## 12. Pre-Flight Checklist

Before running your pipeline, ensure:

### Jenkins Setup
- [ ] All required plugins installed
- [ ] Tools configured (JDK, Maven, etc.)
- [ ] All credentials created
- [ ] SonarQube server configured
- [ ] Email notifications configured

### Jenkins Agent
- [ ] Agent with label `docker-server` exists
- [ ] Docker installed and running on agent
- [ ] Jenkins user has Docker permissions
- [ ] Network connectivity to all external services

### Application Repository
- [ ] Dockerfile exists
- [ ] `trivy-docker-image-scan.sh` exists
- [ ] `opa-docker-security.rego` exists
- [ ] `opa-k8s-security.rego` exists (if using K8s stage)
- [ ] Build file exists (pom.xml, package.json, etc.)

### K8s Manifest Repository
- [ ] Repository exists and accessible
- [ ] Contains valid Kubernetes manifests
- [ ] Credentials have write access

### External Services
- [ ] SonarQube server accessible
- [ ] Docker registry accessible
- [ ] NVD API key valid
- [ ] Email server configured

### Environment Variables
- [ ] All variables in Jenkinsfile updated for your project
- [ ] Credential IDs match your Jenkins credentials
- [ ] Registry URLs correct
- [ ] Email addresses correct

---

## 13. Common Issues & Troubleshooting

### Issue: "Agent with label 'docker-server' not found"
**Solution**: Create a Jenkins agent and assign label `docker-server`

### Issue: "Docker command not found"
**Solution**: Install Docker on Jenkins agent and ensure it's in PATH

### Issue: "Permission denied accessing Docker socket"
**Solution**: Add Jenkins user to docker group:
```bash
sudo usermod -aG docker jenkins
sudo systemctl restart jenkins
```

### Issue: "SonarQube server not found"
**Solution**: Configure SonarQube server in Jenkins with exact name `SonarQube Server`

### Issue: "Credential not found"
**Solution**: Verify credential IDs match exactly (case-sensitive)

### Issue: "NVD API rate limit exceeded"
**Solution**: Get API key from https://nvd.nist.gov/developers/request-an-api-key

### Issue: "Trivy/OPA scripts not found"
**Solution**: Add required scripts to your application repository root

### Issue: "Quality Gate timeout"
**Solution**: Increase `timeoutMinutes` in SonarQube stage or disable Quality Gate wait

---

## 14. Next Steps

1. **Review this document** and ensure you understand all requirements
2. **Set up Jenkins** with all required plugins and tools
3. **Configure credentials** for Git, Docker, NVD
4. **Set up Jenkins agent** with Docker
5. **Configure SonarQube** server connection
6. **Add required scripts** to your application repository
7. **Update Jenkinsfile** environment variables
8. **Create test pipeline** to verify setup
9. **Run pipeline** and monitor for issues

---

## 15. Additional Resources

- [Jenkins Pipeline Documentation](https://www.jenkins.io/doc/book/pipeline/)
- [SonarQube Scanner for Jenkins](https://docs.sonarqube.org/latest/analysis/scan/sonarscanner-for-jenkins/)
- [OWASP Dependency-Check](https://jeremylong.github.io/DependencyCheck/dependency-check-maven/)
- [Trivy Documentation](https://aquasecurity.github.io/trivy/)
- [OPA Conftest](https://www.conftest.dev/)
- [Docker Best Practices](https://docs.docker.com/develop/dev-best-practices/)
