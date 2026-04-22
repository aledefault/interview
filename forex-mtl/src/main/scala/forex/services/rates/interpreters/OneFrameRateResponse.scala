package forex.services.rates.interpreters

import java.time.OffsetDateTime
import scala.util.Try
import io.circe.Decoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder

final case class OneFrameRateResponse(
  from: String,
  to: String,
  bid: BigDecimal,
  ask: BigDecimal,
  price: BigDecimal,
  time_stamp: OffsetDateTime)

object OneFrameRateResponse {
  implicit val configuration: Configuration = Configuration.default.withSnakeCaseMemberNames

  implicit val offsetDateTimeDecoder: Decoder[OffsetDateTime] =
    Decoder.decodeString.emap { value =>
      Try(OffsetDateTime.parse(value)).toEither.left.map(_.getMessage)
    }

  implicit val decoder: Decoder[OneFrameRateResponse] = deriveConfiguredDecoder[OneFrameRateResponse]
}