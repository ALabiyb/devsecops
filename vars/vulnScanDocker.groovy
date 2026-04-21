// File: `vars/vulnScanDocker.groovy`
def call(Map params = [:]) {
    try {
        echo "Stage: Vulnerability Scan - Docker (parallel)"
        parallel(
                "Dependency Scan": {
                    def mvnHome  = tool name: 'mave-3.9.15', type: 'maven'
                    def javaHome = tool name: 'jdk21', type: 'jdk'
                    withEnv(["PATH+MAVEN=${mvnHome}/bin", "PATH+JAVA=${javaHome}/bin", "JAVA_HOME=${javaHome}"]) {
                        withCredentials([string(credentialsId: 'nvd-api-key', variable: 'NVD_API_KEY')]) {
                            sh 'mvn dependency-check:check -Dnvd.api.key=$NVD_API_KEY'
                            // Note: use single quotes to avoid Groovy interpolation warning
                        }
                    }
                },
                "Trivy Scan - Base Image": {
                    sh params.trivyCmd ?: "bash trivy-docker-image-scan.sh"
                },
                 "OPA Conftest - Dockerfile": {
                     sh """
                         docker run --rm -v "\$(pwd)":/project openpolicyagent/conftest test  --policy opa-docker-security.rego Dockerfile
                     """
                 }
        )

//        sh params.dependencyCmd ?: "mvn dependency-check:check || true"
        echo "✅ All vulnerability scans completed."
        return [success: true]
    } catch (Exception e) {
        env.failedStage = "Vulnerability Scan - Docker"
        env.failedReason = e.getMessage()
        echo "Docker vulnerability scanning error: ${e}"
        currentBuild.result = 'UNSTABLE'
        throw e
    }
}
