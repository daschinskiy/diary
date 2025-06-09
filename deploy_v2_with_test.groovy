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
                        echo "DB_HOST=non_existent_db" >> .env  # Неправильный хост!
                    '''
                }
            }
        }

        stage('Attempt Deploy v2') {
            steps {
                script {
                    try {
                        sh '''
                            docker compose down || true
                            
                            docker compose up -d --build
                            
                            sleep 15
                            
                            if [ "$(docker inspect -f '{{.State.Status}}' diary-web 2>/dev/null)" != "running" ]; then
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
                    slackSend(channel: '#reports', message: 'Starting guaranteed rollback to v1...')
                    
                    dir('rollback') {
                        deleteDir()
                        git branch: 'main', url: 'https://github.com/daschinskiy/diary.git'
                        
                        sh '''
                            docker compose -f ../docker-compose.yml down || true
                            docker stop diary-web diary-db registry || true
                            docker rm diary-web diary-db registry || true
                        '''
                        
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
                                
                                docker compose up -d --force-recreate
                                
                                sleep 10
                                echo "Container status:"
                                docker ps -a | grep diary-web
                                echo "Ports:"
                                docker inspect -f '{{range \$p, \$conf := .NetworkSettings.Ports}}{{\$p}} {{end}}' diary-web
                            '''
                        }
                    }
                    
                    def webStatus = sh(
                        script: 'docker inspect -f "{{.State.Status}}" diary-web', 
                        returnStdout: true
                    ).trim()
                    
                    slackSend(
                        channel: '#reports', 
                        message: "Rollback result - Status: $webStatus | " +
                                "View: http://your-server-ip:5001"
                    )
                }
            }
        }
    }
}
