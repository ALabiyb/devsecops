/**
 * signImage.groovy
 *
 * Signs the Docker image in Harbor using cosign (key-based signing).
 * The signature is stored as an OCI artifact alongside the image in Harbor.
 *
 * STAGE ORDER:
 *   Stage 8:  buildDockerImageAndPush  → pushes image to Harbor
 *   Stage 9:  signImage                → signs the Harbor image       ← THIS
 *   Stage 10: generateSbom             → generates SBOM from local image
 *   Stage 11: vulnScanApplicationImage → scans + removes local image
 *
 * Annotations embedded in the signature:
 *   git-commit   → full commit SHA for traceability
 *   builder      → always "jenkins" — proves CI/CD origin
 *   build-number → Jenkins build number
 *
 * Verification (run anywhere with cosign + cosign.pub from this repo):
 *   cosign verify --key cosign.pub --insecure-skip-tls-verify <image>
 *
 * Jenkins requirements:
 *   cosign installed on Jenkins host: /usr/local/bin/cosign
 *   Credential 'cosign-private-key'  → Secret file  (cosign.key)
 *   Credential 'cosign-password'     → Secret text  (key password)
 */
def call(Map params = [:]) {
    def registryUrl   = params.registryUrl   ?: env.REGISTRY_URL
    def harborProject = params.harborProject ?: env.HARBOR_PROJECT
    def imageName     = params.imageName     ?: env.IMAGE_NAME
    def imageTag      = params.imageTag      ?: env.BUILD_NUMBER
    def keyCredId     = params.keyCredId     ?: 'cosign-private-key'
    def passCredId    = params.passCredId    ?: 'cosign-password'

    def fullImage = "${registryUrl}/${harborProject}/${imageName}:${imageTag}"

    try {
        echo "=== Image Signing (cosign) ==="
        echo "Signing: ${fullImage}"

        withCredentials([
            file(credentialsId: keyCredId,  variable: 'COSIGN_KEY_FILE'),
            string(credentialsId: passCredId, variable: 'COSIGN_PASS')
        ]) {
            sh """
                COSIGN_PASSWORD="\${COSIGN_PASS}" \
                cosign sign --key "\${COSIGN_KEY_FILE}" \
                  --insecure-skip-tls-verify \
                  --yes \
                  -a "git-commit=${env.GIT_COMMIT}" \
                  -a "builder=jenkins" \
                  -a "build-number=${env.BUILD_NUMBER}" \
                  "${fullImage}"
            """
        }

        echo "✅ Signature pushed to Harbor alongside image: ${fullImage}"
        echo "   Verify with: cosign verify --key cosign.pub --insecure-skip-tls-verify ${fullImage}"

    } catch (e) {
        // Non-blocking: signing failure marks UNSTABLE but does not stop delivery
        echo "⚠️  Image signing failed: ${e.message}"
        echo "Check: cosign installed at /usr/local/bin/cosign, credentials exist, Harbor reachable"
        currentBuild.result = 'UNSTABLE'
    }
}
