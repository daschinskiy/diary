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
                    sh 'exit 1'  // Симулируем ошибку сборки
                }
            }
        }

        stage('Rollback') {
            when { failed() }
            steps {
                slackSend(channel: '#reports', message: 'Error detected. Rolling back...')
                sh '''
                    docker rm -f diary-web || true
                    docker rm -f registry || true
                    docker rm -f diary-db || true

                    git checkout main
                    docker compose build
                    docker compose up -d --remove-orphans
                '''
                slackSend(channel: '#reports', message: 'Rollback complete.')
            }
        }
    }
}

