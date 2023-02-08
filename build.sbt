ThisBuild / scalaVersion     := "2.13.8"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "uk.gov.nationalarchives"
ThisBuild / organizationName := "The National Archives"
ThisBuild / startYear := Some(2023)
ThisBuild / licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / description := "JMS4S Request-Reply Library"
ThisBuild / githubOwner := "nationalarchives"
ThisBuild / githubRepository := "jms4s-request-reply"

lazy val root = (project in file("."))
  .enablePlugins(AutomateHeaderPlugin)
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(DockerPlugin)
  .settings(
    name := "jms4s-request-reply",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % "2.9.0",
      "org.typelevel" %% "cats-effect" % "3.4.4",
      "org.typelevel" %% "cats-effect-kernel" % "3.4.4",
      "org.typelevel" %% "log4cats-slf4j" % "2.5.0",
      "dev.fpinbo" %% "jms4s-active-mq-artemis" % "0.0.1-53518bb-SNAPSHOT",
      "dev.fpinbo" %% "jms4s-simple-queue-service" % "0.0.1-53518bb-SNAPSHOT",
      "org.scalatest" %% "scalatest" % "3.2.15" % Test,
      "org.typelevel" %% "cats-effect-testing-scalatest" % "1.5.0" % Test,
      "org.slf4j" % "slf4j-simple" % "2.0.3" % Test
    ),
    resolvers += Resolver.githubPackages("rwalpole"),
    headerLicense := Some(HeaderLicense.MIT("2023", "The National Archives")),
    fork := true,
    dockerExposedPorts := Seq(9324)
  )

