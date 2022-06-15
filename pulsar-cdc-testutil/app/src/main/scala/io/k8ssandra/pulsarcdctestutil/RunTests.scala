package io.k8ssandra.pulsarcdctestutil

import com.sksamuel.pulsar4s.PulsarClient
import picocli.CommandLine._

import java.util.concurrent.Callable

@Command(
  name = "run-tests",
  mixinStandardHelpOptions = true,
  version = Array("checksum 4.0"),
  description = Array(
    "Configures Pulsar Cassandra connector, adds tables + data to Cassandra, checks Pulsar topic to ensure data has appeared."
  )
)
class RunTests extends Callable[Int] {
  @Option(
    names = Array("--pulsar-url"),
    description = Array("Pulsar URL.")
  )
  private var pulsarURL = "pulsar://pulsar-proxy.pulsar.svc.cluster.local:5600"

  @Option(
    names = Array("--schema-registry-url"),
    description = Array("Schema Registry URL.")
  )
  private var schemaRegistryURL = "pulsar://pulsar-proxy.pulsar.svc.cluster.local:5600"

  @Option(
    names = Array("--pulsar-admin-url"),
    description = Array("Pulsar admin URL.")
  )
  private var pulsarAdminURL = "http://pulsar-proxy.pulsar.svc.cluster.local:8080"

  @Option(
    names = Array("--pulsar-topic"),
    description = Array("Pulsar topic.")
  )
  private var pulsarTopic ="persistent://public/default/data-db1.table1"

  @Option(
    names = Array("--pulsar-cass-contact-point"),
    description = Array("Pulsar's Cassandra contact points (as opposed to this application's.")
  ) // This is needed in case the contact points available to Pulsar aren't the same as those available to this application.
  private var PulsarCassContactPoints =cassandraContactPoints

  @Option(
    names = Array("--pulsar-auth-class"),
    description = Array("Pulsar Auth plugin class.")
  )
  private var pulsarAuthClass = ""
  @Option(
    names = Array("--pulsar-auth-params"),
    description = Array("comma delimited Pulsar auth params.")
  )
  private var pulsarAuthParms = ""

  @Option(
    names = Array("--cass-contact-points"),
    description = Array("Cassandra contact point.")
  )
  private var cassandraContactPoints = "cluster2-dc1-all-pods-service.default.svc.cluster.local"

  @Option(
    names = Array("--cass-dc"),
    description = Array("Cassandra datacenter name.")
  )
  private var cassDCName = "dc1"

  @Option(
    names = Array("--cass-auth-class"),
    description = Array("Cassandra auth plugin class.")
  )
  private var cassAuthClass = ""

  @Option(
    names = Array("--cass-auth-parms"),
    description = Array("comma delimited Cassandra auth params.")
  )
  private var cassAuthParms = ""

  override def call(): Int = {
    val cassClient = CassandraOrchestrator(
      cassandraContactPoints,
      cassAuthClass,
      cassAuthParms)
    val pulsarClient = PulsarOrchestrator(
      pulsarURL,
      schemaRegistryURL,
      pulsarAdminURL,
      pulsarTopic,
      pulsarAuthClass,
      pulsarAuthParms)

    // Create tables in Cassandra
    var res : Either[Throwable, _] = cassClient.migrate(cassDCName)
    if (res.isLeft) { println("Failed to create Cassandra schema"); return ExitCode.SOFTWARE }

    // Configure Pulsar connector.
    res = pulsarClient.connectorConfigure(
      cassDC=cassDCName,
      cassContactPoint=PulsarCassContactPoints,
      keyspace="db1",
      table="table1"
    )
    if (res.isLeft) {
      println(s"Failed to configure Pulsar connector: ${res.left.get.getMessage}")
      return ExitCode.SOFTWARE
    }

    // Add data to Cassandra
    res = cassClient.addData(cassDCName)
    if (res.isLeft) {
      println(s"Failed to add data to Cassandra: ${res.left.get.getMessage}")
      return ExitCode.SOFTWARE
    }

    val maybeData = pulsarClient.fetchData()
    if (maybeData.isLeft) {
      println(s"Data fetch on topic failed: ${maybeData.left.get.getMessage}")
      return ExitCode.SOFTWARE
    }

    val data = maybeData.right.get

    // Check data from Pulsar
    pulsarClient.checkData(data)
    if (res.isLeft) {
      println(s"Data validation on topic failed: ${res.left.get.getMessage}")
      return ExitCode.SOFTWARE
    }
    println("SUCCESS")
    ExitCode.OK
  }
}