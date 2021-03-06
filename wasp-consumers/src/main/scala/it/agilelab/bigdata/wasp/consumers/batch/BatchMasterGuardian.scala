package it.agilelab.bigdata.wasp.consumers.batch

import akka.actor._
import akka.pattern.gracefulStop
import it.agilelab.bigdata.wasp.consumers.writers.SparkWriterFactory
import it.agilelab.bigdata.wasp.consumers.{CamelQuartz2Scheduler, SparkHolder}
import it.agilelab.bigdata.wasp.core.WaspMessage
import it.agilelab.bigdata.wasp.core.WaspSystem._
import it.agilelab.bigdata.wasp.core.bl._
import it.agilelab.bigdata.wasp.core.cluster.ClusterAwareNodeGuardian
import it.agilelab.bigdata.wasp.core.logging.WaspLogger
import it.agilelab.bigdata.wasp.core.models.{BatchJobModel, BatchSchedulerModel, JobStateEnum}
import it.agilelab.bigdata.wasp.core.utils.SparkBatchConfiguration
import reactivemongo.bson.BSONObjectID
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

case class StopBatchJobsMessage() extends WaspMessage

case class CheckJobsBucketMessage() extends WaspMessage
case class BatchJobProcessedMessage(id: String, jobState: String) extends WaspMessage
case class StartBatchJobMessage(id: String) extends WaspMessage
case class BatchJobResult(id:String, result: Boolean) extends WaspMessage
case class StartSchedulersMessage() extends WaspMessage

object BatchMasterGuardian {
  val name = "BatchMasterGuardian"
}

