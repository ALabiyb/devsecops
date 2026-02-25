# Multibranch Pipeline - Usage Guide

## 🎯 What is a Multibranch Pipeline?

A **Multibranch Pipeline** automatically creates a pipeline for each branch in your repository. When you push a new branch, Jenkins automatically:
1. Discovers the branch
2. Creates a pipeline job for it
3. Runs the Jenkinsfile from that branch

---

## 📋 How to Set Up Multibranch Pipeline

### Step 1: Create the Job in Jenkins

1. **Go to Jenkins Dashboard**
2. **Click**: "New Item"
3. **Enter name**: e.g., `landlord-management-api`
4. **Select**: "Multibranch Pipeline"
5. **Click**: "OK"

### Step 2: Configure Branch Sources

1. **Branch Sources** → Click "Add source" → Select "Git" or "GitHub"

2. **For Git**:
   ```
   Project Repository: https://github.com/ALabiyb/Landlord-Managment-API.git
   Credentials: github-personal-access-token
   ```

3. **For GitHub** (recommended):
   ```
   Repository HTTPS URL: https://github.com/ALabiyb/Landlord-Managment-API.git
   Credentials: github-personal-access-token
   ```

### Step 3: Configure Behaviors

**Discover branches**: 
- ✅ All branches
- Or filter by name (e.g., only `main`, `develop`, `feature/*`)

**Discover pull requests**:
- ✅ Enable if you want to build PRs

### Step 4: Build Configuration

**Mode**: by Jenkinsfile
**Script Path**: `Jenkinsfile` (default)

### Step 5: Scan Triggers

**Periodically if not otherwise run**:
- Interval: 1 hour (or as needed)

**Scan by webhook**:
- Configure webhook in GitHub/GitLab for instant builds

### Step 6: Save and Scan

1. Click **"Save"**
2. Click **"Scan Multibranch Pipeline Now"**
3. Jenkins will discover branches and create jobs

---

## 🔧 How Your Application Uses This Pipeline

### Your Application Repository Structure

```
your-application-repo/
├── Jenkinsfile                    # Pipeline definition (uses shared library)
├── Dockerfile                     # Docker image definition
├── pom.xml                        # Maven build file
├── src/                           # Application source code
├── trivy-docker-image-scan.sh    # Security scanning script
├── opa-docker-security.rego      # Dockerfile security policy
└── opa-k8s-security.rego         # K8s security policy
```

### The Jenkinsfile

Your `Jenkinsfile` is the **entry point**. It:
1. Loads the shared library from `https://github.com/ALabiyb/devsecops.git`
2. Defines environment variables
3. Calls shared library functions

**Example from your Jenkinsfile**:
```groovy
// Load shared library
library identifier: 'jenkins-shared-library@main', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/ALabiyb/devsecops.git',
    traits: [[$class: 'jenkins.plugins.git.traits.BranchDiscoveryTrait']]
])

pipeline {
    agent {
        label 'docker-server'  // ← Must have agent with this label
    }
    
    environment {
        // ← EDIT THESE FOR YOUR PROJECT
        PROJECT_NAME = 'Landlord Management API'
        IMAGE_NAME = 'landlord-management-api'
        HARBOR_PROJECT = 'munimdevops'
        REGISTRY_URL = 'docker.io'
        NOTIFICATION_EMAIL = 'hackermunim@gmail.com'
        // ... more variables
    }
    
    stages {
        stage('Build') {
            steps {
                script {
                    buildArtifact()  // ← Calls shared library function
                }
            }
        }
    }
}
```

---

## ✏️ Variables You MUST Edit

### In Your Application's Jenkinsfile

Open `Jenkinsfile` in your application repository and update:

#### 1. Project Information
```groovy
PROJECT_NAME = 'Your Project Name'           // ← Change this
IMAGE_NAME = 'your-docker-image-name'        // ← Change this
HARBOR_PROJECT = 'your-registry-project'     // ← Change this
```

#### 2. Registry Configuration
```groovy
REGISTRY_URL = 'docker.io'                   // ← Your registry (docker.io, harbor.example.com, etc.)
REGISTRY_CREDENTIALS_ID = 'registry-credentials'  // ← Must match Jenkins credential ID
```

#### 3. Notification Settings
```groovy
NOTIFICATION_EMAIL = 'your-email@example.com'  // ← Your email (comma-separated for multiple)
```

#### 4. Git Configuration
```groovy
GIT_REPO_URL = 'https://github.com/your-org/your-repo.git'  // ← Your app repo
BRANCH_NAME = 'main'                         // ← Branch to build
GIT_CREDENTIALS_ID = 'github-personal-access-token'  // ← Must match Jenkins credential ID
```

