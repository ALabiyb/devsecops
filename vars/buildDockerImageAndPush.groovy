/**
 * buildDockerImageAndPush.groovy
 *
 * Builds a Docker image, pushes it to Harbor, then cleans up local images
 * so Jenkins disk stays clean (Harbor is the artifact store).
 *
 * Disk management:
 *   - removeAfterPush: true  → removes both local tag AND registry tag after push
 *   - builder prune          → caps Docker build cache at 2GB
 *
 * Usage:
 *   def result = buildDockerImageAndPush(
 *       imageName:              env.IMAGE_NAME,
 *       imageTag:               env.BUILD_NUMBER,
 *       harborProject:          env.HARBOR_PROJECT,
 *       registryUrl:            env.REGISTRY_URL,
 *       registryCredentialsId:  env.REGISTRY_CREDENTIALS_ID,
 *       pushToRegistry:         true,
 *       buildArgs: [
 *           GIT_AUTHOR : env.GIT_AUTHOR,
 *           VERSION    : "1.0.${env.BUILD_NUMBER}"
 *       ]
 *   )
 *   env.FINAL_IMAGE_NAME = result.localImageName  // used by vulnScanApplicationImage
 */
def call(Map params = [:]) {

    def projectName           = params.projectName           ?: env.JOB_NAME
    def imageName             = params.imageName             ?: env.JOB_NAME?.toLowerCase()?.replaceAll(/[^a-z0-9\-_.]/, '-')
    def imageTag              = params.imageTag              ?: env.BUILD_NUMBER ?: 'latest'
    def harborProject         = params.harborProject         ?: ''
    def registryUrl           = params.registryUrl           ?: ''
    def registryCredentialsId = params.registryCredentialsId ?: ''
    def dockerfilePath        = params.dockerfilePath        ?: 'Dockerfile'
    def buildContext          = params.buildContext          ?: '.'
    def buildArgs             = params.buildArgs             ?: [:]
    def dockerTarget          = params.dockerTarget          ?: ''   // multi-stage --target
    def pushToRegistry        = params.pushToRegistry        ?: false
    def removeAfterPush       = params.removeAfterPush       ?: true  // keep disk clean
    def failOnError           = params.containsKey('failOnError') ? params.failOnError : true

    def localImageName    = "${imageName}:${imageTag}"
    def registryImageName = registryUrl ? "${registryUrl}/${harborProject}/${imageName}:${imageTag}" : localImageName

    echo "=== Build Docker Image and Push ==="
    echo "Image:          ${localImageName}"
    echo "Registry image: ${registryImageName}"
    echo "Push to Harbor: ${pushToRegistry}"

    try {
        if (!fileExists(dockerfilePath)) {
            error "Dockerfile not found: ${dockerfilePath}"
        }

        // Build --build-arg string from map
        def buildArgsString = buildArgs.collect { k, v -> "--build-arg ${k}='${v}'" }.join(' ')
        def targetFlag      = dockerTarget ? "--target ${dockerTarget}" : ''

        echo "🔨 Building Docker image..."
        sh """
            docker build -t ${localImageName} \
                -f ${dockerfilePath} \
                ${targetFlag} \
                ${buildArgsString} \
                ${buildContext}
        """
        echo "✅ Built: ${localImageName}"

        def result = [success: true, imageName: registryImageName, localImageName: localImageName, pushed: false]

        if (pushToRegistry) {
            if (!registryUrl)           error "registryUrl is required to push"
            if (!registryCredentialsId) error "registryCredentialsId is required to push"

            echo "🚀 Pushing to Harbor registry..."
            withCredentials([usernamePassword(
                credentialsId: registryCredentialsId,
                usernameVariable: 'REGISTRY_USER',
                passwordVariable: 'REGISTRY_PASS'
            )]) {
                sh "echo \$REGISTRY_PASS | docker login ${registryUrl} -u \$REGISTRY_USER --password-stdin"
                sh "docker tag ${localImageName} ${registryImageName}"
                sh "docker push ${registryImageName}"
            }
            echo "✅ Pushed: ${registryImageName}"
            result.pushed = true
        }

        // ─────────────────────────────────────────────────────────────────────
        // DISK CLEANUP — critical for keeping Jenkins disk healthy
        // Removes both the local tag and registry tag from Jenkins host
        // Caps Docker build cache at 2GB to prevent gradual accumulation
        // Without this: each build leaves 100-500MB of layers on disk
        // ─────────────────────────────────────────────────────────────────────
        if (removeAfterPush && pushToRegistry) {
            echo "🧹 Removing local Docker images (Harbor has the image)..."
            sh "docker rmi ${registryImageName} || true"   // remove registry-tagged copy
            sh "docker rmi ${localImageName} || true"      // remove local-tagged copy
            sh "docker builder prune -f --keep-storage=2gb || true"  // cap build cache at 2GB
            echo "✅ Local images removed — disk kept clean"
        }

        echo "✅ Docker build and push complete"
        return result

    } catch (Exception e) {
        env.failedStage  = "Build Docker Image and Push"
        env.failedReason = e.getMessage()
        echo "❌ Docker build/push failed: ${e.getMessage()}"
        if (failOnError) {
            currentBuild.result = 'FAILURE'
            throw e
        } else {
            currentBuild.result = 'UNSTABLE'
            return [success: false, error: e.getMessage()]
        }
    }
}