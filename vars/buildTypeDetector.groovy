/**
 * Enhanced build type detection utility
 * Provides comprehensive build type detection with multiple strategies
 */
def call(Map params = [:]) {
    def commitMessage = params.get('commitMessage', env.GIT_COMMIT_MESSAGE ?: '')
    def branch = params.get('branch', env.BRANCH_NAME ?: 'main')
    def override = params.get('override', '')
    def enableFileDetection = params.get('enableFileDetection', true)
    def enableCommitDetection = params.get('enableCommitDetection', true)
    def enableBranchRules = params.get('enableBranchRules', true)

    echo "=== Build Type Detection Starting ==="
    echo "Commit Message: ${commitMessage}"
    echo "Branch: ${branch}"
    echo "Override: ${override}"

    def detectionResult = [
            buildType: 'app-only',
            confidence: 'low',
            method: 'default',
            reasons: [],
            warnings: []
    ]

    try {
        // 1. Check for manual override first
        if (override && override != 'auto-detect') {
            detectionResult.buildType = override
            detectionResult.confidence = 'high'
            detectionResult.method = 'manual-override'
            detectionResult.reasons.add("Manual override specified: ${override}")
            return detectionResult
        }

        // 2. Commit message detection
        if (enableCommitDetection) {
            def commitResult = detectFromCommitMessage(commitMessage)
            if (commitResult.detected) {
                detectionResult.buildType = commitResult.buildType
                detectionResult.confidence = commitResult.confidence
                detectionResult.method = 'commit-message'
                detectionResult.reasons.addAll(commitResult.reasons)

                // If we have high confidence from commit, use it
                if (commitResult.confidence == 'high') {
                    return detectionResult
                }
            }
        }

        // 3. File structure detection
        if (enableFileDetection) {
            def fileResult = detectFromFileStructure()
            if (fileResult.detected) {
                // If commit detection was uncertain, use file detection
                if (detectionResult.confidence == 'low') {
                    detectionResult.buildType = fileResult.buildType
                    detectionResult.method = 'file-structure'
                }
                detectionResult.confidence = 'medium'
                detectionResult.reasons.addAll(fileResult.reasons)
                detectionResult.warnings.addAll(fileResult.warnings)
            }
        }

        // 4. Branch-based rules
        if (enableBranchRules) {
            def branchResult = detectFromBranchRules(branch)
            if (branchResult.detected) {
                detectionResult.reasons.addAll(branchResult.reasons)
                if (branchResult.override) {
                    detectionResult.buildType = branchResult.buildType
                    detectionResult.method = 'branch-rules'
                    detectionResult.confidence = 'medium'
                }
            }
        }

        // 5. Enhanced analysis
        def enhancedResult = performEnhancedAnalysis(commitMessage, detectionResult.buildType)
        detectionResult.reasons.addAll(enhancedResult.reasons)
        detectionResult.warnings.addAll(enhancedResult.warnings)

        // Final confidence adjustment
        detectionResult.confidence = calculateFinalConfidence(detectionResult)

        echo "✅ Detection completed: ${detectionResult.buildType} (${detectionResult.confidence} confidence)"
        echo "Method: ${detectionResult.method}"
        echo "Reasons: ${detectionResult.reasons.join(', ')}"

        if (detectionResult.warnings) {
            echo "⚠️  Warnings: ${detectionResult.warnings.join(', ')}"
        }

        return detectionResult

    } catch (Exception e) {
        echo "❌ Detection failed: ${e.getMessage()}"
        return [
                buildType: 'app-only',
                confidence: 'low',
                method: 'error-fallback',
                reasons: ["Detection failed: ${e.getMessage()}"],
                warnings: ['Using fallback app-only build type']
        ]
    }
}

/**
 * Detect build type from commit message
 */
