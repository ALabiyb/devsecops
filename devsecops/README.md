# DevSecOps Jenkins Shared Library

This repository contains the Jenkins Shared Library for our DevSecOps pipelines. It provides a set of reusable, standardized Groovy functions to build, scan, and deploy applications securely.

By using this shared library, all our projects benefit from:
- Consistent security scanning (SAST, SCA, Container scanning, IaC scanning).
- Standardized Docker builds and Harbor registry pushes.
- Automated GitOps manifest updates (ArgoCD integration).
- Centralized pipeline logic that is easy to maintain and upgrade.

---

## 🚀 How to Use Custom Functions in Your Pipeline

To use this shared library in your project's `Jenkinsfile`, you must first import it at the very top of the file:

```groovy
library identifier: 'jenkins-shared-library@main', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'http://192.168.15.85/devsecops1/pipeline.git',  // URL to this shared library
    credentialsId: 'gitlab-pat-jenkins',                     // Jenkins credential with GitLab PAT
    traits: [[$class: 'jenkins.plugins.git.traits.BranchDiscoveryTrait']]
])
```

Once imported, all functions in the `vars/` directory of this library become available as global commands in your pipeline.

---

## 🛠️ Available Functions

Here is a quick overview of the functions provided by this library. 

| Function | Purpose |
|----------|---------|
| `checkoutAndGitInfo()` | Clones the repository and extracts Git metadata (Author, Commit Hash, Message) into environment variables for later use. |
| `sendStartNotification()` | Sends an HTML-formatted email indicating that the pipeline has started. |
| `buildArtifact()` | Auto-detects the build tool (Maven, npm, Gradle, etc.) and compiles the application. *Not needed if using multi-stage Docker builds.* |
| `sonarSast()` | Runs SonarQube Static Application Security Testing (SAST) and optionally blocks the build if the Quality Gate fails. |
| `vulnScanDocker()` | Runs parallel vulnerability scans mapping to OWASP/npm audit, Trivy base image scanning, and OPA Conftest Dockerfile policy checks. |
| `buildDockerImageAndPush()`| Builds the Docker image, tags it, and pushes it to the Harbor registry securely. |
| `updateManifest()` | GitOps deployment: Clones the K8s manifest / Helm repo, updates the image tag, and pushes the change back so ArgoCD can sync it. |
| `sendSuccessNotification()`| Sends an HTML-formatted success email with deployment details. |
| `sendFailureNotification()`| Sends an HTML-formatted failure email with error logs/links. |

> **Note:** For a deep dive into every parameter available for each function, usage examples, and internal code workings, please reference the [Full Function Documentation](docs/function_documentation.md).

---

## 📖 Complete Pipeline Examples

### Example 1: Node.js / React (Multi-stage Docker Build)
Best for modern frontend or Node backend apps where compilation happens **inside** the Dockerfile.

