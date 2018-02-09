// SBT plugin resolvers
resolvers +=
  Resolver.url("bintray-sbilinski", url("http://dl.bintray.com/sbilinski/maven"))(Resolver.ivyStylePatterns)

// Show dependency graph.
// sbt> dependencyTree
addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.8.2")

// Push sbt-native-packager docker images to AWS ECR
// The following command creates a local Docker image, logs into ECR
// and pushes the docker image to ECR
// sbt> ecr:push
addSbtPlugin("com.mintbeans" % "sbt-ecr" % "0.7.0")

// Use git in sbt, show git prompt and use versions from git.
// sbt> git <your git command>
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.9.3")

// Automatically adds license information to each source code file.
addSbtPlugin("de.heikoseeberger" %  "sbt-header" % "3.0.2")

// Enable several package formats, especially docker.
// sbt> docker:publishLocal
// sbt> docker:publish
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.2.2")

// Release a new version of the app
// The following command builds a Docker image, publishes it to ECR and bumps the version in version.sbt
// sbt> release
addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.6")

// Formatting in scala
// See .scalafmt.conf for configuration details.
// Formatting takes place before the project is compiled.
addSbtPlugin("com.lucidchart" %  "sbt-scalafmt" % "1.12")

// Code coverage report. The code has to be instrumented, therefore a clean build is needed.
// sbt> clean
// sbt> coverage test
// sbt> coverageReport
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.1")

// Static code analysis.
// sbt> scapegoat
addSbtPlugin("com.sksamuel.scapegoat" %% "sbt-scapegoat" % "1.0.4")

addSbtPlugin("com.typesafe.sbt" % "sbt-twirl" % "1.3.4")