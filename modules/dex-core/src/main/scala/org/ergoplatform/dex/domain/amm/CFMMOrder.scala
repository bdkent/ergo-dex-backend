package org.ergoplatform.dex.domain.amm

import derevo.circe.{decoder, encoder}
import derevo.derive
import org.ergoplatform.ergo.domain.Output
import tofu.logging.derivation.loggable

@derive(encoder, decoder, loggable)
sealed trait CFMMOrder {
  val poolId: PoolId
  val maxMinerFee: Long
  val box: Output
  val timestamp: Long

  def id: OrderId = OrderId.fromBoxId(box.boxId)
}

@derive(encoder, decoder, loggable)
final case class Deposit(poolId: PoolId, maxMinerFee: Long, timestamp: Long, params: DepositParams, box: Output)
  extends CFMMOrder

@derive(encoder, decoder, loggable)
final case class Redeem(poolId: PoolId, maxMinerFee: Long, timestamp: Long, params: RedeemParams, box: Output)
  extends CFMMOrder

@derive(encoder, decoder, loggable)
final case class Swap(poolId: PoolId, maxMinerFee: Long, timestamp: Long, params: SwapParams, box: Output)
  extends CFMMOrder
