pipeline {
    agent any
    stages {
        stage('Prepare') {
            steps {
                slackSend(channel: '#reports', message: 'Deploying version 2...')
                sh 'git checkout v2'
            }
        }
        stage('Build') {
            steps {
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    sh 'exit 1'
                }
            }
        }
        stage('Rollback') {
            when { failed() }
            steps {
                slackSend(channel: '#reports', message: 'Error detected. Rolling back...')
                sh 'git checkout main'
                sh 'docker compose build'
                sh 'docker compose up -d'
                slackSend(channel: '#reports', message: 'Rollback complete.')
            }
        }
    }
}