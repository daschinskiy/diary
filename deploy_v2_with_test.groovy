pipeline {
    agent any
    environment {
        DB_NAME = 'diary'
    }
    stages {
        stage('Clone v2') {
            steps {
                slackSend(channel: '#reports', message: 'Starting deployment test with forced DB error...')
                git branch: 'v2', url: 'https://github.com/daschinskiy/diary.git'
            }
        }

        stage('Setup Broken DB Config') {
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
                        echo "DB_HOST=non_existent_db" >> .env
                    '''
                }
            }
        }

        stage('Attempt Deploy v2') {
            steps {
                script {
                    try {
                        sh '''
                            docker stop diary-web || true
                            docker rm diary-web || true
                            docker compose build web
                            docker compose up -d web
                            
                            sleep 10
                            
                            if [ "$(docker inspect -f '{{.State.Status}}' diary-web)" != "running" ]; then
                                echo "Container failed to start as expected"
                                exit 1
                            fi
                        '''
                    } catch (Exception e) {
                        currentBuild.result = 'FAILURE'
                        error("Deployment failed as expected: ${e.getMessage()}")
                    }
                }
            }
        }

        stage('Rollback to v1') {
            when {
                expression { currentBuild.result == 'FAILURE' }
            }
            steps {
                script {
                    slackSend(channel: '#reports', message: 'Starting rollback to v1...')
                    
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
                                
                                docker compose down || true
                                docker compose up -d
                                
                                sleep 5
                                docker ps -a | grep diary-web
                                docker inspect -f '{{.State.Status}}' diary-web
                            '''
                        }
                    }
                    
                    // Финальная проверка
                    def webStatus = sh(script: 'docker inspect -f "{{.State.Status}}" diary-web', returnStdout: true).trim()
                    def webPorts = sh(script: 'docker inspect -f "{{range \$p, \$conf := .NetworkSettings.Ports}}{{\$p}} {{end}}" diary-web', returnStdout: true).trim()
                    
                    slackSend(channel: '#reports', 
                        message: "Rollback complete! Status: $webStatus, Ports: $webPorts | " +
                                "View: http://your-server-ip:5001")
                }
            }
        }
    }
}
