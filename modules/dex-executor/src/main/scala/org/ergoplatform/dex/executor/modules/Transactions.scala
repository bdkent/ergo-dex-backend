package org.ergoplatform.dex.executor.modules

import cats.data.NonEmptyList
import cats.syntax.option._
import cats.syntax.traverse._
import cats.instances.list._
import cats.{Applicative, Functor, Monad}
import derevo.derive
import org.ergoplatform.ErgoBox._
import org.ergoplatform.contracts.{DexBuyerContractParameters, DexLimitOrderContracts, DexSellerContractParameters}
import org.ergoplatform.dex.AssetId
import org.ergoplatform.dex.configs.ProtocolConfig
import org.ergoplatform.dex.domain.models.FilledOrder._
import org.ergoplatform.dex.domain.models.Trade
import org.ergoplatform.dex.domain.models.Trade.AnyTrade
import org.ergoplatform.dex.{BoxId => DexBoxId}
import org.ergoplatform.dex.domain.syntax.ergo._
import org.ergoplatform.dex.domain.syntax.trade._
import org.ergoplatform.dex.executor.config.ExchangeConfig
import org.ergoplatform.dex.executor.context.BlockchainContext
import org.ergoplatform.dex.executor.domain.errors.ExecutionFailure
import org.ergoplatform.{ErgoAddressEncoder, ErgoBoxCandidate, ErgoLikeTransaction, Input}
import sigmastate.SType.AnyOps
import sigmastate.Values._
import sigmastate.eval._
import sigmastate.interpreter.ProverResult
import sigmastate.{SByte, SCollection, SLong, SType}
import tofu.Raise
import tofu.higherKind.Mid
import tofu.higherKind.derived.representableK
import tofu.syntax.context._
import tofu.syntax.embed._
import tofu.syntax.monadic._
import tofu.syntax.feither._

@derive(representableK)
trait Transactions[F[_]] {

  /** Translate a given `trade` to executing transaction.
    */
  def translate(trade: AnyTrade): F[ErgoLikeTransaction]
}

object Transactions {

  implicit def instance[
    F[_]: Monad: Raise[*[_], ExecutionFailure]: ExchangeConfig.Has: ProtocolConfig.Has: BlockchainContext.Has
  ]: Transactions[F] =
    (hasContext[F, ExchangeConfig], hasContext[F, ProtocolConfig], hasContext[F, BlockchainContext]).mapN {
      (dexConf, protoConf, blockchainCtx) =>
        new DexOutputsCompaction[F](dexConf, protoConf) attach new Live[F](dexConf, protoConf, blockchainCtx)
    }.embed

