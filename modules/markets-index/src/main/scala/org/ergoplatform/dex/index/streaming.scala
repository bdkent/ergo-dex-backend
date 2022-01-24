package org.ergoplatform.dex.index

import fs2.kafka.types.KafkaOffset
import org.ergoplatform.common.streaming.Consumer
import org.ergoplatform.dex.domain.amm.state.Confirmed
import org.ergoplatform.dex.domain.amm.{CFMMPool, EvaluatedCFMMOrder, OrderId, PoolId}

object streaming {
  type CFMMHistConsumer[F[_], G[_]]  = Consumer.Aux[OrderId, Option[EvaluatedCFMMOrder.Any], KafkaOffset, F, G]
  type CFMMPoolsConsumer[F[_], G[_]] = Consumer.Aux[PoolId, Confirmed[CFMMPool], KafkaOffset, F, G]
}
