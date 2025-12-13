// File: `vars/buildArtifact.groovy`
def call(Map params = [:]) {
    try {
        echo "Stage: Build Artifact - Maven"
        sh params.command ?: "mvn clean package -DskipTests=true"
        archiveArtifacts artifacts: params.artifacts ?: 'target/*.jar', fingerprint: true
        return [success: true]
    } catch (e) {
        env.failedStage = "Build Artifact - Maven"
        echo "Error in Build Artifact: ${e}"
        currentBuild.result = 'FAILURE'
        throw e
    }
}
