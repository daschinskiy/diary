pipeline {
    agent any
    environment {
        DB_NAME = 'diary'
    }
    stages {
        stage('Clone repo') {
            steps {
                git branch: 'v2', url: 'https://github.com/daschinskiy/diary.git'
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
        stage('Build and Deploy') {
            steps {
                sh 'docker compose pull || true'
                sh 'docker compose build --no-cache'
                sh 'docker compose up -d --remove-orphans'
            }
        }

        stage('Notify') {
            steps {
                slackSend(channel: '#reports', message: 'Version 2 deployed successfully!')
            }
        }
    }
}
