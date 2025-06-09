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
                        echo "DB_HOST=db" >> .env
                    '''
                }
            }
        }

        stage('Prepare Docker') {
            steps {
                sh '''
                    docker network inspect diary_default >/dev/null 2>&1 || \
                    docker network create diary_default
                    
                    docker volume inspect diary_db_data >/dev/null 2>&1 || \
                    docker volume create diary_db_data
                '''
            }
        }

        stage('Deploy Application') {
            steps {
                sh '''
                    docker stop diary-web || true
                    docker rm diary-web || true
                    
                    docker compose up -d --no-deps web
                '''
            }
        }

        stage('Verify') {
            steps {
                sh '''
                    for i in {1..10}; do
                        curl -f http://localhost:5001/ && break || sleep 5
                    done
                '''
            }
        }

        stage('Notify') {
            steps {
                slackSend(channel: '#reports', message: 'Version 2 deployed successfully!')
            }
        }
    }
}
