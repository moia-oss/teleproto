// *****************************************************************************
// Projects
// *****************************************************************************

lazy val `teleproto` =
  project
    .in(file("."))
    .enablePlugins(GitVersioning, GitBranchPrompt, JavaAppPackaging)
    .configs(IntegrationTest)
    .settings(commonSettings: _*)
    .settings(packageSettings: _*)
    .settings(Defaults.itSettings: _*)
    .settings(
      name := "teleproto",
      libraryDependencies ++= Seq(
        library.scalaPBJson % Compile,
        // protobuf extensions, needed for proto generation
        library.protobuf % "protobuf",
        library.scalaPB % "protobuf",
        // test dependencies
        library.scalaCheck % "it,test",
        library.scalaTest % "it,test"
      )
    )

// *****************************************************************************
// Dependencies
// *****************************************************************************

lazy val library =
  new {

    object Version {
      val protobuf = "3.4.0"
      val scalaPBJson = "0.3.2"
      val scalaCheck = "1.13.5"
      val scalaFmt = "1.2.0"
      val scalaLogging = "3.7.2"
      val scalaTest = "3.0.3"
      val scapeGoat = "1.3.3"
    }

    val protobuf = "com.google.protobuf" % "protobuf-java" % Version.protobuf
    val scalaPB = "com.trueaccord.scalapb" %% "scalapb-runtime" % com.trueaccord.scalapb.compiler.Version.scalapbVersion
    val scalaPBJson = "com.trueaccord.scalapb" %% "scalapb-json4s" % Version.scalaPBJson
    val scalaCheck = "org.scalacheck" %% "scalacheck" % Version.scalaCheck
    val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % Version.scalaLogging
    val scalaTest = "org.scalatest" %% "scalatest" % Version.scalaTest
  }

// *****************************************************************************
// Settings
// *****************************************************************************

lazy val commonSettings =
  compilerSettings ++
    gitSettings ++
    licenseSettings ++
    organizationSettings ++
    sbtSettings ++
    scalaFmtSettings ++
    scapegoatSettings

lazy val packageSettings =
  releaseSettings ++
    artifactorySettings

lazy val compilerSettings =
  Seq(
    scalaVersion := "2.12.3",
    mappings.in(Compile, packageBin) +=
      baseDirectory.in(ThisBuild).value / "LICENSE" -> "LICENSE",
    scalacOptions ++= Seq(
      "-unchecked",
      "-deprecation",
      "-language:_",
      "-target:jvm-1.8",
      "-encoding",
      "UTF-8",
      "-DLog4jContextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector",
      //      "-Xfatal-warnings",
      //      "-Ywarn-unused-import",
      "-Yno-adapted-args",
      "-Ywarn-numeric-widen",
      "-Ywarn-dead-code",
      "-Ywarn-inaccessible",
      "-Ywarn-infer-any",
      "-Ywarn-nullary-override",
      "-Ywarn-nullary-unit"
      // disable warn-unused-import because of manu false positives in twirl etc.
      //"-Ywarn-unused-import"
    ),
    javacOptions ++= Seq(
      "-source",
      "1.8",
      "-target",
      "1.8"
    ),
    unmanagedSourceDirectories.in(Compile) := Seq(
      scalaSource.in(Compile).value),
    unmanagedSourceDirectories.in(Test) := Seq(scalaSource.in(Test).value)
  )

import com.amazonaws.regions.{Region, Regions}

lazy val gitSettings =
  Seq(
    git.useGitDescribe := true
  )

lazy val licenseSettings =
  Seq(
    headerLicense := Some(
      HeaderLicense.Custom(
        """|Copyright (c) MOIA GmbH 2017
           |""".stripMargin
      )),
    headerMappings := headerMappings.value + (HeaderFileType.conf -> HeaderCommentStyle.HashLineComment)
  )

lazy val organizationSettings =
  Seq(
    organization := "io.moia.pricing"
  )

import sbtrelease.ReleasePlugin.autoImport.ReleaseTransformations._

lazy val releaseSettings =
  Seq(
    releaseProcess := Seq(
      checkSnapshotDependencies,
      inquireVersions,
      runClean,
      runTest,
      releaseStepCommand("scapegoat"),
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      setNextVersion,
      commitNextVersion,
      pushChanges
    )
  )

lazy val artifactorySettings =
  Seq(
    resolvers += "Artifactory" at "https://moiadev.jfrog.io/moiadev/sbt-release",
    credentials ++= Seq(Path.userHome / ".ivy2" / ".credentials")
      .filter(_.exists)
      .map(Credentials(_)),
    credentials ++= Seq("ARTIFACTORY_USER")
      .filter(sys.env.isDefinedAt)
      .map(
        user =>
          Credentials("Artifactory Realm",
                      "moiadev.jfrog.io",
                      sys.env(user),
                      sys.env("ARTIFACTORY_PASS"))),
    publishTo := Some(
      "Artifactory Realm" at "https://moiadev.jfrog.io/moiadev/sbt-pricing-local")
  )

lazy val sbtSettings =
  Seq(
    cancelable in Global := true,
    logLevel in sourceGenerators := Level.Error
  )

lazy val scalaFmtSettings =
  Seq(
    scalafmtOnCompile := true,
    scalafmtVersion := library.Version.scalaFmt
  )

lazy val scapegoatSettings =
  Seq(
    scapegoatVersion := library.Version.scapeGoat,
    scapegoatDisabledInspections := Seq("FinalModifierOnCaseClass"),
    // do not check generated files
    scapegoatIgnoredFiles := Seq(".*/src_managed/.*", ".*/twirl/.*")
  )
