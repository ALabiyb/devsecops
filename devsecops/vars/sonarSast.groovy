// vars/sonarSast.groovy
def call(Map params = [:]) {
    try {
        echo "=== Starting SonarQube Analysis (SAST) ==="

        def sonarServer = params.sonarServer ?: 'SonarQube Server'
        def projectKey   = params.projectKey   ?: env.JOB_NAME
        def projectName  = params.projectName  ?: env.JOB_NAME
        def waitForQGate = params.waitForQualityGate ?: false
        def timeoutMinutes = params.timeoutMinutes ?: 5


        //Step 1: Run the SonarQube analysis using Maven
        withSonarQubeEnv(sonarServer) {
            sh """mvn sonar:sonar \
                -Dsonar.projectKey=${projectKey} \
                -Dsonar.projectName="${projectName}" """
        }

        //Step 2: Wait for Quality Gate result
        if (waitForQGate) {
            echo "Waiting for SonarQube Quality Gate result..."
            timeout(time: timeoutMinutes, unit: 'MINUTES') {
                def qg = waitForQualityGate()
                if (qg.status != 'OK') {
                    error "SonarQube Quality Gate failed: ${qg.status}"
                }
            }
            echo "SonarQube Quality Gate passed."
        }

        echo "✅ SonarQube analysis completed and uploaded successfully!"
        return [success: true]

    } catch (Exception e) {
        env.failedStage = "SonarQube - SAST"
        env.failedReason = e.getMessage()
        echo "❌ SonarQube analysis or Quality Gate failed: ${e.message}"
        currentBuild.result = 'FAILURE'
        throw e
    }
}