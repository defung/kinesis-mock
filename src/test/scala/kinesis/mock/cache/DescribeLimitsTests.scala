package kinesis.mock.cache

import cats.effect.{Blocker, IO}
import cats.syntax.all._

import kinesis.mock.LoggingContext

class DescribeLimitsTests extends munit.CatsEffectSuite {
  test("It should describe limits")(
    Blocker[IO].use(blocker =>
      for {
        cacheConfig <- CacheConfig.read(blocker)
        cache <- Cache(cacheConfig)
        res <- cache.describeLimits(LoggingContext.create).rethrow
      } yield assert(
        res.openShardCount == 0 && res.shardLimit == cacheConfig.shardLimit,
        res
      )
    )
  )
}
