package com.ergodicity.capture

import org.slf4j.LoggerFactory
import akka.actor._
import akka.util.duration._
import com.twitter.ostrich.admin.{ServiceTracker, RuntimeEnvironment, Service}
import java.util.concurrent.TimeUnit
import com.ergodicity.cgate.config.ConnectionConfig
import ru.micexrts.cgate.{Connection => CGConnection, P2TypeParser, CGate}
import com.ergodicity.capture.MarketCapture.{ShutDown, Capture}
import akka.actor.FSM.Failure
import com.ergodicity.cgate.config.CGateConfig
import akka.actor.Terminated
import akka.actor.SupervisorStrategy.Stop

object CaptureEngine {
  val log = LoggerFactory.getLogger(getClass.getName)

  var marketCapture: CaptureEngine = null
  var runtime: RuntimeEnvironment = null

  def main(args: Array[String]) {
    try {
      runtime = RuntimeEnvironment(this, args)
      marketCapture = runtime.loadRuntimeConfig[CaptureEngine]()

      marketCapture.start()
    } catch {
      case e: Throwable =>
        log.error("Exception during startup; exiting!", e)
        System.exit(1)
    }
  }
}

class CaptureEngine(cgateConfig: CGateConfig, connectionConfig: ConnectionConfig, scheme: ReplicationScheme, kestrel: KestrelConfig) extends Service {
  val log = LoggerFactory.getLogger(classOf[CaptureEngine])

  implicit val system = ActorSystem("CaptureEngine")

  // Register service using Ostrich
  ServiceTracker.register(this)

  private val repo = new MarketCaptureRepository with ReplicationStateRepository with SessionRepository with FutSessionContentsRepository with OptSessionContentsRepository

  private def conn = new CGConnection(connectionConfig())

  def newMarketCaptureInstance = new MarketCapture(scheme, repo, kestrel) with CaptureConnection with UnderlyingListenersImpl with CaptureListenersImpl {
    lazy val underlyingConnection = conn
  }

  var guardian: ActorRef = system.deadLetters

  def start() {
    log.info("Start CaptureEngine")

    // Prepare CGate
    CGate.open(cgateConfig())
    P2TypeParser.setCharset("windows-1251")

    // Watch for Market Capture is working
    guardian = system.actorOf(Props(new Guardian(this)), "CaptureGuardian")

    // Let all actors to activate and perform all activities
    Thread.sleep(TimeUnit.SECONDS.toMillis(1))

    // Schedule periodic restarting
    system.scheduler.schedule(60.minutes, 60.minutes, guardian, Restart)

    guardian ! Capture
  }

  def shutdown() {
    guardian ! ShutDown
  }

  case object Restart

}

sealed trait GuardianState

case object Working extends GuardianState

case object Restarting extends GuardianState

class Guardian(engine: CaptureEngine) extends Actor with FSM[GuardianState, ActorRef] {

  import engine._

  case class CatchedException(e: Throwable)

  // Supervisor
  override val supervisorStrategy = AllForOneStrategy() {
    case e: MarketCaptureException =>
      self ! CatchedException(e)
      Stop
  }

  // Create Market Capture system
  var marketCapture = context.actorOf(Props(newMarketCaptureInstance), "MarketCapture")
  context.watch(marketCapture)

  startWith(Working, marketCapture)

  when(Working) {
    case Event(Terminated(ref), mc) if (ref == mc) =>
      system.shutdown()
      System.exit(-1)
      stop(Failure("Market Capture unexpected terminated"))

    case Event(CatchedException(e), _) =>
      log.error("Catched exception from MarketCapture = "+e)
      stay()

    case Event(Restart, mc) =>
      log.info("Restart Market Capture")
      mc ! ShutDown
      goto(Restarting)
  }

  when(Restarting) {
    case Event(CatchedException(e), _) =>
      log.error("Failure during MarketCapture shut down = "+e)
      system.shutdown()
      System.exit(-1)
      stop(Failure("Failure during MarketCapture shut down = " + e))

    case Event(Terminated(ref), mc) if (ref == mc) =>
      // Let connection to be closed
      Thread.sleep(TimeUnit.SECONDS.toMillis(10))

      // Create new Market Capture system
      marketCapture = system.actorOf(Props(newMarketCaptureInstance), "MarketCapture")
      context.watch(marketCapture)

      // Wait for capture initialized
      Thread.sleep(TimeUnit.SECONDS.toMillis(1))

      goto(Working) using (marketCapture)
  }

  onTransition {
    case Restarting -> Working =>
      // Start capturing
      marketCapture ! Capture
  }

  whenUnhandled {
    case Event(e@(Capture | ShutDown), capture) =>
      capture ! e
      stay()
  }

}