#### 5. Kubernetes Manifest Repository
```groovy
K8S_MANIFEST_REPO_URL = 'https://github.com/your-org/k8s-manifests.git'  // ← Your K8s repo
K8S_MANIFEST_CREDENTIALS_ID = 'github-personal-access-token'  // ← Must match Jenkins credential ID
K8S_MANIFEST_BRANCH = 'main'                 // ← K8s manifest branch
```

#### 6. Build Tool (Optional - Auto-detected)
```groovy
BUILD_TOOL = 'maven'  // ← Leave empty for auto-detection, or specify: maven, npm, go, gradle, dotnet
```

---

## 🔌 How to Call Shared Library Functions

### Understanding Function Parameters

Each shared library function accepts a `Map` of parameters. You can:
1. **Use defaults** (from environment variables)
2. **Override specific parameters**

### Example: buildArtifact()

**Simple call (uses all defaults)**:
```groovy
buildArtifact()
```

**Override build tool**:
```groovy
buildArtifact(buildTool: 'npm')
```

**Override build command**:
```groovy
buildArtifact(
    buildTool: 'maven',
    command: 'mvn clean package -Pproduction -DskipTests=false'
)
```

### Example: sonarSast()

**Simple call**:
```groovy
sonarSast(
    sonarServer: 'SonarQube Server',
    projectKey: 'my-project',
    projectName: 'My Project',
    waitForQualityGate: true,
    timeoutMinutes: 5
)
```

**All parameters are optional** - defaults from environment:
```groovy
sonarSast()  // Uses env.JOB_NAME for projectKey and projectName
```

### Example: buildDockerImageAndPush()

**Full example**:
```groovy
def result = buildDockerImageAndPush(
    imageName: env.IMAGE_NAME,              // From environment
    imageTag: env.BUILD_NUMBER,             // From environment
    harborProject: env.HARBOR_PROJECT,      // From environment
    registryUrl: env.REGISTRY_URL,          // From environment
    registryCredentialsId: env.REGISTRY_CREDENTIALS_ID,
    pushToRegistry: true,
    buildArgs: [
        GIT_AUTHOR: env.GIT_AUTHOR,
        GIT_COMMIT: env.GIT_COMMIT,
        BUILD_DATE: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC')),
        VERSION: "1.0.${env.BUILD_NUMBER}"
    ]
)

// Save result for later use
env.FINAL_IMAGE_NAME = result.localImageName
```

---

## 📖 Complete Function Reference

### 1. checkoutAndGitInfo()

**Purpose**: Checkout code and extract Git metadata

**Parameters**:
- `repo` (optional): Git repository URL (default: `env.GIT_REPO_URL`)
- `credentialsId` (optional): Credential ID (default: `env.GIT_CREDENTIALS_ID`)
- `branch` (optional): Branch name (default: `env.BRANCH_NAME`)

**Example**:
```groovy
checkoutAndGitInfo()  // Uses environment variables
```

---

### 2. buildArtifact()

**Purpose**: Build application artifact (auto-detects build tool)

**Parameters**:
- `buildTool` (optional): Build tool (`maven`, `npm`, `go`, `gradle`, `dotnet`)
- `command` (optional): Custom build command
- `artifacts` (optional): Artifact path pattern

**Examples**:
```groovy
buildArtifact()  // Auto-detect and use defaults

buildArtifact(buildTool: 'maven')  // Force Maven

buildArtifact(
    buildTool: 'maven',
    command: 'mvn clean package -Pproduction'
)

buildArtifact(
    buildTool: 'npm',
    installCommand: 'npm ci',
    buildCommand: 'npm run build:prod'
)
```

---

### 3. sonarSast()

**Purpose**: Run SonarQube SAST analysis

**Parameters**:
- `sonarServer` (optional): SonarQube server name (default: `'SonarQube Server'`)
- `projectKey` (optional): Project key (default: `env.JOB_NAME`)
- `projectName` (optional): Project name (default: `env.JOB_NAME`)
- `waitForQualityGate` (optional): Wait for Quality Gate (default: `false`)
- `timeoutMinutes` (optional): Quality Gate timeout (default: `5`)

**Example**:
```groovy
sonarSast(
    projectKey: 'landlord-api',
    projectName: 'Landlord Management API',
    waitForQualityGate: true,
    timeoutMinutes: 10
)
```

---

### 4. vulnScanDocker()

**Purpose**: Run vulnerability scans on Docker/dependencies

