package org.scalafmt

import java.io.File

import org.scalatest.funsuite.AnyFunSuite
import org.scalafmt.Error.{Incomplete, SearchStateExploded}
import org.scalafmt.util._
import org.scalatest.{BeforeAndAfterAllConfigMap, ConfigMap}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.meta.Tree
import scala.meta.parsers.Parse
// TODO(olafur) property test: same solution without optimization or timeout.

class FormatTests
    extends AnyFunSuite
    with CanRunTests
    with FormatAssertions
    with DiffAssertions
    with BeforeAndAfterAllConfigMap {
  import LoggerOps._
  lazy val onlyUnit = UnitTests.tests.exists(_.only)
  lazy val onlyManual = !onlyUnit && ManualTests.tests.exists(_.only)
  lazy val onlyOne = tests.exists(_.only)

  override def ignore(t: DiffTest): Boolean = false

  override val tests = {
    if (onlyManual) ManualTests.tests
    else UnitTests.tests
  }

  tests
    .sortWith(bySpecThenName)
    .withFilter(testShouldRun)
    .foreach(runTest(run))

  def run(t: DiffTest, parse: Parse[_ <: Tree]): Unit = {
    val runner = scalafmtRunner(t.style.runner).copy(parser = parse)
    val obtained = Scalafmt.formatCode(
      t.original,
      t.style.copy(runner = runner),
      filename = t.filename
    ) match {
      case Formatted.Failure(e)
          if t.style.onTestFailure.nonEmpty && e.getMessage.contains(
            e.getMessage
          ) =>
        t.expected
      case Formatted.Failure(e: Incomplete) => e.formattedCode
      case Formatted.Failure(e: SearchStateExploded) =>
        logger.elem(e)
        e.partialOutput
      case x => x.get
    }
    debugResults += saveResult(t, obtained, onlyOne)
    if (t.style.rewrite.rules.isEmpty &&
      !t.style.assumeStandardLibraryStripMargin &&
      t.style.onTestFailure.isEmpty) {
      assertFormatPreservesAst(t.original, obtained)(
        parse,
        t.style.runner.dialect
      )
    }
    val formattedAgain = Scalafmt
      .formatCode(
        obtained,
        t.style.copy(runner = runner),
        filename = t.filename
      )
      .get
//          getFormatOutput(t.style, true) // uncomment to debug
    assertNoDiff(formattedAgain, obtained, "Idempotency violated")
    if (!onlyManual) {
      assertNoDiff(obtained, t.expected)
      Debug.newTest()
    }
  }

  def testShouldRun(t: DiffTest): Boolean = !onlyOne || t.only

  def bySpecThenName(left: DiffTest, right: DiffTest): Boolean = {
    import scala.math.Ordered.orderingToOrdered
    (left.spec, left.name).compare(right.spec -> right.name) < 0
  }

  override def afterAll(configMap: ConfigMap): Unit = {
    val splits = Debug.enqueuedSplits
      .groupBy(_.line.value)
      .toVector
      .sortBy(-_._2.size)
      .map(x => s"Split(line=${x._1}, count=${x._2.size})")
      .take(3)
    logger.debug(splits.mkString(", "))
    logger.debug(s"Total explored: ${Debug.explored}")
    val results = debugResults.result()
    // TODO(olafur) don't block printing out test results.
    // I don't want to deal with scalaz's Tasks :'(
    val k = for {
      _ <- Future(
        FileOps.writeFile(
          s"target${File.separator}index.html",
          Report.heatmap(results)
        )
      )
    } yield ()
    // Travis exits right after running tests.
    if (sys.env.contains("TRAVIS")) Await.ready(k, 20.seconds)
  }
}
