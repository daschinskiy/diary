pipeline {
    agent any
    stages {
        stage('Prepare') {
            steps {
                slackSend(channel: '#deploy', message: 'Deploying version 2...')
                sh 'git checkout v2'
            }
        }
        stage('Build') {
            steps {
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    sh 'exit 1' // имитация ошибки
                }
            }
        }
        stage('Rollback') {
            when { failed() }
            steps {
                slackSend(channel: '#deploy', message: 'Error detected. Rolling back...')
                sh 'git checkout main'
                sh 'docker-compose build'
                sh 'docker-compose up -d'
                slackSend(channel: '#deploy', message: 'Rollback complete.')
            }
        }
    }
}