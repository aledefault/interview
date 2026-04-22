package forex.services.rates

import forex.domain.Rate
import errors._

trait RatesProvider[F[_]] {
  def get(pair: Rate.Pair): F[Error Either Rate]
  def getAll: F[Error Either List[Rate]]
}