def detectFromCommitMessage(String commitMessage) {
    if (!commitMessage) {
        return [detected: false]
    }

    def message = commitMessage.toLowerCase()
    def reasons = []

    // High confidence indicators (explicit tags)
    if (message.contains('[app-only]') || message.contains('#app-only')) {
        return [
                detected: true,
                buildType: 'app-only',
                confidence: 'high',
                reasons: ['Explicit [app-only] tag found in commit message']
        ]
    }

    if (message.contains('[app-and-db]') || message.contains('#app-and-db') ||
            message.contains('[with-db]') || message.contains('#with-db')) {
        return [
                detected: true,
                buildType: 'app-and-db',
                confidence: 'high',
                reasons: ['Explicit [app-and-db] tag found in commit message']
        ]
    }

    // Medium confidence indicators (keywords)
    def dbKeywords = [
            'database', 'migration', 'schema', 'db-', 'sql', 'table',
            'postgres', 'postgresql', 'mysql', 'mongodb', 'mongo', 'redis',
            'prisma', 'sequelize', 'typeorm', 'knex', 'liquibase', 'flyway'
    ]

    def appOnlyKeywords = [
            'frontend', 'ui-', 'styling', 'css', 'hotfix', 'bugfix',
            'docs', 'readme', 'lint', 'format', 'refactor-ui', 'component'
    ]

    def dbMatches = dbKeywords.findAll { keyword -> message.contains(keyword) }
    def appOnlyMatches = appOnlyKeywords.findAll { keyword -> message.contains(keyword) }

    if (dbMatches && !appOnlyMatches) {
        reasons.add("Database-related keywords found: ${dbMatches.join(', ')}")
        return [
                detected: true,
                buildType: 'app-and-db',
                confidence: 'medium',
                reasons: reasons
        ]
    }

    if (appOnlyMatches && !dbMatches) {
        reasons.add("App-only keywords found: ${appOnlyMatches.join(', ')}")
        return [
                detected: true,
                buildType: 'app-only',
                confidence: 'medium',
                reasons: reasons
        ]
    }

    // Low confidence indicators (file patterns in commit)
    if (message.contains('.sql') || message.contains('migration') || message.contains('seed')) {
        reasons.add("Database file patterns detected in commit message")
        return [
                detected: true,
                buildType: 'app-and-db',
                confidence: 'low',
                reasons: reasons
        ]
    }

    return [detected: false]
}

/**
 * Detect build type from file structure
 */
def detectFromFileStructure() {
    def reasons = []
    def warnings = []

    try {
        // Check for docker-compose files
        def composeFiles = ['docker-compose.yml', 'docker-compose.yaml', 'compose.yml', 'compose.yaml']
        def foundComposeFile = null

        composeFiles.each { file ->
            if (!foundComposeFile && sh(script: "test -f '${file}'", returnStatus: true) == 0) {
                foundComposeFile = file
            }
        }

        if (foundComposeFile) {
            reasons.add("Docker Compose file found: ${foundComposeFile}")

            // Analyze compose file content
            def composeContent = readFile(foundComposeFile).toLowerCase()

            // Database service indicators
            def dbServices = [
                    'postgres', 'postgresql', 'mysql', 'mariadb', 'mongodb', 'mongo',
                    'redis', 'elasticsearch', 'cassandra', 'dynamodb', 'oracle'
            ]

            def foundDbServices = dbServices.findAll { service ->
                composeContent.contains("image:") && composeContent.contains(service) ||
                        composeContent.contains("${service}:") ||
                        composeContent.contains("- ${service}")
            }

            if (foundDbServices) {
                reasons.add("Database services detected: ${foundDbServices.join(', ')}")
                return [
                        detected: true,
                        buildType: 'app-and-db',
                        reasons: reasons,
                        warnings: warnings
                ]
            } else {
                warnings.add("Docker Compose found but no database services detected")
                reasons.add("Multi-service setup without databases")
            }
        }

        // Check for standalone Dockerfile
        def dockerfileExists = sh(script: 'test -f Dockerfile', returnStatus: true) == 0
        if (dockerfileExists) {
            reasons.add("Dockerfile found for single service build")

            // Analyze Dockerfile for database connections
            try {
                def dockerfileContent = readFile('Dockerfile').toLowerCase()
                def dbConnections = ['pg_', 'mysql', 'mongodb', 'redis', 'database_url']
                def foundConnections = dbConnections.findAll { conn -> dockerfileContent.contains(conn) }

                if (foundConnections) {
                    warnings.add("Database connections found in Dockerfile but no compose file")
                    reasons.add("Database connection indicators: ${foundConnections.join(', ')}")
                }
            } catch (Exception e) {
                warnings.add("Could not analyze Dockerfile content")
            }

            return [
                    detected: true,
                    buildType: 'app-only',
                    reasons: reasons,
                    warnings: warnings
            ]
        }

        warnings.add("No Docker configuration files found")
        return [
                detected: false,
                reasons: reasons,
                warnings: warnings
        ]

    } catch (Exception e) {
        warnings.add("File structure analysis failed: ${e.getMessage()}")
        return [
                detected: false,
                reasons: reasons,
                warnings: warnings
        ]
    }
}

/**
 * Apply branch-based rules
 */
def detectFromBranchRules(String branch) {
    def reasons = []
    def rules = [
            // Production branches might need different handling
            'main': [defaultType: 'app-and-db', requireConfirmation: true],
            'master': [defaultType: 'app-and-db', requireConfirmation: true],
            'production': [defaultType: 'app-and-db', requireConfirmation: true],

            // Development branches
            'develop': [defaultType: 'auto', requireConfirmation: false],
            'dev': [defaultType: 'auto', requireConfirmation: false],

            // Feature branches
            'feature/': [defaultType: 'auto', requireConfirmation: false],
            'hotfix/': [defaultType: 'app-only', requireConfirmation: false],
            'bugfix/': [defaultType: 'app-only', requireConfirmation: false]
    ]

    // Find matching rule
    def matchedRule = null
    def matchedPattern = null

    rules.each { pattern, rule ->
        if (matchedRule == null) {
            if (pattern.endsWith('/')) {
                // Prefix match
                if (branch.startsWith(pattern)) {
                    matchedRule = rule
                    matchedPattern = pattern
                }
            } else {
                // Exact match
                if (branch == pattern) {
                    matchedRule = rule
                    matchedPattern = pattern
                }
            }
        }
    }

    if (matchedRule) {
        reasons.add("Branch rule applied for pattern: ${matchedPattern}")

        if (matchedRule.requireConfirmation) {
            reasons.add("Manual confirmation required for ${matchedPattern} branch")
        }

        if (matchedRule.defaultType != 'auto') {
            return [
                    detected: true,
                    buildType: matchedRule.defaultType,
                    override: true,
                    reasons: reasons,
                    requireConfirmation: matchedRule.requireConfirmation
            ]
        }
    }

    return [
            detected: false,
            reasons: reasons
    ]
}

