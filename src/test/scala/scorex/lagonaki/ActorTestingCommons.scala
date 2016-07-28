package scorex.lagonaki

import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKitBase, TestProbe}
import akka.util.Timeout
import org.scalamock.scalatest.PathMockFactory
import org.scalatest.Matchers
import scorex.block.Block
import scorex.block.Block._
import scorex.lagonaki.mocks.BlockMock
import scorex.network.NetworkController.{DataFromPeer, RegisterMessagesHandler, SendToNetwork}
import scorex.network.message.{Message, MessageSpec}
import scorex.network.{ConnectedPeer, SendToChosen}

import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps}

abstract class ActorTestingCommons extends TestKitBase
  with org.scalatest.path.FreeSpecLike
  with Matchers
  with ImplicitSender
  with PathMockFactory {

  protected implicit val testTimeout = Timeout(500 milliseconds)
  protected val testDuration = testTimeout.duration

  implicit final lazy val system = ActorSystem(getClass.getSimpleName)

  protected lazy val networkController = TestProbe("NetworkController")
  protected def networkControllerMock = networkController.ref

  networkController.ignoreMsg {
    case RegisterMessagesHandler(_, _) => true
  }

  protected final def testSafely(fun: => Unit): Unit = getClass.getSimpleName testSafely fun

  protected final class ActorTestingStringWrapper(s: String) {
    def testSafely(fun: => Unit): Unit = {
      s - {
        try {
          fun
        } finally {
          try verifyExpectations
          finally shutdown()
        }
      }
    }
  }

  protected final implicit def convertTo(s: String): ActorTestingStringWrapper = new ActorTestingStringWrapper(s)

  protected val peerId = 9977
  protected lazy val peerHandler = TestProbe("PeerHandler")
  protected lazy val peer = ConnectedPeer(new InetSocketAddress(peerId), peerHandler.ref)

  protected val actorRef: ActorRef

  protected def dataFromNetwork[C](spec: MessageSpec[C], data: C, fromPeer: ConnectedPeer = peer): Unit =
    actorRef ! DataFromPeer(spec.messageCode, data, fromPeer)

  protected def blockIds(ids: Int*): BlockIds = ids.map(toBlockId)
  protected implicit def toBlockIds(ids: Seq[Int]): BlockIds = blockIds(ids:_*)
  protected implicit def toBlockId(i: Int): BlockId = Array(i.toByte)

  protected def mockBlock[Id](id: Id)(implicit conv: Id => BlockId): Block =
    new BlockMock(Seq.empty) {
      override val uniqueId: BlockId = id
    }

  protected trait TestDataExtraction[T] {
    def extract(actual: T) : Any
  }

  protected implicit object BlockIdsExtraction extends TestDataExtraction[BlockIds] {
    override def extract(blockIds: BlockIds): Seq[Int] = blockIds.map(BlockIdExtraction.extract)
  }

  protected implicit object BlockIdExtraction extends TestDataExtraction[BlockId] {
    override def extract(blockId: BlockId): Int = blockId(0)
  }

  protected def expectNetworkMessage[Content : TestDataExtraction](expectedSpec: MessageSpec[Content], expectedData: Any): Unit =
    networkController.expectMsgPF(hint = expectedData.toString) {
      case SendToNetwork(Message(spec, Right(data: Content@unchecked), None), SendToChosen(peers)) =>
        peers should contain (peer)
        spec shouldEqual expectedSpec
        implicitly[TestDataExtraction[Content]].extract(data) shouldEqual expectedData
    }
}