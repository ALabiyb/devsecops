/**
 * generateSbom.groovy
 *
 * Generates a Software Bill of Materials (SBOM) for the built Docker image
 * using Syft and uploads it to Dependency-Track for continuous CVE monitoring.
 *
 * STAGE ORDER (critical):
 *   Stage 8: buildDockerImageAndPush  → sets env.FINAL_IMAGE_NAME, keeps local image
 *   Stage 9: generateSbom             → scans local image, uploads to DT       ← THIS
 *   Stage 10: vulnScanApplicationImage → scans + REMOVES local image
 *
 * Dependency-Track auto-creates the project on first upload.
 * Subsequent builds update the same project — DT tracks CVE drift over time.
 *
 * Jenkins requirements:
 *   Credential: 'dependency-track-api-key' (Secret text — from DT Admin → Teams → Automation)
 *   Env var:    DEPENDENCY_TRACK_URL in Jenkinsfile environment block
 */
def call(Map params = [:]) {
    def dtUrl          = params.dtUrl          ?: env.DEPENDENCY_TRACK_URL ?: 'https://dependencytrack.devops.softnethq.co.tz'
    def imageName      = params.imageName      ?: env.FINAL_IMAGE_NAME
    def projectName    = params.projectName    ?: env.IMAGE_NAME    ?: env.JOB_NAME
    def projectVersion = params.projectVersion ?: env.APP_VERSION   ?: env.BUILD_NUMBER?.toString()
    def credId         = params.credentialsId  ?: 'dependency-track-api-key'
    def sbomFile       = 'sbom.json'

    try {
        echo "=== SBOM Generation (Syft → Dependency-Track) ==="
        echo "Image  : ${imageName}"
        echo "Project: ${projectName}   Version: ${projectVersion}"

        if (!imageName) {
            error "FINAL_IMAGE_NAME is not set — run buildDockerImageAndPush before generateSbom"
        }

        // Run Syft against the local Docker image, output CycloneDX JSON
        sh """
            docker run --rm \
              -v /var/run/docker.sock:/var/run/docker.sock \
              anchore/syft:latest \
              "${imageName}" \
              -o cyclonedx-json \
              > ${sbomFile}
        """

        if (!fileExists(sbomFile)) {
            error "Syft did not produce ${sbomFile}"
        }

        def sbomSize = sh(script: "wc -c < ${sbomFile}", returnStdout: true).trim()
        echo "SBOM size: ${sbomSize} bytes"

        // Upload SBOM to Dependency-Track
        withCredentials([string(credentialsId: credId, variable: 'DT_API_KEY')]) {
            def httpCode = sh(
                script: """
                    curl -s \
                      --connect-timeout 30 \
                      --max-time 120 \
                      -X POST \
                      -H "X-Api-Key: \${DT_API_KEY}" \
                      -F "projectName=${projectName}" \
                      -F "projectVersion=${projectVersion}" \
                      -F "autoCreate=true" \
                      -F "bom=@${sbomFile}" \
                      "${dtUrl}/api/v1/bom" \
                      -w "%{http_code}" -o /dev/null
                """,
                returnStdout: true
            ).trim()

            if (httpCode == '200') {
                echo "✅ SBOM uploaded to Dependency-Track — project '${projectName}' version '${projectVersion}'"
            } else {
                error "Dependency-Track rejected the upload (HTTP ${httpCode}) — check API key and project permissions"
            }
        }

    } catch (e) {
        // Non-blocking: SBOM failure marks build UNSTABLE but does not halt delivery
        echo "⚠️  SBOM stage failed: ${e.message}"
        echo "Pipeline continues — investigate Dependency-Track connectivity or API key"
        currentBuild.result = 'UNSTABLE'
    } finally {
        sh "rm -f ${sbomFile}"
    }
}
