package kinesis.mock.cache

import scala.concurrent.duration._

import cats.effect.{Blocker, IO}
import cats.syntax.all._
import org.scalacheck.Test
import org.scalacheck.effect.PropF

import kinesis.mock.LoggingContext
import kinesis.mock.api._
import kinesis.mock.instances.arbitrary._
import kinesis.mock.models._

class GetShardIteratorTests
    extends munit.CatsEffectSuite
    with munit.ScalaCheckEffectSuite {

  override def scalaCheckTestParameters: Test.Parameters =
    Test.Parameters.default.withMinSuccessfulTests(5)

  test("It should get a shard iterator")(PropF.forAllF {
    (
      streamName: StreamName
    ) =>
      Blocker[IO].use(blocker =>
        for {
          cacheConfig <- CacheConfig.read(blocker)
          cache <- Cache(cacheConfig)
          context = LoggingContext.create
          _ <- cache
            .createStream(CreateStreamRequest(1, streamName), context, false)
            .rethrow
          _ <- IO.sleep(cacheConfig.createStreamDuration.plus(200.millis))
          shard <- cache
            .listShards(
              ListShardsRequest(None, None, None, None, None, Some(streamName)),
              context,
              false
            )
            .rethrow
            .map(_.shards.head)
          res <- cache
            .getShardIterator(
              GetShardIteratorRequest(
                shard.shardId,
                ShardIteratorType.TRIM_HORIZON,
                None,
                streamName,
                None
              ),
              context,
              false
            )
            .rethrow
            .map(_.shardIterator)
        } yield assert(
          res.parse.isValid,
          s"$res"
        )
      )
  })
}