class BatchMasterGuardian(env: {val batchJobBL: BatchJobBL; val indexBL: IndexBL; val rawBL: RawBL; val mlModelBL: MlModelBL; val batchSchedulerBL: BatchSchedulersBL},
                           sparkWriterFactory: SparkWriterFactory)
  extends ClusterAwareNodeGuardian  with Stash with SparkBatchConfiguration {

  val logger = WaspLogger(this.getClass.getName)

  /** STARTUP PHASE **/
  /** *****************/

  /** Initialize and retrieve the SparkContext */
  val scCreated = SparkHolder.createSparkContext(sparkBatchConfig)
  if (!scCreated) logger.warn("The spark context was already intialized: it might not be using the spark batch configuration!")
  val sc = SparkHolder.getSparkContext
  val batchActor = context.actorOf(Props(new BatchJobActor(env, sparkWriterFactory, sc)))


  context become notinitialized

  /** BASIC METHODS **/
  /** *****************/
  var lastRestartMasterRef: ActorRef = _

  override def initialize(): Unit = {
    //no initialization actually, clean code
    context become initialized
    logger.info("BatchMasterGuardian Initialized")
    unstashAll()
  }

  override def preStart(): Unit = {
    super.preStart()
    //TODO:capire joinseednodes
    cluster.joinSeedNodes(Vector(cluster.selfAddress))
  }

  def notinitialized: Actor.Receive = {
    case message: StopBatchJobsMessage =>
      lastRestartMasterRef = sender()
      //TODO: logica di qualche tipo?
    case message: CheckJobsBucketMessage =>
      lastRestartMasterRef = sender()
      stash()
      initialize()
    case message: StartBatchJobMessage =>
      lastRestartMasterRef = sender()
      stash()
      initialize()
    case message: BatchJobProcessedMessage =>
      stash()
      initialize()
    case message: StartSchedulersMessage =>
      stash()
      initialize()
  }

  def initialized: Actor.Receive = {
    case message: StopBatchJobsMessage =>
      lastRestartMasterRef = sender()
      stopGuardian()
    case message: CheckJobsBucketMessage =>
      lastRestartMasterRef = sender()
      logger.info(s"Checking batch jobs bucket ...")
      checkJobsBucket()
    case message: StartBatchJobMessage =>
      lastRestartMasterRef = sender()
      logger.info(s"Processing batch job ${message.id} .")

      lastRestartMasterRef ! BatchJobResult(message.id, startJob(message.id))

    case message: BatchJobProcessedMessage =>
      logger.info(s"Batch job ${message.id} processed with result ${message.jobState}")
      lastRestartMasterRef ! BatchJobProcessedMessage

    case message: StartSchedulersMessage =>
      logger.info(s"Starting scheduled batches activity")
      startSchedulerActors()
  }

  /** PRIVATE METHODS **/
  /** ******************/

  private def stopGuardian() {

    //Stop all actors bound to this guardian and the guardian itself
    logger.info(s"Stopping actors bound to BatchMasterGuardian ...")
    val globalStatus = Future.traverse(context.children)(gracefulStop(_, 60 seconds))
    val res = Await.result(globalStatus, 20 seconds)

    if (res reduceLeft (_ && _)) {
      logger.info(s"Graceful shutdown completed.")
    }
    else {
      logger.error(s"Something went wrong! Unable to shutdown all nodes")
    }

  }

  private def checkJobsBucket() {
    val batchJobs = loadBatchJobs

    if (batchJobs.isEmpty) {
      logger.info("There are no new pending batch jobs")
      lastRestartMasterRef ! true
    } else {
      batchJobs.foreach(element => {
        logger.info(s"***Starting Batch job actor [${element.name}]")
        context.children.foreach( child => {
          child ! element
        })
      })
      logger.info("Pending jobs sent to BatchJobsActor")
    }
  }

  private def startSchedulerActors(): Unit = {
    val schedulers = loadSchedulers

    if(schedulers.isEmpty) {
        logger.info("There are no active batch schedulers")
      } else {
      logger.info(s"${schedulers.length}Batch schedulers to be activated")
      //TODO salvo una lista degli scheduler per gestioni successive? (e.g. stop scheduling...?)
      schedulers.foreach(scheduler =>
        context.actorOf(Props(new CamelQuartz2Scheduler(self,scheduler))))
    }
  }

  private def startJob(id: String): Boolean = {
    //TODO: cambiare tutti stati stringa in enum.Value
    val jobFut: Future[Option[BatchJobModel]] = env.batchJobBL.getById(id)
    val job : Option[BatchJobModel] = Await.result(jobFut, timeout.duration)
    logger.info(s"Job that will be processed, job: $job")
    job match {
      case Some(element) =>
        if (!element.state.equals(JobStateEnum.PROCESSING)) {
          changeBatchState(element._id.get, JobStateEnum.PENDING)
            batchActor ! element
          true
        } else{
          logger.error(s"Batch job ${element.name} is already in processing phase")
          false
        }
      case None => logger.error("BatchEndedMessage with invalid id found.")
        false
    }
  }

  private def loadBatchJobs: List[BatchJobModel] = {
    logger.info(s"Loading all batch jobs ...")
    val batchJobs  = env.batchJobBL.getPendingJobs()
    val batchJobEntries: List[BatchJobModel] = Await.result(batchJobs, timeout.duration)
    logger.info(s"Found ${batchJobEntries.length} pending batch jobs...")

    batchJobEntries

  }

  private def loadSchedulers: List[BatchSchedulerModel] = {
    logger.info(s"Loading all batch schedulers ...")
    val schedulers  = env.batchSchedulerBL.getActiveSchedulers()
    val schedulersEntries: List[BatchSchedulerModel] = Await.result(schedulers, timeout.duration)
    logger.info(s"Found ${schedulersEntries.length} active schedulers...")

    schedulersEntries
  }

  //TODO: duplicato in BatchJobActor -> Rendere utility? Check esistenza id in BL?
  private def changeBatchState(id: BSONObjectID, newState: String): Unit =
  {
    val jobFut = env.batchJobBL.getById(id.stringify)
    val job: Option[BatchJobModel] = Await.result(jobFut, timeout.duration)
    job match {
      case Some(jobModel) => env.batchJobBL.setJobState(jobModel, newState)
      case None => logger.error("BatchEndedMessage with invalid id found.")
    }

  }


}
