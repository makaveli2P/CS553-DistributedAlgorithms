package main.utility

import akka.actor.ActorRef
import akka.event.slf4j.Logger

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

// USED TO STORE SNAPSHOT DATA OF SYSTEM AND PRINT SNAPSHOT DATA OF PROCESS

class MessageRecord(val process: ActorRef, val messageBody: String) {}

class ProcessSnapshotData(val storedInteger: Int, val messageQueue: ListBuffer[MessageRecord]) {}

class SystemSnapshot {
  val systemSnapshot: mutable.Map[Int, ProcessSnapshotData] = mutable.Map.empty

  var log = Logger(getClass.getName)

  def printSnapshotData(processId: Int): Unit = {
    val sb = new StringBuilder()
    sb.append(s"SNAPSHOT PROCEDURE ENDED FOR process${processId}\n")
    sb.append(s"SAVED SNAPSHOT FOR process${processId} -->\n")
    sb.append(s"STORED VALUE ==> ${systemSnapshot(processId).storedInteger}\n")
    sb.append(s"MESSAGE QUEUE ==>\n")

    if (systemSnapshot(processId).messageQueue.isEmpty) {
      sb.append("EMPTY")
    } else {
      for (record <- systemSnapshot(processId).messageQueue) {
        sb.append(s"{Sender Process: ${record.process}, Message: ${record.messageBody}}")
      }
    }

    log.info(sb.toString())
  }
}