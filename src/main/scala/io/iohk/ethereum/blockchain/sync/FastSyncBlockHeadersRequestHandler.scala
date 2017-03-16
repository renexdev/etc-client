package io.iohk.ethereum.blockchain.sync

import akka.actor.{ActorRef, Props, Scheduler}
import io.iohk.ethereum.domain.{BlockHeader, Blockchain}
import io.iohk.ethereum.network.p2p.messages.PV62.{BlockHeaders, GetBlockHeaders}

class FastSyncBlockHeadersRequestHandler(
    peer: ActorRef,
    val requestMsg: GetBlockHeaders,
    resolveBranches: Boolean,
    blockchain: Blockchain)(implicit scheduler: Scheduler)
  extends FastSyncRequestHandler[GetBlockHeaders, BlockHeaders](peer) {

  override val responseMsgCode: Int = BlockHeaders.code

  override def handleResponseMsg(blockHeaders: BlockHeaders): Unit = {
    val parentExists = blockHeaders.headers.headOption.flatMap(h => blockchain.getBlockByHash(h.parentHash)).nonEmpty

    val headers = if (requestMsg.reverse)
      blockHeaders.headers.reverse
     else
      blockHeaders.headers

    val consistentHeaders = checkHeaders(headers)

    (parentExists, resolveBranches, consistentHeaders) match {
      case (false, false, true) =>
        fastSyncController ! BlacklistSupport.BlacklistPeer(peer)

      case (true, false, true) if headers.nonEmpty =>
        val blockHashes = headers.map(_.hash)
        fastSyncController ! SyncController.BlockHeadersReceived(headers)
        fastSyncController ! SyncController.EnqueueBlockBodies(blockHashes)
        fastSyncController ! SyncController.EnqueueReceipts(blockHashes)
        log.info("Received {} block headers in {} ms", headers.size, timeTakenSoFar())

      case (_, true, true) if headers.nonEmpty =>
        fastSyncController ! SyncController.BlockHeadersToResolve(peer, blockHeaders.headers)
        log.info("Received {} block headers in {} ms", headers.size, timeTakenSoFar())

      case (_, _, false) =>
        fastSyncController ! BlacklistSupport.BlacklistPeer(peer)

      case _ if headers.isEmpty =>
        fastSyncController ! SyncController.BlockHeadersReceived(Seq.empty)
        log.info("Received {} block headers in {} ms", headers.size, timeTakenSoFar())
    }

    cleanupAndStop()
  }

  override def handleTimeout(): Unit = {
    fastSyncController ! BlacklistSupport.BlacklistPeer(peer)
    cleanupAndStop()
  }

  override def handleTerminated(): Unit = {
    cleanupAndStop()
  }

  private def checkHeaders(headers: Seq[BlockHeader]): Boolean =
    headers.zip(headers.tail).forall { case (parent, child) => parent.hash == child.parentHash && parent.number + 1 == child.number }
}

object FastSyncBlockHeadersRequestHandler {
  def props(peer: ActorRef, requestMsg: GetBlockHeaders, resolveBranches: Boolean, blockchain: Blockchain)
           (implicit scheduler: Scheduler): Props =
    Props(new FastSyncBlockHeadersRequestHandler(peer, requestMsg, resolveBranches, blockchain))
}
