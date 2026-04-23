package forex.services.rates

import java.time.Instant
import forex.domain.Rate

final case class RatesSnapshot(values: Map[Rate.Pair, Rate], lastUpdated: Instant, isRefreshing: Boolean) {
  def isStale(now: Instant, ttlSeconds: Long): Boolean = lastUpdated.plusSeconds(ttlSeconds).isBefore(now)
}