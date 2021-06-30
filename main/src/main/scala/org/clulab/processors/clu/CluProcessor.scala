package org.clulab.processors.clu

import com.typesafe.config.{Config, ConfigFactory}
import org.clulab.dynet.{AnnotatedSentence, ConstEmbeddingParameters, ConstEmbeddingsGlove, Metal}
import org.clulab.processors.{Document, IntermediateDocumentAttachment, Processor, Sentence}
import org.clulab.processors.clu.CluProcessor._
import org.clulab.processors.clu.tokenizer._
import org.clulab.struct.{DirectedGraph, Edge, GraphMap}
import org.clulab.utils.{BeforeAndAfter, Configured, DependencyUtils, ScienceUtils, ToEnhancedDependencies, ToEnhancedSemanticRoles, SeqUtils}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/** This is the per sentence information necessary for NER. */
case class NerInput(
  words: Array[String],
  lemmas: Option[Array[String]],
  tags: Array[String],
  startCharOffsets: Array[Int],
  endCharOffsets: Array[Int]
)

object NerInput {

  def apply(sentence: Sentence): NerInput = NerInput(
    sentence.words,
    sentence.lemmas,
    sentence.tags.get,
    sentence.startOffsets,
    sentence.endOffsets
  )
}

/** Again this is per sentence so that it can be grouped for multiple sentences. */
case class NerOutput(
  labels: IndexedSeq[String],
  normsOpt: Option[IndexedSeq[String]]
)

object NerOutput {

  def apply(labelses: IndexedSeq[IndexedSeq[String]]): NerOutput = NerOutput(labelses.head, None)
}

/** Again this is per sentence so that it can be grouped for multiple sentences. */
case class TagOutput(
  tags: IndexedSeq[String],
  chunks: IndexedSeq[String],
  preds: IndexedSeq[String]
)

case class SrlInput(
  words: Array[String],
  tags: Array[String],
  entities: Array[String],
  predicateIndexes: IndexedSeq[Int]
)

object SrlInput {
  def apply(sentence: Sentence, predicateIndexes: IndexedSeq[Int]): SrlInput =
    new SrlInput(sentence.words, sentence.tags.get, sentence.entities.get, predicateIndexes)
}

/**
  * Processor that uses only tools that are under Apache License
  * Currently supports:
  *   tokenization (in-house),
  *   lemmatization (Morpha, copied in our repo to minimize dependencies),
  *   POS tagging, NER, chunking, dependency parsing - using our MTL architecture (dep parsing coming soon)
  */
class CluProcessor (val config: Config = ConfigFactory.load("cluprocessor")) extends Processor with Configured {

  override def getConf: Config = config

  // should we intern strings or not?
  val internStrings:Boolean = getArgBoolean(s"$prefix.internStrings", Some(false))

  // This strange construction is designed to allow subclasses access to the value of the tokenizer while
  // at the same time allowing them to override the value.
  // val tokenizer: Tokenizer = new ModifiedTokenizer(super.tokenizer)
  // does not work in a subclass because super.tokenizer is invalid.  Instead it needs to be something like
  // val tokenizer: Tokenizer = new ModifiedTokenizer(localTokenizer)
  protected lazy val localTokenizer: Tokenizer = getArgString(s"$prefix.language", Some("EN")) match {
    case "PT" => new OpenDomainPortugueseTokenizer
    case "ES" => new OpenDomainSpanishTokenizer
    case _ => new OpenDomainEnglishTokenizer
  }

  // the actual tokenizer
  lazy val tokenizer: Tokenizer = localTokenizer

  // the lemmatizer
  lazy val lemmatizer: Lemmatizer = getArgString(s"$prefix.language", Some("EN")) match {
    case "PT" => new PortugueseLemmatizer
    case "ES" => new SpanishLemmatizer
    case _ => new EnglishLemmatizer
  }

  // one of the multi-task learning (MTL) models, which covers: POS, chunking, and SRL (predicates)
  lazy val mtlPosChunkSrlp: Metal = getArgString(s"$prefix.language", Some("EN")) match {
    case "PT" => throw new RuntimeException("PT model not trained yet") // Add PT
    case "ES" => throw new RuntimeException("ES model not trained yet") // Add ES
    case _ => Metal(getArgString(s"$prefix.mtl-pos-chunk-srlp", Some("mtl-en-pos-chunk-srlp")))
  }

  // one of the multi-task learning (MTL) models, which covers: NER
  lazy val mtlNer: Metal = getArgString(s"$prefix.language", Some("EN")) match {
    case "PT" => throw new RuntimeException("PT model not trained yet") // Add PT
    case "ES" => throw new RuntimeException("ES model not trained yet") // Add ES
    case _ => Metal(getArgString(s"$prefix.mtl-ner", Some("mtl-en-ner")))
  }

