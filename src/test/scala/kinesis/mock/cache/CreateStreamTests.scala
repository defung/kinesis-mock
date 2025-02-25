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

class CreateStreamTests
    extends munit.CatsEffectSuite
    with munit.ScalaCheckEffectSuite {

  override def scalaCheckTestParameters: Test.Parameters =
    Test.Parameters.default.withMinSuccessfulTests(5)

  test("It should create a stream")(PropF.forAllF {
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
          describeStreamSummaryReq = DescribeStreamSummaryRequest(streamName)
          checkStream1 <- cache.describeStreamSummary(
            describeStreamSummaryReq,
            context,
            false
          )
          _ <- IO.sleep(cacheConfig.createStreamDuration.plus(200.millis))
          checkStream2 <- cache.describeStreamSummary(
            describeStreamSummaryReq,
            context,
            false
          )
        } yield assert(
          checkStream1.exists(
            _.streamDescriptionSummary.streamStatus == StreamStatus.CREATING
          ) &&
            checkStream2.exists(
              _.streamDescriptionSummary.streamStatus == StreamStatus.ACTIVE
            )
        )
      )
  })
}
