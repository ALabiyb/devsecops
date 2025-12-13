/**
 * buildAppandDb.groovy
 * A Jenkins shared library function to build both the application and database components of a project.
 */

def call(Map params = [:]) {
    // Extract parameters with default values
    def projectName = params.projectName ?: env.JOB_NAME
    def imageName = params.imageName ?: env.JOB_NAME?.toLowerCase()?.replaceAll(/[^a-z0-9\-_.]/, '-')
    def imageTag = params.imageTag ?: env.BUILD_NUMBER ?: 'latest'
    def registryUrl = params.registryUrl ?: ''
    def registryCredentialsId = params.registryCredentialsId ?: ''
    def skipTests = params.skipTests ?: false
    def pushToRegistry = params.pushToRegistry ?: true
    def removeAfterPush = params.removeAfterPush ?: true
    def gitAuthor = params.gitAuthor ?: 'unknown'
    def gitCommitMessage = params.gitCommitMessage ?: 'No commit message'


    echo "=== Building Application and Database Docker Images ==="
    echo "Project Name: ${projectName}"
    echo "Image Name: ${imageName}"
    echo "Image Tag: ${imageTag}"
    echo "Registry URL: ${registryUrl}"
    echo "Push to Registry: ${pushToRegistry}"

    def composeFile = './docker-compose.yml'
    def composeFileExists = false

    try {
        stage('Validate Compose File') {
            // Check if docker-compose file exists
            if (fileExists('docker-compose.yml')) {
                composeFile = './docker-compose.yml'
                composeFileExists = true
                echo "✅ Found docker-compose.yml file."
            } else if (fileExists('docker-compose.yaml') ) {
                composeFile = './docker-compose.yaml'
                composeFileExists = true
                echo "✅ Found docker-compose.yaml file."
            }
            if (!composeFileExists) {
                error "❌ No docker-compose.yml or docker-compose.yaml file found in the workspace."
            }

            echo "Using compose file: ${composeFile}"

            // Validate the compose file syntax
            sh "docker-compose -f ${composeFile} config --quiet"
            echo "✅ Docker Compose file syntax is valid."
        }

        stage ('Build Images Service') {
            // Build all the services images using docker-compose

            // Set env variables for build args
            withEnv([
                "IMAGE_NAME=${imageName}",
                "IMAGE_TAG=${imageTag}",
                "REGISTRY_URL=${registryUrl}",
                "GIT_AUTHOR=${gitAuthor}",
                "GIT_COMMIT_MESSAGE=${gitCommitMessage}"
            ]) {
                sh """
                docker-compose -f ${composeFile} build --pull --build-arg IMAGE_NAME=\$IMAGE_NAME --build-arg IMAGE_TAG=\$IMAGE_TAG --build-arg REGISTRY_URL=\$REGISTRY_URL --build-arg GIT_AUTHOR="\$GIT_AUTHOR" --build-arg GIT_COMMIT_MESSAGE="\$GIT_COMMIT_MESSAGE
                """

            }
            echo "✅ Successfully built all service images."
        }

    } catch (Exception e) {
        echo "❌ App-and-DB build failed: ${e.getMessage()}"

        try {
            sh """
            echo "Cleaning up after failure..."
            docker-compose -f ${composeFile} down --rmi all --volumes --remove-orphans
            echo "Cleanup completed."
            """
        } catch (Exception cleanupError) {
            echo "❌ Cleanup after failure also failed: ${cleanupError.getMessage()}"
        }

        throw e
    }
}