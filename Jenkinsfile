library identifier: 'jenkins-shared-library@main', retriever: modernSCM([
        $class: 'GitSCMSource',
        remote: 'https://github.com/ALabiyb/devsecops.git',
        traits: [[$class: 'jenkins.plugins.git.traits.BranchDiscoveryTrait']] // This ensures the library checks out the correct branch (main in my case)
])

pipeline {
    agent {
        label 'docker-server'
    }

    tools {
        jdk 'jdk21'
        maven 'maven-3.8.7'
    }

    environment {
        JAVA_HOME = tool 'jdk21'  // Extra safety

        // Project info
        PROJECT_NAME = 'Landlord Management API'
        IMAGE_NAME = 'landlord-management-api'
        HARBOR_PROJECT = 'munimdevops' // Your Harbor project name
        REGISTRY_URL = 'docker.io' // Your Docker registry URL
        REGISTRY_CREDENTIALS_ID = 'registry-credentials' // Jenkins credential ID for registry access

        //Notifications
        NOTIFICATION_EMAIL = 'hackermunim@gmail.com' // Comma-separated emails

        // Git info (automatically set by Jenkins)
        GIT_COMMIT = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
        GIT_AUTHOR = sh(script: 'git log -1 --pretty=format:"%an"', returnStdout: true).trim()
        BRANCH_NAME = 'main' // Branch to checkout
        GIT_REPO_URL = 'https://github.com/ALabiyb/Landlord-Managment-API.git'
        GIT_CREDENTIALS_ID = 'github-personal-access-token' // Jenkins credential ID for Git access

        //K8s repo config
        K8S_MANIFEST_REPO_URL = 'https://github.com/ALabiyb/landlord-management-k8s.git'
        K8S_MANIFEST_CREDENTIALS_ID = 'github-personal-access-token'
        K8S_MANIFEST_BRANCH = 'main'  // optional, defaults to main


        // Build info
        BUILD_DATE_UTC = sh(script: "date -u +'%Y-%m-%dT%H:%M:%SZ'", returnStdout: true).trim()
        APP_VERSION = "1.0.${env.BUILD_NUMBER}"
        BUILD_TOOL = 'maven' // Change to 'npm', 'go', etc. per repo or project

        // App config
        APP_TIMEZONE          = 'Africa/Dar_es_Salaam'
    }

    stages {
        stage('Checkout and Git Info') {
            steps {
                script {
                    checkoutAndGitInfo(repo: env.GIT_REPO_URL, credentialsId: env.GIT_CREDENTIALS_ID, branch: env.BRANCH_NAME) // Uses env.GIT_REPO_URL, env.GIT_CREDENTIALS_ID, env.BRANCH_NAME automatically
                }
            }
        }

        stage('Send Start Notification') {
            steps {
                script {
                    def triggeredBy = detectBuildTrigger()
                    sendStartNotification(
                            subject: "🚀 Pipeline Started: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                            recipients: env.NOTIFICATION_EMAIL,
                            triggeredBy: triggeredBy
                    )
                }
            }
        }

        stage('Build Artifact') {
            steps {
                script {
                    buildArtifact() // Uses env.BUILD_TOOL automatically // Or override: buildArtifact(buildTool: 'npm')
                    // It will auto-detect based on files (pom.xml → maven, package.json → npm, etc.)
                }
            }
        }

        stage('Unit Tests - JUnit and Jacoco') {
            steps {
                script {
                    echo "Running unit tests..."
                }
            }
        }

        stage('Mutation Tests - PIT') {
            steps {
                script {
                    echo "Running mutation tests..."
                }
            }
        }

        stage('SonarQube Analysis') {
            steps {
                script {
                    sonarSast(
                            sonarServer: 'SonarQube Server',
                            projectKey: 'landlord-management-api',
                            projectName: 'Landlord Management API',
                            waitForQualityGate: true,
                            timeoutMinutes: 5
                    )
                }
            }
        }

        stage('Vulnerability Scan - Docker') {
            steps {
                script {
                    vulnScanDocker() // Uses default commands; can override with params if needed
                }
            }
        }

        stage('Build Docker Image and Publish') {

            steps {
                script {
                    def result = buildDockerImageAndPush(
                            imageName: env.IMAGE_NAME,
                            imageTag: env.BUILD_NUMBER,
                            harborProject: env.HARBOR_PROJECT,
                            registryUrl: env.REGISTRY_URL,
                            registryCredentialsId: env.REGISTRY_CREDENTIALS_ID,
                            pushToRegistry: true,
                            buildArgs: [
                                    GIT_AUTHOR : env.GIT_AUTHOR,
                                    GIT_COMMIT : env.GIT_COMMIT,
                                    BUILD_DATE : new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC')),
                                    VERSION    : "1.0.${env.BUILD_NUMBER}",
                                    APP_TIMEZONE: "Africa/Dar_es_Salaam"   // ← Your desired TZ
                            ]
                    )
                    env.FINAL_IMAGE_NAME = result.localImageName // Save for later stages
                }
            }
        }

        stage('k8s Manifest Cloning & Update') {
            steps {
                script {
                    k8sManifestScanAndUpdate()
                }
            }
        }
    }
    post {
        always {
            dependencyCheckPublisher pattern: 'target/dependency-check-report.xml'
        }
        success {
            script {
                def triggeredBy = detectBuildTrigger()
                sendSuccessNotification(
                        recipients: env.NOTIFICATION_EMAIL,
                        triggeredBy: triggeredBy
                )
            }
        }
        failure {
            script {
                def triggeredBy = detectBuildTrigger()
                sendFailureNotification(
                        recipients: env.NOTIFICATION_EMAIL,
                        triggeredBy: triggeredBy
                )
            }
        }
    }
}