node('master') {
  // stage 'Build and Test'
  def mvnHome = tool 'maven3'
  env.JAVA_HOME = tool 'jdk8'
  env.PATH = "${mvnHome}/bin:./:${env.PATH}"
  checkout scm

  stage ('Build cas-json-tool') {
    sh './gradlew distZip'
  }
}
