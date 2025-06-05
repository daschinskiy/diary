pipeline {
    agent any
    environment {
        DB_NAME = 'diary'
    }
    stages {
        stage('Prepare') {
            steps {
                slackSend(channel: '#reports', message: 'Deploying version 2...')
                sh 'git checkout v2'
            }
        }
        stage('Create .env') {
            steps {
                withCredentials([
                    usernamePassword(credentialsId: 'MYSQL_CREDS', usernameVariable: 'DB_USER', passwordVariable: 'DB_PASSWORD'),
                    string(credentialsId: 'MYSQL_ROOT_PASS', variable: 'DB_ROOT_PASSWORD')
                ]) {
                    sh '''
                        echo "DB_NAME=$DB_NAME" > .env
                        echo "DB_USER=$DB_USER" >> .env
                        echo "DB_PASSWORD=$DB_PASSWORD" >> .env
                        echo "DB_ROOT_PASSWORD=$DB_ROOT_PASSWORD" >> .env
                    '''
                }
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