/**
 * Perform enhanced analysis
 */
def performEnhancedAnalysis(String commitMessage, String currentBuildType) {
    def reasons = []
    def warnings = []

    try {
        // Check for recent changes that might affect build type
        def recentFiles = sh(script: 'git diff --name-only HEAD~1 HEAD 2>/dev/null || echo ""', returnStdout: true).trim()

        if (recentFiles) {
            def changedFiles = recentFiles.split('\n')

            // Database-related file changes
            def dbFilePatterns = ['.sql', 'migration', 'schema', 'seed', 'prisma', 'sequelize']
            def dbFiles = changedFiles.findAll { file ->
                dbFilePatterns.any { pattern -> file.toLowerCase().contains(pattern) }
            }

            // Configuration file changes
            def configFilePatterns = ['docker-compose', 'dockerfile', '.env', 'config']
            def configFiles = changedFiles.findAll { file ->
                configFilePatterns.any { pattern -> file.toLowerCase().contains(pattern) }
            }

            // Frontend-only file changes
            def frontendFilePatterns = ['.css', '.scss', '.js', '.ts', '.html', '.vue', '.jsx', '.tsx']
            def frontendFiles = changedFiles.findAll { file ->
                frontendFilePatterns.any { pattern -> file.toLowerCase().endsWith(pattern) }
            }

            if (dbFiles) {
                reasons.add("Database files modified: ${dbFiles.take(3).join(', ')}${dbFiles.size() > 3 ? '...' : ''}")
                if (currentBuildType == 'app-only') {
                    warnings.add("Database files changed but app-only build selected")
                }
            }

            if (configFiles) {
                reasons.add("Configuration files modified: ${configFiles.take(3).join(', ')}${configFiles.size() > 3 ? '...' : ''}")
            }

            if (frontendFiles && frontendFiles.size() == changedFiles.size()) {
                reasons.add("Only frontend files modified (${frontendFiles.size()} files)")
                if (currentBuildType == 'app-and-db') {
                    warnings.add("Only frontend changes but app-and-db build selected")
                }
            }
        }

        // Check for environment-specific indicators
        def envFiles = ['docker-compose.prod.yml', 'docker-compose.staging.yml', '.env.production']
        envFiles.each { file ->
            if (sh(script: "test -f '${file}'", returnStatus: true) == 0) {
                reasons.add("Environment-specific configuration found: ${file}")
            }
        }

    } catch (Exception e) {
        warnings.add("Enhanced analysis failed: ${e.getMessage()}")
    }

    return [
            reasons: reasons,
            warnings: warnings
    ]
}

/**
 * Calculate final confidence level
 */
def calculateFinalConfidence(Map detectionResult) {
    def method = detectionResult.method
    def reasons = detectionResult.reasons
    def warnings = detectionResult.warnings

    // Start with method-based confidence
    def confidence = 'low'

    switch (method) {
        case 'manual-override':
            confidence = 'high'
            break
        case 'commit-message':
            confidence = 'high'
            break
        case 'file-structure':
            confidence = 'medium'
            break
        case 'branch-rules':
            confidence = 'medium'
            break
    }

    // Adjust based on evidence
    if (reasons.size() >= 3) {
        confidence = confidence == 'low' ? 'medium' : confidence
    }

    if (reasons.size() >= 5) {
        confidence = confidence == 'medium' ? 'high' : confidence
    }

    // Reduce confidence if there are warnings
    if (warnings.size() >= 2) {
        confidence = confidence == 'high' ? 'medium' : confidence
    }

    return confidence
}

/**
 * Get detection summary for logging/notifications
 */
def getDetectionSummary(Map detectionResult) {
    return [
            buildType: detectionResult.buildType,
            confidence: detectionResult.confidence,
            method: detectionResult.method,
            summary: "Build type '${detectionResult.buildType}' detected via '${detectionResult.method}' with '${detectionResult.confidence}' confidence",
            reasons: detectionResult.reasons.take(3).join('; ') + (detectionResult.reasons.size() > 3 ? '...' : ''),
            hasWarnings: detectionResult.warnings.size() > 0
    ]
}