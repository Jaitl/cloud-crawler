import Dependencies._
import BuildSettings._
import com.typesafe.sbt.packager.docker.DockerPermissionStrategy
import com.typesafe.sbt.packager.docker.DockerVersion
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.dockerBaseImage
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.dockerExposedPorts
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.dockerRepository

val projectVersion = sys.env.getOrElse("RELEASE_VERSION", "SNAPSHOT")

lazy val root = (project in file("."))
  .aggregate(models, master, worker, `simple-worker`)
  .settings(commonSettings)
  .settings(
    name := "cloud-crawler",
    version := projectVersion,
    skip in publish := true
  )

lazy val models = (project in file("models"))
  .settings(name := "models", version := projectVersion)
  .settings(commonSettings)

lazy val master = (project in file("master"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    name := "master",
    version := projectVersion,
    mainClass in run := Some("com.github.jaitl.crawler.master.MasterApp"),
    skip in publish := true
  )
  .settings(commonSettings)
  .dependsOn(models)
  .settings(
    libraryDependencies ++= Seq(mongoScalaDriver, ficus) ++ Akka.list ++ Logging.list,
    libraryDependencies ++= Seq(scalaTest, scalamock)
  )
  .settings(
    dockerPermissionStrategy := DockerPermissionStrategy.Run,
    dockerVersion := Some(DockerVersion(18, 9, 0, Some("ce"))),
    version in Docker := "latest",
    dockerBaseImage := "java"
  )

lazy val worker = (project in file("worker"))
  .settings(name := "worker", version := projectVersion)
  .settings(commonSettings)
  .dependsOn(models)
  .settings(
    libraryDependencies ++= Seq(mongoScalaDriver, ficus, asyncHttpClient, awsSdk, json4s, jtorctl) ++ Akka.list ++ Logging.list ++ Elasticsearch.list,
    libraryDependencies ++= Seq(scalaTest, scalamock)
  )

lazy val `simple-worker` = (project in file("simple-worker"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    name := "simple-worker",
    version := projectVersion,
    mainClass in run := Some("com.github.jaitl.cloud.simple.worker.App"),
    skip in publish := true
  )
  .settings(commonSettings)
  .dependsOn(worker)
  .settings(
    libraryDependencies += jsoup,
    libraryDependencies += scalaTest
  )
  .settings(
    dockerPermissionStrategy := DockerPermissionStrategy.Run,
    dockerVersion := Some(DockerVersion(18, 9, 0, Some("ce"))),
    version in Docker := "latest",
    dockerBaseImage := "java"
  )
