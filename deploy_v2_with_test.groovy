pipeline {
    agent any
    environment {
        DB_NAME = 'diary'
        FORCE_FAILURE = 'true'
    }
    stages {
        stage('Prepare') {
            steps {
                slackSend(channel: '#reports', message: 'Starting deployment with rollback test...')
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

        stage('Build and Test') {
            steps {
                script {
                    try {
                        sh '''
                            echo "Building version 2..."
                            docker compose build web
                            
                            if [ "$FORCE_FAILURE" = "true" ]; then
                                echo "SIMULATING TEST FAILURE!"
                                exit 1
                            fi
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
                    docker compose up -d --no-deps web
                '''
            }
        }
    }
    
    post {
        failure {
            script {
                slackSend(channel: '#reports', message: "🚨 Deployment failed! Performing rollback to v1...")
                
                sh '''
                    docker stop diary-web diary-db registry || true
                    docker rm diary-web diary-db registry || true
                '''
                
                dir('rollback') {
                    deleteDir()
                    git branch: 'main', url: 'https://github.com/daschinskiy/diary.git'
                    
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
                        echo "Deploying version 1..."
                        docker compose up -d
                        
                        sleep 10
                        docker ps
                        docker logs diary-web
                    '''
                }
                
                def webStatus = sh(script: 'docker inspect -f "{{.State.Status}}" diary-web', returnStdout: true).trim()
                def webPorts = sh(script: 'docker inspect -f "{{.NetworkSettings.Ports}}" diary-web', returnStdout: true).trim()
                
                slackSend(channel: '#reports', message: "Rollback result - Web status: $webStatus, Ports: $webPorts")
            }
        }
    }
}
