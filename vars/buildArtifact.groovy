/**
 * buildArtifact.groovy
 *
 * Compiles the application artifact for all supported build tools.
 * The compiled output stays in the workspace and is picked up by
 * the Dockerfile — NO archiveArtifacts (Harbor is the artifact store).
 *
 * Supported build tools (auto-detected or set via BUILD_TOOL env):
 *   maven  → mvn clean package     → target/*.jar
 *   npm    → npm ci + npm run build → dist/ or build/ or .next/
 *   node   → same as npm
 *   next   → same as npm
 *   go     → go build              → app binary
 *   gradle → ./gradlew clean build → build/libs/*.jar
 *   dotnet → dotnet publish        → out/
 *
 * Usage:
 *   buildArtifact()                                  // auto-detect
 *   buildArtifact(buildTool: 'maven')                // explicit
 *   buildArtifact(command: 'mvn clean package -Pprod') // custom command
 */
def call(Map params = [:]) {
    // -------------------------------------------------------------------------
    // Build tool priority:
    // 1. Explicit param:    buildArtifact(buildTool: 'maven')
    // 2. Env variable:      BUILD_TOOL = 'npm' in Jenkinsfile environment{}
    // 3. Auto-detect:       checks pom.xml, package.json, go.mod, etc.
    // 4. Default fallback:  maven
    // -------------------------------------------------------------------------
    def buildTool = params.buildTool ?: env.BUILD_TOOL

    try {
        if (!buildTool) {
            buildTool = detectBuildTool()
            echo "Auto-detected build tool: ${buildTool}"
        } else {
            echo "Using build tool: ${buildTool}"
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
            case 'python':
                buildPython(params)
                break
            default:
                error "Unsupported build tool: ${buildTool}. Supported: maven, npm, node, next, go, gradle, dotnet"
        }

        return [success: true, buildTool: tool]

    } catch (Exception e) {
        env.failedStage  = "Build Artifact (${buildTool ?: 'unknown'})"
        env.failedReason = e.getMessage()
        echo "Build failed: ${e.getMessage()}"
        currentBuild.result = 'FAILURE'
        throw e
    }
}

// -----------------------------------------------------------------------------
// MAVEN — Java Spring Boot microservices
// Tools configured in: Jenkins → Global Tool Configuration
//   Maven: 'mave-3.9.15'
//   JDK:   'jdk21'
// Output: target/*.jar  →  picked up by Dockerfile COPY target/*.jar app.jar
// NO archiveArtifacts — JAR goes into Docker image, Harbor is the artifact store
// -----------------------------------------------------------------------------
def buildMaven(Map params) {
    echo "Stage: Build Artifact - Maven"
    def mvnHome  = tool name: 'mave-3.9.15', type: 'maven'
    def javaHome = tool name: 'jdk21',        type: 'jdk'

    withEnv(["PATH+MAVEN=${mvnHome}/bin", "PATH+JAVA=${javaHome}/bin", "JAVA_HOME=${javaHome}"]) {
        sh 'java -version'
        sh 'javac -version'
        sh 'mvn -version'

        // Override: buildArtifact(command: 'mvn clean package -Pproduction -B')
        def buildCmd = params.command ?: 'mvn clean package -DskipTests=true -B'
        echo "Running: ${buildCmd}"
        sh buildCmd

        // ✅ JAR in target/ is picked up by: COPY target/*.jar app.jar in Dockerfile
        // ❌ NO archiveArtifacts — was wasting 80-140MB per build (confirmed in disk audit)
        // ❌ dependencyCheckPublisher reads from workspace directly — no archiving needed
    }
}

