package forex.config

import scala.concurrent.duration.FiniteDuration

case class ApplicationConfig(
    http: HttpConfig,
    oneFrame: OneFrameConfig,
    cacheRates: CacheRatesConfig
)

case class HttpConfig(
    host: String,
    port: Int,
    timeout: FiniteDuration
)

case class OneFrameConfig(
    baseUri: String,
    token: String
)

case class CacheRatesConfig(
    ttl: FiniteDuration,
    retryDelay: FiniteDuration,
    maxRetries: Int,
)