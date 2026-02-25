def call() {
    def triggeredBy = "Unknown"
    def causes = currentBuild.getBuildCauses()
    if (causes && causes.size() > 0) {
        def cause = causes[0]
        if (cause.shortDescription && cause.shortDescription.contains("GitLab")) {
            triggeredBy = "GitLab Webhook"
        } else if (cause.userId) {
            triggeredBy = cause.userId
        } else if (cause.shortDescription && cause.shortDescription.toLowerCase().contains("scm change")) {
            triggeredBy = "SCM Change"
        } else {
            triggeredBy = cause.shortDescription ?: "Manual Trigger"
        }
    }
    return triggeredBy
}