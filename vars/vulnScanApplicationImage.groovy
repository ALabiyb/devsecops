/**
 * vulnScanApplicationImage.groovy
 *
 * Scans the BUILT application image (after docker build & push to Harbor).
 * This is deeper than vulnScanDocker — scans ALL image layers including
 * the compiled app, OS packages, Java JARs inside the image, npm packages, etc.
 *
 * Runs two checks in parallel:
 *
 *   1. Trivy Full Image Scan — ALL layers, OS + app dependencies
 *      Round 1: HIGH+CRITICAL shown, never fails (full visibility)
 *      Round 2: CRITICAL only, fails build (blocks bad deployments)
 *
 *   2. OPA K8s Manifest Scan — validates your deployment yamls
 *      Policy: opa-k8s-security.rego
 *      Checks: non-root containers, resource limits, no privileged,
 *              no latest tags, no hostPID/hostIPC, readOnlyRootFilesystem
 *
 * Prerequisites:
 *   - env.FINAL_IMAGE_NAME must be set (done by buildDockerImageAndPush)
 *   - opa-k8s-security.rego in project root
 *   - k8s/ or k8s-manifest/ directory with manifest files
 *
 * Usage:
 *   vulnScanApplicationImage()
 *   vulnScanApplicationImage(imageName: 'harbor.example.com/proj/app:42')
 *   vulnScanApplicationImage(k8sDir: 'manifests/')
 */
def call(Map params = [:]) {
    try {
        echo "=== Vulnerability Scan - Application Image ==="

        // FINAL_IMAGE_NAME is set by buildDockerImageAndPush
        // Falls back to constructing from env vars if not set
        def imageName = params.imageName
            ?: env.FINAL_IMAGE_NAME
            ?: "${env.REGISTRY_URL}/${env.HARBOR_PROJECT}/${env.IMAGE_NAME}:${env.BUILD_NUMBER}"

        echo "Scanning image: ${imageName}"

        // Auto-detect k8s manifest directory, or accept explicit override
        def k8sDir = params.k8sDir ?: detectK8sDir()

        parallel(

            // -----------------------------------------------------------------
            // SCAN 1: Trivy — full application image scan (all layers)
            // Scans: OS packages, Java JARs, npm packages, Python packages, etc.
            //
            // Round 1: informational — show ALL HIGH+CRITICAL, never fails
            //          gives full visibility without blocking the pipeline
            // Round 2: enforcement  — CRITICAL only, exit 1 blocks the build
            //          prevents deploying critically vulnerable images
            //
            // Trivy cache mounted from host to avoid re-downloading DB
            // Docker socket mounted to access built image layers
            // -----------------------------------------------------------------
            "Trivy — Application Image Scan": {
                echo "🔍 Trivy Round 1: informational scan (HIGH+CRITICAL)..."
                sh """
                    docker run --rm \
                        -v /var/run/docker.sock:/var/run/docker.sock \
                        -v /var/lib/jenkins/.trivy-cache:/root/.cache/trivy \
                        aquasec/trivy:latest \
                        image \
                        --quiet \
                        --no-progress \
                        --severity HIGH,CRITICAL \
                        --exit-code 0 \
                        ${imageName} || true
                """

                echo "🔍 Trivy Round 2: enforcement scan (CRITICAL only)..."
                sh """
                    docker run --rm \
                        -v /var/run/docker.sock:/var/run/docker.sock \
                        -v /var/lib/jenkins/.trivy-cache:/root/.cache/trivy \
                        aquasec/trivy:latest \
                        image \
                        --quiet \
                        --no-progress \
                        --severity CRITICAL \
                        --exit-code 1 \
                        ${imageName}
                """
                echo "✅ Trivy application image scan passed (no CRITICAL CVEs)"
            },

            // -----------------------------------------------------------------
            // SCAN 2: OPA Conftest — K8s manifest security policies
            // Policy file: opa-k8s-security.rego (in project root)
            //
            // Policies enforced (from your opa-k8s-security.rego):
            //   ✅ All resources must have namespace
            //   ✅ No root containers (runAsUser: 0 forbidden)
            //   ✅ runAsNonRoot: true required
            //   ✅ No privileged containers
            //   ✅ No allowPrivilegeEscalation
            //   ✅ readOnlyRootFilesystem: true (except postgres/mysql/mongodb/redis)
            //   ✅ Resource limits required (CPU + memory)
            //   ✅ No hostPID or hostIPC sharing
            //   ✅ No dangerous hostPath mounts (/proc, /sys, /)
            //   ✅ No :latest image tags
            //   ✅ Services must be NodePort (your custom rule)
            // -----------------------------------------------------------------
            "OPA Conftest — K8s Manifest Policies": {
                echo "🔍 OPA: validating K8s manifests against security policies..."
                if (k8sDir) {
                    sh """
                        docker run --rm \
                            -v "\$(pwd)":/project \
                            openpolicyagent/conftest:latest test \
                            --policy opa-k8s-security.rego \
                            /project/${k8sDir}
                    """
                    echo "✅ OPA K8s manifest scan passed"
                } else {
                    echo "⚠️  No k8s manifest directory found — skipping OPA K8s scan"
                    echo "ℹ️  Expected directory: k8s/, k8s-manifest/, manifests/, or kubernetes/"
                }
            }
        )

        echo "✅ Application image scan passed"
        return [success: true, imageName: imageName]

    } catch (Exception e) {
        env.failedStage  = "Vulnerability Scan - Application Image"
        env.failedReason = e.getMessage()
        echo "❌ Application image scan failed: ${e.getMessage()}"
        currentBuild.result = 'UNSTABLE'
        throw e
    }
}

// -----------------------------------------------------------------------------
// Auto-detect k8s manifest directory from common names
// Returns directory name or null if none found
// -----------------------------------------------------------------------------
def detectK8sDir() {
    def candidates = ['k8s', 'k8s-manifest', 'manifests', 'kubernetes', 'deploy', 'k8s-temp']
    for (def dir : candidates) {
        if (fileExists(dir)) {
            echo "Auto-detected K8s directory: ${dir}/"
            return dir
        }
    }
    echo "ℹ️  No K8s manifest directory found — OPA K8s scan will be skipped"
    return null
}