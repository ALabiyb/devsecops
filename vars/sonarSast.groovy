// vars/sonarSast.groovy
def call(Map params = [:]) {
    try {
        echo "=== Starting SonarQube Analysis (SAST) ==="

        def sonarServer = params.sonarServer ?: 'SonarQube Server'
        def projectKey   = params.projectKey   ?: env.JOB_NAME
        def projectName  = params.projectName  ?: env.JOB_NAME

        withSonarQubeEnv(sonarServer) {
            sh """mvn sonar:sonar \
                -Dsonar.projectKey=${projectKey} \
                -Dsonar.projectName="${projectName}" """
        }

        echo "✅ SonarQube analysis completed and uploaded successfully!"
        return [success: true]

    } catch (Exception e) {
        env.failedStage = "SonarQube - SAST"
        echo "❌ SonarQube analysis failed: ${e.message}"
        currentBuild.result = 'FAILURE'
        throw e
    }
}