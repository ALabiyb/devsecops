# DevSecOps Pipeline - Quick Setup Checklist

Use this checklist to ensure you have everything configured before running your pipeline.

---

## ✅ Jenkins Server Setup

### Plugins Installation
- [ ] Pipeline (workflow-aggregator)
- [ ] Git Plugin
- [ ] GitHub/GitLab Branch Source
- [ ] Credentials Binding
- [ ] Docker Pipeline
- [ ] Maven Integration
- [ ] SonarQube Scanner
- [ ] OWASP Dependency-Check
- [ ] Email Extension
- [ ] Pipeline: Shared Groovy Libraries

### Global Tool Configuration
- [ ] JDK 21 configured with name: `jdk21`
- [ ] Maven 3.8.7+ configured with name: `maven-3.8.7`
- [ ] (Optional) Node.js for NPM projects
- [ ] (Optional) Go for Go projects
- [ ] (Optional) Gradle for Gradle projects

### System Configuration
- [ ] SonarQube server configured
  - Name: `SonarQube Server`
  - Server URL: `http://your-sonarqube:9000`
  - Authentication token added
- [ ] Email notifications configured
  - SMTP server settings
  - Default sender email
  - Test email sent successfully

---

## ✅ Jenkins Agent (docker-server)

### Agent Configuration
- [ ] Agent created with label: `docker-server`
- [ ] Agent is online and connected
- [ ] Agent has sufficient disk space (20GB+ recommended)

### Docker Installation
- [ ] Docker installed on agent
  ```bash
  docker --version  # Should show version
  ```
- [ ] Docker daemon running
  ```bash
  docker ps  # Should list containers
  ```
- [ ] Jenkins user has Docker permissions
  ```bash
  sudo usermod -aG docker jenkins
  sudo systemctl restart jenkins
  ```

### Network Connectivity
- [ ] Can reach GitHub/GitLab
- [ ] Can reach Docker Hub/Harbor registry
- [ ] Can reach SonarQube server
- [ ] Can reach NVD API (nvd.nist.gov)

---

## ✅ Jenkins Credentials

### Git Access
- [ ] Credential ID: `github-personal-access-token`
- [ ] Type: Username with password
- [ ] Username: Your GitHub username
- [ ] Password: Personal Access Token with `repo` scope
- [ ] Test: Can clone your repository

### Docker Registry
- [ ] Credential ID: `registry-credentials`
- [ ] Type: Username with password
- [ ] Username: Docker Hub/Harbor username
- [ ] Password: Docker Hub/Harbor password
- [ ] Test: Can login to registry
  ```bash
  docker login docker.io -u <username>
  ```

### NVD API Key
- [ ] Credential ID: `nvd-api-key`
- [ ] Type: Secret text
- [ ] Secret: API key from https://nvd.nist.gov/developers/request-an-api-key
- [ ] Test: API key is valid (not expired)

---

## ✅ External Services

### SonarQube
- [ ] SonarQube server is running
- [ ] Can access SonarQube web UI
- [ ] Project created (or will be auto-created)
- [ ] Quality Gate configured (optional)
- [ ] Jenkins integration token created

### Docker Registry
- [ ] Registry is accessible (Docker Hub or Harbor)
- [ ] Project/namespace exists (e.g., `munimdevops`)
- [ ] Credentials have push permissions
- [ ] Test push/pull works

### Email Server
- [ ] SMTP server accessible
- [ ] Credentials valid
- [ ] Test email sent successfully
- [ ] Recipient email addresses correct

---

## ✅ Application Repository

### Required Files
- [ ] `Dockerfile` exists in repository root
- [ ] `trivy-docker-image-scan.sh` exists
- [ ] `opa-docker-security.rego` exists
- [ ] `opa-k8s-security.rego` exists (if using K8s stage)
- [ ] Build file exists (`pom.xml`, `package.json`, etc.)

### Dockerfile Validation
- [ ] Dockerfile builds successfully locally
  ```bash
  docker build -t test .
  ```