  // one of the multi-task learning (MTL) models, which covers: SRL (arguments)
  lazy val mtlSrla: Metal = getArgString(s"$prefix.language", Some("EN")) match {
    case "PT" => throw new RuntimeException("PT model not trained yet") // Add PT
    case "ES" => throw new RuntimeException("ES model not trained yet") // Add ES
    case _ => Metal(getArgString(s"$prefix.mtl-srla", Some("mtl-en-srla")))
  }

  /*
  lazy val mtlDepsHead: Metal = getArgString(s"$prefix.language", Some("EN")) match {
    case "PT" => throw new RuntimeException("PT model not trained yet") // Add PT
    case "ES" => throw new RuntimeException("ES model not trained yet") // Add ES
    case _ => Metal(getArgString(s"$prefix.mtl-depsh", Some("mtl-en-depsh")))
  }

  lazy val mtlDepsLabel: Metal = getArgString(s"$prefix.language", Some("EN")) match {
    case "PT" => throw new RuntimeException("PT model not trained yet") // Add PT
    case "ES" => throw new RuntimeException("ES model not trained yet") // Add ES
    case _ => Metal(getArgString(s"$prefix.mtl-depsl", Some("mtl-en-depsl")))
  }
  */
  lazy val mtlDeps: Metal = getArgString(s"$prefix.language", Some("EN")) match {
    case "PT" => throw new RuntimeException("PT model not trained yet") // Add PT
    case "ES" => throw new RuntimeException("ES model not trained yet") // Add ES
    case _ => Metal(getArgString(s"$prefix.mtl-deps", Some("mtl-en-deps")))
  }

  // Although this uses no class members, the method is sometimes called from tests
  // and can't easily be moved to a separate class without changing client code.
  def mkConstEmbeddings(doc: Document): Unit = GivenConstEmbeddingsAttachment.mkConstEmbeddings(doc)

  case class SentenceAndIndex(sentence: Sentence, index: Int)
  // This maps sentence length to the (sentence object, index in document).
  type GroupsOfSentencesAndIndexes = Map[Int, Array[SentenceAndIndex]]

  // TODO: Make this just sentences and indexes, i.e., take values at end
  def groupSentences(doc: Document): GroupsOfSentencesAndIndexes = doc.sentences
      .zipWithIndex
      .map { case (sentence, index) => SentenceAndIndex(sentence, index) }
      .groupBy(_.sentence.words.length)

  def toAnnotatedSentence(sentence: Sentence): AnnotatedSentence =
      AnnotatedSentence(sentence.words, sentence.tags.map(_.toIndexedSeq), sentence.entities.map(_.toIndexedSeq))

  override def annotate(doc:Document): Document = {
    val groupsOfSentencesAndIndexes = groupSentences(doc) // Do this just once.
    val isWorthIt = groupsOfSentencesAndIndexes.size.toFloat / doc.sentences.length >= 0.7f

    GivenConstEmbeddingsAttachment(doc).perform {
      if (isWorthIt) annotateBySentences(doc, groupsOfSentencesAndIndexes)
      else annotateBySentence(doc)
      doc.clear()
      doc
    }
  }

  def annotateBySentence(doc: Document): Unit = {
    tagPartsOfSpeech(doc)
    // The above has the side-effect of adding a predicate attachment that srl() needs.
    GivenExistingPredicateAttachment(doc).perform {
      recognizeNamedEntities(doc) // the call to the NER MTL is in here
      chunking(doc) // Nothing, kept for the record
      parse(doc) // dependency parsing
      lemmatize(doc) // lemmatization has access to POS tags, which are needed in some languages
      srl(doc) // SRL (arguments)
    }
    // these are not implemented yet
    resolveCoreference(doc)
    discourse(doc)
  }

  def annotateBySentences(doc: Document, groupsOfSentencesAndIndexes: GroupsOfSentencesAndIndexes): Unit = {
    tagPartsOfSpeech(doc, groupsOfSentencesAndIndexes) // the call to the POS/chunking/SRLp MTL is in here
    // The above has the side-effect of adding a predicate attachment that srl() needs.
    GivenExistingPredicateAttachment(doc).perform {
      recognizeNamedEntities(doc, groupsOfSentencesAndIndexes) // the call to the NER MTL is in here
      chunking(doc, groupsOfSentencesAndIndexes) // Nothing, kept for the record
      parse(doc, groupsOfSentencesAndIndexes) // dependency parsing
      lemmatize(doc) // lemmatization has access to POS tags, which are needed in some languages
      srl(doc, groupsOfSentencesAndIndexes) // SRL (arguments)
    }
    // these are not implemented yet
    resolveCoreference(doc)
    discourse(doc)
  }

