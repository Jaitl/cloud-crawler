package com.github.jaitl.crawler.worker.executor

import java.util.UUID

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorRefFactory
import akka.actor.Props
import com.github.jaitl.crawler.master.client.task.Task
import com.github.jaitl.crawler.master.client.task.TasksBatch
import com.github.jaitl.crawler.worker.client.QueueClient
import com.github.jaitl.crawler.worker.creator.ActorCreator
import com.github.jaitl.crawler.worker.creator.OneArgumentActorCreator
import com.github.jaitl.crawler.worker.creator.ThreeArgumentActorCreator
import com.github.jaitl.crawler.worker.exception.PageNotFoundException
import com.github.jaitl.crawler.worker.executor.CrawlExecutor.Crawl
import com.github.jaitl.crawler.worker.executor.CrawlExecutor.CrawlFailureResult
import com.github.jaitl.crawler.worker.executor.CrawlExecutor.CrawlSuccessResult
import com.github.jaitl.crawler.worker.executor.SaveCrawlResultController.AddResults
import com.github.jaitl.crawler.worker.executor.SaveCrawlResultController.BannedTask
import com.github.jaitl.crawler.worker.executor.SaveCrawlResultController.FailedTask
import com.github.jaitl.crawler.worker.executor.SaveCrawlResultController.FailureSaveResults
import com.github.jaitl.crawler.worker.executor.SaveCrawlResultController.ParsingFailedTask
import com.github.jaitl.crawler.worker.executor.SaveCrawlResultController.SaveResults
import com.github.jaitl.crawler.worker.executor.SaveCrawlResultController.SkippedTask
import com.github.jaitl.crawler.worker.executor.SaveCrawlResultController.SuccessAddedResults
import com.github.jaitl.crawler.worker.executor.SaveCrawlResultController.SuccessCrawledTask
import com.github.jaitl.crawler.worker.executor.SaveCrawlResultController.SuccessSavedResults
import com.github.jaitl.crawler.worker.executor.TasksBatchController.ExecuteTask
import com.github.jaitl.crawler.worker.executor.TasksBatchController.QueuedTask
import com.github.jaitl.crawler.worker.executor.TasksBatchController.TasksBatchControllerConfig
import com.github.jaitl.crawler.worker.executor.resource.ResourceController.NoFreeResource
import com.github.jaitl.crawler.worker.executor.resource.ResourceController.NoResourcesAvailable
import com.github.jaitl.crawler.worker.executor.resource.ResourceController.RequestResource
import com.github.jaitl.crawler.worker.executor.resource.ResourceController.ReturnBannedResource
import com.github.jaitl.crawler.worker.executor.resource.ResourceController.ReturnFailedResource
import com.github.jaitl.crawler.worker.executor.resource.ResourceController.ReturnParsingFailedResource
import com.github.jaitl.crawler.worker.executor.resource.ResourceController.ReturnSkippedResource
import com.github.jaitl.crawler.worker.executor.resource.ResourceController.ReturnSkippedResourceNoWait
import com.github.jaitl.crawler.worker.executor.resource.ResourceController.ReturnSuccessResource
import com.github.jaitl.crawler.worker.executor.resource.ResourceController.SuccessRequestResource
import com.github.jaitl.crawler.worker.executor.resource.ResourceHelper
import com.github.jaitl.crawler.worker.notification.NotificationExecutor.SendNotification
import com.github.jaitl.crawler.worker.parser.ParsingException
import com.github.jaitl.crawler.worker.pipeline.ConfigurablePipeline
import com.github.jaitl.crawler.worker.pipeline.Pipeline
import com.github.jaitl.crawler.worker.pipeline.ResourceType
import com.github.jaitl.crawler.worker.scheduler.Scheduler

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success

