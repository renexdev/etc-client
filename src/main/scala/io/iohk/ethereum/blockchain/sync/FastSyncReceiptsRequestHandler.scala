package io.iohk.ethereum.blockchain.sync

import akka.actor.{ActorRef, Props, Scheduler}
import akka.util.ByteString
import io.iohk.ethereum.db.storage.AppStateStorage
import io.iohk.ethereum.domain.Blockchain
import io.iohk.ethereum.network.p2p.messages.PV63.{GetReceipts, Receipts}
import org.spongycastle.util.encoders.Hex

class FastSyncReceiptsRequestHandler(
    peer: ActorRef,
    requestedHashes: Seq[ByteString],
    appStateStorage: AppStateStorage,
    blockchain: Blockchain)(implicit scheduler: Scheduler)
  extends SyncRequestHandler[GetReceipts, Receipts](peer) {

  override val requestMsg = GetReceipts(requestedHashes)
  override val responseMsgCode: Int = Receipts.code

  override def handleResponseMsg(receipts: Receipts): Unit = {
    (requestedHashes zip receipts.receiptsForBlocks).foreach { case (hash, receiptsForBlock) =>
      blockchain.save(hash, receiptsForBlock)
    }

    val receivedHashes = requestedHashes.take(receipts.receiptsForBlocks.size)
    updateBestBlockIfNeeded(receivedHashes)

    if (receipts.receiptsForBlocks.isEmpty) {
      val reason = s"got empty receipts for known hashes: ${requestedHashes.map(h => Hex.toHexString(h.toArray[Byte]))}"
      syncController ! BlacklistSupport.BlacklistPeer(peer, reason)
    }

    val remainingReceipts = requestedHashes.drop(receipts.receiptsForBlocks.size)
    if (remainingReceipts.nonEmpty) {
      syncController ! FastSync.EnqueueReceipts(remainingReceipts)
    }

    log.info("Received {} receipts in {} ms", receipts.receiptsForBlocks.size, timeTakenSoFar())
    cleanupAndStop()
  }

  private def updateBestBlockIfNeeded(receivedHashes: Seq[ByteString]): Unit = {
    val fullBlocks = receivedHashes.flatMap { hash =>
      for {
        header <- blockchain.getBlockHeaderByHash(hash)
        _ <- blockchain.getBlockBodyByHash(hash)
      } yield header
    }

    if (fullBlocks.nonEmpty) {
      val bestReceivedBlock = fullBlocks.maxBy(_.number)
      if (bestReceivedBlock.number > appStateStorage.getBestBlockNumber()) {
        appStateStorage.putBestBlockNumber(bestReceivedBlock.number)
      }
    }
  }

  override def handleTimeout(): Unit = {
    val reason = s"time out on receipts response for known hashes: ${requestedHashes.map(h => Hex.toHexString(h.toArray[Byte]))}"
    syncController ! BlacklistSupport.BlacklistPeer(peer, reason)
    syncController ! FastSync.EnqueueReceipts(requestedHashes)
    cleanupAndStop()
  }

  override def handleTerminated(): Unit = {
    syncController ! FastSync.EnqueueReceipts(requestedHashes)
    cleanupAndStop()
  }
}

object FastSyncReceiptsRequestHandler {
  def props(peer: ActorRef, requestedHashes: Seq[ByteString], appStateStorage: AppStateStorage, blockchain: Blockchain)
           (implicit scheduler: Scheduler): Props =
    Props(new FastSyncReceiptsRequestHandler(peer, requestedHashes, appStateStorage, blockchain))
}