  /** Constructs a document of tokens from free text; includes sentence splitting and tokenization */
  def mkDocument(text:String, keepText:Boolean = false): Document = {
    CluProcessor.mkDocument(tokenizer, text, keepText)
  }

  /** Constructs a document of tokens from an array of untokenized sentences */
  def mkDocumentFromSentences(sentences:Iterable[String],
                              keepText:Boolean = false,
                              charactersBetweenSentences:Int = 1): Document = {
    CluProcessor.mkDocumentFromSentences(tokenizer, sentences, keepText, charactersBetweenSentences)
  }

  /** Constructs a document of tokens from an array of tokenized sentences */
  def mkDocumentFromTokens(sentences:Iterable[Iterable[String]],
                           keepText:Boolean = false,
                           charactersBetweenSentences:Int = 1,
                           charactersBetweenTokens:Int = 1): Document = {
    CluProcessor.mkDocumentFromTokens(tokenizer, sentences, keepText, charactersBetweenSentences, charactersBetweenTokens)
  }

  class PredicateAttachment(val predicates: IndexedSeq[IndexedSeq[Int]]) extends IntermediateDocumentAttachment

  /** Produces POS tags, chunks, and semantic role predicates for one sentence */
  def tagSentence(words: IndexedSeq[String], embeddings: ConstEmbeddingParameters): TagOutput = {
    val allLabels = mtlPosChunkSrlp.predictJointly(AnnotatedSentence(words), embeddings)
    val tags = allLabels(0)
    val chunks = allLabels(1)
    val preds = allLabels(2)
    TagOutput(tags, chunks, preds)
}

  def tagSentences(wordses: Array[Array[String]], embeddings: ConstEmbeddingParameters): Array[TagOutput] = {
    val annotatedSentences = wordses.map(AnnotatedSentence(_))
    val allLabelses = mtlPosChunkSrlp.predictJointly(annotatedSentences, embeddings)
    allLabelses.map { allLabels => TagOutput(tags = allLabels(0), chunks = allLabels(1), preds = allLabels(2)) }
  }

  /** Produces NE labels for one sentence */
  def nerSentence(words: Array[String],
                  lemmas: Option[Array[String]],
                  tags: Array[String], // this are only used by the NumericEntityRecognizer
                  startCharOffsets: Array[Int],
                  endCharOffsets: Array[Int],
                  docDateOpt: Option[String],
                  embeddings: ConstEmbeddingParameters): (IndexedSeq[String], Option[IndexedSeq[String]]) = {
    val nerInput = NerInput(words, lemmas, tags, startCharOffsets, endCharOffsets)
    val nerOutputs = nerSentences(Array(nerInput), docDateOpt, embeddings)

    (nerOutputs.head.labels, None)
  }

  /** Produces NE labels for multiple sentences which have been converted into NerInput.
    * It is required that the number of words in each sentence is the same.
    */
  def nerSentences(nerInputs: Array[NerInput], docDateOpt: Option[String], embeddings: ConstEmbeddingParameters): Array[NerOutput] = {
    val annotatedSentences = nerInputs.map { nerInput => AnnotatedSentence(nerInput.words) }
    val predictions = mtlNer.predictJointly(annotatedSentences, embeddings)
    val nerOutputs = predictions.map(NerOutput(_))

    nerOutputs
  }

  /** Gets the index of all predicates in this sentence */
  def getPredicateIndexes(preds: IndexedSeq[String]): IndexedSeq[Int] =
    SeqUtils.indexesOf(preds, "B-P")

  def parseSentence(annotatedSentence: AnnotatedSentence,
                    embeddings: ConstEmbeddingParameters): DirectedGraph[String] = {


    //println(s"Words: ${words.mkString(", ")}")
    //println(s"Tags: ${posTags.mkString(", ")}")
    //println(s"NEs: ${nerLabels.mkString(", ")}")

    val headsAndLabels: IndexedSeq[(Int, String)] = mtlDeps.parse(annotatedSentence, embeddings)
    val directedGraph = newDirectedGraph(headsAndLabels)

    //
    // Old algorithm, with separate models for heads and labels
    //
    /*
    val headsAsStringsWithScores = mtlDeps.predictWithScores(0, annotatedSentence, embeddings)
    val heads = new ArrayBuffer[Int]()
    for(wi <- headsAsStringsWithScores.indices) {
      val predictionsForThisWord = headsAsStringsWithScores(wi)

      // pick the prediction with the highest score, which makes sense for the current sentence
      var done = false
      for(hi <- predictionsForThisWord.indices if ! done) {
        try {
          val relativeHead = predictionsForThisWord(hi)._1.toInt
          if (relativeHead == 0) { // this is the root
            heads += -1
            done = true
          } else {
            val headPosition = wi + relativeHead
            if (headPosition >= 0 && headPosition < words.size) {
              heads += headPosition
              done = true
            }
          }
        } catch {
          // some valid predictions may not be integers, e.g., "<STOP>" may be predicted by the sequence model
          case e: NumberFormatException => done = false
        }
      }
      if(! done) {
        // we should not be here, but let's be safe
        // if nothing good was found, assume root
        heads += -1
      }
    }

    val annotatedSentenceWithHeads =
      AnnotatedSentence(words, Some(posTags), Some(nerLabels), Some(heads))

    val labels = mtlDepsLabel.predict(0, annotatedSentenceWithHeads, embeddings)
    assert(labels.size == heads.size)
    //println(s"Labels: ${labels.mkString(", ")}")

    val edges = new ListBuffer[Edge[String]]()
    val roots = new mutable.HashSet[Int]()

    for(i <- heads.indices) {
      if(heads(i) == -1) {
        roots += i
      } else {
        val edge = Edge[String](heads(i), i, labels(i))
        edges.append(edge)
      }
    }
    */

    directedGraph
  }

