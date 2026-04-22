package forex.programs.rates

import cats.Functor
import cats.data.EitherT
import errors._
import forex.domain._
import forex.services.rates.RatesProvider

class RatesProgram[F[_]: Functor](ratesProvider: RatesProvider[F]) extends Rates[F] {

  override def get(request: Protocol.GetRatesRequest): F[Error Either Rate] =
    EitherT(ratesProvider.get(Rate.Pair(request.from, request.to))).leftMap(toProgramError(_)).value

}

object RatesProgram {

  def apply[F[_]: Functor](ratesProvider: RatesProvider[F]): Rates[F] = new RatesProgram[F](ratesProvider)

}
