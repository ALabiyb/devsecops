# Shared Library Functions - Complete Documentation

This document provides detailed documentation for all shared library functions with parameter explanations and code comments.

---

## 📋 Table of Contents

1. [buildArtifact](#buildartifact)
2. [buildDockerImageAndPush](#builddockerimagean dpush)
3. [checkoutAndGitInfo](#checkoutandgitinfo)
4. [sonarSast](#sonarsast)
5. [vulnScanDocker](#vulnscandocker)
6. [vulnScanApplicationImage](#vulnscanapplicationimage)
7. [k8sManifestScanAndUpdate](#k8smanifestscanandupdate)
8. [Notification Functions](#notification-functions)

---

## buildArtifact

**File**: `vars/buildArtifact.groovy`

### Purpose
Builds application artifacts with automatic build tool detection. Supports Maven, NPM, Go, Gradle, and .NET.

### Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `buildTool` | String | No | Auto-detect | Build tool to use: `maven`, `npm`, `go`, `gradle`, `dotnet` |
| `command` | String | No | Tool-specific | Custom build command |
| `artifacts` | String | No | Tool-specific | Artifact path pattern for archiving |
| `installCommand` | String | No | `npm ci` | NPM install command (NPM only) |
| `buildCommand` | String | No | `npm run build` | NPM build command (NPM only) |

### Usage Examples

```groovy
// Auto-detect build tool and use defaults
buildArtifact()

// Force specific build tool
buildArtifact(buildTool: 'maven')

// Custom Maven command
buildArtifact(
    buildTool: 'maven',
    command: 'mvn clean package -Pproduction -DskipTests=false'
)

// NPM with custom commands
buildArtifact(
    buildTool: 'npm',
    installCommand: 'npm install',
    buildCommand: 'npm run build:prod'
)

// Go with custom output
buildArtifact(
    buildTool: 'go',
    command: 'go build -o myapp cmd/main.go',
    artifacts: 'myapp'
)
```

### How It Works

```groovy
def call(Map params = [:]) {
    try {
        // 1. Determine build tool
        // Priority: params.buildTool > env.BUILD_TOOL > auto-detect > default (maven)
        def buildTool = params.buildTool ?: env.BUILD_TOOL
        
        if (!buildTool) {
            buildTool = detectBuildTool()  // Auto-detect based on files
            echo "Auto-detected build tool: ${buildTool}"
        }
        
        // 2. Normalize to lowercase
        def tool = buildTool.toLowerCase()
        
        // 3. Call appropriate build function
        switch (tool) {
            case 'maven':
                buildMaven(params)  // Runs: mvn clean package -DskipTests=true -B
                break
            case 'npm':
                buildNpm(params)    // Runs: npm ci && npm run build
                break
            case 'go':
                buildGo(params)     // Runs: go build -o app .
                break
            // ... other tools
        }
        
        return [success: true, buildTool: tool]
        
    } catch (Exception e) {
        // Set failure metadata for notifications
        env.failedStage = "Build Artifact (${buildTool ?: 'unknown'})"
        env.failedReason = e.getMessage()
        currentBuild.result = 'FAILURE'
        throw e
    }
}
```

### Auto-Detection Logic

```groovy
def detectBuildTool() {
    if (fileExists('pom.xml'))          return 'maven'
    if (fileExists('package.json'))     return 'npm'
    if (fileExists('go.mod'))           return 'go'
    if (fileExists('build.gradle') || fileExists('build.gradle.kts')) return 'gradle'
    return 'maven'  // Default fallback
}
```

### Tool-Specific Defaults

**Maven**:
- Command: `mvn clean package -DskipTests=true -B`
- Artifacts: `target/*.jar`

**NPM**:
- Install: `npm ci`
- Build: `npm run build`
- Artifacts: `dist/**, build/**, .next/**`

**Go**:
- Command: `go build -o app .`
- Artifacts: `app`

**Gradle**:
- Command: `./gradlew clean build -x test`
- Artifacts: `build/libs/*.jar`

**. NET**:
- Command: `dotnet publish -c Release -o out`
- Artifacts: `out/**`

---

## buildDockerImageAndPush

**File**: `vars/buildDockerImageAndPush.groovy`

### Purpose
Builds Docker images with build arguments and optionally pushes to a registry.

### Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `imageName` | String | No | `env.JOB_NAME` | Docker image name |
| `imageTag` | String | No | `env.BUILD_NUMBER` | Image tag |
| `harborProject` | String | No | `''` | Registry project/namespace |
| `registryUrl` | String | No | `''` | Docker registry URL |
| `registryCredentialsId` | String | No | `''` | Jenkins credential ID |
| `dockerfilePath` | String | No | `'Dockerfile'` | Path to Dockerfile |
| `buildContext` | String | No | `'.'` | Docker build context |
| `buildArgs` | Map | No | `[:]` | Build arguments (key-value pairs) |
| `pushToRegistry` | Boolean | No | `false` | Whether to push to registry |
| `removeAfterPush` | Boolean | No | `true` | Remove local image after push |
| `failOnError` | Boolean | No | `true` | Fail pipeline on error |

### Usage Examples

```groovy
// Basic build (no push)
buildDockerImageAndPush(
    imageName: 'my-app',
    imageTag: '1.0.0'
)

// Build and push to registry
def result = buildDockerImageAndPush(
    imageName: env.IMAGE_NAME,
    imageTag: env.BUILD_NUMBER,
    harborProject: env.HARBOR_PROJECT,
    registryUrl: env.REGISTRY_URL,
    registryCredentialsId: env.REGISTRY_CREDENTIALS_ID,
    pushToRegistry: true,
    buildArgs: [
        GIT_AUTHOR: env.GIT_AUTHOR,
        GIT_COMMIT: env.GIT_COMMIT,
        BUILD_DATE: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC')),
        VERSION: "1.0.${env.BUILD_NUMBER}",
        APP_TIMEZONE: "Africa/Dar_es_Salaam"
    ]
)

// Save image name for later stages
env.FINAL_IMAGE_NAME = result.localImageName
```

### Return Value

```groovy
[
    success: true,              // Build success status
    imageName: "registry/project/image:tag",  // Full registry image name
    localImageName: "image:tag",  // Local image name
    pushed: true                // Whether image was pushed
]
```

### How It Works

```groovy
def call(Map params = [:]) {
    // 1. Extract and validate parameters
    def imageName = params.imageName ?: env.JOB_NAME?.toLowerCase()?.replaceAll(/[^a-z0-9\-_.]/, '-')
    def imageTag = params.imageTag ?: env.BUILD_NUMBER ?: 'latest'
    def buildArgs = params.buildArgs ?: [:]
    
    // 2. Construct image names
    def localImageName = "${imageName}:${imageTag}"
    def registryImageName = registryUrl ? "${registryUrl}/${harborProject}/${imageName}:${imageTag}" : localImageName
    
    try {
        // 3. Validate Dockerfile exists
        if (!fileExists(dockerfilePath)) {
            error "Dockerfile not found at path: ${dockerfilePath}"
        }
        
        // 4. Convert build args to string
        def buildArgsString = buildArgs.collect { key, value ->
            "--build-arg ${key}=${value}"
        }.join(' ')
        
        // 5. Build Docker image
        sh """
            docker build -t ${localImageName} \
                -f ${dockerfilePath} \
                ${buildArgsString} \
                ${buildContext}
        """
        
        // 6. Push to registry if requested
        if (pushToRegistry) {
            withCredentials([usernamePassword(
                credentialsId: registryCredentialsId,
                usernameVariable: 'REGISTRY_USER',
                passwordVariable: 'REGISTRY_PASS'
            )]) {
                sh "echo \$REGISTRY_PASS | docker login ${registryUrl} -u \$REGISTRY_USER --password-stdin"
                sh "docker tag ${localImageName} ${registryImageName}"
                sh "docker push ${registryImageName}"
            }
        }
        
        // 7. Clean up local image if requested
        if (removeAfterPush) {
            sh "docker rmi ${registryImageName} || true"
        }
        
        return [
            success: true,
            imageName: registryImageName,
            localImageName: localImageName,
            pushed: pushToRegistry
        ]
        
    } catch (Exception e) {
        env.failedStage = "Build Docker Image and Push"
        env.failedReason = e.getMessage()
        if (failOnError) {
            currentBuild.result = 'FAILURE'
            throw e
        } else {
            currentBuild.result = 'UNSTABLE'
            return [success: false, error: e.getMessage()]
        }
    }
}
```

---

## checkoutAndGitInfo

**File**: `vars/checkoutAndGitInfo.groovy`

### Purpose
Checks out code from Git repository and extracts metadata (commit hash, author, branch).

### Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `repo` | String | No | `env.GIT_REPO_URL` | Git repository URL |
| `credentialsId` | String | No | `env.GIT_CREDENTIALS_ID` | Jenkins credential ID |
| `branch` | String | No | `env.BRANCH_NAME` | Branch to checkout |

### Usage Examples

```groovy
// Use environment variables
checkoutAndGitInfo()

// Override specific parameters
checkoutAndGitInfo(
    repo: 'https://github.com/myorg/myrepo.git',
    branch: 'develop'
)
```

### Sets Environment Variables

- `GIT_COMMIT` - Full commit hash
- `GIT_AUTHOR` - Commit author name
- `GIT_BRANCH` - Branch name

---

## sonarSast

**File**: `vars/sonarSast.groovy`

### Purpose
Runs SonarQube SAST (Static Application Security Testing) analysis.

### Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `sonarServer` | String | No | `'SonarQube Server'` | SonarQube server name in Jenkins |
| `projectKey` | String | No | `env.JOB_NAME` | SonarQube project key |
| `projectName` | String | No | `env.JOB_NAME` | SonarQube project name |
| `waitForQualityGate` | Boolean | No | `false` | Wait for Quality Gate result |
| `timeoutMinutes` | Integer | No | `5` | Quality Gate timeout in minutes |

### Usage Examples

```groovy
// Basic analysis (no Quality Gate wait)
sonarSast(
    projectKey: 'my-project',
    projectName: 'My Project'
)

// With Quality Gate enforcement
sonarSast(
    projectKey: 'landlord-api',
    projectName: 'Landlord Management API',
    waitForQualityGate: true,
    timeoutMinutes: 10
)
```

### How It Works

```groovy
def call(Map params = [:]) {
    try {
        def sonarServer = params.sonarServer ?: 'SonarQube Server'
        def projectKey = params.projectKey ?: env.JOB_NAME
        def projectName = params.projectName ?: env.JOB_NAME
        def waitForQGate = params.waitForQualityGate ?: false
        def timeoutMinutes = params.timeoutMinutes ?: 5
        
        // Step 1: Run SonarQube analysis
        withSonarQubeEnv(sonarServer) {
            sh """mvn sonar:sonar \
                -Dsonar.projectKey=${projectKey} \
                -Dsonar.projectName="${projectName}" """
        }
        
        // Step 2: Wait for Quality Gate (if enabled)
        if (waitForQGate) {
            timeout(time: timeoutMinutes, unit: 'MINUTES') {
                def qg = waitForQualityGate()
                if (qg.status != 'OK') {
                    error "SonarQube Quality Gate failed: ${qg.status}"
                }
            }
        }
        
        return [success: true]
        
    } catch (Exception e) {
        env.failedStage = "SonarQube - SAST"
        env.failedReason = e.getMessage()
        currentBuild.result = 'FAILURE'
        throw e
    }
}
```

---

## vulnScanDocker

**File**: `vars/vulnScanDocker.groovy`

### Purpose
Runs vulnerability scans on Docker dependencies and Dockerfile. Executes scans in parallel for speed.

### Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `trivyCmd` | String | No | `bash trivy-docker-image-scan.sh` | Custom Trivy command |

### Usage Examples

```groovy
// Use defaults
vulnScanDocker()

// Custom Trivy command
vulnScanDocker(trivyCmd: 'bash custom-trivy-scan.sh')
```

### Parallel Scans

Runs three scans in parallel:

1. **Dependency Scan**: OWASP Dependency-Check
   ```groovy
   withCredentials([string(credentialsId: 'nvd-api-key', variable: 'NVD_API_KEY')]) {
       sh "mvn dependency-check:check -Dnvd.api.key=${NVD_API_KEY}"
   }
   ```

2. **Trivy Scan**: Base image vulnerabilities
   ```groovy
   sh "bash trivy-docker-image-scan.sh"
   ```

3. **OPA Conftest**: Dockerfile security policy
   ```groovy
   sh """
       docker run --rm -v "\$(pwd)":/project \
           openpolicyagent/conftest test \
           --policy opa-docker-security.rego Dockerfile
   """
   ```

---

## vulnScanApplicationImage

**File**: `vars/vulnScanApplicationImage.groovy`

### Purpose
Scans the built application Docker image for vulnerabilities.

### Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `imageName` | String | No | `env.FINAL_IMAGE_NAME` | Docker image to scan |

### Usage Examples

```groovy
// Scan image from environment
vulnScanApplicationImage()

// Scan specific image
vulnScanApplicationImage(imageName: 'myapp:1.0.0')
```

### Scans Performed

1. **Trivy Application Image Scan**:
   - Informational scan (HIGH, CRITICAL)
   - Blocking scan (CRITICAL only - fails build)

2. **K8s Security Check** (⚠️ Currently broken - see missing_components_analysis.md):
   - OPA policy validation on K8s manifests

---

## k8sManifestScanAndUpdate

**File**: `vars/k8sManifestScanAndUpdate.groovy`

### Purpose
Clones Kubernetes manifest repository and updates image tags.

### Environment Variables Used

- `K8S_MANIFEST_REPO_URL` - K8s manifest repository URL
- `K8S_MANIFEST_CREDENTIALS_ID` - Git credentials
- `K8S_MANIFEST_BRANCH` - Branch to update (default: `main`)
- `IMAGE_NAME` - Application image name
- `BUILD_NUMBER` - Build number for tag
- `HARBOR_PROJECT` - Registry project
- `REGISTRY_URL` - Registry URL

### Usage Examples

```groovy
// Uses all environment variables
k8sManifestScanAndUpdate()
```

### How It Works

```groovy
def call() {
    def repoUrl = env.K8S_MANIFEST_REPO_URL ?: error("repoUrl is required")
    def credentialsId = env.K8S_MANIFEST_CREDENTIALS_ID ?: error("credentialsId is required")
    def branch = env.K8S_MANIFEST_BRANCH ?: 'main'
    def imageTag = env.BUILD_NUMBER
    def fullImageName = "${env.HARBOR_PROJECT}/${env.IMAGE_NAME}"
    def finalImage = "${env.REGISTRY_URL}/${fullImageName}:${imageTag}"
    def tempDir = "${env.WORKSPACE}/k8s-temp"
    
    try {
        // 1. Clean temp directory
        sh "rm -rf ${tempDir} || true"
        
        // 2. Clone K8s manifest repository
        withCredentials([usernamePassword(
            credentialsId: credentialsId,
            usernameVariable: 'GIT_USER',
            passwordVariable: 'GIT_PASSWORD'
        )]) {
            def repoInfo = getRepoInfo(repoUrl)
            
            if (repoInfo.protocol == 'https') {
                sh """
                    git clone --depth 1 --branch ${branch} \
                    https://\${GIT_USER}:\${GIT_PASSWORD}@${repoInfo.domain} ${tempDir}
                """
            } else {
                sh """
                    git clone --depth 1 --branch ${branch} \
                    http://\${GIT_USER}:\${GIT_PASSWORD}@${repoInfo.domain} ${tempDir}
                """
            }
        }
        
        // 3. Update manifests (implementation needed)
        // 4. Commit and push changes (implementation needed)
        
    } catch (Exception e) {
        env.failedStage = "K8s Manifest Scan & Update"
        env.failedReason = e.getMessage()
        throw e
    }
}
```

---

## Notification Functions

### sendStartNotification

**Purpose**: Sends email when build starts

**Parameters**:
- `subject` - Email subject
- `recipients` - Email addresses (comma-separated)
- `triggeredBy` - Build trigger information

**Example**:
```groovy
sendStartNotification(
    subject: "🚀 Pipeline Started: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
    recipients: env.NOTIFICATION_EMAIL,
    triggeredBy: detectBuildTrigger()
)
```

### sendSuccessNotification

**Purpose**: Sends email when build succeeds

**Parameters**:
- `recipients` - Email addresses
- `triggeredBy` - Build trigger information

**Example**:
```groovy
sendSuccessNotification(
    recipients: env.NOTIFICATION_EMAIL,
    triggeredBy: detectBuildTrigger()
)
```

### sendFailureNotification

**Purpose**: Sends email when build fails

**Parameters**:
- `recipients` - Email addresses
- `triggeredBy` - Build trigger information

**Example**:
```groovy
sendFailureNotification(
    recipients: env.NOTIFICATION_EMAIL,
    triggeredBy: detectBuildTrigger()
)
```

### detectBuildTrigger

**Purpose**: Detects what triggered the build

**Returns**: String describing trigger (e.g., "Manual", "SCM Change", "Timer")

**Example**:
```groovy
def trigger = detectBuildTrigger()
echo "Build triggered by: ${trigger}"
```

---

## 📝 Best Practices

1. **Always use environment variables** for sensitive data
2. **Provide meaningful project keys** for SonarQube
3. **Use build args** to pass metadata to Docker images
4. **Enable Quality Gate** for production branches
5. **Archive artifacts** for traceability
6. **Handle errors gracefully** with try-catch blocks

---

## 🔍 Debugging Tips

1. **Check console output** for detailed error messages
2. **Verify environment variables** are set correctly
3. **Test functions individually** before full pipeline
4. **Use `echo` statements** to debug parameter values
5. **Check credential IDs** match Jenkins configuration

---

**Last Updated**: 2026-02-09  
**Version**: 1.0