  def newDirectedGraph(headsAndLabels: IndexedSeq[(Int, String)]): DirectedGraph[String] = {
    val headGroups = headsAndLabels.zipWithIndex.groupBy { case ((head, _), _) => head == -1 }
    val roots = headGroups(true).map { case (_, index) => index }
    val edges = headGroups(false).map { case ((head, label), index) => Edge[String](head, index, label) }

    new DirectedGraph[String](edges.toList, roots.toSet)
  }

  /** Dependency parsing */
  def parseSentence(
    annotatedSentences: Array[AnnotatedSentence],
    embeddings: ConstEmbeddingParameters
  ): Array[DirectedGraph[String]] = {
    val headsAndLabelses: Array[IndexedSeq[(Int, String)]] = mtlDeps.parse(annotatedSentences, embeddings)
    val directedGraphs = headsAndLabelses.map(newDirectedGraph)

    directedGraphs
  }

  def srlSentence(sent: Sentence,
                  predicateIndexes: IndexedSeq[Int],
                  embeddings: ConstEmbeddingParameters): DirectedGraph[String] = {
    srlSentence(SrlInput(sent, predicateIndexes), embeddings)
  }

  /** Produces semantic role frames for one sentence */
  def srlSentence(srlInput: SrlInput, embeddings: ConstEmbeddingParameters): DirectedGraph[String] = {
    val edges = srlInput.predicateIndexes.flatMap { pred => // This must be done for every single predicate.
      // SRL needs POS tags and NEs, as well as the position of the predicate.
      val headPositions = Array.fill(srlInput.words.length)(pred) // Fill with the single value?
      val annotatedSentence =
        AnnotatedSentence(srlInput.words, Some(srlInput.tags), Some(srlInput.entities), Some(headPositions))
      val argLabels = mtlSrla.predict(0, annotatedSentence, embeddings)
      val predEdges = argLabels.zipWithIndex
          .filter { case (argLabel, _) => argLabel != "O" }
          .map { case (argLabel, index) => Edge[String](pred, index, argLabel) }

      predEdges
    }

    // All predicates become roots.
    new DirectedGraph[String](edges.toList, roots = srlInput.predicateIndexes.toSet, Some(srlInput.words.length))
  }

  // TODO
  def srlSentence(arlInputs: Array[SrlInput], embeddings: ConstEmbeddingParameters): Array[DirectedGraph[String]] = {
    null
  }

  private def hasEmbeddings(doc: Document): Boolean =
    doc.getAttachment(CONST_EMBEDDINGS_ATTACHMENT_NAME).isDefined

  private def getEmbeddings(doc: Document): ConstEmbeddingParameters =
    doc.getAttachment(CONST_EMBEDDINGS_ATTACHMENT_NAME).get.asInstanceOf[EmbeddingsAttachment].embeddings

  private def hasPredicates(doc: Document): Boolean =
    doc.getAttachment(PREDICATE_ATTACHMENT_NAME).isDefined

  private def getPredicates(doc: Document): IndexedSeq[IndexedSeq[Int]] =
    doc.getAttachment(PREDICATE_ATTACHMENT_NAME).get.asInstanceOf[PredicateAttachment].predicates

  private def setPredicates(doc: Document, predicates: IndexedSeq[IndexedSeq[Int]]): Unit =
    doc.addAttachment(PREDICATE_ATTACHMENT_NAME, new PredicateAttachment(predicates))


  /** Part of speech tagging + chunking + SRL (predicates), jointly */
  override def tagPartsOfSpeech(doc:Document) {
    basicSanityCheck(doc)

    val embeddings = getEmbeddings(doc)
    val predsForAllSents = new ArrayBuffer[IndexedSeq[Int]]()

    for(sent <- doc.sentences) {
      val TagOutput(tags, chunks, preds) = tagSentence(sent.words, embeddings)
      sent.tags = Some(tags.toArray)
      sent.chunks = Some(chunks.toArray)
      predsForAllSents += getPredicateIndexes(preds)
    }

    // store the index of all predicates as a doc attachment
    setPredicates(doc, predsForAllSents)
  }

