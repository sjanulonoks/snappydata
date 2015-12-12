package io.snappydata.cluster

import java.io.File
import java.net.URL
import java.util
import java.util.concurrent.locks.ReentrantLock

import scala.collection.mutable
import scala.util.control.NonFatal

import com.gemstone.gemfire.distributed.internal.MembershipListener
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl
import com.pivotal.gemfirexd.internal.engine.Misc
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils

import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.executor.SnappyCoarseGrainedExecutorBackend
import org.apache.spark.{Logging, SparkCallbacks, SparkConf, SparkEnv}

/**
 * This class is responsible for initiating the executor process inside
 * the jvm. Also, if an executor has to be stopped, driverURL can be set as None
 * and it will take care of stopping the executor.
 *
 * Created by hemant on 15/10/15.
 */
object ExecutorInitiator extends Logging {

  private val SNAPPY_BLOCK_MANAGER = "org.apache.spark.storage.SnappyBlockManager"
  private val SNAPPY_SHUFFLE_MEMORY_MANAGER = "org.apache.spark.shuffle.SnappyShuffleMemoryManager"

  var executorRunnable: ExecutorRunnable = new ExecutorRunnable

  var executorThread: Thread = new Thread(executorRunnable)

  class ExecutorRunnable() extends Runnable {
    private var driverURL: Option[String] = None
    private var driverDM: InternalDistributedMember = null
    var stopTask = false
    private val lock = new ReentrantLock

    val membershipListener = new MembershipListener {
      override def quorumLost(failures: util.Set[InternalDistributedMember],
          remaining: util.List[InternalDistributedMember]): Unit = {}

      override def memberJoined(id: InternalDistributedMember): Unit = {}

      override def memberSuspect(id: InternalDistributedMember,
          whoSuspected: InternalDistributedMember): Unit = {}

      override def memberDeparted(id: InternalDistributedMember, crashed: Boolean): Unit = {
        executorRunnable.memberDeparted(id)
      }
    }

    def memberDeparted(departedDM: InternalDistributedMember): Unit = lock.synchronized {
      if (departedDM.equals(driverDM)) {
        setDriverDetails(None, null)
      }
    }

    def setDriverDetails(url: Option[String],
        dm: InternalDistributedMember): Unit = lock.synchronized {
      driverURL = url
      driverDM = dm
      lock.notify()
    }

