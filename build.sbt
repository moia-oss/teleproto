import com.typesafe.tools.mima.core._
import xerial.sbt.Sonatype._

addCommandAlias("validate", "all test doc scapegoat mimaReportBinaryIssues")

// *****************************************************************************
// Projects
// *****************************************************************************

lazy val `teleproto` = project
  .in(file("."))
  .enablePlugins(GitVersioning, GitBranchPrompt)
  .settings(commonSettings: _*)
  .settings(sonatypeSettings: _*)
  .settings(Project.inConfig(Test)(sbtprotoc.ProtocPlugin.protobufConfigSettings): _*)
  .settings(
    name          := "teleproto",
    version       := "2.4.1",
    versionScheme := Some("early-semver"),
    libraryDependencies ++= Seq(
      library.scalaPB            % "protobuf;compile",
      library.scalaPBJson        % "compile;optional",
      library.scalaTest          % Test,
      library.scalaTestPlusCheck % Test,
      library.scalaCheck         % Test,
      library.scalaCollectionCompat,
      "org.scala-lang" % "scala-reflect" % (ThisBuild / scalaVersion).value
    )
  )

// *****************************************************************************
// Dependencies
// *****************************************************************************

lazy val library = new {
  object Version {
    val scalaPB               = scalapb.compiler.Version.scalapbVersion
    val scalaPBJson           = "0.12.2"
    val scalaCheck            = "1.18.1"
    val scalaTest             = "3.2.19"
    val scalaTestPlusCheck    = "3.2.14.0"
    val scapeGoat             = "3.1.9"
    val scalaCollectionCompat = "2.13.0"
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
  gitSettings,
  organizationSettings,
  scmSettings,
  sbtSettings,
  scalaFmtSettings,
  scapegoatSettings,
  mimaSettings
)

lazy val compilerSettings = Seq(
  scalaVersion                                                                     := "2.13.16",
  Compile / packageBin / mappings += (ThisBuild / baseDirectory).value / "LICENSE" -> "LICENSE",
  scalacOptions ++= scalacOptions_2_13
)

lazy val scalacOptions_2_13 = Seq(
  "-unchecked",
  "-deprecation",
  "-language:_",
  "-release",
  "8",
  "-encoding",
  "UTF-8",
  "-Xfatal-warnings",
  "-Xlint",
  "-Ywarn-dead-code",
  "-Ymacro-annotations"
)

lazy val gitSettings = Seq(
  git.useGitDescribe.withRank(KeyRanks.Invisible) := false
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

lazy val scapegoatSettings = Seq(
  ThisBuild / scapegoatVersion := library.Version.scapeGoat,
  // do not check generated files
  scapegoatIgnoredFiles := Seq(".*/src_managed/.*")
)

lazy val mimaSettings = Seq(
  mimaPreviousArtifacts := Set("io.moia" %% "teleproto" % "2.0.0"),
  mimaBinaryIssueFilters ++= Seq(
    // Method was added in 2.1.0
    ProblemFilters.exclude[ReversedMissingMethodProblem]("io.moia.protos.teleproto.PbResult.toEither")
  )
)

Test / PB.targets      := Seq(scalapb.gen(flatPackage = false) -> (Test / sourceManaged).value)
Test / PB.protoSources := Seq(baseDirectory.value / "src" / "test" / "protobuf")
