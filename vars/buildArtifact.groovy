def call(Map params = [:]) {
    // -------------------------------------------------------------------------
    // Determine build tool priority:
    // 1. Explicitly passed as param:  buildArtifact(buildTool: 'maven')
    // 2. Set as environment variable: BUILD_TOOL = 'npm' in Jenkinsfile
    // 3. Auto-detected from files:    pom.xml → maven, package.json → npm, etc.
    // 4. Default fallback:            maven
    // -------------------------------------------------------------------------
    def buildTool = params.buildTool ?: env.BUILD_TOOL

    try {
        if (!buildTool) {
            buildTool = detectBuildTool()
            echo "Auto-detected build tool: ${buildTool}"
        } else {
            echo "Using specified build tool: ${buildTool}"
        }

        // Normalize to lowercase to avoid case sensitivity issues (Maven vs maven)
        def tool = buildTool.toLowerCase()

        switch (tool) {
            case 'maven':
                buildMaven(params)
                break
            case 'npm':
            case 'node':
            case 'next':
                // All Node.js based projects use the same npm build function
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
        // Capture failed stage info for use in failure notification emails
        env.failedStage = "Build Artifact (${buildTool ?: 'unknown'})"
        env.failedReason = e.getMessage()
        echo "Build failed for tool '${buildTool}': ${e.getMessage()}"
        currentBuild.result = 'FAILURE'
        throw e
    }
}

// -----------------------------------------------------------------------------
// Auto-detects build tool by checking for common project files in the workspace.
// Order matters — check most specific first.
// -----------------------------------------------------------------------------
def detectBuildTool() {
    if (fileExists('pom.xml'))                                         return 'maven'
    if (fileExists('package.json'))                                    return 'npm'
    if (fileExists('go.mod'))                                          return 'go'
    if (fileExists('build.gradle') || fileExists('build.gradle.kts')) return 'gradle'
    if (fileExists('Makefile'))                                        return 'maven' // fallback, adjust if needed
    return 'maven' // safe default
}

// -----------------------------------------------------------------------------
// MAVEN BUILD
// Used for: Java Spring Boot microservices (TCE, UAA, API Gateway, etc.)
//
// Tool configuration:
//   - Maven: 'mave-3.9.15' (configured in Jenkins → Global Tool Configuration)
//   - JDK:   'jdk21'       (configured in Jenkins → Global Tool Configuration)
//
// Why no archiveArtifacts?
//   - The compiled JAR is copied into the Docker image during docker build
//   - The Docker image is pushed to Harbor registry — Harbor IS the artifact store
//   - Archiving JARs in Jenkins wastes ~80-140MB per build (confirmed from disk audit)
//   - Dependency check report is published via dependencyCheckPublisher in post{}
//     which reads directly from workspace — no archiving needed
// -----------------------------------------------------------------------------
def buildMaven(Map params) {
    echo "Stage: Build Artifact - Maven"

    // Resolve Maven and JDK tool paths from Jenkins Global Tool Configuration
    def mvnHome  = tool name: 'mave-3.9.15', type: 'maven'
    def javaHome = tool name: 'jdk21',        type: 'jdk'

    // Inject Maven and Java into PATH for this build step only (does not affect other stages)
    withEnv(["PATH+MAVEN=${mvnHome}/bin", "PATH+JAVA=${javaHome}/bin", "JAVA_HOME=${javaHome}"]) {
        sh 'java -version'
        sh 'javac -version'
        sh 'mvn -version'

        // Default: clean build, skip tests (tests run in separate stage if needed)
        // Override via: buildArtifact(command: 'mvn clean package -Pproduction -B')
        def buildCmd = params.command ?: 'mvn clean package -DskipTests=true -B'
        echo "Running: ${buildCmd}"
        sh buildCmd

        // ✅ JAR is built in target/ and will be picked up by Dockerfile (COPY target/*.jar)
        // ❌ archiveArtifacts REMOVED — was storing 80-140MB JARs per build unnecessarily
        // ❌ dependencyCheckPublisher does NOT need archiveArtifacts — reads from workspace
    }
}

// -----------------------------------------------------------------------------
// NPM / NODE.JS / NEXT.JS BUILD
// Used for: React frontends, Next.js apps (SoftAML Portal, CBS, SoftPaperless, etc.)
//
// Why no archiveArtifacts?
//   - The dist/build/.next output is copied into the Docker image (COPY dist/ /app)
//   - Docker image is pushed to Harbor — no need to store build output in Jenkins
//   - node_modules alone can be 200MB+ — never archive these
// -----------------------------------------------------------------------------
def buildNpm(Map params) {
    echo "Stage: Build Artifact - NPM/Node.js"
    sh 'node --version'
    sh 'npm --version'

    // Use 'npm ci' by default (faster, uses package-lock.json, better for CI)
    // Override via: buildArtifact(installCommand: 'npm install')
    def installCmd = params.installCommand ?: 'npm ci'

    // Override via: buildArtifact(buildCommand: 'npm run build:prod')
    def buildCmd = params.buildCommand ?: 'npm run build'

    echo "Installing dependencies: ${installCmd}"
    sh installCmd

    // Only run build if a build script exists in package.json or explicitly passed
    if (params.buildCommand || fileExists('package.json') &&
        sh(script: 'grep -q "\\"build\\"" package.json', returnStatus: true) == 0) {
        echo "Building: ${buildCmd}"
        sh buildCmd
    } else {
        echo "No build script defined in package.json, skipping build step"
    }

    // ✅ dist/build/.next output will be picked up by Dockerfile (COPY dist/ /app)
    // ❌ archiveArtifacts REMOVED — was storing dist folders unnecessarily in Jenkins
}

// -----------------------------------------------------------------------------
// GO BUILD
// Used for: Go-based microservices
//
// Why no archiveArtifacts?
//   - The compiled binary is copied into the Docker image (COPY app /usr/local/bin/)
//   - Docker image is pushed to Harbor
// -----------------------------------------------------------------------------
def buildGo(Map params) {
    echo "Stage: Build Artifact - Go"
    sh 'go version'

    // Override via: buildArtifact(command: 'go build -o myapp ./cmd/server')
    def buildCmd = params.command ?: 'go build -o app .'
    echo "Running: ${buildCmd}"
    sh buildCmd

    // ✅ Binary 'app' will be picked up by Dockerfile (COPY app /usr/local/bin/)
    // ❌ archiveArtifacts REMOVED — binary goes into Docker image, not Jenkins storage
}

// -----------------------------------------------------------------------------
// GRADLE BUILD
// Used for: Gradle-based Java projects
//
// Why no archiveArtifacts?
//   - The JAR in build/libs/ is copied into the Docker image
//   - Docker image is pushed to Harbor
// -----------------------------------------------------------------------------
def buildGradle(Map params) {
    echo "Stage: Build Artifact - Gradle"
    sh './gradlew --version'

    // Override via: buildArtifact(command: './gradlew clean build')
    def buildCmd = params.command ?: './gradlew clean build -x test'
    echo "Running: ${buildCmd}"
    sh buildCmd

    // ✅ JAR in build/libs/ will be picked up by Dockerfile
    // ❌ archiveArtifacts REMOVED — JAR goes into Docker image, not Jenkins storage
}

// -----------------------------------------------------------------------------
// .NET BUILD
// Used for: .NET / ASP.NET Core projects
//
// Why no archiveArtifacts?
//   - Published output is copied into the Docker image (COPY out/ /app)
//   - Docker image is pushed to Harbor
// -----------------------------------------------------------------------------
def buildDotnet(Map params) {
    echo "Stage: Build Artifact - .NET"
    sh 'dotnet --version'

    // Override via: buildArtifact(command: 'dotnet publish -c Release -o out --self-contained')
    def buildCmd = params.command ?: 'dotnet publish -c Release -o out'
    sh buildCmd

    // ✅ Output in out/ will be picked up by Dockerfile (COPY out/ /app)
    // ❌ archiveArtifacts REMOVED — output goes into Docker image, not Jenkins storage
}