private[worker] class TasksBatchController(
  batch: TasksBatch,
  pipeline: Pipeline[_],
  configPipeline: ConfigurablePipeline,
  resourceControllerCreator: OneArgumentActorCreator[ResourceType],
  crawlExecutorCreator: ActorCreator,
  notifierExecutorCreator: ActorCreator,
  saveCrawlResultCreator: ThreeArgumentActorCreator[Pipeline[_], ActorRef, ConfigurablePipeline],
  queueClient: QueueClient,
  executeScheduler: Scheduler,
  config: TasksBatchControllerConfig
) extends Actor
    with ActorLogging {
  implicit private val executionContext: ExecutionContext = context.dispatcher

  var currentActiveCrawlTask: Int = 0
  var forcedStop: Boolean = false

  val taskQueue: mutable.Queue[QueuedTask] = mutable.Queue.apply(batch.tasks.map(QueuedTask(_, 0)): _*)

  private var resourceController: ActorRef = _
  private var notifier: ActorRef = _
  private var saveCrawlResultController: ActorRef = _
  private var crawlExecutor: ActorRef = _

  override def preStart(): Unit = {
    super.preStart()
    log.info(s"Start new TasksBatchController, batch id: ${batch.id} with ")

    resourceController = resourceControllerCreator.create(this.context, configPipeline.resourceType)
    saveCrawlResultController = saveCrawlResultCreator.create(this.context, pipeline, self, configPipeline)
    crawlExecutor = crawlExecutorCreator.create(this.context)
    notifier = notifierExecutorCreator.create(this.context)
    executeScheduler.schedule(config.executeInterval, self, ExecuteTask)
  }

  override def postStop(): Unit = {
    super.postStop()
    log.info(s"Stop TasksBatchController, batch id: ${batch.id}")
  }

  override def receive: Receive =
    Seq(waitRequest, resourceRequestHandler, crawlResultHandler, resultSavedHandler)
      .reduce(_.orElse(_))

  private def waitRequest: Receive = {
    case ExecuteTask =>
      if (taskQueue.nonEmpty && !forcedStop) {
        resourceController ! RequestResource(UUID.randomUUID())
      } else {
        saveCrawlResultController ! SaveResults
      }
  }

  private def resourceRequestHandler: Receive = {
    case SuccessRequestResource(requestId, requestExecutor) =>
      if (taskQueue.nonEmpty) {
        val task = taskQueue.dequeue()
        if (task.task.skipped) {
          currentActiveCrawlTask = currentActiveCrawlTask + 1
          saveCrawlResultController ! AddResults(SkippedTask(task.task, new PageNotFoundException("")))
          log.info(s"crawl task: ${task.task.taskData} skipped, activeTasks: $currentActiveCrawlTask")
          resourceController ! ReturnSkippedResourceNoWait(requestId, requestExecutor)
        } else {
          crawlExecutor ! Crawl(requestId, task, requestExecutor, pipeline)
          currentActiveCrawlTask = currentActiveCrawlTask + 1
          log.info(s"crawl task: ${task.task.id}, activeTasks: $currentActiveCrawlTask")
        }
      } else {
        resourceController ! ReturnSuccessResource(requestId, requestExecutor)
      }

    case NoFreeResource(requestId) =>
      log.debug(s"NoFreeResource, requestId: $requestId")

    case NoResourcesAvailable(requestId) =>
      log.debug(s"NoResourcesAvailable, requestId: $requestId")
      forcedStop = true
      saveCrawlResultController ! SaveResults
  }

  private def crawlResultHandler: Receive = {
    case CrawlSuccessResult(requestId, task, requestExecutor, crawlResult, parseResult) =>
      log.info(s"success crawl completed: ${task.task.taskData}")

      resourceController ! ReturnSuccessResource(requestId, requestExecutor)
      saveCrawlResultController ! AddResults(SuccessCrawledTask(task.task, crawlResult, parseResult))

    case CrawlFailureResult(requestId, task, requestExecutor, t) =>
      log.error(t, s"failure crawl completed: ${task.task.taskData}, attempt: ${task.attempt}")
      if (ResourceHelper.isParsingFailed(t)) {
        if (configPipeline.enableNotification) {
          notifier ! SendNotification(
            t.asInstanceOf[ParsingException].message,
            t.asInstanceOf[ParsingException].data,
            pipeline)
        }
        resourceController ! ReturnParsingFailedResource(requestId, requestExecutor, t)
        saveCrawlResultController ! AddResults(ParsingFailedTask(task.task, t))
      } else if (ResourceHelper.isResourceSkipped(t)) {
        resourceController ! ReturnSkippedResource(requestId, requestExecutor, t)
        saveCrawlResultController ! AddResults(SkippedTask(task.task, t))
      } else if (ResourceHelper.isBotBanned(t)) {
        resourceController ! ReturnBannedResource(requestId, requestExecutor, t)
        taskQueue += task
        saveCrawlResultController ! AddResults(BannedTask(task.task, t))
      } else if (ResourceHelper.isResourceFailed(t)) {
        currentActiveCrawlTask = currentActiveCrawlTask - 1
        resourceController ! ReturnFailedResource(requestId, requestExecutor, t)
        taskQueue += task
      } else {
        resourceController ! ReturnSuccessResource(requestId, requestExecutor)
        if (task.attempt + 1 < config.maxAttempts) {
          taskQueue += task.copy(attempt = task.attempt + 1, t = task.t :+ t)
          currentActiveCrawlTask = currentActiveCrawlTask - 1
        } else {
          saveCrawlResultController ! AddResults(FailedTask(task.task, t))
        }
      }
  }

  private def resultSavedHandler: Receive = {
    case SuccessAddedResults =>
      currentActiveCrawlTask = currentActiveCrawlTask - 1
      log.info(s"SuccessAddedResults, activeTasks: $currentActiveCrawlTask")

    case SuccessSavedResults =>
      if ((taskQueue.isEmpty || forcedStop) && currentActiveCrawlTask == 0) {
        log.info(s"Stop task batch controller: ${batch.id}, forcedStop: $forcedStop")

        if (taskQueue.nonEmpty) {
          val ids = taskQueue.map(_.task.id).toSeq
          val requestId = UUID.randomUUID()
          queueClient.returnTasks(requestId, ids).onComplete {
            case Success(_) =>
              log.debug(s"Tasks returned to queue, controller: ${batch.id}, requestId: $requestId")
            case Failure(ex) =>
              log.error(ex, s"Failure during return tasks to queue, controller: ${batch.id}, requestId: $requestId")
          }
        }

        context.stop(self)
      } else {
        //log.error(s"Can not request new tasks currentActiveCrawlTask: ${currentActiveCrawlTask}")
      }

    case FailureSaveResults(t) =>
      log.error(t, "Error during save results")
  }
}

