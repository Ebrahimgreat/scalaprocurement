ThisBuild / scalaVersion := "3.3.4"
ThisBuild / organization := "io.github.ebrahimgreat"
ThisBuild / version      := "0.1.0"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Wunused:all")

lazy val versions = new {
  val catsEffect      = "3.5.4"
  val http4s          = "0.23.27"
  val circe           = "0.14.9"
  val doobie          = "1.0.0-RC5"
  val h2              = "2.2.224"
  val logback         = "1.5.6"
  val munit           = "1.0.0"
  val munitCatsEffect = "2.0.0"
}

lazy val root = (project in file("."))
  .aggregate(core, api)
  .settings(name := "procurement-service", publish / skip := true)

/** Pure domain logic: no dependencies besides the test framework. */
lazy val core = project
  .settings(
    name := "procurement-core",
    libraryDependencies += "org.scalameta" %% "munit" % versions.munit % Test
  )

/** HTTP API and persistence on top of the domain. */
lazy val api = project
  .dependsOn(core)
  .settings(
    name := "procurement-api",
    Compile / run / fork := true,
    libraryDependencies ++= Seq(
      "org.typelevel"  %% "cats-effect"         % versions.catsEffect,
      "org.http4s"     %% "http4s-ember-server" % versions.http4s,
      "org.http4s"     %% "http4s-dsl"          % versions.http4s,
      "org.http4s"     %% "http4s-circe"        % versions.http4s,
      "io.circe"       %% "circe-generic"       % versions.circe,
      "org.tpolecat"   %% "doobie-core"         % versions.doobie,
      "com.h2database"  % "h2"                  % versions.h2,
      "ch.qos.logback"  % "logback-classic"     % versions.logback,
      "org.scalameta"  %% "munit"               % versions.munit           % Test,
      "org.typelevel"  %% "munit-cats-effect"   % versions.munitCatsEffect % Test
    )
  )
