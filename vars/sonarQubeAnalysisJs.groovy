// vars/sonarQubeAnalysisJs.groovy
def call(Map params = [:]) {
    def defaultParams = [
        projectKey       : 'default-js-project',
        projectName      : 'Default JS Project',
        sources          : 'src',
        tests            : 'src',
        sonarServerName  : 'SonarQube Remote Server',
        sonarHostUrl     : 'http://62.84.183.2:9000',
        sonarTokenId       : 'sonarqube-token-remote',
        lcovPath         : 'coverage/lcov.info',   // optional, if you run tests
        exclusions       : '',
        additionalParams : ''
    ]

    def config = defaultParams + params

    def result = [
        success      : false,
        dashboardUrl : null,
        error        : null
    ]

    try {
        echo "=== Starting SonarQube Analysis (JavaScript/TypeScript) ==="
        echo "Project: ${config.projectName} (${config.projectKey})"

        // Use official SonarScanner CLI via Docker — ZERO dependencies on agent!
        withCredentials([string(credentialsId: config.sonarToken.split(':')[0], variable: 'TOKEN')]) {
            def dockerCmd = """
                docker run --rm \
                  -e SONAR_HOST_URL=${config.sonarHostUrl} \
                  -e SONAR_LOGIN=\$TOKEN \
                  -v "\$(pwd):/usr/src" \
                  sonarsource/sonar-scanner-cli \
                  -Dsonar.projectKey=${config.projectKey} \
                  -Dsonar.projectName=${config.projectName} \
                  -Dsonar.sources=${config.sources}
            """

            if (fileExists(config.lcovPath)) {
                dockerCmd += " -Dsonar.javascript.lcov.reportPaths=${config.lcovPath}"
            }

            if (config.exclusions) {
                dockerCmd += " -Dsonar.exclusions=${config.exclusions}"
            }

            if (config.additionalParams) {
                dockerCmd += " ${config.additionalParams}"
            }

            sh '''
                set +x
                ''' + dockerCmd
        }

        result.success = true
        result.dashboardUrl = "${config.sonarHostUrl}/dashboard?id=${config.projectKey}"
        echo "SonarQube JS analysis completed: ${result.dashboardUrl}"

    } catch (Exception e) {
        result.success = false
        result.error = e.message
        echo "SonarQube JS analysis failed: ${e.message}"
        if (params.abortPipeline) {
            error("SonarQube analysis failed")
        }
    }

    return result
}