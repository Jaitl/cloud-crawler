import sbt._

object Dependencies {
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.0.8" % Test
  lazy val scalamock = "org.scalamock" %% "scalamock" % "4.1.0" % Test

  lazy val ficus = "com.iheart" %% "ficus" % "1.4.3"
  
  lazy val mongoScalaDriver = "org.mongodb.scala" %% "mongo-scala-driver" % "2.2.1"
  lazy val asyncHttpClient = "org.asynchttpclient" % "async-http-client" % "2.4.7"
  lazy val awsSdk = "com.amazonaws" % "aws-java-sdk" % "1.9.20.1"
  lazy val json4s = "org.json4s" %% "json4s-jackson" % "3.6.4"
  lazy val jtorctl = "com.github.avchu" % "jtorctl" % "v0.3.3"
  lazy val jsoup = "org.jsoup" % "jsoup" % "1.11.3"

  object Logging {
    lazy val logback = "ch.qos.logback" % "logback-classic" % "1.2.3"
    lazy val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % "3.7.2"

    lazy val list: Seq[ModuleID] = Seq(logback, scalaLogging)
  }

  object Akka {
    val version = "2.5.9"

    lazy val cluster = "com.typesafe.akka" %% "akka-cluster" % version
    lazy val slf4j = "com.typesafe.akka" %% "akka-slf4j" % version
    lazy val clusterTools = "com.typesafe.akka" %% "akka-cluster-tools" % version
    lazy val clusterSharding = "com.typesafe.akka" %% "akka-cluster-sharding" % version

    lazy val testkit = "com.typesafe.akka" %% "akka-testkit" % version % Test

    lazy val list: Seq[ModuleID] = Seq(cluster, slf4j, clusterTools, clusterSharding, testkit)
  }

  object Elasticsearch {
    val version = "6.3.3"

    lazy val elastic4sCore = "com.sksamuel.elastic4s" %% "elastic4s-core" % version
    lazy val elastic4sHttp = "com.sksamuel.elastic4s" %% "elastic4s-http" % version

    lazy val list: Seq[ModuleID] = Seq(elastic4sCore, elastic4sHttp)
  }
}