```groovy
library identifier: 'jenkins-shared-library@main', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'http://192.168.15.85/devsecops1/pipeline.git',
    credentialsId: 'gitlab-pat-jenkins'
])

pipeline {
    agent any

    environment {
        PROJECT_NAME       = 'my-node-app'
        IMAGE_NAME         = 'my-node-app'
        NOTIFICATION_EMAIL = 'team@company.com'
        
        // Git Repo
        GIT_REPO_URL       = 'http://192.168.15.85/my-group/my-node-app.git'
        GIT_CREDENTIALS_ID = 'gitlab-pat-jenkins'
        BRANCH_NAME        = 'main'

        // Registry & Manifests
        REGISTRY_URL            = 'registry.192.168.15.230.nip.io'
        HARBOR_PROJECT          = 'frontend'
        REGISTRY_CREDENTIALS_ID = 'harbor-local-secret'
        K8S_MANIFEST_REPO_URL   = 'http://192.168.15.85/my-group/manifests.git'
        K8S_MANIFEST_CREDENTIALS_ID = 'gitlab-pat-jenkins'
        K8S_MANIFEST_BRANCH     = 'main'
        MANIFEST_FILE_PATH      = 'my-node-app/deployment.yaml'

        // SonarQube
        SONAR_SERVER       = 'SonarQube Server'
        SONAR_PROJECT_KEY  = 'my-node-app'
        SONAR_PROJECT_NAME = 'My Node App'
    }

    stages {
        stage('Checkout') {
            steps { checkoutAndGitInfo(repo: env.GIT_REPO_URL, credentialsId: env.GIT_CREDENTIALS_ID, branch: env.BRANCH_NAME) }
        }
        stage('Scan & Build') {
            steps {
                script {
                    // SAST
                    withSonarQubeEnv(env.SONAR_SERVER) {
                        def scannerHome = tool 'SonarScanner'
                        sh "\${scannerHome}/bin/sonar-scanner -Dsonar.projectKey=${env.SONAR_PROJECT_KEY}"
                    }
                    
                    // SCA & Base Image Scan
                    vulnScanDocker()
                    
                    // Docker Build & Push
                    def result = buildDockerImageAndPush(
                        imageName: env.IMAGE_NAME,
                        imageTag: env.BUILD_NUMBER,
                        harborProject: env.HARBOR_PROJECT,
                        registryUrl: env.REGISTRY_URL,
                        registryCredentialsId: env.REGISTRY_CREDENTIALS_ID,
                        dockerfilePath: 'Dockerfile',
                        pushToRegistry: true,
                        removeAfterPush: true
                    )
                    env.FINAL_IMAGE_NAME = result.imageName
                }
            }
        }
        stage('Update Manifest') {
            steps {
                updateManifest(
                    repoUrl: env.K8S_MANIFEST_REPO_URL,
                    credentialsId: env.K8S_MANIFEST_CREDENTIALS_ID,
                    branch: env.K8S_MANIFEST_BRANCH,
                    manifestPath: env.MANIFEST_FILE_PATH,
                    imageName: "${env.HARBOR_PROJECT}/${env.IMAGE_NAME}",
                    imageTag: env.BUILD_NUMBER,
                    registryUrl: env.REGISTRY_URL
                )
            }
        }
    }
}
```

### Example 2: Java Spring Boot (Maven Build)
Best for traditional Java apps where Jenkins needs to compile the `.jar` file *before* Docker builds the image.

```groovy
library identifier: 'jenkins-shared-library@main', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'http://192.168.15.85/devsecops1/pipeline.git',
    credentialsId: 'gitlab-pat-jenkins'
])

pipeline {
    agent { label 'docker-host' }
    
    tools {
        jdk 'jdk21'
        maven 'maven-3.8.7'
    }

    environment {
        // ... (Environment variables remain mostly identical to Example 1) ...
        BUILD_TOOL = 'maven'
    }

    stages {
        stage('Checkout') {
            steps { checkoutAndGitInfo(repo: env.GIT_REPO_URL, credentialsId: env.GIT_CREDENTIALS_ID, branch: env.BRANCH_NAME) }
        }
        stage('Build Artifact') {
            steps { buildArtifact() } // <-- Compiles the .jar using Maven
        }
        stage('SonarQube SAST') {
            steps {
                sonarSast(
                    sonarServer: env.SONAR_SERVER,
                    projectKey: env.SONAR_PROJECT_KEY,
                    projectName: env.SONAR_PROJECT_NAME,
                    waitForQualityGate: true
                )
            }
        }
        stage('Docker & Manifest') {
            steps {
                script {
                    def result = buildDockerImageAndPush(...)
                    updateManifest(...)
                }
            }
        }
    }
}
```

---

## 🔒 Security Requirements

To successfully run these functions, the Jenkins agent requires the following to be available:
- **Trivy**: Installed and accessible in the system PATH.
- **OPA Conftest**: Installed and accessible in the system PATH.
- **Docker**: The Jenkins user must be part of the `docker` group to run docker commands securely.
- **Policies**: Applications must include required security policies in their root directory (e.g., `opa-docker-security.rego`).
