// File: `vars/vulnScanDocker.groovy`
def call(Map params = [:]) {
    try {
        echo "Stage: Vulnerability Scan - Docker (parallel)"
//        parallel(
//                "Dependency Scan": { sh params.dependencyCmd ?: "mvn dependency-check:check || true" },
//                "Trivy Scan": { sh params.trivyCmd ?: "bash trivy-docker-image-scan.sh || true" },
//                "OPA Conftest": { sh params.opaCmd ?: "docker run --rm -v \$(pwd):/project openpolicyagent/conftest test --policy opa-docker-security.rego Dockerfile || true" }
//        )
        withCredentials([string(credentialsId: 'nvd-api-key', variable: 'NVD_API_KEY')]) {
            sh """
                mvn dependency-check:check -Dnvd.api.key=${NVD_API_KEY}
               """
        }
//        sh params.dependencyCmd ?: "mvn dependency-check:check || true"
        return [success: true]
    } catch (e) {
        env.failedStage = "Vulnerability Scan - Docker"
        env.failedReason = e.getMessage()
        echo "Docker vulnerability scanning error: ${e}"
        currentBuild.result = 'UNSTABLE'
        throw e
    }
}
