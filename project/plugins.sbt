// Formatting in scala
// See .scalafmt.conf for configuration details.
// Formatting takes place before the project is compiled.
addSbtPlugin("com.lucidchart" % "sbt-scalafmt" % "1.15")

// Use git in sbt, show git prompt and use versions from git.
// sbt> git <your git command>
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.9.3")

// Code coverage report. The code has to be instrumented, therefore a clean build is needed.
// sbt> clean
// sbt> coverage test
// sbt> coverageReport
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.1")

// Static code analysis.
// sbt> scapegoat
addSbtPlugin("com.sksamuel.scapegoat" %% "sbt-scapegoat" % "1.1.0")

// Make sbt build information available to the runtime
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.9.0")

// This plugin is able to generate a license report from all used licenses.
// sbt> dumpLicenseReport
addSbtPlugin("com.typesafe.sbt" % "sbt-license-report" % "1.2.0")

// Uses protoc to generate code from proto files. This SBT plugin is meant supercede sbt-protobuf and sbt-scalapb.
addSbtPlugin("com.thesamet" % "sbt-protoc" % "0.99.31")

libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.10.3"

addSbtPlugin("de.heikoseeberger" % "sbt-header"   % "5.2.0")
addSbtPlugin("org.xerial.sbt"    % "sbt-sonatype" % "3.8.1")
addSbtPlugin("com.dwijnand"      % "sbt-dynver"   % "4.0.0")
addSbtPlugin("com.jsuereth"      % "sbt-pgp"      % "1.1.2")