  def tagPartsOfSpeech(doc: Document, groupsOfSentencesAndIndexes: GroupsOfSentencesAndIndexes): Unit = {
    basicSanityCheck(doc)

    val embeddings = getEmbeddings(doc)
    // These preds need to stay in the same order as the sentences.
    val predsForAllSents: Array[IndexedSeq[Int]] = new Array(doc.sentences.length)
    groupsOfSentencesAndIndexes.values.foreach { sentencesAndIndexes =>
      val wordses = sentencesAndIndexes.map(_.sentence.words)
      val tagOutputs = tagSentences(wordses, embeddings)
      sentencesAndIndexes.zip(tagOutputs).foreach { case (SentenceAndIndex(sentence, index), TagOutput(tags, chunks, preds)) =>
        sentence.tags = Some(tags.toArray)
        sentence.chunks = Some(chunks.toArray)
        predsForAllSents(index) = getPredicateIndexes(preds)
      }
    }
    // store the index of all predicates as a doc attachment
    setPredicates(doc, predsForAllSents)
  }

  /** Lematization; modifies the document in place */
  override def lemmatize(doc:Document) {
    basicSanityCheck(doc)
    for(sent <- doc.sentences) {
      //println(s"Lemmatize sentence: ${sent.words.mkString(", ")}")
      val lemmas = new Array[String](sent.size)
      for(i <- sent.words.indices) {
        lemmas(i) = lemmatizer.lemmatizeWord(sent.words(i))

        // a lemma may be empty in some weird Unicode situations
        if(lemmas(i).isEmpty) {
          logger.debug(s"""WARNING: Found empty lemma for word #$i "${sent.words(i)}" in sentence: ${sent.words.mkString(" ")}""")
          lemmas(i) = sent.words(i).toLowerCase()
        }
      }
      sent.lemmas = Some(lemmas)
    }
  }

  /** Generates cheap lemmas with the word in lower case, for languages where a lemmatizer is not available */
  def cheapLemmatize(doc:Document) {
    basicSanityCheck(doc)
    for(sent <- doc.sentences) {
      val lemmas = sent.words.map(_.toLowerCase())
      sent.lemmas = Some(lemmas)
    }
  }

  /** NER; modifies the document in place */
  override def recognizeNamedEntities(doc:Document): Unit = {
    basicSanityCheck(doc)
    val embeddings = getEmbeddings(doc)
    val docDate = doc.getDCT
    for(sent <- doc.sentences) {
      val (labels, norms) = nerSentence(
        sent.words,
        sent.lemmas,
        sent.tags.get,
        sent.startOffsets,
        sent.endOffsets,
        docDate,
        embeddings)

      sent.entities = Some(labels.toArray)
      if(norms.nonEmpty) {
        sent.norms = Some(norms.get.toArray)
      }
    }
  }

  def recognizeNamedEntities(doc: Document, groupsOfSentencesAndIndexes: GroupsOfSentencesAndIndexes): Unit = {
    basicSanityCheck(doc)
    val embeddings = getEmbeddings(doc)
    groupsOfSentencesAndIndexes.values.foreach { sentencesAndIndexes: Array[SentenceAndIndex] =>
      val sentences = sentencesAndIndexes.map(_.sentence)
      val nerInputs = sentences.map(NerInput(_))
      val nerOutputs = nerSentences(nerInputs, doc.getDCT, embeddings)

      sentences.zip(nerOutputs).foreach { case (sentence, nerOutput) =>
        sentence.entities = Some(nerOutput.labels.toArray)
        val normsOpt = nerOutput.normsOpt
        if (normsOpt.nonEmpty)
          sentence.norms = Some(normsOpt.get.toArray)
      }
    }
  }

  private def hasDep(dependencies: Array[(Int, String)], label: String): Boolean = {
    for(d <- dependencies) {
      if (d._2 == label) {
        return true
      }
    }

    false
  }

  private def predicateCorrections(origPreds: IndexedSeq[Int], sentence: Sentence): IndexedSeq[Int] = {

    if(sentence.universalBasicDependencies.isEmpty) return origPreds
    if(sentence.tags.isEmpty) return origPreds

    val preds = origPreds.toSet
    val newPreds = new mutable.HashSet[Int]()
    newPreds ++= preds

    val outgoing = sentence.universalBasicDependencies.get.outgoingEdges
    val words = sentence.words
    val tags = sentence.tags.get

    for(i <- words.indices) {
      if(! preds.contains(i)) {
        // -ing NN with a compound outgoing dependency
        if(words(i).endsWith("ing") && tags(i).startsWith("NN") &&
           outgoing.length > i && hasDep(outgoing(i), "compound")) {
          newPreds += i
        }
      }
    }

    newPreds.toVector.sorted
  }

