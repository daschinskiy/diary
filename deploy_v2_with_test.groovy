pipeline {
    agent any
    environment {
        DB_NAME = 'diary'
    }
    stages {
        stage('Prepare') {
            steps {
                slackSend(channel: '#reports', message: 'Deploying version 2...')
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
                            
                            echo "Running tests..."
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
                    docker stop diary-web || true
                    docker rm diary-web || true
                    docker compose up -d --no-deps web
                '''
                slackSend(channel: '#reports', message: 'Version 2 deployed successfully!')
            }
        }
    }
    
    post {
        failure {
            slackSend(channel: '#reports', message: "Deployment failed! Build: ${BUILD_URL}")
            sh '''
                if [ ! "$(docker ps -q -f name=diary-web)" ]; then
                    docker compose up -d --no-deps web
                fi
            '''
        }
        success {
            slackSend(channel: '#reports', message: "Version 2 deployed successfully! Build: ${BUILD_URL}")
        }
    }
}
