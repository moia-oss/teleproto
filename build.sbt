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
    version       := "3.0.0",
    versionScheme := Some("early-semver"),
    libraryDependencies ++= Seq(
      library.scalaPB            % "protobuf;compile",
      library.scalaPBJson        % "compile;optional",
      library.scalaTest          % Test,
      library.scalaTestPlusCheck % Test,
      library.scalaCheck         % Test,
      library.scalaCollectionCompat,
      "org.scala-lang" % "scala-reflect" % (ThisBuild / scalaVersion).value,
      "io.scalaland" %% "chimney"           % "1.0.0",
      "io.scalaland" %% "chimney-protobufs" % "1.0.0"
    )
  )

// *****************************************************************************
// Dependencies
// *****************************************************************************

lazy val library = new {
  object Version {
    val scalaPB               = scalapb.compiler.Version.scalapbVersion
    val scalaPBJson           = "0.12.1"
    val scalaCheck            = "1.18.0"
    val scalaTest             = "3.2.18"
    val scalaTestPlusCheck    = "3.2.14.0"
    val scapeGoat             = "2.1.6"
    val scalaCollectionCompat = "2.12.0"
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
  scalaVersion                                                                     := crossScalaVersions.value.head,
  crossScalaVersions                                                               := List("2.13.14", "2.13.14"),
  Compile / packageBin / mappings += (ThisBuild / baseDirectory).value / "LICENSE" -> "LICENSE",
  scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, 12)) => scalacOptions_2_12
    case Some((2, 13)) => scalacOptions_2_13
    case _             => Nil
  })
)

lazy val scalacOptions_2_12 = Seq(
  "-unchecked",
  "-deprecation",
  "-language:_",
  "-release",
  "8",
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
  "-release",
  "8",
  "-encoding",
  "UTF-8",
//  "-Xfatal-warnings", // TODO: restore
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
    ProblemFilters.exclude[ReversedMissingMethodProblem]("io.moia.protos.teleproto.PbResult.toEither"),
    // Classes were removed in 3.0.0
    ProblemFilters.exclude[MissingClassProblem]("io.moia.protos.teleproto.Migration"),
    ProblemFilters.exclude[MissingClassProblem]("io.moia.protos.teleproto.Migration$"),
    ProblemFilters.exclude[MissingClassProblem]("io.moia.protos.teleproto.MigrationImpl"),
    ProblemFilters.exclude[MissingClassProblem]("io.moia.protos.teleproto.MigrationImpl$Automatically"),
    ProblemFilters.exclude[MissingClassProblem]("io.moia.protos.teleproto.MigrationImpl$Automatically$"),
    ProblemFilters.exclude[MissingClassProblem]("io.moia.protos.teleproto.MigrationImpl$ParamMigration"),
    ProblemFilters.exclude[MissingClassProblem]("io.moia.protos.teleproto.MigrationImpl$Required"),
    ProblemFilters.exclude[MissingClassProblem]("io.moia.protos.teleproto.MigrationImpl$Required$"),
    // Chimney migration stuff
    ProblemFilters.exclude[DirectMissingMethodProblem]("io.moia.protos.teleproto.Reader.transform"),
    ProblemFilters.exclude[DirectMissingMethodProblem]("io.moia.protos.teleproto.Writer.transform"),
    ProblemFilters.exclude[MissingClassProblem]("io.moia.protos.teleproto.WriterImpl$Compatible"),
    ProblemFilters.exclude[MissingClassProblem]("io.moia.protos.teleproto.WriterImpl$Compatible$"),
    ProblemFilters.exclude[MissingClassProblem]("io.moia.protos.teleproto.WriterImpl$ForwardCompatible"),
    ProblemFilters.exclude[MissingClassProblem]("io.moia.protos.teleproto.WriterImpl$ForwardCompatible$"),
    ProblemFilters.exclude[MissingClassProblem]("io.moia.protos.teleproto.WriterImpl$Matching"),
    ProblemFilters.exclude[MissingClassProblem]("io.moia.protos.teleproto.WriterImpl$MatchingParam"),
    ProblemFilters.exclude[MissingClassProblem]("io.moia.protos.teleproto.WriterImpl$SkippedDefaultParam$"),
    ProblemFilters.exclude[MissingClassProblem]("io.moia.protos.teleproto.WriterImpl$TransformParam"),
    ProblemFilters.exclude[MissingClassProblem]("io.moia.protos.teleproto.WriterImpl$TransformParam$"),
  )
)

Test / PB.targets      := Seq(scalapb.gen(flatPackage = false) -> (Test / sourceManaged).value)
Test / PB.protoSources := Seq(baseDirectory.value / "src" / "test" / "protobuf")
