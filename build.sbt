import com.typesafe.tools.mima.core.*
import xerial.sbt.Sonatype.*

addCommandAlias("validate", "all test doc mimaReportBinaryIssues")

// *****************************************************************************
// Projects
// *****************************************************************************

// TODO: how to rename coproductInstances?
// TODO: extract required givens into library
// TODO: try PbResult.fromEither

lazy val `teleproto` = project
  .in(file("."))
  .enablePlugins(GitVersioning, GitBranchPrompt)
  .settings(commonSettings*)
  .settings(sonatypeSettings*)
  .settings(Project.inConfig(Test)(sbtprotoc.ProtocPlugin.protobufConfigSettings)*)
  .settings(
    name          := "teleproto",
    version       := "2.3.0",
    versionScheme := Some("early-semver"),
    libraryDependencies ++= Seq(
      library.scalaPB            % "protobuf;compile",
      library.scalaPBJson        % "compile;optional",
      library.scalaTest          % Test,
      library.scalaTestPlusCheck % Test,
      library.scalaCheck         % Test,
      library.scalaCollectionCompat,
      "io.scalaland" %% "chimney"           % "1.0.0-M1",
      "io.scalaland" %% "chimney-protobufs" % "1.0.0-M1"
    )
  )

// *****************************************************************************
// Dependencies
// *****************************************************************************

lazy val library = new {
  object Version {
    val scalaPB               = "0.11.15"
    val scalaPBJson           = "0.12.1"
    val scalaCheck            = "1.17.0"
    val scalaTest             = "3.2.18"
    val scalaTestPlusCheck    = "3.2.14.0"
    val scalaCollectionCompat = "2.11.0"
  }

  val scalaPB               = "com.thesamet.scalapb"   %% "scalapb-runtime"         % Version.scalaPB
  val scalaPBJson           = "com.thesamet.scalapb"   %% "scalapb-json4s"          % Version.scalaPBJson
  val scalaCheck            = "org.scalacheck"         %% "scalacheck"              % Version.scalaCheck
  val scalaTest             = "org.scalatest"          %% "scalatest"               % Version.scalaTest
  val scalaTestPlusCheck    = "org.scalatestplus"      %% "scalacheck-1-16"         % Version.scalaTestPlusCheck
  val scalaCollectionCompat = "org.scala-lang.modules" %% "scala-collection-compat" % Version.scalaCollectionCompat
}

// *****************************************************************************
// Settings
// *****************************************************************************

lazy val commonSettings = Seq.concat(
  compilerSettings,
  organizationSettings,
  scmSettings,
  sbtSettings,
  scalaFmtSettings,
  mimaSettings
)

lazy val compilerSettings = Seq(
  scalaVersion                                                                     := crossScalaVersions.value.head,
  crossScalaVersions                                                               := List("3.4.0"),
  Compile / packageBin / mappings += (ThisBuild / baseDirectory).value / "LICENSE" -> "LICENSE",
  scalacOptions ++= scalacOptions3
)

lazy val scalacOptions3 = Seq(
  "-deprecation",
  "-encoding",
  "UTF-8",
  "-explain",
  "-explain-types",
  "-feature",
  "-language:_",
  "-release",
  "8",
  "-rewrite",
//  "-source",
//  "3.4-migration",
  "-unchecked",
  "-Xfatal-warnings"
)

lazy val organizationSettings = Seq(
  organization     := "io.moia",
  organizationName := "MOIA GmbH",
  startYear        := Some(2019),
  licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))
)

lazy val scmSettings = Seq(
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/moia-dev/teleproto"),
      "scm:git@github.com:moia-dev/teleproto.git"
    )
  ),
  homepage := Some(url("https://github.com/moia-dev/teleproto"))
)

lazy val sonatypeSettings = Seq(
  publishTo              := sonatypePublishTo.value,
  sonatypeProfileName    := organization.value,
  publishMavenStyle      := true,
  sonatypeProjectHosting := Some(GitHubHosting("moia-dev", "teleproto", "oss-support@moia.io")),
  credentials += Credentials(Path.userHome / ".sbt" / "sonatype_credential")
)

lazy val sbtSettings = Seq(
  Global / cancelable                                      := true,
  sourceGenerators / logLevel.withRank(KeyRanks.Invisible) := Level.Error
)

lazy val scalaFmtSettings = Seq(
  scalafmtOnCompile := true
)

lazy val mimaSettings = Seq(
  mimaPreviousArtifacts := Set("io.moia" %% "teleproto" % "2.0.0"),
  mimaBinaryIssueFilters ++= Seq(
    // Method was added in 2.1.0
    ProblemFilters.exclude[ReversedMissingMethodProblem]("io.moia.protos.teleproto.PbResult.toEither")
  )
)

Compile / PB.targets   := Seq(scalapb.gen() -> (Compile / sourceManaged).value / "scalapb")
Test / PB.targets      := Seq(scalapb.gen(flatPackage = false) -> (Test / sourceManaged).value / "scalapb")
Test / PB.protoSources := Seq(baseDirectory.value / "src" / "test" / "protobuf")
