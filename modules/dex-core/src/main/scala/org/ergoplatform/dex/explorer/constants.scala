package org.ergoplatform.dex.explorer

import sttp.model.Uri.Segment

object constants {

  private val pathSegmentEncoding       = (x: String) => x
  val submitTransactionPathSeg: Segment = Segment("api/v0/transactions/send", pathSegmentEncoding)
  val blocksPathSeg: Segment            = Segment("api/v0/blocks", pathSegmentEncoding)
  val utxoPathSeg: Segment              = Segment("api/v1/boxes/unspent/byLastEpochs", pathSegmentEncoding)
}
