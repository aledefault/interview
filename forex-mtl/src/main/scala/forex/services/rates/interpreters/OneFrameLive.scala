package forex.services.rates.interpreters

import cats.effect.Sync
import cats.implicits._
import forex.config.ApplicationConfig
import forex.domain.{Price, Rate, Timestamp}
import forex.services.rates.Algebra
import forex.services.rates.errors._
import org.http4s.Method.GET
import org.http4s.{Header, Request, Uri}
import org.http4s.client.Client
import org.http4s.circe.CirceEntityCodec._
import org.typelevel.ci.CIString

class OneFrameLive[F[_]: Sync](httpClient: Client[F], config: ApplicationConfig) extends Algebra[F] {
  override def get(pair: Rate.Pair): F[Error Either Rate] = {
    val request = Request[F](
      method = GET,
      uri = buildUri(pair, config)
    ).putHeaders(Header.Raw(CIString("token"), config.oneFrame.token))

    httpClient.expect[List[OneFrameRateResponse]](request).map { body =>
        val price = extractPrice(body)
        Rate(pair, Price(price), Timestamp.now).asRight[Error]
      }.handleError(error => Error.OneFrameLookupFailed(error.getMessage).asLeft[Rate])
  }

  private def buildUri(pair: Rate.Pair, config: ApplicationConfig): Uri =
    Uri.unsafeFromString(s"${config.oneFrame.baseUri}/rates?pair=${pair.from}${pair.to}")

  private def extractPrice(body: List[OneFrameRateResponse]): BigDecimal = body(0).price //TODO: Add error handling
}