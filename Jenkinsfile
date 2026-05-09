/**
 * ============================================================================
 * PRODUCTION-GRADE DEVSECOPS JENKINSFILE TEMPLATE
 * ============================================================================
 *
 * CHANGE ONLY THE environment{} BLOCK PER PROJECT.
 * Stages, scanning, and notifications are in the shared library.
 *
 * PIPELINE STAGES:
 *   1.  Checkout & Git Info
 *   2.  Notify Start
 *   3.  Build Artifact            (mvn/npm/go — no archiveArtifacts)
 *   4.  Unit Tests                (optional — uncomment when ready)
 *   5.  SonarQube SAST
 *   6.  Dependency Check          (OWASP/npm audit/govulncheck — all languages)
 *   7.  Vuln Scan - Dockerfile    (Trivy base image + OPA Dockerfile + Gitleaks)
 *   8.  Docker Build & Push       (Harbor, removes local images after push)
 *   9.  Vuln Scan - App Image     (Trivy full image + OPA K8s manifests)
 *   10. K8s Manifest Update       (pause on missing file, never silently succeed)
 *
 * DISK MANAGEMENT:
 *   - buildDiscarder: last 5 builds, 0 archived artifacts
 *   - Docker images removed after push (Harbor = artifact store)
 *   - Build cache capped at 2GB
 *   - cleanWs() removes workspace after success
 *
 * CHOOSE MANIFEST STAGE:
 *   updateK8sManifest()          → update only
 *   k8sManifestScanAndUpdate()   → update + OPA scan before push (recommended)
 *
 * ============================================================================
 * JENKINS ONE-TIME SETUP
 * ============================================================================
 *
 * PLUGINS (Manage Jenkins → Plugins):
 *   ✅ Pipeline, Git, Credentials Binding   (usually pre-installed)
 *   ✅ Email Extension (emailext)
 *   ✅ SonarQube Scanner
 *   ✅ OWASP Dependency-Check               (for Maven/Gradle projects)
 *   ✅ SSH Agent                            (for sshRemoteDeploy)
 *   ✅ Docker Pipeline
 *   ✅ Workspace Cleanup                    (for cleanWs())
 *
 * CREDENTIALS (Manage Jenkins → Credentials → Global):
 *   lsaid             Username/Password   GitLab access
 *   robot-jenkins     Username/Password   Harbor robot account
 *   nvd-api-key       Secret text         NVD API key (free at nvd.nist.gov)
 *
 * GLOBAL TOOLS (Manage Jenkins → Global Tool Configuration):
 *   Maven:            name = 'mave-3.9.15'
 *   JDK:              name = 'jdk21'
 *   SonarQube Scanner: name = 'SonarScanner'
 *
 * SYSTEM CONFIG (Manage Jenkins → Configure System):
 *   SonarQube Servers: name = 'SonarQube Server'
 *   Extended Email:    SMTP settings
 * ============================================================================
 */

library identifier: 'jenkins-shared-library@main', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'http://192.168.15.85/devsecops1/pipeline.git',
    credentialsId: 'lsaid',
    traits: [[$class: 'jenkins.plugins.git.traits.BranchDiscoveryTrait']]
])

