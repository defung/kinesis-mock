package kinesis.mock

import scala.concurrent.duration._

import cats.effect.IO
import software.amazon.awssdk.services.kinesis.model._

import kinesis.mock.instances.arbitrary._
import kinesis.mock.syntax.javaFuture._
import kinesis.mock.syntax.scalacheck._

class StopStreamEncryptionTests
    extends munit.CatsEffectSuite
    with AwsFunctionalTests {

  fixture.test("It should stop stream encryption") { resources =>
    for {
      keyId <- IO(keyIdGen.one)
      _ <- resources.kinesisClient
        .startStreamEncryption(
          StartStreamEncryptionRequest
            .builder()
            .streamName(resources.streamName.streamName)
            .keyId(keyId)
            .encryptionType(EncryptionType.KMS)
            .build()
        )
        .toIO
      _ <- IO.sleep(
        resources.cacheConfig.startStreamEncryptionDuration.plus(200.millis)
      )
      _ <- resources.kinesisClient
        .stopStreamEncryption(
          StopStreamEncryptionRequest
            .builder()
            .streamName(resources.streamName.streamName)
            .keyId(keyId)
            .encryptionType(EncryptionType.KMS)
            .build()
        )
        .toIO
      _ <- IO.sleep(
        resources.cacheConfig.stopStreamEncryptionDuration.plus(200.millis)
      )
      res <- describeStreamSummary(resources)
    } yield assert(
      res.streamDescriptionSummary().keyId() == null, // scalafix:ok
      res
    )
  }
}
