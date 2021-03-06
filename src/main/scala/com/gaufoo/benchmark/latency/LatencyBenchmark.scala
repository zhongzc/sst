package com.gaufoo.benchmark.latency

import com.gaufoo.benchmark.utils._
import com.gaufoo.sst.{KVEngine, SSTEngine}
import org.mpierce.metrics.reservoir.hdrhistogram.HdrHistogramReservoir
import org.slf4s.Logging

import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}

object LatencyBenchmark extends Logging  {

  case class CommandsPerSecond(value: Int) extends AnyVal
  case class BenchmarkIterationCount(value: Int) extends AnyVal
  case class CommandSentTimestamp(value: Long) extends AnyVal

  def runBenchmark(
    kvEngine: KVEngine,
    ops: List[Op],
    cps: CommandsPerSecond = CommandsPerSecond(10000)): Unit = {
    log.debug("Begin latency benchmarking")

    @tailrec
    def sendOperations(
      xs: List[(Op, Int)],
      kvEngine: KVEngine,
      testStart: Long,
      histogram: HdrHistogramReservoir): (KVEngine, HdrHistogramReservoir) = xs match {

      case head :: tail =>
        val (op, offsetInMs) = head
        val shouldStart = testStart + offsetInMs

        while (shouldStart > System.currentTimeMillis()) {
          // keep the thread busy while waiting for the next batch to be sent
        }

        op.sendTo(kvEngine).foreach { _ =>
          val operationEnd = System.currentTimeMillis()
          histogram.update(operationEnd - shouldStart)
        }(ExecutionContext.global)

        sendOperations(tail, kvEngine, testStart, histogram)

      case Nil => (kvEngine, histogram)
    }

    val (_, histogram) = sendOperations(
      ops.grouped(cps.value).toList.zipWithIndex
        .flatMap {
          case (secondBatch, sBatchIndex) =>
            val batchOffsetInMs = sBatchIndex * 1000
            val commandIntervalInMs = 1000.0 / cps.value
            secondBatch.zipWithIndex.map {
              case (command, commandIndex) =>
                val commandOffsetInMs =
                  Math.floor(commandIntervalInMs * commandIndex).toInt
                (command, batchOffsetInMs + commandOffsetInMs)
            }
        },
      kvEngine,
      System.currentTimeMillis(),
      new HdrHistogramReservoir())

    Thread.sleep(2000)
    printSnapshot(histogram.getSnapshot)

    log.debug("End latency benchmarking")
  }

  def main(args: Array[String]): Unit = {
    val warmUp = SSTEngine.build("warmUp")
    Await.result(jvmWarmUp(warmUp), Duration.Inf)
    warmUp.shutdown()

    val latency = SSTEngine.build("latency", bufferSize = 1500)
    LatencyBenchmark.runBenchmark(latency, getOps(500000))
    latency.shutdown()
  }
}