pipeline {
    agent any

    // =========================================================================
    // OPTIONS — applied to ALL projects using this template
    // =========================================================================
    options {
        // Keep last 5 builds, ZERO Jenkins-archived artifacts
        // Without this: each build stores 80-140MB JARs → disk fills in days
        buildDiscarder(logRotator(numToKeepStr: '5', artifactNumToKeepStr: '0'))
        disableConcurrentBuilds()              // prevent race conditions
        timestamps()                           // timestamps in all log lines
        timeout(time: 60, unit: 'MINUTES')     // kill hung builds automatically
    }

    // =========================================================================
    // ↓↓↓ CHANGE THESE PER PROJECT — everything else stays the same ↓↓↓
    // =========================================================================
    environment {

        // Project identity
        PROJECT_NAME            = 'SoftAML Branding Service'
        IMAGE_NAME              = 'soft-aml-branding-service'
        HARBOR_PROJECT          = 'softaml'
        REGISTRY_URL            = 'harbor.devops.softnethq.co.tz'
        REGISTRY_CREDENTIALS_ID = 'robot-jenkins'

        // Notifications — comma-separated emails
        NOTIFICATION_EMAIL = 'lsaid@softnet.co.tz, paulnkingwa34@gmail.com'

        // Source repo
        GIT_REPO_URL       = 'http://192.168.15.85/soft-aml/microservices/branding-configuration-service.git'
        GIT_CREDENTIALS_ID = 'lsaid'
        BRANCH_NAME        = 'main'

        // K8s manifest repo
        K8S_MANIFEST_REPO_URL       = 'http://192.168.15.85/kubernetes-manifest/softaml-k8s-manifest/microservices/branding-configuration-service.git'
        K8S_MANIFEST_CREDENTIALS_ID = 'lsaid'
        K8S_MANIFEST_BRANCH         = 'main'
        K8S_MANIFEST_PATHS          = '04-deployment.yaml'
        // Multiple files: K8S_MANIFEST_PATHS = '03-deployment.yaml,06-ingress.yaml'

        // Build tool: maven | npm | node | next | go | gradle | dotnet
        // Remove this line to auto-detect from project files
        BUILD_TOOL = 'maven'

        // App config
        APP_TIMEZONE = 'Africa/Dar_es_Salaam'

        // Auto-populated — do not edit
        GIT_COMMIT     = sh(script: 'git rev-parse HEAD 2>/dev/null || echo unknown', returnStdout: true).trim()
        GIT_AUTHOR     = sh(script: 'git log -1 --pretty=format:"%an" 2>/dev/null || echo unknown', returnStdout: true).trim()
        APP_VERSION    = "1.0.${env.BUILD_NUMBER}"
        BUILD_DATE_UTC = sh(script: "date -u +'%Y-%m-%dT%H:%M:%SZ'", returnStdout: true).trim()
    }
    // =========================================================================
    // ↑↑↑ END OF PER-PROJECT SECTION ↑↑↑
    // =========================================================================

    stages {

        // ── 1. CHECKOUT ───────────────────────────────────────────────────────
        stage('Checkout and Git Info') {
            steps {
                script {
                    checkoutAndGitInfo(
                        repo: env.GIT_REPO_URL,
                        credentialsId: env.GIT_CREDENTIALS_ID,
                        branch: env.BRANCH_NAME
                    )
                }
            }
        }

        // ── 2. NOTIFY START ───────────────────────────────────────────────────
        stage('Send Start Notification') {
            steps {
                script {
                    sendStartNotification(
                        subject: "🚀 Pipeline Started: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                        recipients: env.NOTIFICATION_EMAIL,
                        triggeredBy: detectBuildTrigger()
                    )
                }
            }
        }

        // ── 3. BUILD ARTIFACT ─────────────────────────────────────────────────
        // Compiles the app — output stays in workspace for:
        //   - SonarQube analysis (needs compiled bytecode for Java)
        //   - OWASP scan (needs resolved dependency tree)
        //   - Docker build (COPY target/*.jar or dist/ into image)
        // NO archiveArtifacts — Harbor is the artifact store
        stage('Build Artifact') {
            steps {
                script { buildArtifact() }
            }
        }

        // ── 4. UNIT TESTS (optional) ──────────────────────────────────────────
        // Uncomment when your projects have unit tests
        // stage('Unit Tests') {
        //     steps {
        //         script { unitTests() }
        //     }
        // }

        // ── 5. SONARQUBE SAST ─────────────────────────────────────────────────
        // Static code analysis — finds security vulnerabilities, code smells
        // waitForQualityGate: false = non-blocking (change to true to enforce)
        stage('SonarQube Analysis') {
            steps {
                script {
                    sonarSast(
                        sonarServer: 'SonarQube Server',
                        projectKey: "${env.IMAGE_NAME}",
                        projectName: "${env.PROJECT_NAME}",
                        waitForQualityGate: false,
                        timeoutMinutes: 5
                    )
                }
            }
        }

        // ── 6. DEPENDENCY CHECK ───────────────────────────────────────────────
        // Scans ALL dependencies for known CVEs — auto-detects language:
        //   Maven  → OWASP Maven plugin → XML report → dependencyCheckPublisher
        //   npm    → npm audit          → npm-audit-report.json
        //   Go     → govulncheck        → govulncheck-report.txt
        //   Gradle → OWASP Gradle       → XML report
        //   .NET   → dotnet list        → dotnet-vuln-report.txt
        //
        // Start with failOnCVSS: 0 (report only) to see what vulnerabilities
        // exist before enforcing. Change to 9 when team is ready.
        stage('Dependency Check') {
            steps {
                script {
                    owaspDependencyCheck(
                        failOnCVSS: 0   // 0=report | 7=fail HIGH+ | 9=fail CRITICAL
                    )
                }
            }
        }

        // ── 7. VULNERABILITY SCAN - DOCKERFILE ────────────────────────────────
        // Three parallel checks BEFORE building the image:
        //   1. Trivy:    scans FROM base image for OS/package CVEs
        //   2. OPA:      enforces Dockerfile security policies (opa-docker-security.rego)
        //   3. Gitleaks: detects hardcoded secrets/API keys in source code
        stage('Vulnerability Scan - Docker') {
            steps {
                script { vulnScanDocker() }
            }
        }

        // ── 8. DOCKER BUILD & PUSH ────────────────────────────────────────────
        // Builds Docker image using compiled artifact from stage 3
        // Pushes to Harbor registry
        // Removes local images after push + caps build cache at 2GB
        stage('Build Docker Image and Publish') {
            steps {
                script {
                    def result = buildDockerImageAndPush(
                        imageName:              env.IMAGE_NAME,
                        imageTag:               env.BUILD_NUMBER,
                        harborProject:          env.HARBOR_PROJECT,
                        registryUrl:            env.REGISTRY_URL,
                        registryCredentialsId:  env.REGISTRY_CREDENTIALS_ID,
                        pushToRegistry:         true,
                        buildArgs: [
                            GIT_AUTHOR  : env.GIT_AUTHOR,
                            GIT_COMMIT  : env.GIT_COMMIT,
                            BUILD_DATE  : new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC')),
                            VERSION     : "1.0.${env.BUILD_NUMBER}",
                            APP_TIMEZONE: env.APP_TIMEZONE,
                            APP_NAME    : env.PROJECT_NAME
                        ]
                    )
                    env.FINAL_IMAGE_NAME = result.localImageName  // used by stage 9
                }
            }
        }

        // ── 9. VULNERABILITY SCAN - APPLICATION IMAGE ─────────────────────────
        // Scans the BUILT image (all layers) — deeper than stage 7:
        //   1. Trivy Round 1: shows ALL HIGH+CRITICAL (informational)
        //   2. Trivy Round 2: CRITICAL only, fails build (enforcement)
        //   3. OPA:           scans k8s/ manifest files (opa-k8s-security.rego)
        stage('Vulnerability Scan - Application Image') {
            steps {
                script { vulnScanApplicationImage() }
            }
        }

        // ── 10. K8S MANIFEST UPDATE ───────────────────────────────────────────
        // Two options — choose ONE:
        //
        // Option A: updateK8sManifest()
        //   → update image tag only, no OPA scan
        //
        // Option B: k8sManifestScanAndUpdate()  ← RECOMMENDED
        //   → update image tag + OPA policy scan → only pushes if scan passes
        //   → prevents non-compliant manifests reaching the k8s repo
        //
        // BOTH OPTIONS:
        //   - Pre-flight verifies files exist before changes
        //   - Missing file → warning email + 30min pause → auto-fail
        //   - Image update verified before commit
        //   - NEVER silently succeeds
        stage('k8s Manifest Update') {
            steps {
                script {
                    k8sManifestScanAndUpdate()   // recommended: update + OPA scan
                    // updateK8sManifest()        // alternative: update only
                }
            }
        }
    }

    post {

        always {
            // Publish OWASP Dependency Check report (Maven projects)
            // Creates trend graph on Jenkins job page — reads from workspace
            // Skipped automatically for non-Maven projects (no XML report)
            script {
                if (fileExists('target/dependency-check-report.xml')) {
                    dependencyCheckPublisher(
                        pattern: 'target/dependency-check-report.xml',
                        failedTotalCritical: 0,    // mark UNSTABLE on any CRITICAL
                        unstableTotalHigh: 10      // mark UNSTABLE if >10 HIGH
                    )
                } else {
                    echo "ℹ️  No dependency-check-report.xml — skipping (non-Maven project uses npm audit / govulncheck)"
                }
            }
        }

        success {
            script {
                sendSuccessNotification(
                    recipients: env.NOTIFICATION_EMAIL,
                    triggeredBy: detectBuildTrigger()
                )
            }
        }

        failure {
            script {
                sendFailureNotification(
                    recipients: env.NOTIFICATION_EMAIL,
                    triggeredBy: detectBuildTrigger()
                )
            }
        }

        // Clean workspace after SUCCESS to prevent workspace disk accumulation
        // node_modules, target/, dist/ are recreated on next build anyway
        // Keep workspace on FAILURE for debugging
        cleanup {
            cleanWs(
                cleanWhenSuccess:  true,
                cleanWhenFailure:  false,   // keep for debugging
                cleanWhenAborted:  true,
                notFailBuild:      true     // don't fail build if cleanup fails
            )
        }
    }
}