#!/usr/bin/env groovy
// vars/sshRemoteDeploy.groovy
// ─────────────────────────────────────────────────────────────────────────────
//  Copies project files to a remote server over SSH and restarts
//  docker compose. Works for both normal deploy (dev mode) and
//  OpenUpgrade (upgrade mode).
//
//  Required Jenkins plugin: SSH Agent Plugin
//  (Manage Jenkins → Plugins → search "SSH Agent")
//
//  Usage:
//    sshRemoteDeploy(
//        sshCredentialsId : 'odoo-server-ssh-key',
//        remoteUser       : 'administrator',
//        remoteHost       : '192.168.2.157',
//        remoteProjectPath: '/opt/odoo-staging',
//        odooPort         : '8069',
//        odooMode         : 'dev',                  // 'dev' or 'upgrade'
//        odooImage        : 'harbor.example.com/stl/stl-odoo:42',
//        registryHost     : 'harbor.devops.softnethq.co.tz',
//        registryCredentialsId: 'harbor-credentials'
//    )
// ─────────────────────────────────────────────────────────────────────────────

def call(Map config = [:]) {

    // ── Validate required params ───────────────────────────────────────────────
    ['sshCredentialsId','remoteUser','remoteHost',
     'remoteProjectPath','odooPort','odooMode',
     'odooImage','registryHost','registryCredentialsId'].each { key ->
        if (!config[key]) error("sshRemoteDeploy: missing required param '${key}'")
    }

    def remote = config.remoteUser + '@' + config.remoteHost
    def path   = config.remoteProjectPath

    sshagent(credentials: [config.sshCredentialsId]) {

        // 1. Ensure remote project directory exists
        sh "ssh -o StrictHostKeyChecking=no ${remote} 'mkdir -p ${path}/config ${path}/addons'"

        // 2. Copy project files from workspace to remote server
        sh """
            rsync -az --delete \
                -e 'ssh -o StrictHostKeyChecking=no' \
                docker-compose.yml \
                ${remote}:${path}/docker-compose.yml

            rsync -az --delete \
                -e 'ssh -o StrictHostKeyChecking=no' \
                config/ \
                ${remote}:${path}/config/

            rsync -az --delete \
                -e 'ssh -o StrictHostKeyChecking=no' \
                addons/ \
                ${remote}:${path}/addons/
        """

        // 3. Write .env on the remote server
        sh """
            ssh -o StrictHostKeyChecking=no ${remote} \
            'cat > ${path}/.env << EOF
ODOO_PORT=${config.odooPort}
ODOO_MODE=${config.odooMode}
ODOO_IMAGE=${config.odooImage}
EOF'
        """

        // 4. Log in to Harbor from the remote server and pull the new image
        withCredentials([usernamePassword(
        credentialsId: config.registryCredentialsId,
        usernameVariable: 'REG_USER',
        passwordVariable: 'REG_PASS')]) {
    sh """
        ssh -o StrictHostKeyChecking=no ${remote} << EOF
echo '${REG_PASS}' | docker login ${config.registryHost} \
    -u '${REG_USER}' --password-stdin
cd ${path}
docker compose pull
docker compose down
docker compose up -d
EOF
    """
}

        echo "✓ Remote deploy complete → ${remote}:${path} (mode: ${config.odooMode}, port: ${config.odooPort})"
    }
}