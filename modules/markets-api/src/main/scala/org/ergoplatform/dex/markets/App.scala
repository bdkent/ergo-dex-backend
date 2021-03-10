package org.ergoplatform.dex.markets

import cats.effect.{Blocker, ExitCode, Resource}
import cats.tagless.syntax.functorK._
import org.ergoplatform.dex.EnvApp
import org.ergoplatform.dex.clients.StreamingErgoNetworkClient
import org.ergoplatform.dex.db.{doobieLogging, PostgresTransactor}
import org.ergoplatform.dex.markets.configs.ConfigBundle
import org.ergoplatform.dex.markets.processes.MarketsIndexer
import org.ergoplatform.dex.markets.repositories.TradesRepo
import org.ergoplatform.dex.protocol.ContractType
import sttp.capabilities.fs2.Fs2Streams
import sttp.client3.SttpBackend
import sttp.client3.asynchttpclient.fs2.AsyncHttpClientFs2Backend
import tofu.WithRun
import tofu.doobie.instances.implicits._
import tofu.doobie.log.EmbeddableLogHandler
import tofu.doobie.transactor.Txr
import tofu.fs2Instances._
import tofu.logging.Logs
import tofu.syntax.unlift._

object App extends EnvApp[ConfigBundle] {

  def run(args: List[String]): InitF[ExitCode] =
    resources(args.headOption).use { case (indexer, ctx) =>
      val appF = indexer.run.compile.drain
      appF.run(ctx) as ExitCode.Success
    }

  private def resources(configPathOpt: Option[String]): Resource[InitF, (MarketsIndexer[StreamF], ConfigBundle)] =
    for {
      blocker <- Blocker[InitF]
      configs <- Resource.liftF(ConfigBundle.load[InitF](configPathOpt))
      trans   <- PostgresTransactor.make("markets-api-pool", configs.db)
      implicit0(xa: Txr.Contextual[RunF, ConfigBundle]) = Txr.contextual[RunF](trans)
      implicit0(elh: EmbeddableLogHandler[xa.DB]) <-
        Resource.liftF(doobieLogging.makeEmbeddableHandler[InitF, RunF, xa.DB]("matcher-db-logging"))
      implicit0(logsDb: Logs[InitF, xa.DB]) = Logs.sync[InitF, xa.DB]
      repos <- Resource.liftF(TradesRepo.make[InitF, xa.DB])
      implicit0(reposF: TradesRepo[RunF]) = repos.mapK(xa.trans)
      implicit0(backend: SttpBackend[RunF, Fs2Streams[RunF]]) <- makeBackend(configs, blocker)
      implicit0(client: StreamingErgoNetworkClient[StreamF, RunF]) = StreamingErgoNetworkClient.make[StreamF, RunF]
      indexer <- Resource.liftF(MarketsIndexer.make[InitF, StreamF, RunF, ContractType.LimitOrder])
    } yield indexer -> configs

  private def makeBackend(
    configs: ConfigBundle,
    blocker: Blocker
  )(implicit wr: WithRun[RunF, InitF, ConfigBundle]): Resource[InitF, SttpBackend[RunF, Fs2Streams[RunF]]] =
    Resource
      .liftF(wr.concurrentEffect)
      .flatMap(implicit ce => AsyncHttpClientFs2Backend.resource[RunF](blocker))
      .mapK(wr.runContextK(configs))
}
