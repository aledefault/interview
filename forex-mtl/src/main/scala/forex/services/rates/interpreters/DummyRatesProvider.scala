package forex.services.rates.interpreters

import forex.services.rates.RatesProvider
import cats.Applicative
import cats.syntax.applicative._
import cats.syntax.either._
import forex.domain.{ Price, Rate, Timestamp }
import forex.services.rates.errors._

class DummyRatesProvider[F[_]: Applicative] extends RatesProvider[F] {

  override def get(pair: Rate.Pair): F[Error Either Rate] =
    Rate(pair, Price(BigDecimal(100)), Timestamp.now).asRight[Error].pure[F]

  override def getAll: F[Either[Error, List[Rate]]] = ??? //TODO: Fix
}
