pipeline {
    agent any
    environment {
        DB_NAME = 'diary'
    }
    stages {
        stage('Prepare') {
            steps {
                slackSend(channel: '#reports', message: '🚀 Deploying version 2...')
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
        stage('Test build') {
            steps {
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    sh 'exit 1'  // Заменить на настоящие тесты позже
                }
            }
        }
        stage('Deploy') {
            when { expression { currentBuild.currentResult == 'SUCCESS' } }
            steps {
                sh '''
                    docker compose pull || true
                    docker compose build web
                    docker compose stop web registry
                    docker compose rm -f web registry
                    docker compose up -d web registry
                '''
                slackSend(channel: '#reports', message: '✅ Version 2 deployed successfully!')
            }
        }
        stage('Rollback') {
            when { failed() }
            steps {
                slackSend(channel: '#reports', message: '❌ Build failed. Rolling back to main...')
                sh '''
                    docker compose stop web registry
                    docker compose rm -f web registry
                    git checkout main
                    docker compose build web
                    docker compose up -d web registry
                '''
                slackSend(channel: '#reports', message: '🔁 Rollback to main complete.')
            }
        }
    }
}

