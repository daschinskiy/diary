pipeline {
    agent any
    stages {
        stage('Checkout v2') {
            steps {
                git 'https://github.com/daschinskiy/diary.git'
                sh 'git checkout v2'
            }
        }
        stage('Build and Deploy') {
            steps {
                sh 'docker-compose down'
                sh 'docker-compose build'
                sh 'docker-compose up -d'
            }
        }
        stage('Notify') {
            steps {
                slackSend(channel: '#deploy', message: 'Version 2 deployed successfully')
            }
        }
    }
}
