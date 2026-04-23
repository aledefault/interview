package forex.http
package rates

import cats.data.Validated
import cats.effect.Sync
import cats.implicits.catsSyntaxTuple2Semigroupal
import cats.syntax.flatMap._
import forex.programs.rates.{Rates, errors, Protocol => RatesProgramProtocol}
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import org.http4s.{HttpRoutes, Response}

class RatesHttpRoutes[F[_]: Sync](rates: Rates[F]) extends Http4sDsl[F] {

  import Converters._
  import Protocol._
  import QueryParams._

  private[http] val prefixPath = "/rates"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root :? FromQueryParam(from) +& ToQueryParam(to) =>
      (from, to).mapN(RatesProgramProtocol.GetRatesRequest) match {
        case Validated.Valid(request) =>
          rates.get(request).flatMap {
            case Right(rate) => Ok(rate.asGetApiResponse)
            case Left(error) => toPublicErrorResponse(error)
          }

        case Validated.Invalid(errs) =>
          toPublicErrorResponse(
            errors.Error.UnsupportedRate(errs.toList.map(_.sanitized).mkString(", ")))
      }
  }

  val routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )

  private def toPublicErrorResponse(error: errors.Error): F[Response[F]] =
    error match {
      case errors.Error.UnsupportedRate(msg) => BadRequest(msg)
      case errors.Error.ServiceUnavailable(msg) => ServiceUnavailable(msg)
      case errors.Error.RateLookupFailed(msg) => BadGateway(msg)
    }

}
