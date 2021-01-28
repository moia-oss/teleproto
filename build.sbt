import com.typesafe.tools.mima.core._

addCommandAlias("validate", "all test doc scapegoat mimaReportBinaryIssues")

// *****************************************************************************
// Projects
// *****************************************************************************

lazy val `teleproto` =
  project
    .in(file("."))
    .enablePlugins(GitVersioning, GitBranchPrompt)
    .settings(commonSettings: _*)
    .settings(sonatypeSettings: _*)
    .settings(Project.inConfig(Test)(sbtprotoc.ProtocPlugin.protobufConfigSettings): _*)
    .settings(
      name := "teleproto",
      version := "1.11.0",
      libraryDependencies ++= Seq(
        library.scalaPB            % "protobuf",
        library.scalaPBJson        % Compile,
        library.scalaTest          % Test,
        library.scalaTestPlusCheck % Test,
        library.scalaCheck         % Test,
        "org.scala-lang.modules"   %% "scala-collection-compat" % "2.3.2",
        "org.scala-lang"           % "scala-reflect" % scalaVersion.in(ThisBuild).value
      )
    )

// *****************************************************************************
// Dependencies
// *****************************************************************************

lazy val library = new {
  object Version {
    val scalaPB            = scalapb.compiler.Version.scalapbVersion
    val scalaPBJson        = "0.10.2"
    val scalaCheck         = "1.15.2"
    val scalaTest          = "3.2.2"
    val scalaTestPlusCheck = "3.2.2.0"
    val scapeGoat          = "1.4.7"
  }

  val scalaPB            = "com.thesamet.scalapb" %% "scalapb-runtime" % Version.scalaPB
  val scalaPBJson        = "com.thesamet.scalapb" %% "scalapb-json4s"  % Version.scalaPBJson
  val scalaCheck         = "org.scalacheck"       %% "scalacheck"      % Version.scalaCheck
  val scalaTest          = "org.scalatest"        %% "scalatest"       % Version.scalaTest
  val scalaTestPlusCheck = "org.scalatestplus"    %% "scalacheck-1-14" % Version.scalaTestPlusCheck
}

// *****************************************************************************
// Settings
// *****************************************************************************

lazy val commonSettings = Seq.concat(
  compilerSettings,
  gitSettings,
  organizationSettings,
  scmSettings,
  sbtSettings,
  scalaFmtSettings,
  scapegoatSettings,
  mimaSettings
)

lazy val compilerSettings = Seq(
  scalaVersion := crossScalaVersions.value.head,
  crossScalaVersions := List("2.13.4", "2.12.13"),
  mappings.in(Compile, packageBin) +=
    baseDirectory.in(ThisBuild).value / "LICENSE" -> "LICENSE",
  scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 12)) => scalacOptions_2_12
      case Some((2, 13)) => scalacOptions_2_13
      case _             => Seq()
    }
  }
)

lazy val scalacOptions_2_12 = Seq(
  "-unchecked",
  "-deprecation",
  "-language:_",
  "-target:jvm-1.8",
  "-encoding",
  "UTF-8",
  "-Xfatal-warnings",
  "-Ywarn-unused-import",
  "-Yno-adapted-args",
  "-Ywarn-dead-code",
  "-Ywarn-inaccessible",
  "-Ywarn-infer-any",
  "-Ywarn-nullary-override",
  "-Ywarn-nullary-unit"
)

lazy val scalacOptions_2_13 = Seq(
  "-unchecked",
  "-deprecation",
  "-language:_",
  "-target:jvm-1.8",
  "-encoding",
  "UTF-8",
  "-Xfatal-warnings",
  "-Xlint",
  "-Ywarn-dead-code",
  "-Ymacro-annotations"
)

lazy val gitSettings =
  Seq(
    git.useGitDescribe := false
  )

lazy val organizationSettings =
  Seq(
    organization := "io.moia",
    organizationName := "MOIA GmbH",
    startYear := Some(2019),
    licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))
  )

lazy val scmSettings =
  Seq(
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/moia-dev/teleproto"),
        "scm:git@github.com:moia-dev/teleproto.git"
      )
    ),
    homepage := Some(url("https://github.com/moia-dev/teleproto"))
  )

lazy val sonatypeSettings = {
  import xerial.sbt.Sonatype._
  Seq(
    publishTo := sonatypePublishTo.value,
    sonatypeProfileName := organization.value,
    publishMavenStyle := true,
    sonatypeProjectHosting := Some(GitHubHosting("moia-dev", "teleproto", "oss-support@moia.io")),
    credentials += Credentials(Path.userHome / ".sbt" / "sonatype_credential")
  )
}

lazy val sbtSettings =
  Seq(
    cancelable in Global := true,
    logLevel in sourceGenerators := Level.Error
  )

lazy val scalaFmtSettings =
  Seq(
    scalafmtOnCompile := true
  )

lazy val scapegoatSettings = Seq(
  scapegoatVersion in ThisBuild := library.Version.scapeGoat,
  // do not check generated files
  scapegoatIgnoredFiles := Seq(".*/src_managed/.*")
)

lazy val mimaSettings = Seq(
  // First 2.13 release of 1.x
  mimaPreviousArtifacts := Set("io.moia" %% "teleproto" % "1.2.0"),
  mimaBinaryIssueFilters ++= Seq(
    // No binary compatibility guarantees for macro implementations (they run at compile time).
    ProblemFilters.exclude[Problem]("io.moia.protos.teleproto.FormatImpl*"),
    ProblemFilters.exclude[Problem]("io.moia.protos.teleproto.MigrationImpl*"),
    ProblemFilters.exclude[Problem]("io.moia.protos.teleproto.ReaderImpl*"),
    ProblemFilters.exclude[Problem]("io.moia.protos.teleproto.WriterImpl*"),
    // PbResult is a sealed trait so linking from Scala should be fine.
    // Also, this method was added before introducing MiMa.
    ProblemFilters.exclude[ReversedMissingMethodProblem]("io.moia.protos.teleproto.PbResult.toOption"),
    // Writer.Mapped was an unused private class.
    ProblemFilters.exclude[MissingClassProblem]("io.moia.protos.teleproto.Writer$Mapped")
  )
)

PB.targets in Test := Seq(scalapb.gen(flatPackage = false) -> (sourceManaged in Test).value)
PB.protoSources in Test := Seq(file("src/test/protobuf"))
