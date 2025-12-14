// vars/sendStartNotification.groovy
def call(Map params = [:]) {
    try {
        def subject = params.subject ?: "🚀 Pipeline Started: ${env.JOB_NAME} #${env.BUILD_NUMBER}"
        def recipients = params.recipients ?: env.NOTIFICATION_EMAIL

        // Use all the rich Git data we captured
        def defaultData = [
                JOB_NAME           : env.JOB_NAME,
                BUILD_NUMBER       : env.BUILD_NUMBER,
                BUILD_URL          : env.BUILD_URL,
                BRANCH             : env.BRANCH_NAME,
                TRIGGERED_BY       : params.triggeredBy ?: 'Unknown',
                GIT_COMMIT_MESSAGE : env.GIT_COMMIT,
                GIT_AUTHOR         : env.GIT_AUTHOR,
                GIT_AUTHOR_USERNAME: env.GIT_AUTHOR_USERNAME,
                GIT_AUTHOR_EMAIL   : env.GIT_AUTHOR_EMAIL,
                GIT_COMMIT_HASH    : env.GIT_COMMIT_HASH,
                GIT_COMMIT_DATE    : env.GIT_COMMIT_DATE
        ]
        def data = defaultData + (params.data ?: [:])

        def body = getTemplate(data)

        if (recipients) {
            emailext(
                    subject: subject,
                    body: body,
                    to: recipients,
                    mimeType: 'text/html',
                    recipientProviders: [[$class: 'DevelopersRecipientProvider']]
            )
        }

        // Optional Slack
//        if (env.SLACK_CHANNEL) {
//            slackSend(
//                    channel: env.SLACK_CHANNEL,
//                    color: 'good',
//                    message: """${subject}
//Commit: ${data.GIT_COMMIT}
//Author: ${data.GIT_AUTHOR} (${data.GIT_AUTHOR_EMAIL})
//<${env.BUILD_URL}|View Build>"""
//            )
//        }

        echo "✅ Start notification sent"
    } catch (e) {
        echo "⚠️ sendStartNotification failed (continuing): ${e.message}"
    }
}

private String getTemplate(Map data) {
    return """
    <!DOCTYPE html>
    <html>
    <head>
        <style>
            body { font-family: 'Segoe UI', sans-serif; background: #f8f9fa; margin: 0; padding: 20px; }
            .container { max-width: 600px; margin: auto; background: white; border-radius: 12px; overflow: hidden; box-shadow: 0 4px 20px rgba(0,0,0,0.1); border: 3px solid #007bff; }
            .header { background: linear-gradient(135deg, #007bff, #0056b3); color: white; padding: 30px; text-align: center; }
            .content { padding: 30px; line-height: 1.6; color: #333; }
            .info { background: #e3f2fd; border-radius: 8px; padding: 20px; margin: 20px 0; }
            .button { display: inline-block; background: #007bff; color: white; padding: 12px 28px; text-decoration: none; border-radius: 30px; font-weight: bold; }
        </style>
    </head>
    <body>
        <div class="container">
            <div class="header">
                <h1>🚀 Pipeline Started</h1>
                <p>Jenkins Build #${data.BUILD_NUMBER}</p>
            </div>
            <div class="content">
                <p>Hello <strong>Team</strong>!</p>
                <p>A new build has been triggered for your commit:</p>
                
                <div class="info">
                    <p><strong>Commit Message:</strong></p>
                    <blockquote style="margin: 10px 0; padding: 10px 20px; background: #f0f8ff; border-left: 4px solid #007bff; font-style: italic;">
                    ${data.GIT_COMMIT_MESSAGE}
                    </blockquote>
                    <p><strong>Author:</strong> ${data.GIT_AUTHOR} (${data.GIT_AUTHOR_USERNAME})</p>
                    <p><strong>Email:</strong> <a href="mailto:${data.GIT_AUTHOR_EMAIL}">${data.GIT_AUTHOR_EMAIL}</a></p>
                    <p><strong>Branch:</strong> ${data.BRANCH}</p>
                    <p><strong>Triggered by:</strong> ${data.TRIGGERED_BY}</p>
                    <p><strong>Date:</strong> ${data.GIT_COMMIT_DATE}</p>
                </div>
                
                <p><a href="${data.BUILD_URL}" class="button">View Build in Jenkins</a></p>
            </div>
        </div>
    </body>
    </html>
    """
}