  final class Live[F[_]: Monad: Raise[*[_], ExecutionFailure]: BlockchainContext.Has](
    exchangeConfig: ExchangeConfig,
    protocolConfig: ProtocolConfig,
    ctx: BlockchainContext
  )(implicit valueValidation: OutputValueValidation[F])
    extends Transactions[F] {

    implicit private val addressEncoder: ErgoAddressEncoder = protocolConfig.networkType.addressEncoder
    private val dexRewardProp                               = exchangeConfig.rewardAddress.toErgoTree

    def translate(trade: AnyTrade): F[ErgoLikeTransaction] = {
      val outsF =
        trade.refine match {
          case Right(Trade(order, counterOrders)) =>
            outputs(NonEmptyList.one(order), counterOrders)
          case Left(Trade(order, counterOrders)) =>
            outputs(counterOrders, NonEmptyList.one(order))
        }
      outsF.map { outs =>
        val ins = inputs(trade.orders)
        ErgoLikeTransaction(ins.toList.toVector, outs.toList.toVector)
      }
    }

    private def inputs(orders: NonEmptyList[AnyFilledOrder]): NonEmptyList[Input] =
      orders.map(ord => Input(ord.base.meta.boxId.toErgo, ProverResult.empty))

    private def outputs(
      asks: NonEmptyList[FilledAsk],
      bids: NonEmptyList[FilledBid]
    ): F[NonEmptyList[ErgoBoxCandidate]] = {
      val askAmount = asks.toList.map(_.base.amount).sum
      val bidAmount = bids.toList.map(_.base.amount).sum
      (executeAsks(asks.toList)(bidAmount), executeBids(bids.toList)(askAmount))
        .mapN((asks, bids) => NonEmptyList.fromListUnsafe(asks ++ bids))
    }

    private def executeAsks(
      asks: List[FilledAsk]
    )(amountToFill: Long, acc: List[ErgoBoxCandidate] = List.empty): F[List[ErgoBoxCandidate]] =
      asks match {
        case ask :: tl =>
          val desiredAmount   = ask.base.amount
          val amount          = desiredAmount min amountToFill
          val price           = ask.executionPrice
          val feePerToken     = ask.base.feePerToken
          val fee             = amount * feePerToken
          val rem             = ask.base.meta.boxValue - fee // todo: what if remainder is too small? Check
          val reward          = amount * price
          val sellerPk        = ask.base.meta.pk
          val dexFeeBox       = new ErgoBoxCandidate(fee, dexRewardProp, ctx.currentHeight)
          val returnRegisters = returnBoxRegs(ask.base.meta.boxId)
          if (desiredAmount <= amountToFill) {
            val returnAmount = reward + rem
            val returnBox =
              new ErgoBoxCandidate(returnAmount, sellerPk, ctx.currentHeight, additionalRegisters = returnRegisters)
            val outs = List(returnBox, dexFeeBox)
            outs.traverse(valueValidation.validate(_).absolve) >>
            executeAsks(tl)(amountToFill - amount, outs ++ acc)
          } else {
            val returnBox =
              new ErgoBoxCandidate(reward, sellerPk, ctx.currentHeight, additionalRegisters = returnRegisters)
            val assetId = ask.base.quoteAsset
            val residualParams =
              DexSellerContractParameters(ask.base.meta.pk, assetId.toErgo, ask.base.price, feePerToken)
            val residualScript    = DexLimitOrderContracts.sellerContractInstance(residualParams).ergoTree
            val unfilled          = ask.base.amount - amountToFill
            val residualTokens    = Colls.fromItems(assetId.toErgo -> unfilled)
            val residualRegisters = residualBoxRegs(assetId, price, feePerToken, ask.base.meta.boxId)
            val residualBox =
              new ErgoBoxCandidate(rem, residualScript, ctx.currentHeight, residualTokens, residualRegisters)
            val outs = List(residualBox, returnBox, dexFeeBox)
            outs.traverse(valueValidation.validate(_).absolve) >>
            executeAsks(tl)(amountToFill = 0L, outs ++ acc)
          }
        case Nil =>
          acc.pure[F]
      }

    private def executeBids(
      bids: List[FilledBid]
    )(amountToFill: Long, acc: List[ErgoBoxCandidate] = List.empty): F[List[ErgoBoxCandidate]] =
      bids match {
        case bid :: tl =>
          val desiredAmount   = bid.base.amount
          val amount          = desiredAmount min amountToFill
          val price           = bid.executionPrice
          val feePerToken     = bid.base.feePerToken
          val assetId         = bid.base.quoteAsset
          val fee             = amount * feePerToken
          val rem             = bid.base.meta.boxValue - fee - amount * price
          val reward          = amount
          val buyerPk         = bid.base.meta.pk
          val returnTokens    = Colls.fromItems(assetId.toErgo -> reward)
          val returnRegisters = returnBoxRegs(bid.base.meta.boxId)
          val returnBox       = new ErgoBoxCandidate(rem, buyerPk, ctx.currentHeight, returnTokens, returnRegisters)
          if (desiredAmount <= amountToFill) {
            val minReturnBoxValue = returnBox.bytesWithNoRef.length * ctx.nanoErgsPerByte
            val missingValue      = minReturnBoxValue - rem
            val dexFeeLeft        = fee - missingValue
            val returnBoxRefilled =
              new ErgoBoxCandidate(minReturnBoxValue, buyerPk, ctx.currentHeight, returnTokens, returnRegisters)
            val dexFeeBox = new ErgoBoxCandidate(dexFeeLeft, dexRewardProp, ctx.currentHeight)
            val outs      = List(returnBoxRefilled, dexFeeBox)
            outs.traverse(valueValidation.validate(_).absolve) >>
            executeBids(tl)(amountToFill - amount, outs ++ acc)
          } else {
            val residualParams =
              DexBuyerContractParameters(
                bid.base.meta.pk,
                assetId.toErgo,
                bid.base.price,
                feePerToken
              )
            val residualScript    = DexLimitOrderContracts.buyerContractInstance(residualParams).ergoTree
            val residualRegisters = residualBoxRegs(assetId, price, feePerToken, bid.base.meta.boxId)
            val residualBox =
              new ErgoBoxCandidate(rem, residualScript, ctx.currentHeight, Colls.emptyColl, residualRegisters)
            val dexFeeBox = new ErgoBoxCandidate(fee, dexRewardProp, ctx.currentHeight)
            val outs      = List(residualBox, returnBox, dexFeeBox)
            outs.traverse(valueValidation.validate(_).absolve) >>
            executeBids(tl)(amountToFill = 0L, outs ++ acc)
          }
        case Nil =>
          acc.pure[F]
      }

    private def returnBoxRegs(orderBoxId: DexBoxId): Map[NonMandatoryRegisterId, EvaluatedValue[SType]] =
      Map(R4 -> Constant(orderBoxId.toSigma.asWrappedType, SCollection(SByte)))

    private def residualBoxRegs(
      assetId: AssetId,
      price: Long,
      feePerToken: Long,
      originBoxId: DexBoxId
    ): Map[NonMandatoryRegisterId, EvaluatedValue[SType]] =
      Map(
        R4 -> Constant(assetId.toSigma.asWrappedType, SCollection(SByte)),
        R5 -> Constant(price.asWrappedType, SLong),
        R6 -> Constant(feePerToken.asWrappedType, SLong),
        R7 -> Constant(originBoxId.toSigma.asWrappedType, SCollection(SByte))
      )
  }

  /** An aspect merging dex reward outputs into single one.
    */
  final class DexOutputsCompaction[F[_]: Functor](exchangeConfig: ExchangeConfig, protocolConfig: ProtocolConfig)
    extends Transactions[Mid[F, *]] {

    implicit private val addressEncoder: ErgoAddressEncoder = protocolConfig.networkType.addressEncoder
    private val dexRewardProp                               = exchangeConfig.rewardAddress.toErgoTree

    def translate(trade: AnyTrade): Mid[F, ErgoLikeTransaction] =
      _.map(compact)

    private def compact(tx: ErgoLikeTransaction): ErgoLikeTransaction =
      tx.outputCandidates.partition(_.ergoTree == dexRewardProp) match {
        case (dexOuts, _) if dexOuts.length == 1 => tx
        case (dexOuts, otherOuts) =>
          val dexOutsMerged = dexOuts.foldLeft(none[ErgoBoxCandidate]) {
            case (None, out) => out.some
            case (Some(acc), out) =>
              new ErgoBoxCandidate(
                acc.value + out.value,
                acc.ergoTree,
                acc.creationHeight,
                acc.additionalTokens,
                acc.additionalRegisters
              ).some
          }
          val outs = dexOutsMerged ++ otherOuts
          new ErgoLikeTransaction(tx.inputs, tx.dataInputs, outs.toVector)
      }
  }
}
