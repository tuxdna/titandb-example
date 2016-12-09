import AssemblyKeys._

assemblySettings

name := "example-titan"

version := "1.0"

scalaVersion := "2.10.5"

resolvers ++= Seq(
  Resolver.sonatypeRepo("releases"),
  Resolver.sonatypeRepo("snapshots")
)

libraryDependencies ++= Seq(
  "org.apache.spark"  % "spark-core_2.10"              % "1.6.3" % "provided",
  "org.apache.spark"  % "spark-mllib_2.10"             % "1.6.3",
  "com.databricks"    % "spark-csv_2.10"               % "1.5.0",
  "com.thinkaurelius.titan" % "titan-core" % "1.0.0",
  "com.thinkaurelius.titan" % "titan-test" % "1.0.0",
  "com.thinkaurelius.titan" % "titan-es" % "1.0.0",
  "com.thinkaurelius.titan" % "titan-test" % "1.0.0",
  "com.thinkaurelius.titan" % "titan-cassandra" % "1.0.0",
  // "com.amazonaws" % "dynamodb-titan100-storage-backend" % "1.0.0",
  "org.apache.tinkerpop" % "gremlin-core" % "3.0.1-incubating",
  "org.apache.tinkerpop" % "gremlin-groovy" % "3.0.1-incubating",
  "com.tinkerpop.gremlin" % "gremlin-java" % "2.6.0",
  "org.apache.tinkerpop" % "gremlin-test" % "3.0.1-incubating",
  "org.apache.tinkerpop" % "gremlin-console" % "3.0.1-incubating",
  "com.github.fommil.netlib" % "all"                   % "1.1.2"
)

libraryDependencies += "org.scalatest" % "scalatest_2.10" % "2.2.1" % "test"

mainClass in assembly := Some("in.tuxdna.example.titandb.Main")

parallelExecution in Test := false

