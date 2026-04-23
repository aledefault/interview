package forex.services.rates.interpreters

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

import cats.effect.{Concurrent, Timer}
import cats.syntax.all._

import scala.concurrent.duration._

import forex.domain.Rate
import forex.services.rates.errors.Error
import forex.services.rates.{RatesProvider, RatesSnapshot}

final class CachedRatesProvider[F[_]](
  ratesProvider: RatesProvider[F],
  ttlSeconds: Long = 120,
  loadingRetryDelay: FiniteDuration = 50.millis,
  loadingMaxRetries: Int = 20
)(implicit
  F: Concurrent[F],
  timer: Timer[F]
) extends RatesProvider[F] {

  private val ratesSnapshotRef = new AtomicReference[Option[RatesSnapshot]](None)

  override def get(pair: Rate.Pair): F[Error Either Rate] =
    F.delay(ratesSnapshotRef.get()).flatMap {
      case Some(snapshot) if !snapshot.isStale(Instant.now(), ttlSeconds) =>
        F.pure(findRate(snapshot.values, pair))

      case Some(snapshot) if snapshot.isRefreshing =>
        processRefreshResult(waitForRatesSnapshotRefresh(loadingMaxRetries), pair)

      case atomicSnapshot @ Some(snapshot) =>
        F.delay {
          val newSnapshot = snapshot.copy(isRefreshing = true)
          ratesSnapshotRef.compareAndSet(atomicSnapshot, Some(newSnapshot))
        }.flatMap(refreshing => refreshOrWait(pair, refreshing))

      case None =>
        F.delay {
          val newSnapshot = RatesSnapshot(Map.empty, Instant.now, isRefreshing = true)
          ratesSnapshotRef.compareAndSet(None, Some(newSnapshot))
        }.flatMap(refreshing => refreshOrWait(pair, refreshing))
    }

  private def processRefreshResult(result: F[Error Either List[Rate]], pair: Rate.Pair): F[Error Either Rate] =
    result.flatMap {
        case Left(error) => F.pure(error.asLeft[Rate])

        // TODO: Refactor this discard and waitForRatesSnapshotRefresh return.
        case Right(_) => F.delay(ratesSnapshotRef.get()).map {
          case None =>
            Error.LookupFailed("Max refresh retries reached.").asLeft[Rate]
          case Some(snapshot) if snapshot.isStale(Instant.now, ttlSeconds) =>
            Error.LookupFailed("Max refresh retries reached. The rate is stale.").asLeft[Rate]
          case Some(snapshot) =>
            findRate(snapshot.values, pair)
        }
      }

  private def refreshOrWait(pair: Rate.Pair, refreshing: Boolean): F[Error Either Rate] =
    if (refreshing) refreshAllRates.map(_.flatMap(findRate(_, pair)))
    else processRefreshResult(waitForRatesSnapshotRefresh(loadingMaxRetries), pair)

  override def getAll: F[Error Either List[Rate]] =
    F.delay(ratesSnapshotRef.get()).flatMap {
      case Some(snapshot) if !snapshot.isStale(Instant.now(), ttlSeconds) =>
        F.pure(snapshot.values.values.toList.asRight[Error])

      case Some(snapshot) if snapshot.isRefreshing =>
          waitForRatesSnapshotRefresh(loadingMaxRetries)

      case atomicSnapshot @ Some(snapshot)  =>
          F.delay {
            val loading = snapshot.copy(isRefreshing = true)
            ratesSnapshotRef.compareAndSet(atomicSnapshot, Some(loading))
          }.flatMap { refreshing =>
            if (refreshing) refreshAllRates.map(_.map(_.values.toList))
            else waitForRatesSnapshotRefresh(loadingMaxRetries)
          }

      case None =>
        F.delay {
          val newSnapshot = RatesSnapshot(Map.empty, Instant.now, isRefreshing = true)
          ratesSnapshotRef.compareAndSet(None, Some(newSnapshot))
        }.flatMap { refreshing =>
          if (refreshing) refreshAllRates.map(_.map(_.values.toList))
          else waitForRatesSnapshotRefresh(loadingMaxRetries)
        }
    }

  private def waitForRatesSnapshotRefresh(retriesLeft: Int): F[Error Either List[Rate]] =
    if (retriesLeft <= 0) {
      F.delay(ratesSnapshotRef.get()).map {
        case Some(snapshot) if snapshot.isStale(Instant.now(), ttlSeconds) =>
          Error.LookupFailed("Max refresh retries reached. The rate is stale.").asLeft[List[Rate]]
        case Some(snapshot) =>
          snapshot.values.values.toList.asRight[Error]
        case None =>
          Error.LookupFailed("Unable to refresh rates. Please, check external service's health.").asLeft[List[Rate]]
      }
    } else {
      // TODO: Pooling is enough for the test, but there must be a better way, using Deferred? And retries/resilence with Retry?
      timer.sleep(loadingRetryDelay) *> F.delay(ratesSnapshotRef.get()).flatMap {
        case Some(snapshot) if snapshot.isRefreshing =>
          waitForRatesSnapshotRefresh(retriesLeft - 1)
        case Some(snapshot) if snapshot.isStale(Instant.now(), ttlSeconds) =>
          waitForRatesSnapshotRefresh(retriesLeft - 1)
        case Some(snapshot) =>
          F.pure(snapshot.values.values.toList.asRight[Error])
        case None =>
          waitForRatesSnapshotRefresh(retriesLeft - 1)
      }
    }

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