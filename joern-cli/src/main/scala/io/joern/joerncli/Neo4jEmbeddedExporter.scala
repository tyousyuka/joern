package io.joern.joerncli

import org.neo4j.configuration.GraphDatabaseSettings
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder
import org.neo4j.graphdb.{GraphDatabaseService, Label, RelationshipType}
import overflowdb.formats.{ExportResult, Exporter}
import overflowdb.{Edge, Node}

import java.io.File
import java.lang
import java.nio.file.Path
import scala.language.postfixOps

object Neo4jEmbeddedExporter extends Exporter {

  override def defaultFileExtension = ""

  override def runExport(
                          nodes: IterableOnce[Node],
                          edges: IterableOnce[Edge],
                          outputRootDirectory: Path
                        ): ExportResult = {
    val dbName = outputRootDirectory.getFileName.toString
    val home   = new File("/data/Neo4jDatabase")
    val managementService = new DatabaseManagementServiceBuilder(home.toPath)
      .setConfig(GraphDatabaseSettings.initial_default_database, dbName)
      .build
    val graphDb      = managementService.database(dbName)
    val nodesByLabel = nodes.iterator.toSeq.groupBy(_.label).filter(_._2.nonEmpty)
    {
      val tx     = graphDb.beginTx()
      val schema = tx.schema()
      nodesByLabel.map { case (k, _) =>
        try {
          schema.getIndexByName("idx_" + k)
        } catch {
          case _: Exception =>
            schema.indexFor(Label.label(k)).on("id").withName("idx_" + k).create
        }
      }
      tx.commit()
    }
    nodesByLabel.foreach { case (_, nodes) => exportNodes(nodes, graphDb) }
    exportEdges(edges, graphDb)
    ExportResult(nodeCount = 1, edgeCount = 1, files = null, additionalInfo = Option(s"".stripMargin))
  }

  private val DEFAULT_EDGES_BATCH_SIZE = 20000

  private def exportNodes(nodes: IterableOnce[overflowdb.Node], service: GraphDatabaseService): Unit = {
    val list  = nodes.iterator.toList
    val sizes = list.size
    val number =
      if (sizes % DEFAULT_EDGES_BATCH_SIZE == 0) sizes / DEFAULT_EDGES_BATCH_SIZE
      else sizes / DEFAULT_EDGES_BATCH_SIZE + 1

    for (i <- 1 to number) {
      val tx = service.beginTx
      val it = list.slice((i - 1) * DEFAULT_EDGES_BATCH_SIZE, i * DEFAULT_EDGES_BATCH_SIZE)
      for (node <- it) {
        val n = tx.createNode(Label.label(node.label))
        n.setProperty("id", node.id)
        val map: java.util.Map[String, Object] = node.propertiesMap()
        map.forEach((key, value) => n.setProperty(key, value.toString))
      }
      tx.commit()
    }
  }

  private def exportEdges(edges: IterableOnce[Edge], service: GraphDatabaseService): Unit = {
    val start   = System.currentTimeMillis()
    var counter = 0

    val list  = edges.iterator.toList
    val sizes = list.size
    val number =
      if (sizes % DEFAULT_EDGES_BATCH_SIZE == 0) sizes / DEFAULT_EDGES_BATCH_SIZE
      else sizes / DEFAULT_EDGES_BATCH_SIZE + 1
    for (i <- 1 to number) {
      val tx = service.beginTx()
      val it = list.slice((i - 1) * DEFAULT_EDGES_BATCH_SIZE, i * DEFAULT_EDGES_BATCH_SIZE)
      for (edge <- it) {
        val out      = edge.outNode
        val in       = edge.inNode
        val fromNode = tx.findNodes(Label.label(out.label), "id", out.id).stream.findFirst().get()
        val toNode   = tx.findNodes(Label.label(in.label), "id", in.id).stream.findFirst().get()
        if (fromNode != null && toNode != null) {
          fromNode.createRelationshipTo(toNode, RelationshipType.withName(edge.label))
          counter += 1
        }
      }
      tx.commit()
    }

    val end = System.currentTimeMillis()
    println(s"counter: $counter, time: ${end - start}")
  }
}
