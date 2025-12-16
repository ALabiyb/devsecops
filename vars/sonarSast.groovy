// vars/sonarSast.groovy
def call(Map params = [:]) {
    try {
        echo "=== Starting SonarQube Analysis (SAST) ==="

        def sonarServer = params.sonarServer ?: 'SonarQube Server'  // Name as configured in Jenkins Global Config
        def projectKey = params.projectKey ?: env.JOB_NAME
        def projectName = params.projectName ?: env.JOB_NAME

        withSonarQubeEnv("SonarQube Server") {
            def sonarCmd = params.command ?: "mvn clean verify sonar:sonar " +
                    "-Dsonar.projectKey=${projectKey} " +
                    "-Dsonar.projectName=\"${projectName}\" " +

            // For debugging, you can echo what's being set
//            echo "SonarQube Server: ${sonarServer}"
//            echo "Project Key: ${projectKey}"
//            echo "Project Name: ${projectName}"

            sh sonarCmd
        }

        echo "✅ SonarQube analysis passed!"
        return [success: true]

    } catch (Exception e) {
        env.failedStage = "SonarQube - SAST"
        echo "❌ SonarQube analysis failed: ${e.message}"
        currentBuild.result = 'FAILURE'
        throw e
    }
}