def call(Map params = [:]) {
    try {
        // Determine build tool: param > env > auto-detect > default to maven
        def buildTool = params.buildTool ?: env.BUILD_TOOL

        if (!buildTool) {
            buildTool = detectBuildTool()
            echo "Auto-detected build tool: ${buildTool}"
        } else {
            echo "Using specified build tool: ${buildTool}"
        }

        // Normalize to lowercase for switch
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
                error "Unsupported or unknown build tool: ${buildTool}. Supported: maven, npm, go, gradle, dotnet"
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

// Helper: Auto-detect based on common files
def detectBuildTool() {
    if (fileExists('pom.xml'))          return 'maven'
    if (fileExists('package.json'))     return 'npm'
    if (fileExists('go.mod'))           return 'go'
    if (fileExists('build.gradle') || fileExists('build.gradle.kts')) return 'gradle'
    if (fileExists('Makefile'))         return 'maven' // fallback or custom
    return 'maven' // safe default
}

// Maven build
def buildMaven(Map params) {
    echo "Stage: Build Artifact - Maven"
    sh 'java -version'
    sh 'mvn -version'

    def buildCmd = params.command ?: 'mvn clean package -DskipTests=true -B'
    echo "Running: ${buildCmd}"
    sh buildCmd

    def artifactPath = params.artifacts ?: 'target/*.jar'
    archiveArtifacts artifacts: artifactPath, fingerprint: true, allowEmptyArchive: false
}

// NPM / Node.js / Next.js build
def buildNpm(Map params) {
    echo "Stage: Build Artifact - NPM/Node.js"
    sh 'node --version'
    sh 'npm --version'

    def installCmd = params.installCommand ?: 'npm ci'
    def buildCmd   = params.buildCommand   ?: 'npm run build'

    echo "Installing dependencies: ${installCmd}"
    sh installCmd

    if (params.buildCommand || fileExists('package.json') && sh(script: 'grep -q "\\"build\\"" package.json', returnStatus: true) == 0) {
        echo "Building: ${buildCmd}"
        sh buildCmd
    } else {
        echo "No build script defined, skipping build step"
    }

    def artifactPath = params.artifacts ?: 'dist/**, build/**, .next/**'
    archiveArtifacts artifacts: artifactPath, fingerprint: true, allowEmptyArchive: true
}

// Go build
def buildGo(Map params) {
    echo "Stage: Build Artifact - Go"
    sh 'go version'

    def buildCmd = params.command ?: 'go build -o app .'
    echo "Running: ${buildCmd}"
    sh buildCmd

    def artifactPath = params.artifacts ?: 'app'
    archiveArtifacts artifacts: artifactPath, fingerprint: true, allowEmptyArchive: false
}

// Gradle build
def buildGradle(Map params) {
    echo "Stage: Build Artifact - Gradle"
    sh './gradlew --version'

    def buildCmd = params.command ?: './gradlew clean build -x test'
    echo "Running: ${buildCmd}"
    sh buildCmd

    def artifactPath = params.artifacts ?: 'build/libs/*.jar'
    archiveArtifacts artifacts: artifactPath, fingerprint: true, allowEmptyArchive: false
}

// .NET build (example)
def buildDotnet(Map params) {
    echo "Stage: Build Artifact - .NET"
    sh 'dotnet --version'

    def buildCmd = params.command ?: 'dotnet publish -c Release -o out'
    sh buildCmd

    def artifactPath = params.artifacts ?: 'out/**'
    archiveArtifacts artifacts: artifactPath, fingerprint: true, allowEmptyArchive: false
}