// -----------------------------------------------------------------------------
// NPM / NODE.JS / NEXT.JS — React, Next.js frontends
// Output: dist/ or build/ or .next/  →  picked up by Dockerfile COPY dist/ /app
// NO archiveArtifacts — output goes into Docker image
// -----------------------------------------------------------------------------
def buildNpm(Map params) {
    echo "Stage: Build Artifact - NPM/Node.js"
    sh 'node --version'
    sh 'npm --version'

    // 'npm ci' is preferred in CI: faster, reproducible, uses package-lock.json
    // Override: buildArtifact(installCommand: 'npm install')
    def installCmd = params.installCommand ?: 'npm ci'
    def buildCmd   = params.buildCommand   ?: 'npm run build'

    echo "Installing dependencies: ${installCmd}"
    sh installCmd

    // Only run build if a build script is defined in package.json
    if (params.buildCommand || sh(script: 'grep -q \'"build"\' package.json', returnStatus: true) == 0) {
        echo "Building: ${buildCmd}"
        sh buildCmd
    } else {
        echo "No build script in package.json — skipping build step"
    }

    // ✅ dist/build/.next output picked up by: COPY dist/ /app in Dockerfile
    // ❌ NO archiveArtifacts — node_modules alone is 200MB+, never archive
}

// -----------------------------------------------------------------------------
// GO — Go microservices
// Output: app binary  →  picked up by Dockerfile COPY app /usr/local/bin/
// -----------------------------------------------------------------------------
def buildGo(Map params) {
    echo "Stage: Build Artifact - Go"
    sh 'go version'

    // Override: buildArtifact(command: 'go build -o myapp ./cmd/server')
    def buildCmd = params.command ?: 'go build -o app .'
    echo "Running: ${buildCmd}"
    sh buildCmd

    // ✅ Binary 'app' picked up by: COPY app /usr/local/bin/ in Dockerfile
}

// -----------------------------------------------------------------------------
// GRADLE — Gradle-based Java projects
// Output: build/libs/*.jar  →  picked up by Dockerfile
// -----------------------------------------------------------------------------
def buildGradle(Map params) {
    echo "Stage: Build Artifact - Gradle"
    sh './gradlew --version'

    def buildCmd = params.command ?: './gradlew clean build -x test'
    echo "Running: ${buildCmd}"
    sh buildCmd

    // ✅ JAR in build/libs/ picked up by Dockerfile
}

// -----------------------------------------------------------------------------
// .NET — ASP.NET Core projects
// Output: out/  →  picked up by Dockerfile COPY out/ /app
// -----------------------------------------------------------------------------
def buildDotnet(Map params) {
    echo "Stage: Build Artifact - .NET"
    sh 'dotnet --version'

    def buildCmd = params.command ?: 'dotnet publish -c Release -o out'
    echo "Running: ${buildCmd}"
    sh buildCmd

    // ✅ Published output in out/ picked up by Dockerfile
}


// Python — no compilation needed
// pip install runs during Docker build using requirements.txt
// OSV-Scanner and OWASP will scan requirements.txt directly
def buildPython(Map params) {
    echo "Stage: Build Artifact - Python"

    if (!fileExists('requirements.txt')) {
        echo "⚠️  No requirements.txt found — skipping pip install"
        return
    }

    sh """
        python3 --version || python --version
        echo "✅ Python project detected"
        echo "ℹ️  requirements.txt found — dependencies will be installed during Docker build"
        echo "ℹ️  No compilation needed for Python projects"
        pip3 install -r requirements.txt --dry-run 2>/dev/null || \
        pip install -r requirements.txt --dry-run 2>/dev/null || \
        echo "ℹ️  pip dry-run skipped — dependencies will install in Docker"
    """
}

// -----------------------------------------------------------------------------
// Auto-detect build tool from common project files
// Order matters: check most specific first
// -----------------------------------------------------------------------------
def detectBuildTool() {
    if (fileExists('pom.xml'))                                         return 'maven'
    if (fileExists('package.json'))                                    return 'npm'
    if (fileExists('go.mod'))                                          return 'go'
    if (fileExists('build.gradle') || fileExists('build.gradle.kts')) return 'gradle'
    if (fileExists('*.csproj')     || fileExists('*.sln'))             return 'dotnet'
    if (fileExists('requirements.txt') || fileExists('Pipfile'))       return 'python'
    if (fileExists('requirements*.txt'))                              return 'python'
    return 'maven' // safe default
}