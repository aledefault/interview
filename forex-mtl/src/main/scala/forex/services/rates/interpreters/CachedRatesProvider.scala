package forex.services.rates.interpreters

import cats.effect.{Concurrent, Timer}
import cats.syntax.all._
import forex.domain.Rate
import forex.services.rates.errors.Error
import forex.services.rates.{RatesProvider, RatesSnapshot}

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import retry._
import retry.RetryPolicies._

import scala.concurrent.duration._

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
  private val refreshWaitPolicy: RetryPolicy[F] = limitRetries[F](loadingMaxRetries) join constantDelay[F](loadingRetryDelay) // It could be done with a Jitter for better results, but I'm prioritizing other things

  override def get(pair: Rate.Pair): F[Error Either Rate] =
    F.delay(ratesSnapshotRef.get()).flatMap {
      case Some(snapshot) if !snapshot.isStale(Instant.now(), ttlSeconds) =>
        F.pure(findRate(snapshot.values, pair))

      case Some(snapshot) if snapshot.isRefreshing =>
        processRefreshResult(waitForRatesSnapshotRefresh, pair)

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
    else processRefreshResult(waitForRatesSnapshotRefresh, pair)

  override def getAll: F[Error Either List[Rate]] =
    F.delay(ratesSnapshotRef.get()).flatMap {
      case Some(snapshot) if !snapshot.isStale(Instant.now(), ttlSeconds) =>
        F.pure(snapshot.values.values.toList.asRight[Error])

      case Some(snapshot) if snapshot.isRefreshing =>
          waitForRatesSnapshotRefresh

      case atomicSnapshot @ Some(snapshot)  =>
          F.delay {
            val loading = snapshot.copy(isRefreshing = true)
            ratesSnapshotRef.compareAndSet(atomicSnapshot, Some(loading))
          }.flatMap { refreshing =>
            if (refreshing) refreshAllRates.map(_.map(_.values.toList))
            else waitForRatesSnapshotRefresh
          }
      case None =>
        F.delay {
          val newSnapshot = RatesSnapshot(Map.empty, Instant.now, isRefreshing = true)
          ratesSnapshotRef.compareAndSet(None, Some(newSnapshot))
        }.flatMap { refreshing =>
          if (refreshing) refreshAllRates.map(_.map(_.values.toList))
          else waitForRatesSnapshotRefresh
        }
    }

  private def waitForRatesSnapshotRefresh: F[Error Either List[Rate]] =
    retryingOnFailures[Option[List[Rate]] ](
      policy = refreshWaitPolicy,
      wasSuccessful = maybeRates => maybeRates.isDefined,
      onFailure = (result, details) => F.delay(println(s"Retrying because result was $result. Reason: $details")) // Rudimental log
    )(readFreshSnapshot).map {
      case Some(rates) => Right(rates)
      case None => Left(Error.LookupFailed("Unable to refresh rates or snapshot is stale."))
    }

  private def readFreshSnapshot: F[Option[List[Rate]]] =
    F.delay(ratesSnapshotRef.get()).map {
      case Some(snapshot) if !snapshot.isRefreshing && !snapshot.isStale(Instant.now(), ttlSeconds) =>
        Some(snapshot.values.values.toList)
      case _ =>
        None
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
      // We should circuit break in these cases per a couple of second to avoid server starvation
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