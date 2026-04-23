package forex.services.rates

import java.time.Instant
import forex.domain.Rate

import scala.concurrent.duration.FiniteDuration

final case class RatesSnapshot(values: Map[Rate.Pair, Rate], lastUpdated: Instant, isRefreshing: Boolean) {
  def isStale(now: Instant, ttl: FiniteDuration): Boolean = lastUpdated.plusSeconds(ttl.toSeconds).isBefore(now)
}