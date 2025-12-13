// File: `vars/dockerBuildPush.groovy`
def call(Map params = [:]) {
    try {
        echo "Stage: Docker Build and Push"
        def imageFull = params.image ?: "${params.registry ?: env.PRIVATE_REGISTRY_URL}/${params.imageName ?: env.IMAGE_NAME}:${params.imageTag ?: env.IMAGE_TAG}"
        withDockerRegistry([credentialsId: params.credentialsId ?: env.REGISTRY_CREDENTIALS_ID, url: params.registryUrl ?: ""]) {
            sh params.buildCmd ?: "docker build -t ${imageFull} ${params.context ?: '.'}"
            sh params.pushCmd ?: "docker push ${imageFull}"
        }
        return [success: true, imageName: imageFull]
    } catch (e) {
        env.failedStage = "Docker Build and Push"
        echo "Docker build/push failed: ${e}"
        currentBuild.result = 'FAILURE'
        throw e
    }
}
