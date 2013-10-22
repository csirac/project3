name := """project-1"""

version := "0.0"

scalaVersion := "2.10.2"

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.2.0"

libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % "2.2.0"

libraryDependencies += "org.scalatest" % "scalatest_2.10" % "1.9.1" % "test"

TaskKey[File]("mkrun") <<= (baseDirectory, fullClasspath in Runtime, mainClass in Runtime) map { (base, cp, main) =>
  val template = """#!/bin/sh
java -classpath "%s" %s "$@"
"""
  val mainStr = main getOrElse error("No main class specified")
  val contents = template.format(cp.files.absString, mainStr)
  val out = base / "/run-server.sh"
  IO.write(out, contents)
  out.setExecutable(true)
  out
}