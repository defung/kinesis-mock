package kinesis.mock
package api

import java.time.Instant

import cats.data.Validated._
import cats.effect.IO
import cats.effect.concurrent.Ref
import cats.kernel.Eq
import cats.syntax.all._
import io.circe

import kinesis.mock.instances.circe._
import kinesis.mock.models._
import kinesis.mock.validations.CommonValidations

// https://docs.aws.amazon.com/kinesis/latest/APIReference/API_ListShards.html
final case class ListShardsRequest(
    exclusiveStartShardId: Option[String],
    maxResults: Option[Int],
    nextToken: Option[String],
    shardFilter: Option[ShardFilter],
    streamCreationTimestamp: Option[Instant],
    streamName: Option[StreamName]
) {
  def listShards(
      streamsRef: Ref[IO, Streams]
  ): IO[ValidatedResponse[ListShardsResponse]] = streamsRef.get.map { streams =>
    (
      exclusiveStartShardId,
      nextToken,
      shardFilter,
      streamCreationTimestamp,
      streamName
    ) match {
      case (None, Some(nt), None, None, None) =>
        CommonValidations
          .validateNextToken(nt)
          .andThen(ListShardsRequest.parseNextToken)
          .andThen { case (streamName, shardId) =>
            (
              CommonValidations.validateStreamName(streamName),
              CommonValidations.validateShardId(shardId),
              CommonValidations.findStream(streamName, streams),
              maxResults match {
                case Some(l) => CommonValidations.validateMaxResults(l)
                case _       => Valid(())
              },
              shardFilter match {
                case Some(sf) => ListShardsRequest.validateShardFilter(sf)
                case None     => Valid(())
              }
            ).mapN((_, _, stream, _, _) => {
              val allShards = stream.shards.keys.toList
              val lastShardIndex = allShards.length - 1
              val limit = maxResults.map(l => Math.min(l, 100)).getOrElse(100)
              val firstIndex =
                allShards.indexWhere(_.shardId.shardId == shardId) + 1
              val lastIndex = Math.min(firstIndex + limit, lastShardIndex + 1)
              val shards = allShards.slice(firstIndex, lastIndex)
              val nextToken =
                if (lastShardIndex + 1 == lastIndex) None
                else
                  Some(
                    ListShardsRequest
                      .createNextToken(streamName, shards.last.shardId.shardId)
                  )
              ListShardsResponse(nextToken, shards.map(ShardSummary.fromShard))
            })
          }
      case (_, None, _, _, Some(sName)) =>
        CommonValidations
          .findStream(sName, streams)
          .andThen(stream =>
            (
              CommonValidations.validateStreamName(sName),
              exclusiveStartShardId match {
                case Some(eShardId) =>
                  CommonValidations.validateShardId(eShardId)
                case None => Valid(())
              },
              shardFilter match {
                case Some(sf) => ListShardsRequest.validateShardFilter(sf)
                case None     => Valid(())
              }
            ).mapN((_, _, _) => {
              val allShards: List[Shard] = stream.shards.keys.toList
              val filteredShards = shardFilter match {
                case Some(sf)
                    if sf.`type` == ShardFilterType.AT_TRIM_HORIZON ||
                      (sf.`type` == ShardFilterType.AT_TIMESTAMP && sf.timestamp
                        .exists(x =>
                          x.getEpochSecond < stream.streamCreationTimestamp.getEpochSecond
                        )) ||
                      (sf.`type` == ShardFilterType.FROM_TIMESTAMP && sf.timestamp
                        .exists(x =>
                          x.getEpochSecond < stream.streamCreationTimestamp.getEpochSecond
                        )) =>
                  allShards
                case Some(sf)
                    if sf.`type` == ShardFilterType.FROM_TRIM_HORIZON =>
                  val now = Instant.now()
                  allShards.filter(x =>
                    x.closedTimestamp.isEmpty || x.closedTimestamp.exists(x =>
                      x.plusSeconds(stream.retentionPeriod.toSeconds)
                        .getEpochSecond >= now.getEpochSecond
                    )
                  )

                case Some(sf) if sf.`type` == ShardFilterType.AT_LATEST =>
                  allShards.filter(_.isOpen)

                case Some(sf) if sf.`type` == ShardFilterType.AT_TIMESTAMP =>
                  allShards.filter(x =>
                    sf.timestamp.exists(ts =>
                      x.createdAtTimestamp.getEpochSecond <= ts.getEpochSecond && (x.isOpen || x.closedTimestamp
                        .exists(cTs => cTs.getEpochSecond >= ts.getEpochSecond))
                    )
                  )

                case Some(sf) if sf.`type` == ShardFilterType.FROM_TIMESTAMP =>
                  allShards.filter(x =>
                    x.isOpen || sf.timestamp.exists(ts =>
                      x.closedTimestamp
                        .exists(cTs => cTs.getEpochSecond >= ts.getEpochSecond)
                    )
                  )
                case Some(sf) if sf.`type` == ShardFilterType.AFTER_SHARD_ID =>
                  val index = sf.shardId
                    .map(eShardId =>
                      allShards.indexWhere(_.shardId.shardId == eShardId) + 1
                    )
                    .getOrElse(0)
                  allShards.slice(index, allShards.length)

                case _ => allShards
              }
              val lastShardIndex = filteredShards.length - 1
              val limit = maxResults.map(l => Math.min(l, 100)).getOrElse(100)
              val firstIndex = exclusiveStartShardId
                .map(eShardId =>
                  filteredShards.indexWhere(_.shardId.shardId == eShardId) + 1
                )
                .getOrElse(0)
              val lastIndex = Math.min(firstIndex + limit, lastShardIndex + 1)
              val shards = filteredShards.slice(firstIndex, lastIndex)
              val nextToken =
                if (lastShardIndex + 1 == lastIndex) None
                else
                  Some(
                    ListShardsRequest
                      .createNextToken(sName, shards.last.shardId.shardId)
                  )
              ListShardsResponse(nextToken, shards.map(ShardSummary.fromShard))
            })
          )
      case (_, None, _, _, None) =>
        InvalidArgumentException(
          "StreamName is required if NextToken is not provided"
        ).invalidNel
      case _ =>
        InvalidArgumentException(
          "Cannot define ExclusiveStartShardId, StreamCreationTimestamp or StreamName if NextToken is defined"
        ).invalidNel
    }
  }
}

