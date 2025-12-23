// vars/checkoutAndGitInfo.groovy
def call(Map params = [:]) {
    try {
        def repo = params.repo ?: env.GIT_REPO_URL
        def creds = params.credentialsId ?: env.GIT_CREDENTIALS_ID
        def branch = params.branch ?: env.BRANCH_NAME ?: 'main'
        def checkoutSubmodules = params.checkoutSubmodules ?: true  // New param, default true

        echo "Checking out repository: ${repo} @ branch: ${branch}"
        echo "Checkout submodules: ${checkoutSubmodules}"

        // Add submodule support if enabled
        if (checkoutSubmodules) {
            extensions << [$class: 'SubmoduleOption',
                           disableSubmodules: false,
                           parentCredentials: true,
                           recursiveSubmodules: true,
                           trackingSubmodules: true,
                           shallow: true]
        }

        checkout([
                $class: 'GitSCM',
                branches: [[name: "*/${branch}"]],
                userRemoteConfigs: [[url: repo, credentialsId: creds]],
                extensions: [[$class: 'CloneOption', shallow: false, depth: 1]]  // Ensure full history for log
        ])

        // Critical: Ensure we're in the right directory and git is working
        sh 'git status'  // Debug: confirm git repo is active

        // Debug: list submodule status
        sh 'git submodule status || echo "No submodules"'

        // Capture commit message safely into a separate variable (avoid overwriting Jenkins' GIT_COMMIT)
        def commitMessage = sh(script: "git show -s --format=%B HEAD | head -n 1", returnStdout: true)?.trim()
        if (!commitMessage) {
            commitMessage = 'No commit message available'
        }
        env.GIT_COMMIT_MESSAGE = commitMessage

        // Author info
        env.GIT_AUTHOR = sh(script: 'git log -1 --pretty=%an', returnStdout: true)?.trim() ?: 'Unknown author'
        env.GIT_AUTHOR_EMAIL = sh(script: 'git log -1 --pretty=%ae', returnStdout: true)?.trim() ?: 'unknown@example.com'
        env.GIT_AUTHOR_USERNAME = sh(script: 'git log -1 --pretty=%al', returnStdout: true)?.trim()
        if (!env.GIT_AUTHOR_USERNAME || env.GIT_AUTHOR_USERNAME.isEmpty()) {
            env.GIT_AUTHOR_USERNAME = env.GIT_AUTHOR_EMAIL.split('@')[0] ?: 'unknown'
        }

        // Hash and date
        env.GIT_COMMIT_HASH = sh(script: 'git rev-parse --short HEAD', returnStdout: true)?.trim() ?: 'unknown'
        env.GIT_COMMIT_DATE = sh(script: 'git log -1 --pretty=%cd --date=iso', returnStdout: true)?.trim() ?: 'unknown'

        echo "Git Info Captured:"
        echo "  Message: ${env.GIT_COMMIT_MESSAGE}"
        echo "  Author: ${env.GIT_AUTHOR} (${env.GIT_AUTHOR_USERNAME} <${env.GIT_AUTHOR_EMAIL}>)"
        echo "  Hash: ${env.GIT_COMMIT_HASH}"
        echo "  Date: ${env.GIT_COMMIT_DATE}"

        return [
                commitMessage: env.GIT_COMMIT_MESSAGE,
                author: env.GIT_AUTHOR,
                authorEmail: env.GIT_AUTHOR_EMAIL,
                authorUsername: env.GIT_AUTHOR_USERNAME,
                hash: env.GIT_COMMIT_HASH
        ]

    } catch (Exception e) {
        env.failedStage = params.stageName ?: "Checkout and Git Info"
        env.failedReason = e.getMessage()
        currentBuild.result = 'FAILURE'
        echo "Error during checkout or Git info retrieval: ${e.getMessage()}"
        throw e
    }
}