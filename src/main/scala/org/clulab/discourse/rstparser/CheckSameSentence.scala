package org.clulab.discourse.rstparser

import org.clulab.processors.Document

import com.typesafe.scalalogging.LazyLogging

/**
 * Verifies is segments within a sentence are only connected to other segments in the same sentence
 * User: mihais
 * Date: 6/24/14
 */
object CheckSameSentence extends LazyLogging {
  def main(args:Array[String]) {
    val trees = CacheReader.load(args(0), CacheReader.CORENLP_PROCESSOR)
    var inc = 0
    var total = 0
    for(td <- trees) {
      val (i, t) = checkTree(td._1, td._2)
      inc += i
      total += t
    }
    logger.info(s"Found $inc inconsistencies out of $total attachments.")
  }

  def checkTree(tree:DiscourseTree, doc:Document, verbose:Boolean = false):(Int, Int) = {
    var inc = 0
    var total = 0
    if(! tree.isTerminal) {
      val l = tree.children(0)
      val r = tree.children(1)

      if(withinSentence(l, doc)) {
        if(withinSentence(r, doc) && l.firstSentence == r.lastSentence) {
          // we're good
        } else {
          inc += 1
          if(verbose) {
            logger.info("INCONSISTENCY FOR LEFT:")
            logger.info(s"LEFT:\n$l\nRIGHT:$r")
          }
        }
      }
      if(withinSentence(r, doc)) {
        if(withinSentence(l, doc) && l.firstSentence == r.lastSentence) {
          // we're good
        } else {
          inc += 1
          if(verbose) {
            logger.info("INCONSISTENCY FOR RIGHT:")
            logger.info(s"LEFT:\n$l\nRIGHT:$r")
          }
        }
      }

      total = 1
      for(c <- tree.children) {
        val (i, t) = checkTree(c, doc)
        inc += i
        total += t
      }
    }

    (inc, total)
  }

  def withinSentence(tree:DiscourseTree, doc:Document):Boolean = {
    if(tree.firstSentence == tree.lastSentence &&
      (tree.firstToken.token > 0 || tree.lastToken.token < doc.sentences(tree.lastSentence).size - 1)) {
      true
    } else false
  }
}
