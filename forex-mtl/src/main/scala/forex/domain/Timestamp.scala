package forex.domain

import java.time.Instant
import java.time.OffsetDateTime

import scala.concurrent.duration.FiniteDuration

case class Timestamp(value: OffsetDateTime) extends AnyVal {
  def isOlderThan(now: Instant, maxAge: FiniteDuration): Boolean =
    value.toInstant.plusMillis(maxAge.toMillis).isBefore(now)
}

object Timestamp {
  def now: Timestamp =
    Timestamp(OffsetDateTime.now)
}