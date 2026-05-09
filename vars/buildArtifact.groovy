























def call(Map params = [:]) {
    def buildTool = params.buildTool ?: env.BUILD_TOOL

    try {
        if (!buildTool) {
            buildTool = detectBuildTool()
            echo "Auto-detected build tool: ${buildTool}"
        } else {
            echo "Using specified build tool: ${buildTool}"
        }

        def tool = buildTool.toLowerCase()

        switch (tool) {
            case 'maven':
                buildMaven(params)
                break
            case 'npm':
            case 'node':
            case 'next':
                buildNpm(params)
                break
            case 'go':
                buildGo(params)
                break
            case 'gradle':
                buildGradle(params)
                break
            case 'dotnet':
                buildDotnet(params)
                break
            default:
                error "Unsupported build tool: ${buildTool}"
        }

        return [success: true, buildTool: tool]

    } catch (Exception e) {
        env.failedStage = "Build Artifact (${buildTool ?: 'unknown'})"
        env.failedReason = e.getMessage()
        echo "Build failed for tool '${buildTool}': ${e.getMessage()}"
        currentBuild.result = 'FAILURE'
        throw e
    }
}

def detectBuildTool() {
    if (fileExists('pom.xml'))                                          return 'maven'
    if (fileExists('package.json'))                                     return 'npm'
    if (fileExists('go.mod'))                                           return 'go'
    if (fileExists('build.gradle') || fileExists('build.gradle.kts'))  return 'gradle'
    return 'maven'
}

def buildMaven(Map params) {
    echo "Stage: Build Artifact - Maven"

    def mvnHome  = tool name: 'mave-3.9.15', type: 'maven'
    def javaHome = tool name: 'jdk21',        type: 'jdk'

    withEnv(["PATH+MAVEN=${mvnHome}/bin", "PATH+JAVA=${javaHome}/bin", "JAVA_HOME=${javaHome}"]) {
        sh 'java -version'
        sh 'javac -version'
        sh 'mvn -version'

        def buildCmd = params.command ?: 'mvn clean package -DskipTests=true -B'
        echo "Running: ${buildCmd}"
        sh buildCmd

        // ✅ No archiveArtifacts - JAR is copied into Docker image, not stored in Jenkins
        // ✅ Dependency check report is handled by dependencyCheckPublisher in post{}
    }
}

def buildNpm(Map params) {
    echo "Stage: Build Artifact - NPM/Node.js"
    sh 'node --version'
    sh 'npm --version'

    def installCmd = params.installCommand ?: 'npm ci'
    def buildCmd   = params.buildCommand   ?: 'npm run build'

    echo "Installing dependencies: ${installCmd}"
    sh installCmd

    if (params.buildCommand || fileExists('package.json') &&
        sh(script: 'grep -q "\\"build\\"" package.json', returnStatus: true) == 0) {
        echo "Building: ${buildCmd}"
        sh buildCmd
    } else {
        echo "No build script defined, skipping"
    }

    // ✅ No archiveArtifacts - dist is copied into Docker image
}

def buildGo(Map params) {
    echo "Stage: Build Artifact - Go"
    sh 'go version'

    def buildCmd = params.command ?: 'go build -o app .'
    echo "Running: ${buildCmd}"
    sh buildCmd

    // ✅ No archiveArtifacts - binary is copied into Docker image
}

def buildGradle(Map params) {
    echo "Stage: Build Artifact - Gradle"
    sh './gradlew --version'

    def buildCmd = params.command ?: './gradlew clean build -x test'
    echo "Running: ${buildCmd}"
    sh buildCmd

    // ✅ No archiveArtifacts - JAR is copied into Docker image
}

def buildDotnet(Map params) {
    echo "Stage: Build Artifact - .NET"
    sh 'dotnet --version'

    def buildCmd = params.command ?: 'dotnet publish -c Release -o out'
    sh buildCmd

    def artifactPath = params.artifacts ?: 'out/**'
    archiveArtifacts artifacts: artifactPath, fingerprint: true, allowEmptyArchive: false
}