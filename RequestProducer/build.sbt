ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.2.2"

lazy val root = (project in file("."))
  .settings(
    name := "KafkaExample",
    resolvers += "Maven" at "https://packages.confluent.io/maven/",
    libraryDependencies += "dev.zio" %% "zio-kafka" % "2.1.3",
    libraryDependencies += "dev.zio" %% "zio-http" % "0.0.5",
    libraryDependencies += "io.confluent" % "kafka-avro-serializer" % "7.3.3",
)
