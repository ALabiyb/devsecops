// vars/vulnScanDocker.groovy
def call(Map params = [:]) {
    try {
        echo "=== Vulnerability Scan - Application Image ==="

        // Get image name/tag from environment (set in build stage)
        def imageName = params.imageName ?: env.FINAL_IMAGE_NAME ?: "${env.HARBOR_PROJECT}/${env.IMAGE_NAME}:${env.BUILD_NUMBER}"

        echo "Scanning image: ${imageName}"

        parallel(
                "Trivy Scan Application Image": {
                    // Informational scan: show HIGH and CRITICAL
                    sh """
            docker run --rm \
                -v /var/run/docker.sock:/var/run/docker.sock \
                aquasec/trivy:latest \
                image --quiet --no-progress \
                --severity HIGH,CRITICAL \
                ${imageName} || true
        """

                    // Blocking scan: fail only on CRITICAL
                    sh """
            docker run --rm \
                -v /var/run/docker.sock:/var/run/docker.sock \
                aquasec/trivy:latest \
                image --quiet --no-progress \
                --exit-code 1 \
                --severity CRITICAL \
                ${imageName}
        """
                },
                "k8s security check OPA": {
                    sh """
                    docker run --rm -v "\$(pwd)":/project openpolicyagent/conftest test --policy  opa-k8s-security.rego k8s-manifest
                """
                }
        )


        echo "✅ Application image scan passed - No CRITICAL vulnerabilities"
        return [success: true]

    } catch (Exception e) {
        echo "❌ Vulnerability scan failed: ${e.message}"
        currentBuild.result = 'UNSTABLE'
        env.failedStage = "Vulnerability Scan - Application Image"
        env.failedReason = e.getMessage()
        throw e  // or remove to continue pipeline
    }
}