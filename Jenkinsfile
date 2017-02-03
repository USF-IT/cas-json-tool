node('master') {
  // stage 'Build and Test'
  def mvnHome = tool 'maven3'
  env.JAVA_HOME = tool 'jdk7'
  env.GRADLE_HOME = tool 'gradle3.3'
  env.PATH = "${mvnHome}/bin:./:${env.GRADLE_HOME}/bin:${env.PATH}"
  checkout scm

  stage ('Build cas-json-tool') {
    sh 'gradle wrapper'
    sh './gradlew distZip'
  }
}
