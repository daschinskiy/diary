pipeline {
    agent any
    environment {
        DB_NAME = 'diary'
        FORCE_FAILURE = 'true'
        MAIN_BRANCH = 'main'
        V2_BRANCH = 'v2'
    }
    stages {
        stage('Prepare') {
            steps {
                slackSend(channel: '#reports', message: 'Starting deployment with rollback test...')
                git branch: V2_BRANCH, url: 'https://github.com/daschinskiy/diary.git'
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
                            echo "Building version 2..."
                            docker compose -f docker-compose.v2.yml build web
                            
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
                    echo "Deploying version 2..."
                    docker compose -f docker-compose.v2.yml up -d --no-deps web
                '''
            }
        }
    }
    
    post {
        failure {
            script {
                slackSend(channel: '#reports', message: "🚨 Deployment failed! Performing rollback to v1...")
                
                dir('rollback') {
                    deleteDir()
                    git branch: MAIN_BRANCH, url: 'https://github.com/daschinskiy/diary.git'
                    
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
                    
                    sh '''
                        echo "Stopping current containers..."
                        docker stop diary-web diary-db registry || true
                        docker rm diary-web diary-db registry || true
                        
                        echo "Deploying version 1..."
                        docker compose up -d
                        
                        echo "Checking containers..."
                        sleep 5
                        docker ps
                    '''
                }
                
                def webRunning = sh(script: 'docker inspect -f "{{.State.Running}}" diary-web', returnStdout: true).trim() == 'true'
                def dbRunning = sh(script: 'docker inspect -f "{{.State.Running}}" diary-db', returnStdout: true).trim() == 'true'
                
                if (webRunning && dbRunning) {
                    slackSend(channel: '#reports', message: "✅ Successfully rolled back to v1! All containers are running.")
                } else {
                    slackSend(channel: '#reports', message: "❌ Critical: Rollback failed! Containers status - Web: $webRunning, DB: $dbRunning")
                }
            }
        }
        success {
            slackSend(channel: '#reports', message: "✅ Version 2 deployed successfully!")
        }
    }
}
