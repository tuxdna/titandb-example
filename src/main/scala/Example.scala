import java.io.StringReader
import java.util.Properties

import com.thinkaurelius.titan.core._
import com.thinkaurelius.titan.example.GraphOfTheGodsFactory
import org.apache.commons.configuration.BaseConfiguration
import scala.collection.JavaConversions._

object Example {
  def parsePropertiesString(s: String) = {
    val p = new Properties()
    p.load(new StringReader(s))
    p
  }

  val configData =
    """
      |storage.backend=cassandra
      |storage.hostname=localhost
      |storage.port=9160
      |storage.cassandra.keyspace=titan
      |cassandra.input.partitioner.class=org.apache.cassandra.dht.Murmur3Partitioner
      |
      |index.search.backend=elasticsearch
      |index.search.client-only=false
      |index.search.local-mode=true
      |index.search.directory=/tmp/searchindex
      |
    """.stripMargin


  def main(args: Array[String]) {

    val cassConf = new BaseConfiguration()
    cassConf.setProperty("index.search.backend", "elasticsearch")
    cassConf.setProperty("index.search.client-only", "false")
    cassConf.setProperty("index.search.local-mode", "true")
    cassConf.setProperty("index.search.directory", "/tmp/searchindex")
    cassConf.setProperty("storage.backend", "cassandra")
    cassConf.setProperty("storage.hostname", "localhost")
    cassConf.setProperty("storage.port", "9160")
    cassConf.setProperty("storage.keyspace", "titan")
/*
    val props = parsePropertiesString(configData)
    for(k <- props.keys()) {
      cassConf.setProperty(k, props.getProperty(k))
    }
*/

    val titanGraph = TitanFactory.open(cassConf)
    // GraphOfTheGodsFactory.load(titanGraph)
    val g = titanGraph.traversal()
    g.V().toList.foreach(println)
  }
}

