/**
 * Common pipeline steps used by multiple Jenkinsfiles.
 * Accepts a map of parameters that can be overridden in the Jenkinsfile.
 */
def call(Map params = [:]) {
    // Extract parameters with defaults values if not provided
    def branch = params.get('branch', 'main')   // Default branch to check out
    def repoUrl = params.get('repoUrl', '')     // Repository URL to clone
    def credentialsId = params.get('credentialsId', '') // Credentials ID

    // Log the received parameters for debugging purposes
    echo "=== Running common steps ==="
    echo "Branch: ${branch}"
    echo "Repository URL: ${repoUrl}"

    // Step 1: Checkout the code from the specified branch
    checkoutCode(repoUrl, branch, credentialsId)

    // Step 2: Get Git commit information(author, message)
    def gitInfo = getGitInfo()
    echo "Git Author: ${gitInfo.author}"
    echo "Git Commit Message: ${gitInfo.message}"

    // Return the Git Ifo to be used by other steps if needed (e.g., for notifications)
    return gitInfo
}
/**
 * Checks out the code from the specified repository and branch.
 * Uses Jenkins 'built-in' checkout step instead of the raw shell command.
 */
def checkoutCode(String repoUrl, String branch, String credentialsId) {
    if (!repoUrl) {
        error "Repository URL must be provided for checkout."
    }
    if (!branch) {
        error "Branch must be specified for checkout."
    }

    echo "Checking out code from ${repoUrl} on branch ${branch} with credentials ID ${credentialsId}"
    def userRemoteConfigs = [
        url: repoUrl
    ]
    if (credentialsId) {
        userRemoteConfigs.credentialsId = credentialsId
    }

    checkout([
        $class: 'GitSCM',
        branches: [[name: "*/${branch}"]],
        userRemoteConfigs: [userRemoteConfigs],
        doGenerateSubmoduleConfigurations: false,
        extensions: [
            [$class: 'CleanBeforeCheckout'], // Clean workspace before checkout
            [$class: 'CloneOption', noTags: true, shallow: true] // Shallow clone for faster checkout
        ]
    ])
}

def getGitInfo() {
    // Get the latest commit information from the Git repository
    def commit = sh(script: 'git log -1 --pretty=format:"%B"', returnStdout: true).trim()
    // Get the author of the latest commit
    def author = sh(script: 'git log -1 --pretty=format:"%an"', returnStdout: true).trim()

    // Fallback to Jenkins environment variables if shell commands fail or are empty
    if (!commit) {
        commit = env.GIT_COMMIT_MESSAGE ?: "No commit message"
    }
    if (!author) {
        author = env.GIT_AUTHOR_NAME ?: "Unknown"
    }

    // Return a map with commit message and author
    return [message: commit, author: author]
}