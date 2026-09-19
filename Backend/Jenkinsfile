pipeline {
    agent any

    tools {
        jdk 'jdk-17'
    }

    environment {
        MAVEN_OPTS = "-Dmaven.repo.local=.m2/repository"
    }

    stages {


        stage('Verify Java') {
            steps {
                bat 'java -version'
            }
        }

        stage('Clean') {
            steps {
                bat 'mvnw.cmd clean'
            }
        }

        stage('Compile') {
            steps {
                bat 'mvnw.cmd compile'
            }
        }

        stage('Unit Tests') {
            steps {
                bat 'mvnw.cmd test'
            }
        }

        stage('Package') {
            steps {
                bat 'mvnw.cmd package -DskipTests'
            }
        }

        stage('Archive Artifact') {
            steps {
                archiveArtifacts artifacts: 'target\\*.jar', fingerprint: true
            }
        }
    }

    post {
        success {
            echo 'Build completed successfully'
        }

        failure {
            echo 'Build failed'
        }

        always {
            cleanWs()
        }
    }
}