  protected def srlSanityCheck(doc: Document): Unit = {
    assert(hasPredicates(doc))
    assert(hasEmbeddings(doc))

    if (doc.sentences.length > 0) {
      val sentences = doc.sentences.head

      assert(sentences.tags.nonEmpty)
      assert(sentences.entities.nonEmpty)
      assert(sentences.universalBasicDependencies.nonEmpty)
    }

    assert(getPredicates(doc).length == doc.sentences.length)

    // If there are no predicates, a test sentence will not provoke the calling of srlSentence(), which
    // will prevent reference to the lazy val mtlSrla.  This means that the model will not be created
    // until later, possibly in a parallel processing situation which will result sooner or later and
    // under the right conditions in DyNet crashing.  Therefore, make preemptive reference to mtlSrla
    // here so that it is sure to be initialized, regardless of the priming sentence.
    assert(mtlSrla != null)
  }

  protected def addEnhancedSemanticRoles(sentence: Sentence, semanticRoles: DirectedGraph[String]): Unit = {
    // enhanced semantic roles need basic universal dependencies to be generated
    if (sentence.graphs.contains(GraphMap.UNIVERSAL_BASIC)) {
      val enhancedRoles = ToEnhancedSemanticRoles.generateEnhancedSemanticRoles(
          sentence, sentence.universalBasicDependencies.get, semanticRoles)
      sentence.graphs += GraphMap.ENHANCED_SEMANTIC_ROLES -> enhancedRoles
    }
  }

  protected def addHybridDependencies(sentence: Sentence): Unit = {
    // hybrid = universal enhanced + roles enhanced
    if (sentence.graphs.contains(GraphMap.UNIVERSAL_ENHANCED) &&
        sentence.graphs.contains(GraphMap.ENHANCED_SEMANTIC_ROLES)) {
      val mergedGraph = DependencyUtils.mergeGraphs(
          sentence.universalEnhancedDependencies.get, sentence.enhancedSemanticRoles.get)
      sentence.graphs += GraphMap.HYBRID_DEPENDENCIES -> mergedGraph
    }
  }

  protected def addUniversalEnhancedDependencies(sentence: Sentence, depGraph: DirectedGraph[String]): Unit = {
    val enhancedDepGraph = ToEnhancedDependencies.generateUniversalEnhancedDependencies(sentence, depGraph)
    sentence.graphs += GraphMap.UNIVERSAL_ENHANCED -> enhancedDepGraph
  }

  override def srl(doc: Document): Unit = {
    srlSanityCheck(doc)

    val docPredicates = getPredicates(doc)
    val embeddings = getEmbeddings(doc)

    // Generate SRL frames for each predicate in each sentence.
    doc.sentences.zip(docPredicates).foreach { case (sentence, predicates) =>
      val srlInput = {
        val predicateIndexes = predicateCorrections(predicates, sentence)
        SrlInput(sentence, predicateIndexes)
      }
      val semanticRoles = srlSentence(srlInput, embeddings)

      sentence.graphs += GraphMap.SEMANTIC_ROLES -> semanticRoles
      addEnhancedSemanticRoles(sentence, semanticRoles)
      addHybridDependencies(sentence)
    }
  }

  def srl(doc: Document, groupsOfSentencesAndIndexes: GroupsOfSentencesAndIndexes): Unit = {
    srlSanityCheck(doc)

    val docPredicates = getPredicates(doc)
    val embeddings = getEmbeddings(doc)

    groupsOfSentencesAndIndexes.zip(docPredicates).foreach { case ((_, sentencesAndIndexes), predicates) =>
      val sentences = sentencesAndIndexes.map(_.sentence)
      val srlInputs = sentences.map { sentence =>
        val predicateIndexes = predicateCorrections(predicates, sentence)
        SrlInput(sentence, predicateIndexes)
      }
      val semanticRoles: Array[DirectedGraph[String]] = srlSentence(srlInputs, embeddings)

      sentences.zip(semanticRoles).foreach { case (sentence, semanticRoles) =>
        sentence.graphs += GraphMap.SEMANTIC_ROLES -> semanticRoles
        addEnhancedSemanticRoles(sentence, semanticRoles)
        addHybridDependencies(sentence)
      }
    }
  }

  protected def parseSanityCheck(doc: Document): Unit = {
    if (doc.sentences.length > 0) {
      assert(doc.sentences(0).tags.nonEmpty)
      assert(doc.sentences(0).entities.nonEmpty)
    }
    assert(hasEmbeddings(doc))
  }

