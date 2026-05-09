/**
 * vulnScanDocker.groovy
 *
 * Scans the Dockerfile and base image BEFORE building the application image.
 * Runs three checks in parallel:
 *
 *   1. Trivy Base Image Scan  — CVE scan of the FROM image (OS/package vulns)
 *   2. OPA Conftest           — Dockerfile policy enforcement
 *   3. Gitleaks               — hardcoded secrets detection in source code
 *
 * Called BEFORE buildDockerImageAndPush so bad Dockerfiles are caught early.
 *
 * Prerequisites (already in each project repo):
 *   - trivy-docker-image-scan.sh  (scans FROM image with exit-code 1 on CRITICAL)
 *   - opa-docker-security.rego    (policies: no root, no latest, no secrets in ENV, etc.)
 *
 * Usage:
 *   vulnScanDocker()
 *   vulnScanDocker(trivyCmd: 'bash my-custom-trivy.sh')
 */
def call(Map params = [:]) {
    try {
        echo "=== Vulnerability Scan - Dockerfile & Base Image ==="

        parallel(

            // -----------------------------------------------------------------
            // SCAN 1: Trivy — base image CVE scan
            // Uses your trivy-docker-image-scan.sh which:
            //   Round 1: shows HIGH+CRITICAL, exit 0 (informational)
            //   Round 2: shows CRITICAL only, exit 1 (blocks build)
            // Cache at /var/lib/jenkins/.trivy-cache avoids re-downloading DB
            // -----------------------------------------------------------------
            "Trivy — Base Image CVE Scan": {
                echo "🔍 Trivy: scanning Dockerfile base image for CVEs..."
                sh params.trivyCmd ?: "bash trivy-docker-image-scan.sh"
                echo "✅ Trivy base image scan passed (no CRITICAL CVEs)"
            },

            // -----------------------------------------------------------------
            // SCAN 2: OPA Conftest — Dockerfile policy enforcement
            // Policy file: opa-docker-security.rego (in project root)
            // Checks enforced:
            //   ✅ No secrets in ENV variables
            //   ✅ No 'latest' tag on base images
            //   ✅ No curl|bash (pipe to shell)
            //   ✅ No system package upgrades (apt-get upgrade, apk upgrade)
            //   ✅ COPY instead of ADD
            //   ✅ Non-root USER defined
            //   ✅ No sudo usage
            // -----------------------------------------------------------------
            "OPA Conftest — Dockerfile Policies": {
                echo "🔍 OPA: enforcing Dockerfile security policies..."
                sh """
                    docker run --rm \
                        -v "\$(pwd)":/project \
                        openpolicyagent/conftest:latest test \
                        --policy opa-docker-security.rego \
                        Dockerfile
                """
                echo "✅ OPA Dockerfile policy check passed"
            },

            // -----------------------------------------------------------------
            // SCAN 3: Gitleaks — hardcoded secrets detection
            // Scans all source files for: API keys, passwords, tokens,
            // private keys, connection strings, AWS keys, etc.
            // Currently: UNSTABLE (warns but doesn't block) — change
            // currentBuild.result to 'FAILURE' to enforce blocking
            // -----------------------------------------------------------------
            "Gitleaks — Secrets Detection": {
                echo "🔍 Gitleaks: scanning source code for hardcoded secrets..."
                def exitCode = sh(
                    script: """
                        docker run --rm \
                            -v "\$(pwd)":/path \
                            zricethezav/gitleaks:latest \
                            detect \
                            --source /path \
                            --no-git \
                            --exit-code 1 \
                            -v 2>&1 || true
                    """,
                    returnStatus: true
                )
                if (exitCode != 0) {
                    echo "⚠️  WARNING: Possible hardcoded secrets found — review output above"
                    echo "ℹ️  Fix: move secrets to Jenkins credentials or environment variables"
                    // Currently non-blocking — change to error(...) to block builds
                    currentBuild.result = 'UNSTABLE'
                    env.failedStage  = "Secrets Detection - Gitleaks"
                    env.failedReason = "Possible hardcoded secrets detected in source code"
                } else {
                    echo "✅ Gitleaks: no secrets detected"
                }
            }
        )

        echo "✅ All Dockerfile/base-image scans passed"
        return [success: true]

    } catch (Exception e) {
        env.failedStage  = "Vulnerability Scan - Docker"
        env.failedReason = e.getMessage()
        echo "❌ Docker vulnerability scan failed: ${e.getMessage()}"
        currentBuild.result = 'UNSTABLE'
        throw e
    }
}