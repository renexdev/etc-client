package io.iohk.ethereum.nodebuilder

import akka.actor.ActorSystem
import akka.agent.Agent
import io.iohk.ethereum.blockchain.sync.FastSyncController
import io.iohk.ethereum.crypto.generateKeyPair
import io.iohk.ethereum.db.components.{SharedLevelDBDataSources, Storages}
import io.iohk.ethereum.domain.{Blockchain, BlockchainImpl}
import io.iohk.ethereum.network.protocol.{MessageEncoder, PV63}
import io.iohk.ethereum.network.rlpx.{EncoderDecoder, Message}
import io.iohk.ethereum.network.{PeerManagerActor, ServerActor}
import io.iohk.ethereum.rpc.{JsonRpcServer, RpcServerConfig}
import io.iohk.ethereum.utils.{BlockchainStatus, Config, NodeStatus, ServerStatus}
import scala.concurrent.ExecutionContext.Implicits.global


trait NodeKeyBuilder {
  lazy val nodeKey = generateKeyPair()
}

trait ActorSystemBuilder {
  implicit lazy val actorSystem = ActorSystem("etc-client_system")
}

trait StorageBuilder {
  lazy val storagesInstance =  new SharedLevelDBDataSources with Storages.DefaultStorages
}

trait NodeStatusBuilder {

  self : NodeKeyBuilder =>

  val nodeStatus =
    NodeStatus(
      key = nodeKey,
      serverStatus = ServerStatus.NotListening,
      blockchainStatus = BlockchainStatus(Config.Blockchain.genesisDifficulty, Config.Blockchain.genesisHash, 0))

  val nodeStatusHolder = Agent(nodeStatus)
}

trait BlockChainBuilder {
  self: StorageBuilder =>

  lazy val blockchain: Blockchain = BlockchainImpl(storagesInstance.storages)
}

trait ProtocolBuilder {
  val protocolVersion: Int = MessageEncoder.PV63
  val encoderDecoder: EncoderDecoder = new EncoderDecoder {
    override def decode(`type`: Version, payload: Array[Byte], protocolVersion: Version): Message =
      MessageEncoder.decode(`type`, payload, protocolVersion)
  }
}



trait PeerManagerActorBuilder {

  self: ActorSystemBuilder
    with NodeStatusBuilder
    with BlockChainBuilder
    with ProtocolBuilder =>

  val peerManager = actorSystem.actorOf(
    PeerManagerActor.props(nodeStatusHolder, blockchain, encoderDecoder, protocolVersion), "peer-manager")
}

trait ServerActorBuilder {

  self: ActorSystemBuilder
    with NodeStatusBuilder
    with BlockChainBuilder
    with ProtocolBuilder
    with PeerManagerActorBuilder =>

  val server = actorSystem.actorOf(ServerActor.props(nodeStatusHolder, peerManager), "server")

}


trait JSONRpcServerBuilder {

  self: ActorSystemBuilder  with BlockChainBuilder =>
  def startJSONRpcServer(): Unit = JsonRpcServer.run(actorSystem, blockchain, Config.Network.Rpc)

  lazy val rpcServerConfig: RpcServerConfig = Config.Network.Rpc

}

trait FastSyncControllerBuilder {

  self: ActorSystemBuilder with
    ServerActorBuilder with
    BlockChainBuilder with
    NodeStatusBuilder with
    PeerManagerActorBuilder with
    StorageBuilder =>


  lazy val fastSyncController = actorSystem.actorOf(
    FastSyncController.props(
      peerManager,
      nodeStatusHolder,
      blockchain,
      storagesInstance.storages.mptNodeStorage),
    "fast-sync-controller")

}
trait ShutdownHookBuilder {

  def shutdown()

  Runtime.getRuntime.addShutdownHook(new Thread() {
    override def run(): Unit = {
      shutdown()
    }
  })
}

trait App extends NodeKeyBuilder
  with ActorSystemBuilder
  with ProtocolBuilder
  with StorageBuilder
  with BlockChainBuilder
  with NodeStatusBuilder
  with PeerManagerActorBuilder
  with ServerActorBuilder
  with FastSyncControllerBuilder
  with JSONRpcServerBuilder
  with ShutdownHookBuilder