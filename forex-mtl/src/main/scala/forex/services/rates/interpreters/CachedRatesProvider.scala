package forex.services.rates.interpreters

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

import cats.effect.Concurrent.ops.toAllConcurrentOps
import cats.effect.{Concurrent, Timer}
import cats.syntax.all._

import scala.concurrent.duration._

import forex.domain.Rate
import forex.services.rates.errors.Error
import forex.services.rates.{RatesProvider, RatesSnapshot}

final class CachedRatesProvider[F[_]](
  ratesProvider: RatesProvider[F],
  ttlSeconds: Long,
  loadingRetryDelay: FiniteDuration = 50.millis,
  loadingMaxRetries: Int = 20
)(implicit
  F: Concurrent[F],
  timer: Timer[F]
) extends RatesProvider[F] {

  private val ratesSnapshotRef = new AtomicReference[Option[RatesSnapshot]](None)

  override def get(pair: Rate.Pair): F[Error Either Rate] =
    F.delay(ratesSnapshotRef.get()).flatMap {
      case None =>
        handleMissingRatesSnapshot(pair)
      case Some(snapshot) =>
        handleRatesSnapshot(pair, snapshot)
    }

  private def handleMissingRatesSnapshot(pair: Rate.Pair): F[Error Either Rate] =
    F.delay {
      val loading = RatesSnapshot(Map.empty, Instant.EPOCH, isRefreshing = true)
      ratesSnapshotRef.compareAndSet(None, Some(loading))
    }.flatMap { refreshing =>
      if (refreshing)
        refreshAllRates.map(_.flatMap(findRate(_, pair)))
      else
        waitForRatesSnapshot(pair, loadingMaxRetries)
    }

  private def waitForRatesSnapshot(
    pair: Rate.Pair,
    retriesLeft: Int
  ): F[Error Either Rate] =
    if (retriesLeft <= 0) {
      F.delay(ratesSnapshotRef.get()).map {
        case Some(snapshot) => findRate(snapshot.values, pair)
        case None => Left(Error.ExternalServiceError("Unable to refresh rates. Please, check external service's health."))
      }
    } else {
      // TODO: Pooling is enough for the test, but there must be a better way, using Deferred? And retries/resilence with Retry?
      timer.sleep(loadingRetryDelay) *> F.delay(ratesSnapshotRef.get()).flatMap {
          case None => waitForRatesSnapshot(pair, retriesLeft - 1)
          case Some(snapshot) if snapshot.isRefreshing => waitForRatesSnapshot(pair, retriesLeft - 1)
          case Some(snapshot) => F.pure(findRate(snapshot.values, pair))
        }
    }

  private def handleRatesSnapshot(pair: Rate.Pair, snapshot: RatesSnapshot): F[Error Either Rate] = {
    val now = Instant.now()

    if (!snapshot.isStale(now, ttlSeconds))
      F.pure(findRate(snapshot.values, pair))
    else if (!snapshot.isRefreshing)
      tryStartRefresh(snapshot) *> F.pure(findRate(snapshot.values, pair))
    else // TODO: We can either return a staled rate or wait. This should be discussed with the team.
      waitForRatesSnapshot(pair, loadingMaxRetries)
  }

  override def getAll: F[Error Either List[Rate]] =
    F.delay(ratesSnapshotRef.get()).flatMap {
      case None =>
        refreshAllRates.map(_.map(_.values.toList))

      case Some(snapshot) =>
        val now = Instant.now()

        if (!snapshot.isStale(now, ttlSeconds))
          F.pure(snapshot.values.values.toList.asRight[Error])
        else if (!snapshot.isRefreshing)
          tryStartRefresh(snapshot) *> F.pure(snapshot.values.values.toList.asRight[Error])
        else
          F.pure(snapshot.values.values.toList.asRight[Error])
    }

  private def tryStartRefresh(current: RatesSnapshot): F[Unit] =
    F.delay {
      val refreshing = current.copy(isRefreshing = true)
      ratesSnapshotRef.compareAndSet(Some(current), Some(refreshing))
    }.flatMap { refreshing =>
      if (refreshing) refreshInBackground.start.void
      else F.unit
    }

  private def refreshInBackground: F[Unit] = refreshAllRates.void.handleErrorWith(_ => F.unit)

  private def refreshAllRates: F[Error Either Map[Rate.Pair, Rate]] =
    ratesProvider.getAll.flatMap {
      case Right(newRates) =>
        val mapped = newRates.map(rate => rate.pair -> rate).toMap

        F.delay {
          ratesSnapshotRef.set(
            Some(
              RatesSnapshot(
                values = mapped,
                lastUpdated = Instant.now(),
                isRefreshing = false
              )
            )
          )
        } *> F.pure(mapped.asRight[Error])

      // TODO: Decide what to do with stale and non-refreshable values: error after loadingMaxRetries retries?
      // Refresh failed, so we reset isRefreshing
      case Left(error) =>
        F.delay {
          val current = ratesSnapshotRef.get()
          current match {
            case Some(snapshot) =>
              ratesSnapshotRef.set(Some(snapshot.copy(isRefreshing = false)))
            case None =>
              ratesSnapshotRef.set(None)
          }
        } *> F.pure(error.asLeft[Map[Rate.Pair, Rate]])
    }

  private def findRate(values: Map[Rate.Pair, Rate], pair: Rate.Pair): Either[Error, Rate] =
    values.get(pair).toRight(Error.UnsupportedRate(s"Rate from ${pair.from} to ${pair.to} is not supported."))

}