    override def run(): Unit = {
      var prevDriverURL = ""
      var env: SparkEnv = null
      try {
        GemFireXDUtils.getGfxdAdvisor.getDistributionManager
            .addMembershipListener(membershipListener)
        while (!stopTask) {
          try {
            Misc.checkIfCacheClosing(null)
            if (prevDriverURL == getDriverURL) {
              lock.synchronized {
                lock.wait()
              }
            }
            else {
              // kill if an executor is already running.
              SparkCallbacks.stopExecutor(env)
              env = null
              prevDriverURL = getDriverURL

              driverURL match {
                case Some(url) =>

                  /**
                   * The executor initialization code has been picked from
                   * CoarseGrainedExecutorBackend.
                   * We need to track the changes there and merge them here on a regular basis.
                   */
                  val executorHost = GemFireCacheImpl.getInstance().getMyId.getHost
                  val memberId = GemFireCacheImpl.getInstance().getMyId.toString
                  SparkHadoopUtil.get.runAsSparkUser { () =>

                    // Fetch the driver's Spark properties.
                    val executorConf = new SparkConf

                    val port = executorConf.getInt("spark.executor.port", 0)
                    val props = SparkCallbacks.fetchDriverProperty(executorHost,
                      executorConf, port, url)


                    val driverConf = new SparkConf()
                    // Specify a default directory for executor, if the local directory for executor
                    // is set via the executor conf,
                    // it will override this property later in the code
                    val localDirForExecutor = new File("./" + "executor").getAbsolutePath

                    driverConf.set("spark.local.dir", localDirForExecutor)
                    for ((key, value) <- props) {
                      // this is required for SSL in standalone mode
                      if (!key.equals("spark.local.dir")) {
                        if (SparkCallbacks.isExecutorStartupConf(key)) {
                          driverConf.setIfMissing(key, value)
                        } else {
                          driverConf.set(key, value)
                        }
                      }
                    }
                    // TODO: Hemant: add executor specific properties from local conf to
                    // TODO: this conf that was received from driver.
                    driverConf.set("spark.blockManager", SNAPPY_BLOCK_MANAGER)
                    driverConf.set("spark.shuffleMemoryManager", SNAPPY_SHUFFLE_MEMORY_MANAGER)

                    val cores = driverConf.getInt("spark.executor.cores",
                      Runtime.getRuntime().availableProcessors())

                    env = SparkCallbacks.createExecutorEnv(
                      driverConf, memberId, executorHost, port, cores, false)

                    // SparkEnv sets spark.executor.port so it shouldn't be 0 anymore.
                    val boundport = env.conf.getInt("spark.executor.port", 0)
                    assert(boundport != 0)

                    // This is not required with snappy
                    val userClassPath = new mutable.ListBuffer[URL]()

                    val rpcenv = SparkCallbacks.getRpcEnv(env)

                    val executor = new SnappyCoarseGrainedExecutorBackend(
                      rpcenv, url, memberId, executorHost + ":" + boundport,
                      cores, userClassPath, env)

                    val endPoint = rpcenv.setupEndpoint("Executor", executor)
                  }
                case None =>
                // If driver url is none, already running executor is stopped.
              }
            }
          } catch {
            case e@(NonFatal(_) | _: InterruptedException) =>
              try {
                Misc.checkIfCacheClosing(e)
                // log any exception other than those due to cache closing
                logWarning("Unexpected exception in ExecutorInitiator", e)
              } catch {
                case NonFatal(e) => stopTask = true // just stop the task
              }
          }
        } // end of while(true)
      } catch {
        case e: Throwable =>
          logWarning("ExecutorInitiator failing with exception: ", e)
      } finally {
        // kill if an executor is already running.
        SparkCallbacks.stopExecutor(env)
        GemFireXDUtils.getGfxdAdvisor.getDistributionManager
            .removeMembershipListener(membershipListener)
      }
    }

    def getDriverURL: String = driverURL match {
      case Some(x) => x
      case None => ""
    }
  }

  /**
   * This should be called only when the process is terminating.
   * If a process ceases to be an executor, only startOrTransmuteExecutor should be called
   * with None.
   */
  def stop(): Unit = {
    if (executorThread.getState != Thread.State.NEW) {
      executorRunnable.stopTask = true
    }
    executorRunnable.setDriverDetails(None, null)
  }

  /**
   * Set the new driver url and start the thread if not already started
   * @param driverURL
   */
  def startOrTransmuteExecutor(driverURL: Option[String],
      driverDM: InternalDistributedMember): Unit = {
    // Avoid creation of executor inside the Gem accessor
    // that is a Spark driver but has joined the gem system
    // in the non embedded mode
    if (SparkCallbacks.isDriver()) {
      logInfo("Executor cannot be instantiated in this " +
          "VM as a Spark driver is already running. ")
      return
    }

    executorRunnable.setDriverDetails(driverURL, driverDM)
    // start the executor thread if driver URL is set and the thread
    // is not already started.
    driverURL match {
      case Some(x) =>
        if (executorThread.getState == Thread.State.NEW) {
          logInfo("About to start thread " + executorThread.getName)
          executorThread.setDaemon(true)
          executorThread.start()
        } else if (executorThread.getState == Thread.State.TERMINATED) {
          // Restart a thread after it has been stopped
          // This is required for dunit case mainly.
          executorRunnable = new ExecutorRunnable
          executorThread = new Thread(executorRunnable)
          logInfo("Spawning new thread " + executorThread.getName + " and starting")
          executorRunnable.setDriverDetails(driverURL, driverDM)
          executorThread.setDaemon(true)
          executorThread.start()
        }
      case None =>
    }
  }
}