- [ ] Base image is accessible
- [ ] No syntax errors

### Scripts Validation
- [ ] `trivy-docker-image-scan.sh` is executable
  ```bash
  chmod +x trivy-docker-image-scan.sh
  ```
- [ ] Script runs successfully locally
- [ ] OPA policies are valid

---

## ✅ K8s Manifest Repository (Optional)

### Repository Setup
- [ ] K8s manifest repository exists
- [ ] Repository is accessible with credentials
- [ ] Contains valid Kubernetes manifests
- [ ] Credentials have write access (for updates)

### Manifest Structure
- [ ] Deployment YAML exists
- [ ] Service YAML exists (if needed)
- [ ] Image references are correct format
- [ ] Manifests are valid
  ```bash
  kubectl apply --dry-run=client -f deployment.yaml
  ```

---

## ✅ Jenkinsfile Configuration

### Environment Variables Updated
- [ ] `PROJECT_NAME` - Your project name
- [ ] `IMAGE_NAME` - Your Docker image name
- [ ] `HARBOR_PROJECT` - Your registry project/namespace
- [ ] `REGISTRY_URL` - Your registry URL (e.g., `docker.io`)
- [ ] `NOTIFICATION_EMAIL` - Your email address
- [ ] `GIT_REPO_URL` - Your application repository URL
- [ ] `BRANCH_NAME` - Branch to build (e.g., `main`)
- [ ] `K8S_MANIFEST_REPO_URL` - K8s manifest repository URL
- [ ] `K8S_MANIFEST_BRANCH` - K8s manifest branch

### Credential IDs Match
- [ ] `REGISTRY_CREDENTIALS_ID` matches your credential
- [ ] `GIT_CREDENTIALS_ID` matches your credential
- [ ] `K8S_MANIFEST_CREDENTIALS_ID` matches your credential

---

## ✅ Multi-Branch Pipeline Setup

### Pipeline Configuration
- [ ] Multi-branch pipeline job created in Jenkins
- [ ] Repository URL configured
- [ ] Credentials selected
- [ ] Branch discovery configured
- [ ] Jenkinsfile path: `Jenkinsfile` (default)
- [ ] Scan triggers configured (optional)

### Shared Library
- [ ] Library accessible: `https://github.com/ALabiyb/devsecops.git`
- [ ] Branch `main` exists
- [ ] All required `.groovy` files present in `vars/`

---

## ✅ Pre-Flight Test

### Manual Verification
- [ ] Run `Scan Multibranch Pipeline Now` in Jenkins
- [ ] Verify branches are discovered
- [ ] Check first build starts
- [ ] Monitor console output for errors

### Common First-Run Issues
- [ ] Agent connection issues → Check agent status
- [ ] Credential errors → Verify credential IDs match
- [ ] Docker errors → Check Docker permissions
- [ ] SonarQube errors → Verify server configuration
- [ ] Script not found → Check files exist in repo

---

## 🚀 Ready to Run

Once all items are checked:
1. Commit and push your Jenkinsfile and required scripts
2. Trigger a build manually or via webhook
3. Monitor the pipeline execution
4. Review console output for any issues
5. Check email notifications

---

## 📚 Reference Documents

For detailed information, see:
- [pipeline_requirements.md](file:///C:/Users/Munim/.gemini/antigravity/brain/2023b665-6718-4b31-b5bb-fb780a66c61c/pipeline_requirements.md) - Complete requirements guide
- [Jenkinsfile](file:///d:/DevSecOps%20Pipeline/Jenkinsfile) - Your pipeline configuration
- Shared library: `d:/DevSecOps Pipeline/devsecops/vars/` - All pipeline functions

---

## 🆘 Need Help?

If you encounter issues:
1. Check console output for specific error messages
2. Review troubleshooting section in `pipeline_requirements.md`
3. Verify all checklist items are completed
4. Test individual components (Docker, Git, SonarQube) separately
