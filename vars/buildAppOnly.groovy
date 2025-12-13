/**
 * buildAppOnly.groovy
 * A Jenkins shared library function to build application only.
 */

def call(Map params = [:]) {
    //Extract parameters with default values
    def projectName = params.projectName ?: env.JOB_NAME
    def imageName = params.imageName ?: env.JOB_NAME?.toLowerCase()?.replaceAll(/[^a-z0-9\-_.]/, '-')
    def imageTag = params.imageTag ?: env.BUILD_NUMBER ?: 'latest'
    def registryUrl = params.registryUrl ?: ''
    def registryCredentialsId = params.registryCredentialsId ?: ''
    def dockerfilePath = params.dockerfilePath ?: './Dockerfile'
    def buildContext = params.buildContext ?: '.'
    def buildArgs = params.buildArgs ?: [:]
    def pushToRegistry = params.pushToRegistry ?: false // Changed default to false
    def removeAfterPush = params.removeAfterPush ?: true
    def gitAuthor = params.gitAuthor ?: 'unknown'
    def gitCommitMessage = params.gitCommitMessage ?: 'No commit message'
    def failOnError = params.containsKey('failOnError') ? params.failOnError : true  // default: fail pipeline on error


    echo "=== Building Application Docker Image ==="
    echo "Project Name: ${projectName}"
    echo "Image Name: ${imageName}"
    echo "Image Tag: ${imageTag}"
    echo "Registry URL: ${registryUrl}"
    echo "Push to Registry: ${pushToRegistry}"


    try {
        echo "🔨 Building application Docker image..."

        // def fullImageName = registryUrl ? "${registryUrl}/${imageName}:${imageTag}" : "${imageName}:${imageTag}"

        def localImageName = "${imageName}:${imageTag}"
        def fullImageName = registryUrl ? "${registryUrl}/${imageName}:${imageTag}" : localImageName

        if(!fileExists(dockerfilePath)) {
            error "Dockerfile not found at path: ${dockerfilePath}"
        }

        // Build image 
        // def buildImage = docker.build(fullImageName, "-f ${dockerfilePath} ${buildContext}")
        // Build args handling
        def buildArgsString = ""
        if (buildArgs) {
            buildArgs.each { key, value ->
                buildArgsString += "--build-arg ${key}='${value}' "
            }
        }

        // Shell-based build with build args
        sh "docker build -t ${localImageName} -f ${dockerfilePath} ${buildArgsString} ${buildContext}"

        //Separate tagging step
        if (registryUrl){
            sh "docker tag ${localImageName} ${fullImageName}"
        }

        echo "✅ Successfully built Docker image: ${fullImageName}"

        return [
                success: true,
                imageName: fullImageName,
                localImageName: localImageName,
                buildSuccess: true,
                pushSuccess: false,
                pushed: false
            ]
    } catch (Exception e) {
        def errorMsg = "❌ Build failed: ${e.getMessage()}"
        echo errorMsg
        if (failOnError) {
            error("Docker build failed: ${e.getMessage()}")
        }
        //error("Docker build failed: ${e.getMessage()}")
        return [
            success: false,
            buildSuccess: false,
            pushSuccess: false,
            error: e.getMessage(),
            errorType: 'BUILD_ERROR',
            imageName: null,
            imageId: null,
            imageSize: null,
            buildDuration: null,
            localImageName: null,
            pushed: false
        ]
    }
}