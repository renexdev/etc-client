package io.iohk.ethereum.network.p2p

import scala.util.Try
import akka.util.ByteString
import io.iohk.ethereum.utils.RLPImplicits._
import io.iohk.ethereum.utils.{RLPList, _}

object Message {

  def decode(`type`: Int, payload: Array[Byte]): Try[Message] = {
    `type` match {
      case Hello.code => RLP.decode(payload)(Hello.rlpEndDec)
      case Disconnect.code => RLP.decode(payload)(Disconnect.rlpEndDec)
      case Ping.code => RLP.decode(payload)(Ping.rlpEndDec)
      case Pong.code => RLP.decode(payload)(Pong.rlpEndDec)
      case Status.code => RLP.decode(payload)(Status.rlpEndDec)
      case NewBlockHashes.code => RLP.decode(payload)(NewBlockHashes.rlpEndDec)
      case GetBlockHeaders.code => RLP.decode(payload)(GetBlockHeaders.rlpEndDec)
      case BlockBodies.code => RLP.decode(payload)(BlockBodies.rlpEndDec)
      case _ => Try(throw new RuntimeException(s"Unknown message type: ${`type`}"))
    }
  }
}

sealed trait Message {
  def code: Int
}

object Capability {
  implicit val rlpEncDec = new RLPEncoder[Capability] with RLPDecoder[Capability] {
    override def encode(obj: Capability): RLPEncodeable = {
      RLPList(obj.name, obj.version)
    }

    override def decode(rlp: RLPEncodeable): Capability = rlp match {
      case RLPList(name :: version :: Nil) => Capability(name, version)
      case _ => throw new RuntimeException("Cannot decode Capability")
    }
  }
}

case class Capability(name: String, version: Byte)

object Hello {

  implicit val rlpEndDec = new RLPEncoder[Hello] with RLPDecoder[Hello] {
    override def encode(obj: Hello): RLPEncodeable = {
      import obj._
      RLPList(p2pVersion, clientId, capabilities, listenPort, nodeId.toArray[Byte])
    }

    override def decode(rlp: RLPEncodeable): Hello = rlp match {
      case RLPList(p2pVersion :: clientId :: (capabilities: RLPList) :: listenPort :: nodeId :: Nil) =>
        Hello(p2pVersion, clientId, capabilities.items.map(Capability.rlpEncDec.decode),
          listenPort, ByteString(nodeId: Array[Byte]))
      case _ => throw new RuntimeException("Cannot decode Hello")
    }
  }

  val code = 0x00

}

case class Hello(
                  p2pVersion: Long,
                  clientId: String,
                  capabilities: Seq[Capability],
                  listenPort: Long,
                  nodeId: ByteString)
  extends Message {

  override val code: Int = Hello.code
}

object Ping {

  implicit val rlpEndDec = new RLPEncoder[Ping] with RLPDecoder[Ping] {
    override def encode(obj: Ping): RLPEncodeable = RLPList()

    override def decode(rlp: RLPEncodeable): Ping = Ping()
  }

  val code = 0x02
}

case class Ping() extends Message {
  override val code: Int = Ping.code
}

object Pong {

  implicit val rlpEndDec = new RLPEncoder[Pong] with RLPDecoder[Pong] {
    override def encode(obj: Pong): RLPEncodeable = RLPList()

    override def decode(rlp: RLPEncodeable): Pong = Pong()
  }

  val code = 0x03
}

case class Pong() extends Message {
  override val code: Int = Pong.code
}

object BlockBodies {
  implicit val rlpEndDec = new RLPEncoder[BlockBodies] with RLPDecoder[BlockBodies] {
    override def encode(obj: BlockBodies): RLPEncodeable = {
      import obj._
      RLPList(bodies.map(BlockBody.rlpEndDec.encode))
    }

    override def decode(rlp: RLPEncodeable): BlockBodies = rlp match {
      case rlpList: RLPList => BlockBodies(rlpList.items.map(BlockBody.rlpEndDec.decode))
      case _ => throw new RuntimeException("Cannot decode BlockBodies")
    }
  }

  val code: Int = 0x10 + 0x06
}

case class BlockBodies(bodies: Seq[BlockBody]) extends Message {
  val code: Int = BlockBodies.code
}

object BlockBody {
  implicit val rlpEndDec = new RLPEncoder[BlockBody] with RLPDecoder[BlockBody] {
    override def encode(obj: BlockBody): RLPEncodeable = {
      import obj._
      RLPList(transactionList :: uncleNodesList :: Nil)
    }

    override def decode(rlp: RLPEncodeable): BlockBody = rlp match {
      case RLPList((transactions: RLPList) :: (uncles: RLPList) :: Nil) => BlockBody(transactions, uncles)
      case _ => throw new RuntimeException("Cannot decode BlockBody")
    }
  }
}

case class BlockBody(transactionList: RLPList, uncleNodesList: RLPList)

