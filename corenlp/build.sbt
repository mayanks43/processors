name := "processors-corenlp"

libraryDependencies ++= {
  val akkaV = "2.4.3"
  Seq (
    "org.scalatest" %% "scalatest" % "2.2.4" % "test",
    "ai.lum" %% "common" % "0.0.7",
    "org.clulab" % "bioresources" % "1.1.22",
    "edu.stanford.nlp" % "stanford-corenlp" % "3.5.1",
    "edu.stanford.nlp" % "stanford-corenlp" % "3.5.1" classifier "models",
    "ch.qos.logback" % "logback-classic" % "1.0.10",
    "org.slf4j" % "slf4j-api" % "1.7.10",
    // AKKA
    "com.typesafe.akka"      %%  "akka-actor"                            % akkaV,
    "com.typesafe.akka"      %%  "akka-stream"                           % akkaV,
    "com.typesafe.akka"      %%  "akka-testkit"                          % akkaV    % "test",
    "com.typesafe.akka"      %%  "akka-slf4j"                            % akkaV
  )
}
