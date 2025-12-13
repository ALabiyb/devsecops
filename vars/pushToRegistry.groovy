def call(Map params = [:]) {
    // Required parameters: imageName, imageTag, registryType, credentialsId
    def imageName = params.get('imageName')
    def imageTag = params.get('imageTag', 'latest')
    def registryType = params.get('registryType', 'dockerhub')
    def credentialsId = params.get('credentialsId')
    def privateRegistryUrl = params.get('privateRegistryUrl', '')
    def removeAfterPush = params.get('removeAfterPush', true)
    def failOnError = params.containsKey('failOnError') ? params.failOnError : true
    def robotUsername = params.get('robotUsername', 'robot$jenkins')
    def localImageName = params.get('localImageName', "${imageName}:${imageTag}") // NEW: Accept local image name
    
    try {
        if (!imageName || !credentialsId) {
            error "imageName and credentialsId are required parameters"
        }

        def targetImageName = ''
        def registryUrl = ''

        switch(registryType.toLowerCase()) {
            case 'dockerhub':
                targetImageName = "docker.io/${imageName}:${imageTag}"
                registryUrl = 'docker.io'
                echo "Pushing to Docker Hub: ${targetImageName}"
                break
            case 'harbor':
            case 'private':
                if (!privateRegistryUrl) {
                    error "privateRegistryUrl is required for harbor/private registry type"
                }
                targetImageName = "${privateRegistryUrl}/${imageName}:${imageTag}"
                registryUrl = privateRegistryUrl
                echo "Pushing to Harbor Registry: ${targetImageName}"
                break
            default:
                error "Unsupported registryType: ${registryType}. Supported types are 'dockerhub', 'harbor', and 'private'."
        }

        // For Harbor with secret token authentication
        if (registryType.toLowerCase() in ['harbor', 'private']) {
            withCredentials([string(credentialsId: credentialsId, variable: 'HARBOR_SECRET')]) {
                echo "🔐 Authenticating to Harbor registry using robot account: ${robotUsername}"
                echo "📦 Local image: ${localImageName}"
                echo "🎯 Target image: ${targetImageName}"
                
                // Direct login - insecure registry is already configured in Docker daemon
                sh """
                    echo "\$HARBOR_SECRET" | docker login ${privateRegistryUrl} -u '${robotUsername}' --password-stdin
                """
                
                // Tag and push image - use the provided localImageName
                echo "🏷️ Tagging image: ${localImageName} -> ${targetImageName}"
                sh "docker tag ${localImageName} ${targetImageName}"
                
                echo "📤 Pushing image to Harbor..."
                sh "docker push ${targetImageName}"

                if (removeAfterPush) {
                    echo "🧹 Removing local images..."
                    sh "docker rmi ${targetImageName} || true"
                    // Only remove the local image if it's different from the target
                    if (targetImageName != localImageName) {
                        sh "docker rmi ${localImageName} || true"
                    }
                }

                echo "🔓 Logging out from Harbor registry"
                sh "docker logout ${registryUrl}"
            }
        } else {
            // Original DockerHub logic
            withCredentials([usernamePassword(credentialsId: credentialsId, usernameVariable: 'REGISTRY_USER', passwordVariable: 'REGISTRY_PASS')]) {
                sh "echo \$REGISTRY_PASS | docker login -u \$REGISTRY_USER --password-stdin"
                
                echo "🏷️ Tagging image: ${localImageName} -> ${targetImageName}"
                sh "docker tag ${localImageName} ${targetImageName}"
                
                sh "docker push ${targetImageName}"

                if (removeAfterPush) {
                    echo "🧹 Removing local images..."
                    sh "docker rmi ${targetImageName} || true"
                    if (targetImageName != localImageName) {
                        sh "docker rmi ${localImageName} || true"
                    }
                }

                echo "🔓 Logging out from registry"
                sh "docker logout"
            }
        }

        env.BUILD_RESULT_PUSH_SUCCESS = 'true'
        env.FINAL_IMAGE_NAME = targetImageName

        return [
            success: true,
            imageName: targetImageName,
            registryType: registryType,
            pushed: true
        ]
    } catch (Exception e) {
        env.BUILD_RESULT_PUSH_SUCCESS = 'false'
        env.BUILD_RESULT_ERROR_TYPE = 'PUSH_TO_REGISTRY_ERROR'
        env.BUILD_RESULT_ERROR_MESSAGE = e.getMessage()
        env.BUILD_RESULT_MESSAGE = "Failed to push image to registry: ${e.getMessage()}"
        if (failOnError) {
            throw e
        } else {
            echo "⚠️ Push to registry failed but continuing due to failOnError=false: ${e.getMessage()}"
            return [
                success: false,
                error: e.getMessage(),
                imageName: targetImageName ?: "${imageName}:${imageTag}",
                registryType: registryType,
                pushed: false
            ]
        }
    }
}