object Status {
  implicit val rlpEndDec = new RLPEncoder[Status] with RLPDecoder[Status] {
    override def encode(obj: Status): RLPEncodeable = {
      import obj._
      RLPList(protocolVersion, networkId, totalDifficulty, bestHash.toArray[Byte], genesisHash.toArray[Byte])
    }

    override def decode(rlp: RLPEncodeable): Status = rlp match {
      case RLPList(protocolVersion :: networkId :: totalDifficulty :: bestHash :: genesisHash :: Nil) =>
        Status(protocolVersion, networkId, totalDifficulty, ByteString(bestHash: Array[Byte]), ByteString(genesisHash: Array[Byte]))
      case _ => throw new RuntimeException("Cannot decode BlockBodies")
    }
  }

  val code: Int = 0x10 + 0x00
}

case class Status(protocolVersion: Int, networkId: Int, totalDifficulty: BigInt, bestHash: ByteString, genesisHash: ByteString) extends Message {
  override def code: Int = Status.code
}

object NewBlockHashes {
  implicit val rlpEndDec = new RLPEncoder[NewBlockHashes] with RLPDecoder[NewBlockHashes] {
    override def encode(obj: NewBlockHashes): RLPEncodeable = {
      import obj._
      RLPList(hashes.map(BlockHash.rlpEndDec.encode))
    }

    override def decode(rlp: RLPEncodeable): NewBlockHashes = rlp match {
      case rlpList: RLPList => NewBlockHashes(rlpList.items.map(BlockHash.rlpEndDec.decode))
      case _ => throw new RuntimeException("Cannot decode BlockBodies")
    }
  }

  val code: Int = 0x10 + 0x01
}

case class NewBlockHashes(hashes: Seq[BlockHash]) extends Message {
  override def code: Int = NewBlockHashes.code
}


object BlockHash {
  implicit val rlpEndDec = new RLPEncoder[BlockHash] with RLPDecoder[BlockHash] {
    override def encode(obj: BlockHash): RLPEncodeable = {
      import obj._
      RLPList(hash, number)
    }

    override def decode(rlp: RLPEncodeable): BlockHash = rlp match {
      case RLPList(hash :: number :: Nil) => BlockHash(ByteString(hash: Array[Byte]), number)
      case _ => throw new RuntimeException("Cannot decode BlockBodies")
    }
  }
}

case class BlockHash(hash: ByteString, number: BigInt)

object GetBlockHeaders {
  implicit val rlpEndDec = new RLPEncoder[GetBlockHeaders] with RLPDecoder[GetBlockHeaders] {
    override def encode(obj: GetBlockHeaders): RLPEncodeable = {
      import obj._
      block match {
        case Left(blockNumber) => RLPList(blockNumber, maxHeaders, skip, reverse)
        case Right(blockHash) => RLPList(blockHash, maxHeaders, skip, reverse)
      }
    }

    override def decode(rlp: RLPEncodeable): GetBlockHeaders = rlp match {
      case RLPList((block: RLPValue) :: maxHeaders :: skip :: reverse :: Nil) if block.bytes.length < 32 =>
        GetBlockHeaders(Left(block), maxHeaders, skip, reverse)

      case RLPList((block: RLPValue) :: maxHeaders :: skip :: reverse :: Nil) =>
        GetBlockHeaders(Right(ByteString(block: Array[Byte])), maxHeaders, skip, reverse)

      case _ => throw new RuntimeException("Cannot decode BlockBodies")
    }
  }

  val code: Int = 0x10 + 0x03
}

case class GetBlockHeaders(block: Either[BigInt, ByteString], maxHeaders: BigInt, skip: BigInt, reverse: Int) extends Message {
  override def code: Int = GetBlockHeaders.code
}


//0x10 + 0x02

object Disconnect {
  implicit val rlpEndDec = new RLPEncoder[Disconnect] with RLPDecoder[Disconnect] {
    override def encode(obj: Disconnect): RLPEncodeable = {
      RLPList(obj.reason)
    }

    override def decode(rlp: RLPEncodeable): Disconnect = rlp match {
      case rlpList: RLPList =>
        Disconnect(reason = rlpList.items.head)
      case _ => throw new RuntimeException("Cannot decode Disconnect")
    }
  }

  val code = 0x01
}

case class Disconnect(reason: Long) extends Message {
  override val code: Int = Disconnect.code

  override def toString: String = {

    val message = reason match {
      case 0x00 => "Disconnect requested"
      case 0x01 => "TCP sub-system error"
      case 0x03 => "Useless peer"
      case 0x04 => "Too many peers"
      case 0x05 => "Already connected"
      case 0x06 => "Incompatible P2P protocol version"
      case 0x07 => "Null node identity received - this is automatically invalid"
      case 0x08 => "Client quitting"
      case 0x09 => "Unexpected identity"
      case 0x0a => "Identity is the same as this node"
      case 0x0b => "Timeout on receiving a message"
      case 0x10 => "Some other reason specific to a subprotocol"
      case _ => s"unknown code $code"
    }

    s"Disconnect($message)"
  }
}