  /** Syntactic parsing; modifies the document in place */
  def parse(doc:Document): Unit = {
    parseSanityCheck(doc)

    val embeddings = getEmbeddings(doc)

    doc.sentences.foreach { sentence =>
      val annotatedSentence = toAnnotatedSentence(sentence)
      val depGraph = parseSentence(annotatedSentence, embeddings)

      sentence.graphs += GraphMap.UNIVERSAL_BASIC -> depGraph
      addUniversalEnhancedDependencies(sentence, depGraph)
    }
  }

  def parse(doc: Document, groupsOfSentencesAndIndexes: GroupsOfSentencesAndIndexes): Unit = {
    parseSanityCheck(doc)

    val embeddings = getEmbeddings(doc)

    groupsOfSentencesAndIndexes.foreach { case (_, sentencesAndIndexes) =>
      val annotatedSentences = sentencesAndIndexes.map { sentenceAndIndex => toAnnotatedSentence(sentenceAndIndex.sentence) }
      val depGraphs: Array[DirectedGraph[String]] = parseSentence(annotatedSentences, embeddings)

      sentencesAndIndexes.zip(depGraphs).foreach { case (SentenceAndIndex(sentence, index), depGraph) =>
        sentence.graphs += GraphMap.UNIVERSAL_BASIC -> depGraph
        addUniversalEnhancedDependencies(sentence, depGraph)
      }
    }
  }

  /** Shallow parsing; modifies the document in place */
  def chunking(doc:Document): Unit = {
    // Nop, covered by MTL
  }

  def chunking(doc: Document, groupsOfSentencesAndIndexes: GroupsOfSentencesAndIndexes): Unit = {
    // Nop, covered by MTL
  }

  /** Coreference resolution; modifies the document in place */
  def resolveCoreference(doc:Document) {
    // TODO. Implement me
  }

  /** Discourse parsing; modifies the document in place */
  def discourse(doc:Document) {
    // TODO. Implement me
  }

  /** Relation extraction; modifies the document in place. */
  override def relationExtraction(doc: Document): Unit = {
    // TODO. We will probably not include this.
  }

  def basicSanityCheck(doc:Document): Unit = {
    assert(!(doc.sentences == null), "ERROR: Document.sentences == null!")
    assert(!(doc.sentences.nonEmpty && doc.sentences.head.words == null), "ERROR: Sentence.words == null!")
    assert(hasEmbeddings(doc), "ERROR: Const embeddings not set!")
  }
}

trait SentencePostProcessor {
  def process(sentence: Sentence)
}

/** CluProcessor for Spanish */
class SpanishCluProcessor extends CluProcessor(config = ConfigFactory.load("cluprocessorspanish"))

/** CluProcessor for Portuguese */
class PortugueseCluProcessor extends CluProcessor(config = ConfigFactory.load("cluprocessorportuguese")) {

  val scienceUtils = new ScienceUtils

  /** Constructs a document of tokens from free text; includes sentence splitting and tokenization */
  override def mkDocument(text:String, keepText:Boolean = false): Document = {
    // FIXME by calling replaceUnicodeWithAscii we are normalizing unicode and keeping accented characters of interest,
    // but we are also replacing individual unicode characters with sequences of characters that can potentially be greater than one
    // which means we may lose alignment to the original text
    val textWithAccents = scienceUtils.replaceUnicodeWithAscii(text, keepAccents = true)
    CluProcessor.mkDocument(tokenizer, textWithAccents, keepText)
  }

  /** Lematization; modifies the document in place */
  override def lemmatize(doc:Document) {
    basicSanityCheck(doc)
    for(sent <- doc.sentences) {
      // check if sentence tags were defined
      // if not, generate cheap lemmas
      if (sent.tags.isDefined) {
        //println(s"Lemmatize sentence: ${sent.words.mkString(", ")}")
        val lemmas = new Array[String](sent.size)
        for (i <- sent.words.indices) {
          lemmas(i) = lemmatizer.lemmatizeWord(sent.words(i), Some(sent.tags.get(i)))

          // a lemma may be empty in some weird Unicode situations
          if(lemmas(i).isEmpty) {
            logger.debug(s"""WARNING: Found empty lemma for word #$i "${sent.words(i)}" in sentence: ${sent.words.mkString(" ")}""")
            lemmas(i) = sent.words(i).toLowerCase()
          }
        }
        sent.lemmas = Some(lemmas)
      } else {
        cheapLemmatize(doc)
      }
    }
  }

}

object CluProcessor {
  val logger:Logger = LoggerFactory.getLogger(classOf[CluProcessor])
  val prefix:String = "CluProcessor"

  val PREDICATE_ATTACHMENT_NAME = "predicates"

  val CONST_EMBEDDINGS_ATTACHMENT_NAME = "ce"