private[worker] object TasksBatchController {

  case object ExecuteTask

  case class QueuedTask(task: Task, attempt: Int, t: Seq[Throwable] = Seq.empty)

  // scalastyle:off parameter.number
  def props(
    batch: TasksBatch,
    pipeline: Pipeline[_],
    configPipeline: ConfigurablePipeline,
    resourceControllerCreator: OneArgumentActorCreator[ResourceType],
    crawlExecutorCreator: ActorCreator,
    notifierExecutorCreator: ActorCreator,
    saveCrawlResultCreator: ThreeArgumentActorCreator[Pipeline[_], ActorRef, ConfigurablePipeline],
    queueClient: QueueClient,
    executeScheduler: Scheduler,
    config: TasksBatchControllerConfig
  ): Props =
    Props(
      new TasksBatchController(
        batch = batch,
        pipeline = pipeline,
        configPipeline = configPipeline,
        resourceControllerCreator = resourceControllerCreator,
        crawlExecutorCreator = crawlExecutorCreator,
        notifierExecutorCreator = notifierExecutorCreator,
        saveCrawlResultCreator = saveCrawlResultCreator,
        queueClient = queueClient,
        executeScheduler = executeScheduler,
        config = config
      ))

  def name(batchId: String): String = s"tasksBatchController-$batchId"

  case class TasksBatchControllerConfig(
    maxAttempts: Int,
    executeInterval: FiniteDuration
  )
}

class TasksBatchControllerCreator(
  resourceControllerCreator: OneArgumentActorCreator[ResourceType],
  crawlExecutorCreator: ActorCreator,
  notifierExecutorCreator: ActorCreator,
  saveCrawlResultCreator: ThreeArgumentActorCreator[Pipeline[_], ActorRef, ConfigurablePipeline],
  queueClient: QueueClient,
  executeScheduler: Scheduler,
  config: TasksBatchControllerConfig
) extends ThreeArgumentActorCreator[TasksBatch, Pipeline[_], ConfigurablePipeline] {
  override def create(
    factory: ActorRefFactory,
    firstArg: TasksBatch,
    secondArg: Pipeline[_],
    thirdArg: ConfigurablePipeline
  ): ActorRef =
    factory.actorOf(
      props = TasksBatchController.props(
        batch = firstArg,
        pipeline = secondArg,
        configPipeline = thirdArg,
        resourceControllerCreator = resourceControllerCreator,
        crawlExecutorCreator = crawlExecutorCreator,
        notifierExecutorCreator = notifierExecutorCreator,
        saveCrawlResultCreator = saveCrawlResultCreator,
        queueClient = queueClient,
        executeScheduler = executeScheduler,
        config = config
      ),
      name = TasksBatchController.name(firstArg.id)
    )
}
