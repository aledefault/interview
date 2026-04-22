package forex.services.rates.interpreters

import cats.effect.Sync
import cats.implicits._
import forex.config.ApplicationConfig
import forex.domain.{Currency, Price, Rate, Timestamp}
import forex.services.rates.RatesProvider
import forex.services.rates.errors._
import org.http4s.Method.GET
import org.http4s.{Header, Request, Status, Uri}
import org.http4s.client.Client
import org.http4s.circe.CirceEntityCodec._
import org.typelevel.ci.CIString

class OneFrameRatesProvider[F[_]: Sync](httpClient: Client[F], config: ApplicationConfig) extends RatesProvider[F] {
  override def get(pair: Rate.Pair): F[Error Either Rate] = {
    val request = Request[F](
      method = GET,
      uri = buildUri(pair, config)
    ).putHeaders(Header.Raw(CIString("token"), config.oneFrame.token))

    httpClient.run(request).use { response =>
      response.status match {
        case Status.Ok =>
          response.as[List[OneFrameRateResponse]].map {
            case head :: _ => toDomainRate(head).asRight[Error]
            case Nil => Error.ExternalServiceError("Invalid success body: empty list").asLeft[Rate]
          }.handleError { error =>
            Error.ExternalServiceError(s"Invalid success body: ${error.getMessage}").asLeft[Rate]
          }

        case status =>
          Sync[F].pure(Error.ExternalServiceError(s"Unexpected status: $status").asLeft[Rate])
      }
    }
  }

  private def buildUri(pair: Rate.Pair, config: ApplicationConfig): Uri =
    Uri.unsafeFromString(s"${config.oneFrame.baseUri}/rates?pair=${pair.from}${pair.to}")

  override def getAll: F[Error Either List[Rate]] = {
    val pairs = supportedPairs
    val pairQuery = pairs.map(pairToQueryStringPair).mkString("&")

    val request = Request[F](
      method = GET,
      uri = buildAllRatesUri(pairQuery, config)
    ).putHeaders(Header.Raw(CIString("token"), config.oneFrame.token))

    httpClient.run(request).use { response =>
      response.status match {
        case Status.Ok =>
          response.as[List[OneFrameRateResponse]].map {
            case Nil => Error.ExternalServiceError("Invalid success body: empty list").asLeft[List[Rate]]
            case body => body.map(toDomainRate).asRight[Error]
          }.handleError { error =>
            Error.ExternalServiceError(s"Invalid success body: ${error.getMessage}").asLeft[List[Rate]]
          }

        case status =>
          Sync[F].pure(Error.ExternalServiceError(s"Unexpected status: $status").asLeft[List[Rate]])
      }
    }
  }

  private def buildAllRatesUri(pairQuery: String, config: ApplicationConfig): Uri =
    Uri.unsafeFromString(s"${config.oneFrame.baseUri}/rates?$pairQuery")

  private def pairToQueryStringPair(pair: Rate.Pair): String = s"pair=${pair.from}${pair.to}"

  private def toDomainRate(response: OneFrameRateResponse): Rate =
    Rate(
      pair = parsePair(response.from, response.to),
      price = Price(response.price),
      timestamp = Timestamp(response.time_stamp)
    )

  private def parsePair(from: String, to: String): Rate.Pair =
    Rate.Pair(
      from = Currency.fromString(from),
      to = Currency.fromString(to)
    )

  // TODO: Refactor this
  private val supportedCurrencies: List[Currency] =
    List(
      Currency.AUD,
      Currency.CAD,
      Currency.CHF,
      Currency.EUR,
      Currency.GBP,
      Currency.NZD,
      Currency.JPY,
      Currency.SGD,
      Currency.USD
    )

  private val supportedPairs: List[Rate.Pair] =
    for {
      from <- supportedCurrencies
      to   <- supportedCurrencies
      if from != to
    } yield Rate.Pair(from, to)
}