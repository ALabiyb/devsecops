def call(Map params = [:]) {
    // Extract parameters with default values
    def projectName = params.projectName ?: env.JOB_NAME
    def imageName = params.imageName ?: env.JOB_NAME?.toLowerCase()?.replaceAll(/[^a-z0-9\-_.]/, '-')
    def imageTag = params.imageTag ?: env.BUILD_NUMBER ?: 'latest'
    def registryUrl = params.registryUrl ?: ''
    def registryCredentialsId = params.registryCredentialsId ?: ''
    def dockerfilePath = params.dockerfilePath ?: 'Dockerfile'
    def buildContext = params.buildContext ?: '.'
    def buildArgs = params.buildArgs ?: [:]
    def pushToRegistry = params.pushToRegistry ?: false
    def removeAfterPush = params.removeAfterPush ?: true
    def failOnError = params.containsKey('failOnError') ? params.failOnError : true  // default: fail pipeline on error

    echo "=== Building Docker Image and Pushing to Registry ==="
    echo "Project Name: ${projectName}"
    echo "Image Name: ${imageName}"
    echo "Image Tag: ${imageTag}"
    echo "Registry URL: ${registryUrl}"
    echo "Push to Registry: ${pushToRegistry}"

    def localImageName = "${imageName}:${imageTag}"
    def registryImageName = registryUrl ? "${registryUrl}/${imageName}:${imageTag}" : localImageName

    try {
        // Validate Dockerfile existence
        if (!fileExists(dockerfilePath)) {
            error "Dockerfile not found at path: ${dockerfilePath}"
        }

        // Build args handling
        def buildArgsString = buildArgs.collect { key, value ->
        "--build-arg ${key}=${value}"
        }.join(' ')

        echo "🔨 Building Docker image..."
        sh """
            docker build -t ${localImageName} \
                -f ${dockerfilePath} \
                ${buildArgsString} \
                ${buildContext}
        """

        echo "✅ Successfully built Docker image: ${localImageName}"

        def result = [
                success: true,
                imageName: registryImageName,
                localImageName: localImageName,
                pushed: false
        ]

        // Push to registry if required
        if (pushToRegistry) {
            echo "🚀 Pushing Docker image to registry..."
            if(!registryUrl) {
                error "Registry URL is required to push the image."
            }
            if(!registryCredentialsId) {
                error "Registry credentials ID is required to push the image."
            }
            withCredentials([usernamePassword( credentialsId: registryCredentialsId, usernameVariable: 'REGISTRY_USER', passwordVariable: 'REGISTRY_PASS')]) {
                echo "Logging into Docker registry..."
                sh "echo \$REGISTRY_PASS | docker login ${registryUrl} -u \$REGISTRY_USER --password-stdin"
                sh "docker tag ${localImageName} ${registryImageName}"
                sh "docker push ${registryImageName}"
            }
        }

        echo "✅ Successfully pushed Docker image: ${registryImageName}"
        result.pushed = true


        // Remove local image after push if required
        if (removeAfterPush) {
            echo "🧹 Removing local Docker image..."
            sh "docker rmi ${registryImageName} || true"
        }

        echo "✅ Docker image build and push process completed."
        return result

    } catch (Exception e) {
        env.failedStage = "Build Docker Image and Push"
        env.failedReason = e.getMessage()
        echo "Docker build/push error: ${e}"
        if (failOnError) {
            currentBuild.result = 'FAILURE'
            throw e
        } else {
            currentBuild.result = 'UNSTABLE'
            return [success: false, error: e.getMessage()]
        }
    }
}