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
                            echo "Building new version..."
                            docker compose build web
                            
                            if [ "$FORCE_FAILURE" = "true" ]; then
                                echo "SIMULATING TEST FAILURE!"
                                exit 1
                            fi
                            
                            echo "Tests passed successfully"
                        '''
                    } catch (Exception e) {
                        currentBuild.result = 'FAILURE'
                        error("Tests failed: ${e.getMessage()}")
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
                    echo "Deploying new version..."
                    docker compose up -d --no-deps web
                '''
            }
        }
    }
    
    post {
        failure {
            script {
                slackSend(channel: '#reports', message: "🚨 Deployment failed! Performing rollback...")
                
                sh '''
                    echo "Starting rollback to v1..."
                    git checkout main
                    
                    docker stop diary-web || true
                    docker rm diary-web || true
                    
                    docker compose build web
                    if docker compose up -d web; then
                        echo "Rollback completed successfully"
                    else
                        echo "Failed to complete rollback!"
                        exit 1
                    fi
                '''
                
                // Проверяем, что контейнер запущен
                def isRunning = sh(script: 'docker inspect -f "{{.State.Running}}" diary-web', returnStatus: true) == 0
                if (isRunning) {
                    slackSend(channel: '#reports', message: "✅ Successfully rolled back to v1! Container is running.")
                } else {
                    slackSend(channel: '#reports', message: "❌ Rollback failed! Container is not running.")
                }
            }
        }
        success {
            slackSend(channel: '#reports', message: "✅ Version 2 deployed successfully!")
        }
    }
}
