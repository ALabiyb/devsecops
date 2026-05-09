/**
 * buildDockerImageAndPush.groovy
 *
 * Builds a Docker image, pushes it to Harbor, keeps local image for scanning.
 *
 * IMPORTANT — image cleanup flow:
 *   Stage 8: buildDockerImageAndPush  → builds + pushes → KEEPS local image
 *   Stage 9: vulnScanApplicationImage → scans local image → REMOVES it after scan
 *
 *   removeAfterPush defaults to FALSE so the image exists for stage 9 to scan.
 *   vulnScanApplicationImage does the cleanup after scanning completes.
 *   Never scan the Harbor registry URL — use the local image tag for Trivy.
 *
 * Usage:
 *   def result = buildDockerImageAndPush(
 *       imageName:              env.IMAGE_NAME,
 *       imageTag:               env.BUILD_NUMBER,
 *       harborProject:          env.HARBOR_PROJECT,
 *       registryUrl:            env.REGISTRY_URL,
 *       registryCredentialsId:  env.REGISTRY_CREDENTIALS_ID,
 *       pushToRegistry:         true
 *   )
 *   env.FINAL_IMAGE_NAME = result.localImageName  // e.g. "soft-aml-branding-service:42"
 *                                                 // used by vulnScanApplicationImage
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
    def dockerTarget          = params.dockerTarget          ?: ''
    def pushToRegistry        = params.pushToRegistry        ?: false

    // -------------------------------------------------------------------------
    // removeAfterPush defaults to FALSE
    // The local image must stay alive so vulnScanApplicationImage (stage 9)
    // can scan it with Trivy. vulnScanApplicationImage removes it after scanning.
    // Only set removeAfterPush: true if you are NOT using vulnScanApplicationImage.
    // -------------------------------------------------------------------------
    def removeAfterPush = params.containsKey('removeAfterPush') ? params.removeAfterPush : false
    def failOnError     = params.containsKey('failOnError')     ? params.failOnError     : true

    // localImageName  = short tag used locally and for Trivy scanning
    // registryImageName = full Harbor URL used for push
    def localImageName    = "${imageName}:${imageTag}"
    def registryImageName = registryUrl ? "${registryUrl}/${harborProject}/${imageName}:${imageTag}" : localImageName

    echo "=== Build Docker Image and Push ==="
    echo "Local image:    ${localImageName}"
    echo "Registry image: ${registryImageName}"
    echo "Push to Harbor: ${pushToRegistry}"
    echo "Remove after push: ${removeAfterPush} (false = keep for Trivy scan in next stage)"

    try {
        if (!fileExists(dockerfilePath)) {
            error "Dockerfile not found: ${dockerfilePath}"
        }

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

        def result = [
            success        : true,
            imageName      : registryImageName,
            localImageName : localImageName,   // ← Trivy uses this (local, not Harbor URL)
            pushed         : false
        ]

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
                // Remove registry-tagged copy immediately — we only need localImageName for scanning
                sh "docker rmi ${registryImageName} || true"
            }
            echo "✅ Pushed and registry tag removed: ${registryImageName}"
            result.pushed = true
        }

        // Local image (localImageName) is KEPT here intentionally
        // vulnScanApplicationImage will scan it then remove it
        if (removeAfterPush) {
            // Only reached if caller explicitly sets removeAfterPush: true
            // This means they are NOT using vulnScanApplicationImage
            echo "🧹 Removing local image (removeAfterPush=true)..."
            sh "docker rmi ${localImageName} || true"
            sh "docker builder prune -f --reserved-space=2gb 2>/dev/null || docker builder prune -f --keep-storage=2gb 2>/dev/null || true"
            echo "✅ Local image removed"
        } else {
            echo "ℹ️  Local image kept for Trivy scan: ${localImageName}"
            echo "ℹ️  vulnScanApplicationImage will remove it after scanning"
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