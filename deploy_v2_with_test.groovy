pipeline {
    agent any
    environment {
        DB_NAME = 'diary'
        FORCE_FAILURE = 'true'
    }
    stages {
        stage('Prepare') {
            steps {
                slackSend(channel: '#reports', message: 'Starting deployment with forced failure test...')
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
                        echo "DB_HOST=db" >> .env
                    '''
                }
            }
        }

        stage('Prepare Infrastructure') {
            steps {
                sh '''
                    docker network inspect diary_default >/dev/null 2>&1 || \
                    docker network create diary_default
                    
                    docker volume inspect diary_db_data >/dev/null 2>&1 || \
                    docker volume create diary_db_data
                '''
            }
        }

        stage('Build and Test') {
            steps {
                script {
                    try {
                        sh '''
                            docker stop diary-web-test || true
                            docker rm diary-web-test || true
                            docker compose build web
                            
                            echo "Running simulated tests..."
                            
                            if [ "$FORCE_FAILURE" = "true" ]; then
                                echo "SIMULATING TEST FAILURE!"
                                exit 1
                            fi
                        '''
                    } catch (Exception e) {
                        currentBuild.result = 'FAILURE'
                        error("Simulated tests failed as expected: ${e.getMessage()}")
                    }
                }
            }
        }

        stage('Deploy') {
            when {
                expression { currentBuild.result == null || currentBuild.result == 'SUCCESS' }
            }
            steps {
                sh '''
                    docker stop diary-web || true
                    docker rm diary-web || true
                    docker compose up -d --no-deps web
                '''
            }
        }
    }
    
    post {
        failure {
            script {
                slackSend(channel: '#reports', message: "🚨 Deployment failed! Performing rollback to v1. Build: ${BUILD_URL}")
                
                sh '''
                    git checkout main
                    docker stop diary-web || true
                    docker rm diary-web || true
                    docker compose build web
                    docker compose up -d web
                '''
                
                slackSend(channel: '#reports', message: "✅ Rollback to v1 completed successfully")
            }
        }
        success {
            slackSend(channel: '#reports', message: "✅ Version 2 deployed successfully! Build: ${BUILD_URL}")
        }
    }
}
