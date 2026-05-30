/**
 * vulnScanApplicationImage.groovy
 *
 * Scans the BUILT local Docker image, then removes it to keep disk clean.
 *
 * WHY LOCAL IMAGE (not Harbor URL)?
 *   Trivy needs docker socket access to inspect image layers.
 *   The local image (e.g. soft-aml-branding-service:1) is already on the
 *   Jenkins host after buildDockerImageAndPush — no re-pull needed.
 *   Scanning via Harbor URL requires authentication + re-download of layers.
 *
 * CLEANUP ORDER:
 *   Stage 8: buildDockerImageAndPush  → builds, pushes, KEEPS local image
 *   Stage 9: vulnScanApplicationImage → scans local image → REMOVES it here
 *   Docker build cache capped at 2GB here after scan completes
 *
 * env.FINAL_IMAGE_NAME must be set by buildDockerImageAndPush (local tag).
 *
 * Usage:
 *   vulnScanApplicationImage()
 *   vulnScanApplicationImage(k8sDir: 'k8s/')
 */
def call(Map params = [:]) {
    try {
        echo "=== Vulnerability Scan - Application Image ==="

        // Use the LOCAL image name (e.g. soft-aml-branding-service:42)
        // NOT the Harbor URL — Trivy scans local images via docker socket
        def localImageName = params.imageName
            ?: env.FINAL_IMAGE_NAME
            ?: "${env.IMAGE_NAME}:${env.BUILD_NUMBER}"

        echo "Scanning local image: ${localImageName}"
        echo "ℹ️  Image will be removed after scan to keep disk clean"

        def k8sDir = params.k8sDir ?: detectK8sDir()

        // Write OPA policy from shared library — no need to copy rego to project repos
        writeFile file: 'opa-k8s-security.rego',
                  text: libraryResource('opa/opa-k8s-security.rego')

        try {
            parallel(

                // -------------------------------------------------------------
                // SCAN 1: Trivy — full application image scan (all layers)
                // Scans: OS packages, Java JARs inside image, npm packages, etc.
                //
                // Round 1: HIGH+CRITICAL shown, exit 0 (informational — full visibility)
                // Round 2: CRITICAL only, exit 1 (blocks build on critical vulns)
                //
                // Uses local image via docker socket — no registry pull needed
                // Trivy cache at /var/lib/jenkins/.trivy-cache avoids DB re-download
                // -------------------------------------------------------------
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
                            ${localImageName} || true
                    """

                    echo "🔍 Trivy Round 2: enforcement scan (CRITICAL only — blocks build)..."
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
                            ${localImageName}
                    """
                    echo "✅ Trivy scan passed (no CRITICAL CVEs)"
                },

                // -------------------------------------------------------------
                // SCAN 2: OPA Conftest — K8s manifest security policies
                // Scans k8s/ directory against opa-k8s-security.rego
                // Skipped automatically if no k8s directory found in project
                //
                // Policies checked (opa-k8s-security.rego):
                //   ✅ No root containers
                //   ✅ runAsNonRoot: true
                //   ✅ No privileged containers
                //   ✅ Resource limits defined
                //   ✅ No :latest image tags
                //   ✅ readOnlyRootFilesystem: true
                //   ✅ No dangerous hostPath mounts
                // -------------------------------------------------------------
                "OPA Conftest — K8s Manifest Policies": {
                    echo "🔍 OPA: validating K8s manifests..."
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
                        echo "⚠️  No k8s directory found — OPA K8s scan skipped"
                        echo "ℹ️  Expected: k8s/, k8s-manifest/, manifests/, kubernetes/, or deploy/"
                    }
                }
            )

            echo "✅ Application image scan passed"
            return [success: true, imageName: localImageName]

        } finally {
            // -----------------------------------------------------------------
            // ALWAYS clean up local image after scan — success OR failure
            // This is the correct place for cleanup:
            //   ✅ Image was available for Trivy scan above
            //   ✅ Harbor already has the image (pushed in stage 8)
            //   ✅ Cleanup here prevents 100-500MB accumulating per build
            // -----------------------------------------------------------------
            echo "🧹 Removing local image after scan: ${localImageName}"
            sh "docker rmi ${localImageName} || true"

            // Cap Docker build cache at 2GB
            // --reserved-space is the new flag name; fall back to --keep-storage for older Docker
            sh "docker builder prune -f --reserved-space=2gb 2>/dev/null || docker builder prune -f --keep-storage=2gb 2>/dev/null || true"
            echo "✅ Local image removed — disk kept clean"
        }

    } catch (Exception e) {
        env.failedStage  = "Vulnerability Scan - Application Image"
        env.failedReason = e.getMessage()
        echo "❌ Application image scan failed: ${e.getMessage()}"
        currentBuild.result = 'UNSTABLE'
        throw e
    }
}

// Auto-detect k8s manifest directory from common names
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