package in.tuxdna.example.titandb

/*
 * Copyright 2014-2015 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
// package com.amazon.titan.example

import java.io.InputStreamReader
import java.net.URL
import java.security.SecureRandom
import java.util.Arrays
import java.util.concurrent.{BlockingQueue, ExecutorService, Executors, LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import java.util

import au.com.bytecode.opencsv.CSVReader
import com.codahale.metrics.{ConsoleReporter, MetricRegistry}
import com.thinkaurelius.titan.core.{Multiplicity, PropertyKey, TitanGraph}
import com.thinkaurelius.titan.core.schema.TitanManagement
import com.thinkaurelius.titan.util.stats.MetricManager
import org.apache.commons.lang.exception.ExceptionUtils
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource
import org.apache.tinkerpop.gremlin.structure.Vertex
import org.slf4j.{Logger, LoggerFactory}
import scala.collection.JavaConversions._


class MarvelGraphFactory {
  private val RANDOM: SecureRandom = new SecureRandom
  private val BATCH_SIZE: Int = 10
  private val LOG: Logger = LoggerFactory.getLogger(classOf[MarvelGraphFactory])
  val APPEARED: String = "appeared"
  val COMIC_BOOK: String = "comic-book"
  val CHARACTER: String = "character"
  val WEAPON: String = "weapon"
  val REGISTRY: MetricRegistry = MetricManager.INSTANCE.getRegistry
  val REPORTER: ConsoleReporter = ConsoleReporter.forRegistry(REGISTRY).build
  private val TIMER_LINE: String = "MarvelGraphFactory.line"
  private val TIMER_CREATE: String = "MarvelGraphFactory.create_"
  private val COUNTER_GET: String = "MarvelGraphFactory.get_"
  private val WEAPONS: Array[String] = Array("claws", "ring", "shield", "robotic suit", "cards", "surf board", "glider", "gun", "swords", "lasso")
  private val COMPLETED_TASK_COUNT: AtomicInteger = new AtomicInteger(0)
  private val POOL_SIZE: Int = 10

  @throws(classOf[Exception])
  def load(graph: TitanGraph, rowsToLoad: Int, report: Boolean) {
    val mgmt = graph.openManagement
    if (mgmt.getGraphIndex(CHARACTER) == null) {
      val characterKey: PropertyKey = mgmt.makePropertyKey(CHARACTER).dataType(classOf[String]).make
      mgmt.buildIndex(CHARACTER, classOf[Vertex]).addKey(characterKey).unique.buildCompositeIndex
    }
    if (mgmt.getGraphIndex(COMIC_BOOK) == null) {
      val comicBookKey: PropertyKey = mgmt.makePropertyKey(COMIC_BOOK).dataType(classOf[String]).make
      mgmt.buildIndex(COMIC_BOOK, classOf[Vertex]).addKey(comicBookKey).unique.buildCompositeIndex
      mgmt.makePropertyKey(WEAPON).dataType(classOf[String]).make
      mgmt.makeEdgeLabel(APPEARED).multiplicity(Multiplicity.MULTI).make
    }
    mgmt.commit
    val classLoader: ClassLoader = classOf[MarvelGraphFactory].getClassLoader
    val resource: URL = classLoader.getResource("META-INF/marvel.csv")
    var line: Int = 0
    val comicToCharacter: util.Map[String, util.Set[String]] = new util.HashMap[String, util.Set[String]]
    val characterToComic: util.Map[String, util.Set[String]] = new util.HashMap[String, util.Set[String]]
    val characters: util.Set[String] = new util.HashSet[String]
    val creationQueue: BlockingQueue[Runnable] = new LinkedBlockingQueue[Runnable]
    try {
      val reader: CSVReader = new CSVReader(new InputStreamReader(resource.openStream))
      try {
        var nextLine: Array[String] = null
        while ((({
          nextLine = reader.readNext;
          nextLine
        })) != null && line < rowsToLoad) {
          line += 1
          val comicBook: String = nextLine(1)
          val characterNames: Array[String] = nextLine(0).split("/")
          if (!comicToCharacter.containsKey(comicBook)) {
            comicToCharacter.put(comicBook, new util.HashSet[String])
          }
          val comicCharacters: List[String] = characterNames.toList
          comicToCharacter.get(comicBook).addAll(comicCharacters)
          characters.addAll(comicCharacters)
        }
      } finally {
        if (reader != null) reader.close()
      }
    }
    for (character <- characters) {
      creationQueue.add(new CharacterCreationCommand(graph, character))
    }
    val appearedQueue: BlockingQueue[Runnable] = new LinkedBlockingQueue[Runnable]
    for (comicBook <- comicToCharacter.keySet) {
      creationQueue.add(new ComicBookCreationCommand(graph, comicBook))
      val comicCharacters: util.Set[String] = comicToCharacter.get(comicBook)
      for (character <- comicCharacters) {
        val lineCommand: AppearedCommand = new AppearedCommand(graph, new Appeared(character, comicBook))
        appearedQueue.add(lineCommand)
        if (!characterToComic.containsKey(character)) {
          characterToComic.put(character, new util.HashSet[String])
        }
        characterToComic.get(character).add(comicBook)
      }
      REGISTRY.histogram("histogram.comic-to-character").update(comicCharacters.size)
    }
    var maxAppearances: Int = 0
    var maxCharacter: String = ""
    for (character <- characterToComic.keySet) {
      val comicBookSet: util.Set[String] = characterToComic.get(character)
      val numberOfAppearances: Int = comicBookSet.size
      REGISTRY.histogram("histogram.character-to-comic").update(numberOfAppearances)
      if (numberOfAppearances > maxAppearances) {
        maxCharacter = character
        maxAppearances = numberOfAppearances
      }
    }
    LOG.info("Character {} has most appearances at {}", maxCharacter, maxAppearances)
    var executor: ExecutorService = Executors.newFixedThreadPool(POOL_SIZE)

    {
      var i: Int = 0
      while (i < POOL_SIZE) {
        {
          executor.execute(new BatchCommand(graph, creationQueue))
        }
        ({
          i += 1;
          i - 1
        })
      }
    }
    executor.shutdown
    while (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
      LOG.info("Awaiting:" + creationQueue.size)
      if (report) {
        REPORTER.report()
      }
    }
    executor = Executors.newSingleThreadExecutor
    executor.execute(new BatchCommand(graph, appearedQueue))
    executor.shutdown
    while (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
      LOG.info("Awaiting:" + appearedQueue.size)
      if (report) {
        REPORTER.report()
      }
    }
    LOG.info("MarvelGraphFactory.load complete")
  }

  protected def processLine(graph: TitanGraph, appeared: Appeared) {
    val start: Long = System.currentTimeMillis
    process(graph, appeared)
    val end: Long = System.currentTimeMillis
    val time: Long = end - start
    REGISTRY.timer(TIMER_LINE).update(time, TimeUnit.MILLISECONDS)
  }

  private def process(graph: TitanGraph, appeared: Appeared) {
    var comicBookVertex: Vertex = get(graph, COMIC_BOOK, appeared.getComicBook)
    if (null == comicBookVertex) {
      REGISTRY.counter("error.missingComicBook." + appeared.getComicBook).inc
      comicBookVertex = graph.addVertex()
      comicBookVertex.property(COMIC_BOOK, appeared.getComicBook)
    }
    var characterVertex: Vertex = get(graph, CHARACTER, appeared.getCharacter)
    if (null == characterVertex) {
      REGISTRY.counter("error.missingCharacter." + appeared.getCharacter).inc
      characterVertex = graph.addVertex()
      characterVertex.property(CHARACTER, appeared.getCharacter)
      characterVertex.property(WEAPON, WEAPONS(RANDOM.nextInt(WEAPONS.length)))
    }
    characterVertex.addEdge(APPEARED, comicBookVertex)
  }

  private def get(graph: TitanGraph, key: String, value: String): Vertex = {
    val g: GraphTraversalSource = graph.traversal
    val it: java.util.Iterator[Vertex] = g.V().has(key, value)
    return if (it.hasNext) it.next else null
  }


  case class Appeared(val character: String, val comicBook: String) {
    def getCharacter: String = {
      return character
    }

    def getComicBook: String = {
      return comicBook
    }
  }

  class BatchCommand(val graph: TitanGraph = null, val commands: BlockingQueue[Runnable] = null) extends Runnable {
    def run {
      var i: Int = 0
      var command: Runnable = null
      while ((({
        command = commands.poll;
        command
      })) != null) {
        try {
          command.run
        }
        catch {
          case e: Throwable => {
            val rootCause: Throwable = ExceptionUtils.getRootCause(e)
            val rootCauseMessage: String = if (null == rootCause) "" else rootCause.getMessage
            LOG.error("Error processing comic book {} {}", e.getMessage, rootCauseMessage, e)
          }
        }
        if (({
          i += 1;
          i - 1
        }) % BATCH_SIZE == 0) {
          try {
            graph.tx.commit
          }
          catch {
            case e: Throwable => {
              // LOG.error("error processing commit {} {}", e.getMessage(), ExceptionUtils.getRootCause(e).getMessage())
              println("error processing commit %s %s", e.getMessage(), ExceptionUtils.getRootCause(e).getMessage())
            }
          }
        }
      }
      try {
        graph.tx.commit
      }
      catch {
        case e: Throwable => {
          // LOG.error("error processing commit {} {}", e.getMessage, ExceptionUtils.getRootCause(e).getMessage)
          println("error processing commit %s %s", e.getMessage, ExceptionUtils.getRootCause(e).getMessage)
        }
      }
    }
  }

  object ComicBookCreationCommand {
    private def createComicBook(graph: TitanGraph, value: String): Vertex = {
      val start: Long = System.currentTimeMillis
      val vertex: Vertex = graph.addVertex()
      vertex.property(COMIC_BOOK, value)
      REGISTRY.counter(COUNTER_GET + COMIC_BOOK).inc
      val end: Long = System.currentTimeMillis
      val time: Long = end - start
      REGISTRY.timer(TIMER_CREATE + COMIC_BOOK).update(time, TimeUnit.MILLISECONDS)
      return vertex
    }
  }

  class ComicBookCreationCommand(final val graph: TitanGraph = null, final val comicBook: String = null) extends Runnable {
    def run {
      ComicBookCreationCommand.createComicBook(graph, comicBook)
    }
  }

  object CharacterCreationCommand {
    private def createCharacter(graph: TitanGraph, value: String): Vertex = {
      val start: Long = System.currentTimeMillis
      val vertex: Vertex = graph.addVertex()
      vertex.property(CHARACTER, value)
      vertex.property(WEAPON, WEAPONS(RANDOM.nextInt(WEAPONS.length)))
      REGISTRY.counter(COUNTER_GET + CHARACTER).inc
      val end: Long = System.currentTimeMillis
      val time: Long = end - start
      REGISTRY.timer(TIMER_CREATE + CHARACTER).update(time, TimeUnit.MILLISECONDS)
      return vertex
    }
  }

  class CharacterCreationCommand(graph: TitanGraph = null, val character: String = null) extends Runnable {
    def run {
      CharacterCreationCommand.createCharacter(graph, character)
    }
  }

  class AppearedCommand(val graph: TitanGraph = null, appeared: Appeared = null) extends Runnable {
    def run {
      try {
        processLine(graph, appeared)
      }
      catch {
        case e: Throwable => {
          val rootCause: Throwable = ExceptionUtils.getRootCause(e)
          val rootCauseMessage: String = if (null == rootCause) "" else rootCause.getMessage
          LOG.error("Error processing line {} {}", e.getMessage, rootCauseMessage, e)
        }
      } finally {
        COMPLETED_TASK_COUNT.incrementAndGet
      }
    }
  }

}