object ListShardsRequest {
  def listShardsRequestCirceEncoder(implicit
      ESF: circe.Encoder[ShardFilter],
      EI: circe.Encoder[Instant]
  ): circe.Encoder[ListShardsRequest] =
    circe.Encoder.forProduct6(
      "ExclusiveStartShardId",
      "MaxResults",
      "NextToken",
      "ShardFilter",
      "StreamCreationTimestamp",
      "StreamName"
    )(x =>
      (
        x.exclusiveStartShardId,
        x.maxResults,
        x.nextToken,
        x.shardFilter,
        x.streamCreationTimestamp,
        x.streamName
      )
    )
  def listShardsRequestCirceDecoder(implicit
      DSF: circe.Decoder[ShardFilter],
      DI: circe.Decoder[Instant]
  ): circe.Decoder[ListShardsRequest] = { x =>
    for {
      exclusiveStartShardId <- x
        .downField("ExclusiveStartShardId")
        .as[Option[String]]
      maxResults <- x.downField("MaxResults").as[Option[Int]]
      nextToken <- x.downField("NextToken").as[Option[String]]
      shardFilter <- x.downField("ShardFilter").as[Option[ShardFilter]]
      streamCreationTimestamp <- x
        .downField("StreamCreationTimestamp")
        .as[Option[Instant]]
      streamName <- x.downField("StreamName").as[Option[StreamName]]
    } yield ListShardsRequest(
      exclusiveStartShardId,
      maxResults,
      nextToken,
      shardFilter,
      streamCreationTimestamp,
      streamName
    )
  }
  implicit val listShardsRequestEncoder: Encoder[ListShardsRequest] =
    Encoder.instance(
      listShardsRequestCirceEncoder(
        Encoder[ShardFilter].circeEncoder,
        instantBigDecimalCirceEncoder
      ),
      listShardsRequestCirceEncoder(
        Encoder[ShardFilter].circeCborEncoder,
        instantLongCirceEncoder
      )
    )
  implicit val listShardsRequestDecoder: Decoder[ListShardsRequest] =
    Decoder.instance(
      listShardsRequestCirceDecoder(
        Decoder[ShardFilter].circeDecoder,
        instantBigDecimalCirceDecoder
      ),
      listShardsRequestCirceDecoder(
        Decoder[ShardFilter].circeCborDecoder,
        instantLongCirceDecoder
      )
    )

  implicit val listShardsRequestEq: Eq[ListShardsRequest] =
    (x, y) =>
      x.exclusiveStartShardId == y.exclusiveStartShardId &&
        x.maxResults == y.maxResults &&
        x.nextToken == y.nextToken &&
        x.shardFilter === y.shardFilter &&
        x.streamCreationTimestamp.map(
          _.getEpochSecond()
        ) == y.streamCreationTimestamp.map(_.getEpochSecond()) &&
        x.streamName == y.streamName

  def createNextToken(streamName: StreamName, shardId: String): String =
    s"$streamName::$shardId"
  def parseNextToken(
      nextToken: String
  ): ValidatedResponse[(StreamName, String)] = {
    val split = nextToken.split("::")
    if (split.length != 2)
      InvalidArgumentException(s"NextToken is improperly formatted").invalidNel
    else Valid((StreamName(split.head), split(1)))
  }
  def validateShardFilter(
      shardFilter: ShardFilter
  ): ValidatedResponse[ShardFilter] =
    shardFilter.`type` match {
      case ShardFilterType.AFTER_SHARD_ID if shardFilter.shardId.isEmpty =>
        InvalidArgumentException(
          "ShardId must be supplied in a ShardFilter with a Type of AFTER_SHARD_ID"
        ).invalidNel
      case ShardFilterType.AT_TIMESTAMP | ShardFilterType.FROM_TIMESTAMP
          if shardFilter.timestamp.isEmpty =>
        InvalidArgumentException(
          "Timestamp must be supplied in a ShardFilter with a Type of FROM_TIMESTAMP or AT_TIMESTAMP"
        ).invalidNel
      case _ => Valid(shardFilter)
    }
}
