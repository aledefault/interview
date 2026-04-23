package forex.services.rates.interpreters

import cats.effect.Sync
import cats.implicits._
import forex.domain.{Currency, Price, Rate, Timestamp}
import forex.services.rates.RatesProvider
import forex.services.rates.errors._
import org.http4s.Method.GET
import org.http4s.{Header, Request, Status, Uri}
import org.http4s.client.Client
import org.http4s.circe.CirceEntityCodec._
import org.typelevel.ci.CIString

class OneFrameRatesProvider[F[_]: Sync](
  httpClient: Client[F],
  token: String,
  baseUri: String) extends RatesProvider[F] {

  override def get(pair: Rate.Pair): F[Error Either Rate] = {
    val request = Request[F](
      method = GET,
      uri = buildUri(pair)
    ).putHeaders(Header.Raw(CIString("token"), token))

    httpClient.run(request).use { response =>
      response.status match {
        case Status.Ok =>
          response.as[List[OneFrameRateResponse]].map {
            case head :: _ => toDomainRate(head)
            case Nil => Either.left[Error, Rate](Error.ExternalServiceError("Invalid success body: empty list"))
          }.handleError { error =>
            Either.left[Error, Rate](Error.ExternalServiceError(s"Invalid success body: ${error.getMessage}"))
          }

        case status =>
          Sync[F].pure(Either.left[Error, Rate](Error.ExternalServiceError(s"Unexpected status: $status")))
      }
    }.handleError(error => Either.left[Error, Rate](Error.ExternalServiceError(s"Unexpected error: ${error.getMessage}")))
  }

  private def buildUri(pair: Rate.Pair): Uri =
    Uri.unsafeFromString(s"${baseUri}/rates?pair=${pair.from}${pair.to}")

  override def getAll: F[Error Either List[Rate]] = {
    val pairs = Rate.Pair.supportedPairs
    val pairQuery = pairs.map(pairToQueryStringPair).mkString("&")

    val request = Request[F](
      method = GET,
      uri = buildAllRatesUri(pairQuery)
    ).putHeaders(Header.Raw(CIString("token"), token))

    httpClient.run(request).use { response =>
      response.status match {
        case Status.Ok =>
          response.as[List[OneFrameRateResponse]].map {
            case Nil => Either.left[Error, List[Rate]](Error.ExternalServiceError("Invalid success body: empty list"))
            case body => body.traverse(toDomainRate)
          }.handleError { error =>
            Either.left[Error, List[Rate]](Error.ExternalServiceError(s"Invalid success body: ${error.getMessage}"))
          }

        case status =>
          Sync[F].pure(Either.left[Error, List[Rate]](Error.ExternalServiceError(s"Unexpected status: $status")))
      }
    }.handleError(error => Either.left[Error, List[Rate]](Error.ExternalServiceError(s"Unexpected error: ${error.getMessage}")))
  }

  private def buildAllRatesUri(pairQuery: String): Uri =
    Uri.unsafeFromString(s"${baseUri}/rates?$pairQuery")

  private def pairToQueryStringPair(pair: Rate.Pair): String = s"pair=${pair.from}${pair.to}"

  private def toDomainRate(response: OneFrameRateResponse): Either[Error, Rate] =
    parsePair(response.from, response.to).map { pair =>
      Rate(
        pair = pair,
        price = Price(response.price),
        timestamp = Timestamp(response.time_stamp)
      )
    }

  private def parsePair(from: String, to: String): Either[Error, Rate.Pair] =
    for {
      fromCurrency <- Currency.fromString(from).leftMap(msg => Error.ExternalServiceError(msg))
      toCurrency   <- Currency.fromString(to).leftMap(msg => Error.ExternalServiceError(msg))
    } yield Rate.Pair(
      from = fromCurrency,
      to = toCurrency
    )
}