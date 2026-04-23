package forex.services.rates

import cats.Applicative
import cats.effect.{Concurrent, Sync, Timer}
import interpreters._
import forex.config.ApplicationConfig
import org.http4s.client.Client

object RatesProviders {
  def dummy[F[_]: Applicative]: RatesProvider[F] = new DummyRatesProvider[F]()
  def live[F[_]: Sync](httpClient: Client[F], config: ApplicationConfig): RatesProvider[F] =
    new OneFrameRatesProvider[F](httpClient, config.oneFrame.token, config.oneFrame.baseUri)
  def cached[F[_]: Concurrent: Timer](live: RatesProvider[F], config: ApplicationConfig): RatesProvider[F] =
    new CachedRatesProvider[F](
      live,
      config.cacheRates.ttl,
      config.cacheRates.retryDelay,
      config.cacheRates.maxRetries,
      config.cacheRates.maxRateAge)
}
