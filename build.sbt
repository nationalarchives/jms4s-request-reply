import Dependencies._

ThisBuild / scalaVersion     := "2.13.8"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "uk.gov.nationalarchives"
ThisBuild / organizationName := "The National Archives"
ThisBuild / startYear := Some(2023)
ThisBuild / licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / description := "JMS4S Request-Reply Library"

/* IMPORTANT NOTE >>>
 * Until further notice you will need to sbt publishLocal the JMS4S library from the combined-features branch
 * of this repository https://github.com/rwalpole/jms4s
 */

lazy val root = (project in file("."))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    name := "jms4s-request-reply",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % "2.9.0",
      "org.typelevel" %% "cats-effect" % "3.4.4",
      "org.typelevel" %% "cats-effect-kernel" % "3.4.4",
      //"org.typelevel" %% "log4cats-slf4j" % "2.5.0",
      "org.typelevel" %% "log4cats-core" % "2.5.0",
      "dev.fpinbo" %% "jms4s-active-mq-artemis" % "0.0.1-a9c282c",
      "dev.fpinbo" %% "jms4s-simple-queue-service" % "0.0.1-a9c282c",
      "org.scalatest" %% "scalatest" % "3.2.15" % Test,
      "org.typelevel" %% "cats-effect-testing-scalatest" % "1.5.0" % Test,
      "org.typelevel" %% "munit-cats-effect-3" % "1.0.7" % Test
    ),
    headerLicense := Some(HeaderLicense.MIT("2023", "The National Archives")),
  )

