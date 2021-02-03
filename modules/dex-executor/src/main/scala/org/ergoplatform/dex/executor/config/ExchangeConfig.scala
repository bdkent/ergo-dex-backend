package org.ergoplatform.dex.executor.config

import derevo.derive
import derevo.pureconfig.pureconfigReader
import org.ergoplatform.dex.Address
import tofu.Context

@derive(pureconfigReader)
final case class ExchangeConfig(rewardAddress: Address)

object ExchangeConfig extends Context.Companion[ExchangeConfig]
