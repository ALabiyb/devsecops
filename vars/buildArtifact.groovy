def call(Map params = [:]) {
    try {
        echo "Stage: Build Artifact - Maven"

        // Print versions for debugging
        sh 'java -version'
        sh 'mvn -version'

        // Build command: use provided command or default with verbosity and skip tests
        def buildCmd = params.command ?: 'mvn clean package -DskipTests=true -B -e'

        echo "Running Maven build: ${buildCmd}"
        sh buildCmd

        // Archive artifacts (make path configurable)
        def artifactPath = params.artifacts ?: 'target/*.jar'
        archiveArtifacts artifacts: artifactPath, fingerprint: true, allowEmptyArchive: false

        return [success: true]
    } catch (Exception e) {
        env.failedStage = "Build Artifact - Maven"
        echo "Error in Build Artifact: ${e.getMessage()}"
        echo "Full exception: ${e}"
        currentBuild.result = 'FAILURE'
        throw e
    }
}