package forex.services.rates.interpreters

import cats.effect.IO
import forex.config.{ApplicationConfig, OneFrameConfig}
import forex.domain.{Currency, Price, Rate, Timestamp}
import forex.services.rates.errors.Error
import io.circe.Encoder
import io.circe.generic.semiauto._
import io.circe.syntax._
import org.http4s.HttpApp
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.OffsetDateTime

class OneFrameRatesProviderSpec extends AnyFlatSpec with Matchers {

  private val config = ApplicationConfig(
    http = null,
    oneFrame = OneFrameConfig(
      baseUri = "http://localhost",
      token = "test-token"
    )
  )

  "OneFrameRatesProvider.get" should "successfully get a rate pair" in {
    val ratePair = Rate.Pair(Currency.USD, Currency.JPY)
    val responseBody = List(
      OneFrameRateResponse(
        from = ratePair.from.toString, // TODO: Implement Show
        to =  ratePair.to.toString,
        bid = BigDecimal(0),
        ask = BigDecimal(0),
        price = BigDecimal("156.25"),
        time_stamp = Timestamp.now.value
      )
    )

    val provider = new OneFrameRatesProvider[IO](clientReturningJson(responseBody), config)
    val result = provider.get(ratePair).unsafeRunSync()

    result match {
      case Right(rate) =>
        rate.pair shouldBe ratePair
        rate.price shouldBe Price(responseBody.head.price)

      case Left(err) =>
        fail(s"Expected Right but got Left($err)")
    }
  }

  it should "return an error when the external provider fails" in {
    val provider = new OneFrameRatesProvider[IO](failingClient("boom"), config)
    val result = provider.get(Rate.Pair(Currency.USD, Currency.JPY)).unsafeRunSync()

    result shouldBe Left(Error.ExternalServiceError("Unexpected error: boom"))
  }

  "OneFrameRatesProvider.getAll" should "successfully get rate pairs" in {
    val now = Timestamp.now.value
    val ratePairs = Rate.Pair(Currency.USD, Currency.JPY)::Rate.Pair(Currency.EUR, Currency.JPY)::Nil
    val responses = MakeResponse(ratePairs.head, BigDecimal(1), now)::MakeResponse(ratePairs.last, BigDecimal(2), now)::Nil

    val provider = new OneFrameRatesProvider[IO](clientReturningJson(responses), config)
    val result = provider.getAll.unsafeRunSync()

    result match {
      case Right(rate) =>
        rate.head.pair shouldBe ratePairs.head
        rate.head.price shouldBe Price(BigDecimal(1))

        rate.last.pair shouldBe ratePairs.last
        rate.last.price shouldBe Price(BigDecimal(2))

      case Left(err) => fail(s"Expected Right but got Left($err)")
    }
  }

  it should "return an error when the external provider fails" in {
    val client = failingClient("boom")
    val provider = new OneFrameRatesProvider[IO](client, config)
    val result = provider.getAll.unsafeRunSync()

    result shouldBe Left(Error.ExternalServiceError("Unexpected error: boom"))
  }

  private def MakeResponse(pair: Rate.Pair, price: BigDecimal, timestamp: OffsetDateTime) = {
    OneFrameRateResponse(
      from = pair.from.toString, // TODO: Implement Show
      to = pair.to.toString,
      bid = BigDecimal(0),
      ask = BigDecimal(0),
      price = price,
      time_stamp = timestamp
    )
  }

  private def clientReturningJson(body: List[OneFrameRateResponse]): Client[IO] = {
    val httpApp = HttpApp[IO] { _ => Ok(body.asJson) }
    Client.fromHttpApp(httpApp)
  }

  private def failingClient(message: String): Client[IO] = {
    val httpApp = HttpApp[IO] { _ => IO.raiseError(new RuntimeException(message)) }
    Client.fromHttpApp(httpApp)
  }

  // T0DO: Is it a good practice??
  implicit val oneFrameRateResponseEncoder: Encoder[OneFrameRateResponse] =
    deriveEncoder[OneFrameRateResponse]
}