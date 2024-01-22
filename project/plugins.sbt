// Formatting in scala
// See .scalafmt.conf for configuration details.
// Formatting takes place before the project is compiled.
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")

// Use git in sbt, show git prompt and use versions from git.
// sbt> git <your git command>
addSbtPlugin("com.github.sbt" % "sbt-git" % "2.0.1")

// Static code analysis.
// sbt> scapegoat
addSbtPlugin("com.sksamuel.scapegoat" %% "sbt-scapegoat" % "1.2.2")

// Make sbt build information available to the runtime
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.11.0")

// This plugin is able to generate a license report from all used licenses.
// sbt> dumpLicenseReport
addSbtPlugin("com.github.sbt" % "sbt-license-report" % "1.6.1")

// Uses protoc to generate code from proto files. This SBT plugin is meant supercede sbt-protobuf and sbt-scalapb.
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.6")

libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.15"

addSbtPlugin("de.heikoseeberger" % "sbt-header"      % "5.10.0")
addSbtPlugin("org.xerial.sbt"    % "sbt-sonatype"    % "3.10.0")
addSbtPlugin("com.github.sbt"    % "sbt-dynver"      % "5.0.1")
addSbtPlugin("com.github.sbt"    % "sbt-pgp"         % "2.2.1")
addSbtPlugin("com.typesafe"      % "sbt-mima-plugin" % "1.1.3")
