package kinesis.mock
package api

import cats.data.Validated._
import cats.data._
import cats.syntax.all._
import io.circe._

import kinesis.mock.models._

final case class CreateStreamRequest(shardCount: Int, streamName: String) {
  def createStream(
      streams: Streams,
      shardLimit: Int,
      awsRegion: AwsRegion,
      awsAccountId: String
  ): ValidatedNel[KinesisMockException, Streams] =
    (
      CommonValidations.validateStreamName(streamName),
      if (streams.streams.get(streamName).nonEmpty)
        ResourceInUseException(
          s"Stream $streamName already exists"
        ).invalidNel
      else Valid(()),
      CommonValidations.validateShardCount(shardCount),
      if (
        streams.streams.filter { case (_, stream) =>
          stream.streamStatus == StreamStatus.CREATING
        }.size >= 5
      )
        LimitExceededException(
          "Limit for streams being created concurrently exceeded"
        ).invalidNel
      else Valid(()),
      CommonValidations.validateShardLimit(shardCount, streams, shardLimit)
    ).mapN((_, _, _, _, _) =>
      streams.addStream(shardCount, streamName, awsRegion, awsAccountId)
    )
}

object CreateStreamRequest {
  implicit val createStreamRequestCirceEncoder: Encoder[CreateStreamRequest] =
    Encoder.forProduct2("ShardCount", "StreamName")(x =>
      (x.shardCount, x.streamName)
    )
  implicit val createStreamRequestCirceDecoder: Decoder[CreateStreamRequest] = {
    x =>
      for {
        shardCount <- x.downField("ShardCount").as[Int]
        streamName <- x.downField("StreamName").as[String]
      } yield CreateStreamRequest(shardCount, streamName)

  }
}
