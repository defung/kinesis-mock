package kinesis.mock.api

import cats.effect.IO
import cats.effect.concurrent.Ref
import enumeratum.scalacheck._
import org.scalacheck.effect.PropF

import kinesis.mock.instances.arbitrary._
import kinesis.mock.models._
import kinesis.mock.syntax.scalacheck._

class StopStreamEncryptionTests
    extends munit.CatsEffectSuite
    with munit.ScalaCheckEffectSuite {
  test("It should stop stream encryption")(PropF.forAllF {
    (
        streamName: StreamName,
        awsRegion: AwsRegion,
        awsAccountId: AwsAccountId
    ) =>
      val (streams, _) =
        Streams.empty.addStream(1, streamName, awsRegion, awsAccountId)

      val asActive = streams.findAndUpdateStream(streamName)(x =>
        x.copy(streamStatus = StreamStatus.ACTIVE)
      )

      val keyId = keyIdGen.one

      for {
        streamsRef <- Ref.of[IO, Streams](asActive)
        req = StopStreamEncryptionRequest(EncryptionType.KMS, keyId, streamName)
        res <- req.stopStreamEncryption(streamsRef)
        s <- streamsRef.get
      } yield assert(
        res.isValid && s.streams
          .get(streamName)
          .exists { s =>
            s.keyId.isEmpty &&
            s.encryptionType == EncryptionType.NONE &&
            s.streamStatus == StreamStatus.UPDATING
          },
        s"req: $req\nres: $res\nstreams: $asActive"
      )
  })

  test("It should reject when the KMS encryption type is not used")(
    PropF.forAllF {
      (
          streamName: StreamName,
          awsRegion: AwsRegion,
          awsAccountId: AwsAccountId
      ) =>
        val (streams, _) =
          Streams.empty.addStream(1, streamName, awsRegion, awsAccountId)

        val asActive = streams.findAndUpdateStream(streamName)(x =>
          x.copy(streamStatus = StreamStatus.ACTIVE)
        )

        val keyId = keyIdGen.one

        for {
          streamsRef <- Ref.of[IO, Streams](asActive)
          req = StopStreamEncryptionRequest(
            EncryptionType.NONE,
            keyId,
            streamName
          )
          res <- req.stopStreamEncryption(streamsRef)
        } yield assert(
          res.isInvalid,
          s"req: $req\nres: $res\nstreams: $asActive"
        )
    }
  )

  test("It should reject when the stream is not active")(PropF.forAllF {
    (
        streamName: StreamName,
        awsRegion: AwsRegion,
        awsAccountId: AwsAccountId
    ) =>
      val (streams, _) =
        Streams.empty.addStream(1, streamName, awsRegion, awsAccountId)

      val keyId = keyIdGen.one

      for {
        streamsRef <- Ref.of[IO, Streams](streams)
        req = StopStreamEncryptionRequest(
          EncryptionType.KMS,
          keyId,
          streamName
        )
        res <- req.stopStreamEncryption(streamsRef)
      } yield assert(
        res.isInvalid,
        s"req: $req\nres: $res\nstreams: $streams"
      )
  })
}
