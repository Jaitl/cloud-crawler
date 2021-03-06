package com.github.jaitl.crawler.master.queue

import akka.testkit.EventFilter
import akka.testkit.TestProbe
import com.github.jaitl.crawler.master.ActorTestSuite
import com.github.jaitl.crawler.master.queue.QueueTaskRecover.Recover
import com.github.jaitl.crawler.master.queue.QueueTaskRecover.RecoveryConfig
import com.github.jaitl.crawler.master.queue.QueueTaskRecover.Stop
import com.github.jaitl.crawler.master.queue.provider.QueueTaskProvider
import com.github.jaitl.crawler.master.scheduler.Scheduler

import scala.concurrent.Future

class QueueTaskRecoverTest extends ActorTestSuite {
  import scala.concurrent.duration._

  class TaskRecoverSuite {
    val queueTaskProvider = mock[QueueTaskProvider]
    val recoverScheduler = mock[Scheduler]
    val config = RecoveryConfig(1.minute, 1.minute)

    (recoverScheduler.schedule _).expects(*, *, *).returning(())
  }

  "QueueTaskRecover" should {
    "Recover" in new TaskRecoverSuite {
      (queueTaskProvider.updateTasksStatusFromTo _).expects(*, *, *).returning(Future.successful(2))

      val queueTaskRecover = system.actorOf(QueueTaskRecover.props(queueTaskProvider, recoverScheduler, config))

      EventFilter.warning(message = "Recovered tasks: 2", occurrences = 1) intercept {
        queueTaskRecover ! Recover
      }
    }

    "Stop" in new TaskRecoverSuite {
      val queueTaskRecover = system.actorOf(QueueTaskRecover.props(queueTaskProvider, recoverScheduler, config))

      val watcher = TestProbe()
      watcher.watch(queueTaskRecover)

      queueTaskRecover ! Stop

      watcher.expectTerminated(queueTaskRecover)
    }
  }
}
