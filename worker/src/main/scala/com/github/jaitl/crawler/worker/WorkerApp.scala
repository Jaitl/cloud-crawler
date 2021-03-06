package com.github.jaitl.crawler.worker

import akka.actor.ActorRef
import akka.actor.ActorSystem
import com.github.jaitl.crawler.master.client.configuration.ConfigurationServiceGrpc
import com.github.jaitl.crawler.master.client.configuration.ProjectConfiguration
import com.github.jaitl.crawler.master.client.queue.TaskQueueServiceGrpc
import com.github.jaitl.crawler.worker.WorkerManager.RequestBatch
import com.github.jaitl.crawler.worker.client.QueueClient
import com.github.jaitl.crawler.worker.client.QueueClientImpl
import com.github.jaitl.crawler.worker.config.WorkerConfig
import com.github.jaitl.crawler.worker.creator.PropsActorCreator
import com.github.jaitl.crawler.worker.executor.CrawlExecutor
import com.github.jaitl.crawler.worker.executor.SaveCrawlResultController.SaveCrawlResultControllerConfig
import com.github.jaitl.crawler.worker.executor.SaveCrawlResultControllerCreator
import com.github.jaitl.crawler.worker.executor.TasksBatchController.TasksBatchControllerConfig
import com.github.jaitl.crawler.worker.executor.TasksBatchControllerCreator
import com.github.jaitl.crawler.worker.executor.resource.ResourceControllerConfig
import com.github.jaitl.crawler.worker.executor.resource.ResourceControllerCreator
import com.github.jaitl.crawler.worker.notification.NotificationExecutor
import com.github.jaitl.crawler.worker.pipeline.ConfigurablePipeline
import com.github.jaitl.crawler.worker.pipeline.Pipeline
import com.github.jaitl.crawler.worker.scheduler.AkkaScheduler
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.StrictLogging
import io.grpc.netty.NettyChannelBuilder

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success

// scalastyle:off
object WorkerApp extends StrictLogging {
  implicit val executionContextGlobal: ExecutionContext = ExecutionContext.global

  private var warmUpPipelines: Pipeline[_] = null

  def addWarmUpPipeline(pipelines: Pipeline[_]): this.type = {
    this.warmUpPipelines = pipelines
    this
  }

  def run(): Unit = {
    val config = ConfigFactory.load("worker.conf")

    val system = ActorSystem("CCWorkerActorSystem", config)

    val masterGrpcHost = config.getString("master.grpc.host")
    val masterGrpcPort = config.getInt("master.grpc.port")

    val channel = NettyChannelBuilder.forAddress(masterGrpcHost, masterGrpcPort).usePlaintext().build
    val configurationClient = ConfigurationServiceGrpc.stub(channel)
    val taskQueueClient = TaskQueueServiceGrpc.stub(channel)
    val queueClient = new QueueClientImpl(taskQueueClient)
    val startUpService = new StartUpService(configurationClient, warmUpPipelines)
    logger.info(s"Connect master by GRPC on $masterGrpcHost:$masterGrpcPort")

    startUpService.configurePipeline(warmUpPipelines.taskType).onComplete {
      case Success((configuration, pipeline)) =>
        val workerManager = createWorkerManager(queueClient, system, config, configuration, pipeline)
        logger.info("Create worker manager")
        workerManager ! RequestBatch
      case Failure(ex) =>
        logger.error("Failure during configure pipeline", ex)
        System.exit(1)
    }
  }

  def createWorkerManager(
    queueClient: QueueClient,
    system: ActorSystem,
    config: Config,
    configuration: ProjectConfiguration,
    configurablePipeline: ConfigurablePipeline
  ): ActorRef = {
    import net.ceedubs.ficus.Ficus._
    import net.ceedubs.ficus.readers.ArbitraryTypeReader._

    val workerConfig = WorkerConfig(
      configuration.workerParallelBatches,
      config.as[FiniteDuration]("worker.manager.executeInterval"),
      config.as[FiniteDuration]("worker.manager.runExecutionTimeoutCheckInterval"),
      config.as[FiniteDuration]("worker.manager.batchExecutionTimeout")
    )

    val crawlExecutorCreator = new PropsActorCreator(
      actorName = CrawlExecutor.name(),
      props = CrawlExecutor.props().withDispatcher("worker.blocking-io-dispatcher")
    )

    val notifierExecutorCreator = new PropsActorCreator(
      actorName = NotificationExecutor.name(),
      props = NotificationExecutor.props().withDispatcher("worker.blocking-io-dispatcher")
    )
    val saveCrawlResultControllerConfig = config.as[SaveCrawlResultControllerConfig]("worker.save-controller")

    val saveCrawlResultControllerCreator = new SaveCrawlResultControllerCreator(
      queueClient = queueClient,
      saveScheduler = new AkkaScheduler(system),
      config = saveCrawlResultControllerConfig
    )

    val resourceControllerConfig = config.as[ResourceControllerConfig]("worker.resource-controller")
    val tasksBatchControllerConfig = config.as[TasksBatchControllerConfig]("worker.task-batch-controller")

    val resourceControllerCreator = new ResourceControllerCreator(resourceControllerConfig)

    val tasksBatchControllerCreator = new TasksBatchControllerCreator(
      resourceControllerCreator = resourceControllerCreator,
      crawlExecutorCreator = crawlExecutorCreator,
      notifierExecutorCreator = notifierExecutorCreator,
      saveCrawlResultCreator = saveCrawlResultControllerCreator,
      queueClient = queueClient,
      executeScheduler = new AkkaScheduler(system),
      config = tasksBatchControllerConfig
    )

    system.actorOf(
      WorkerManager.props(
        queueClient = queueClient,
        pipelines = Map(warmUpPipelines.taskType -> warmUpPipelines),
        configurablePipeline = configurablePipeline,
        config = workerConfig,
        tasksBatchControllerCreator = tasksBatchControllerCreator,
        batchRequestScheduler = new AkkaScheduler(system),
        batchExecutionTimeoutScheduler = new AkkaScheduler(system),
        batchTasksValidator = warmUpPipelines.batchTasksValidator
      ),
      WorkerManager.name()
    )
  }
}
