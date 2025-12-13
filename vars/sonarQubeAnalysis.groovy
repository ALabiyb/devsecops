def call(Map params = [:]) {
    // Default parameters - ADD SONAR TOKEN SUPPORT
    def defaultParams = [
        projectKey: 'default-project',
        projectName: 'Default Project',
        sonarServerName: 'SonarQubeServer',
        sonarHostUrl: '',
        sonarToken: '',
        javaHome: '/usr/lib/jvm/java-21-openjdk-amd64',
        mavenToolName: 'maven-3.9',
        mavenGoals: 'clean compile sonar:sonar',
        additionalParams: '',
        skipTests: false,
        enableQualityGate: true,
        qualityGateTimeout: 1,
        qualityGateTimeoutUnit: 'HOURS',
        abortPipelineOnFailure: false
    ]
    
    // Merge user parameters with defaults
    def config = defaultParams + params
    
    // Initialize result object
    def result = [
        success: false,
        message: '',
        projectKey: config.projectKey,
        projectName: config.projectName,
        error: null,
        dashboardUrl: null
    ]
    
    try {
        echo "==== Starting SonarQube Analysis ===="
        echo "Project Key: ${config.projectKey}"
        echo "Project Name: ${config.projectName}"
        echo "SonarQube Server: ${config.sonarServerName}"
        echo "SonarQube URL: ${config.sonarHostUrl}"
        echo "Maven Tool: ${config.mavenToolName}"
        
        // Get Maven tool
        def mavenHome = tool name: config.mavenToolName, type: 'maven'
        
        // Set environment variables
        withEnv([
            "JAVA_HOME=${config.javaHome}", 
            "PATH=${config.javaHome}/bin:${mavenHome}/bin:${env.PATH}"
        ]) {
            withSonarQubeEnv(config.sonarServerName) {
                // Build the Maven command safely
                def mavenCommand = new StringBuilder()
                mavenCommand.append("mvn ${config.mavenGoals}")
                mavenCommand.append(" -Dsonar.projectKey=${config.projectKey}")
                mavenCommand.append(" -Dsonar.projectName='${config.projectName}'")
                mavenCommand.append(" -Dsonar.host.url=${config.sonarHostUrl}")
                mavenCommand.append(" -Dsonar.login=${config.sonarToken}")
                mavenCommand.append(" -Dmaven.compiler.fork=true")
                mavenCommand.append(" -Dmaven.compiler.executable=\${JAVA_HOME}/bin/javac")
                
                if (config.skipTests) {
                    mavenCommand.append(" -DskipTests=true")
                }
                
                // Safely handle additional parameters - trim and only add if not empty
                if (config.additionalParams?.trim()) {
                    mavenCommand.append(" ${config.additionalParams.trim()}")
                }
                
                sh """
                    echo "=== Starting SonarQube Analysis ==="
                    ${mavenCommand.toString()}
                """
            }
        }
        
        env.SONAR_ANALYSIS_SUCCESS = 'true'
        env.SONAR_PROJECT_KEY = config.projectKey
        
        // Update result on success
        result.success = true
        result.message = "SonarQube analysis completed successfully"
        result.dashboardUrl = "${config.sonarHostUrl}/dashboard?id=${config.projectKey}"
        
        echo "✅ SonarQube analysis completed successfully"
        
    } catch (Exception e) {
        echo "❌ SonarQube analysis failed: ${e.getMessage()}"
        
        // Update result on failure
        result.success = false
        result.error = e.getMessage()
        result.message = "SonarQube analysis failed: ${e.getMessage()}"
        
        env.SONAR_ANALYSIS_SUCCESS = 'false'
        
        if (config.abortPipelineOnFailure) {
            error("SonarQube analysis failed: ${e.getMessage()}")
        }
    }
    
    // ALWAYS return the result object
    return result
}