  /** Constructs a document of tokens from free text; includes sentence splitting and tokenization */
  def mkDocument(tokenizer:Tokenizer,
                 text:String,
                 keepText:Boolean): Document = {
    val sents = tokenizer.tokenize(text)
    val doc = new Document(sents)
    if(keepText) doc.text = Some(text)
    doc
  }

  /** Constructs a document of tokens from an array of untokenized sentences */
  def mkDocumentFromSentences(tokenizer:Tokenizer,
                              sentences:Iterable[String],
                              keepText:Boolean,
                              charactersBetweenSentences:Int): Document = {
    val sents = new ArrayBuffer[Sentence]()
    var characterOffset = 0
    for(text <- sentences) {
      val sent = tokenizer.tokenize(text, sentenceSplit = false).head // we produce a single sentence here!

      // update character offsets between sentences
      for(i <- 0 until sent.size) {
        sent.startOffsets(i) += characterOffset
        sent.endOffsets(i) += characterOffset
      }

      // move the character offset after the current sentence
      characterOffset = sent.endOffsets.last + charactersBetweenSentences

      //println("SENTENCE: " + sent.words.mkString(", "))
      //println("Start offsets: " + sent.startOffsets.mkString(", "))
      //println("End offsets: " + sent.endOffsets.mkString(", "))
      sents += sent
    }
    val doc = new Document(sents.toArray)
    if(keepText) doc.text = Some(sentences.mkString(mkSep(charactersBetweenSentences)))
    doc
  }

  /** Constructs a document of tokens from an array of tokenized sentences */
  def mkDocumentFromTokens(tokenizer:Tokenizer,
                           sentences:Iterable[Iterable[String]],
                           keepText:Boolean,
                           charactersBetweenSentences:Int,
                           charactersBetweenTokens:Int): Document = {
    var charOffset = 0
    var sents = new ArrayBuffer[Sentence]()
    val text = new StringBuilder
    for(sentence <- sentences) {
      val startOffsets = new ArrayBuffer[Int]()
      val endOffsets = new ArrayBuffer[Int]()
      for(word <- sentence) {
        startOffsets += charOffset
        charOffset += word.length
        endOffsets += charOffset
        charOffset += charactersBetweenTokens
      }
      // note: NO postprocessing happens in this case, so use it carefully!
      sents += new Sentence(sentence.toArray, startOffsets.toArray, endOffsets.toArray, sentence.toArray)
      charOffset += charactersBetweenSentences - charactersBetweenTokens
      if(keepText) {
        text.append(sentence.mkString(mkSep(charactersBetweenTokens)))
        text.append(mkSep(charactersBetweenSentences))
      }
    }

    val doc = new Document(sents.toArray)
    if(keepText) doc.text = Some(text.toString)
    doc
  }

  private def mkSep(size:Int):String = {
    val os = new mutable.StringBuilder
    for (_ <- 0 until size) os.append(" ")
    os.toString()
  }
}

case class EmbeddingsAttachment(embeddings: ConstEmbeddingParameters)
    extends IntermediateDocumentAttachment

class GivenConstEmbeddingsAttachment(doc: Document) extends BeforeAndAfter {

  def before(): Unit = GivenConstEmbeddingsAttachment.mkConstEmbeddings(doc)

  def after(): Unit = {
    val attachment = doc.getAttachment(CONST_EMBEDDINGS_ATTACHMENT_NAME).get.asInstanceOf[EmbeddingsAttachment]
    doc.removeAttachment(CONST_EMBEDDINGS_ATTACHMENT_NAME)

    // This is a memory management optimization.
    val embeddings = attachment.embeddings
    // FatDynet needs to be updated before these can be used.
    embeddings.lookupParameters.close()
    embeddings.collection.close()
  }
}

object GivenConstEmbeddingsAttachment {
  def apply(doc: Document) = new GivenConstEmbeddingsAttachment(doc)

  // This is static so that it can be called without an object.
  def mkConstEmbeddings(doc: Document): Unit = {
    // Fetch the const embeddings from GloVe. All our models need them.
    val embeddings = ConstEmbeddingsGlove.mkConstLookupParams(doc)
    val attachment = EmbeddingsAttachment(embeddings)

    // Now set them as an attachment, so they are available to all downstream methods wo/ changing the API.
    doc.addAttachment(CONST_EMBEDDINGS_ATTACHMENT_NAME, attachment)
  }
}

class GivenExistingPredicateAttachment(doc: Document) extends BeforeAndAfter {

  def before(): Unit = ()

  def after(): Unit = {
    doc.removeAttachment(PREDICATE_ATTACHMENT_NAME)
  }
}

object GivenExistingPredicateAttachment {

  def apply(doc: Document) = new GivenExistingPredicateAttachment(doc)
}