**Parameters**:
- `trivyCmd` (optional): Custom Trivy command

**Example**:
```groovy
vulnScanDocker()  // Uses defaults
```

**Runs in parallel**:
- OWASP Dependency Check
- Trivy base image scan
- OPA Dockerfile validation

---

### 5. buildDockerImageAndPush()

**Purpose**: Build and push Docker image

**Parameters**:
- `imageName` (required): Image name
- `imageTag` (required): Image tag
- `harborProject` (optional): Registry project/namespace
- `registryUrl` (optional): Registry URL
- `registryCredentialsId` (optional): Credential ID
- `dockerfilePath` (optional): Dockerfile path (default: `'Dockerfile'`)
- `buildContext` (optional): Build context (default: `'.'`)
- `buildArgs` (optional): Build arguments (Map)
- `pushToRegistry` (optional): Push to registry (default: `false`)
- `removeAfterPush` (optional): Remove local image (default: `true`)

**Example**:
```groovy
def result = buildDockerImageAndPush(
    imageName: 'my-app',
    imageTag: '1.0.0',
    harborProject: 'myproject',
    registryUrl: 'docker.io',
    registryCredentialsId: 'registry-credentials',
    pushToRegistry: true,
    buildArgs: [
        VERSION: '1.0.0',
        BUILD_DATE: '2024-01-01'
    ]
)

echo "Built image: ${result.imageName}"
```

---

### 6. k8sManifestScanAndUpdate()

**Purpose**: Clone K8s manifests, scan, and update

**Parameters**: Uses environment variables
- `K8S_MANIFEST_REPO_URL`
- `K8S_MANIFEST_CREDENTIALS_ID`
- `K8S_MANIFEST_BRANCH`
- `IMAGE_NAME`
- `BUILD_NUMBER`

**Example**:
```groovy
k8sManifestScanAndUpdate()  // Uses environment variables
```

---

### 7. Notification Functions

**sendStartNotification()**:
```groovy
sendStartNotification(
    subject: "🚀 Build Started: ${env.JOB_NAME}",
    recipients: env.NOTIFICATION_EMAIL,
    triggeredBy: detectBuildTrigger()
)
```

**sendSuccessNotification()**:
```groovy
sendSuccessNotification(
    recipients: env.NOTIFICATION_EMAIL,
    triggeredBy: detectBuildTrigger()
)
```

**sendFailureNotification()**:
```groovy
sendFailureNotification(
    recipients: env.NOTIFICATION_EMAIL,
    triggeredBy: detectBuildTrigger()
)
```

---

## 🎨 Customization Examples

### Example 1: Different Build for Different Branches

```groovy
stage('Build Artifact') {
    steps {
        script {
            if (env.BRANCH_NAME == 'main') {
                buildArtifact(command: 'mvn clean package -Pproduction')
            } else {
                buildArtifact(command: 'mvn clean package -Pdevelopment')
            }
        }
    }
}
```

### Example 2: Skip Stages for Feature Branches

```groovy
stage('Deploy to Production') {
    when {
        branch 'main'  // Only run on main branch
    }
    steps {
        script {
            // Deployment logic
        }
    }
}
```

### Example 3: Custom Docker Build Args per Branch

```groovy
stage('Build Docker Image') {
    steps {
        script {
            def buildArgs = [
                GIT_COMMIT: env.GIT_COMMIT,
                VERSION: env.BUILD_NUMBER
            ]
            
            if (env.BRANCH_NAME == 'main') {
                buildArgs.ENVIRONMENT = 'production'
            } else {
                buildArgs.ENVIRONMENT = 'development'
            }
            
            buildDockerImageAndPush(
                imageName: env.IMAGE_NAME,
                imageTag: env.BUILD_NUMBER,
                buildArgs: buildArgs,
                pushToRegistry: true
            )
        }
    }
}
```

---

## 🚀 Quick Start Workflow

1. **Fork/Clone** the shared library: `https://github.com/ALabiyb/devsecops.git`
2. **Create** your application repository with `Jenkinsfile`
3. **Edit** environment variables in `Jenkinsfile`
4. **Add** required scripts (`trivy-docker-image-scan.sh`, `*.rego`)
5. **Create** multibranch pipeline in Jenkins
6. **Configure** branch source and credentials
7. **Scan** for branches
8. **Watch** the pipeline run!

---

## 📞 Need Help?

- See [README.md](file:///d:/DevSecOps%20Pipeline/README.md) for complete documentation
- Check [function_documentation.md](file:///d:/DevSecOps%20Pipeline/docs/function_documentation.md) for detailed function docs
- Review console output for errors
