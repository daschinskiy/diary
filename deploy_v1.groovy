pipeline {
    agent any
    stages {
        stage('Clone repo') {
            steps { git 'https://github.com/daschinskiy/diary.git' }
        }
        stage('Build and Deploy') {
            steps {
                sh 'docker-compose down'
                sh 'docker-compose build'
                sh 'docker-compose up -d'
            }
        }
        stage('Notify') {
            steps { slackSend(channel: '#reports', message: 'Version 1 deployed') }
        }
    }
}