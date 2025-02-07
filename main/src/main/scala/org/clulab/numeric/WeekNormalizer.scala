package org.clulab.numeric

import java.io.File
import java.time.{Month, YearMonth}

import org.clulab.sequences.CommentedStandardKbSource
import org.clulab.utils.Sourcer

import scala.collection.mutable
import scala.io.Source
import scala.util.Using

class WeekNormalizer(weekPath: String) {
  val normMapper = WeekNormalizer.readNormsFromResource(weekPath)

  /** Normalizes seasons */
  def norm(text: Seq[String]): Option[WeekRange] = {
    val week = text.mkString(" ").toLowerCase()

    normMapper.get(week)
  }
}

object WeekNormalizer {

  def readNormsFromResource(path: String): Map[String, WeekRange] = {
    val customResourcePath = new File(NumericEntityRecognizer.resourceDir, path)

    if (customResourcePath.exists)
      Using.resource(Sourcer.sourceFromFile(customResourcePath))(readNormsFromSource)
    else
      Using.resource(Sourcer.sourceFromResource(path))(readNormsFromSource)
  }

  def readNormsFromSource(source: Source): Map[String, WeekRange] = {
    val norms = new mutable.HashMap[String, WeekRange]()

    CommentedStandardKbSource.read(source) { (week, normOpt, unitClassOpt) =>
      assert(normOpt.isDefined) // We're insisting on this.

      val norm = normOpt.get.split("--").map(_.trim)
      val (start, end) = norm match {
        case Array(start, end) => (start, end)
        case _ => throw new RuntimeException(s"ERROR: incorrect date range in week file")
      }
      val startDay = getDay(start)
      val endDay = getDay(end)
      norms += week -> WeekRange(startDay, endDay)
    }
    norms.toMap
  }

  private def getDay(date: String): Option[Seq[String]] = {
    date.split("-") match {
      case Array(_, _, day) => Some(Seq(day))
      case _ => throw new RuntimeException(s"ERROR: incorrect date value in week file: $date")
    }
  }
}

case class WeekRange(startDay: Option[Seq[String]],
                     endDay: Option[Seq[String]])