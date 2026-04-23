package forex.services.rates.interpreters

import cats.effect.{ContextShift, IO, Timer}
import forex.domain.{Currency, Price, Rate, Timestamp}
import forex.services.rates.RatesProvider
import forex.services.rates.errors.Error
import forex.services.rates.errors.Error.ExternalServiceError
import io.circe.Encoder
import io.circe.generic.semiauto._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext
import scala.util.Random

class CachedRatesProviderSpec extends AnyFlatSpec with Matchers {

  "CachedRatesProvider.get" should "successfully get a rate pair" in {
    val ratePair = Rate.Pair(Currency.USD, Currency.JPY)
    val fakeProvider = new TestRatesProvider[IO](
      getImpl = _ => IO.pure(Left(ExternalServiceError("This implementation don't use single get."))),
      getAllImpl = IO.pure(Right(List(Rate(ratePair, Price(BigDecimal("156.25")), Timestamp.now))))
    )
    val provider = new CachedRatesProvider[IO](fakeProvider)

    val result = provider.get(ratePair).unsafeRunSync()

    result match {
      case Right(rate) =>
        rate.pair shouldBe ratePair
        rate.price shouldBe Price(BigDecimal("156.25"))

      case Left(err) =>
        fail(s"Expected Right but got Left($err)")
    }
  }

  it should "return the same cache rate when calling multiple times" in {
    val ratePair = Rate.Pair(Currency.USD, Currency.JPY)
    val fakeProvider = new TestRatesProvider[IO](
      getImpl = _ => IO.pure(Left(ExternalServiceError("This implementation don't use single get."))),
      getAllImpl = IO.pure(Right(List(Rate(ratePair, Price(BigDecimal("156.25")), Timestamp.now))))
    )
    val provider = new CachedRatesProvider[IO](fakeProvider, ttlSeconds = 60)

    val result1 = provider.get(ratePair).unsafeRunSync()
    val result2 = provider.get(ratePair).unsafeRunSync()
    val result3 = provider.get(ratePair).unsafeRunSync()

    result1 match {
      case Right(rate) =>
        rate.pair shouldBe ratePair
        rate.price shouldBe Price(BigDecimal("156.25"))

      case Left(err) =>
        fail(s"Expected Right but got Left($err)")
    }

    result2 shouldBe result1
    result3 shouldBe result2
  }

  it should "refresh cache rate when stale" in {
    val ratePair = Rate.Pair(Currency.USD, Currency.JPY)
    val fakeProvider = new TestRatesProvider[IO](
      getImpl = _ => IO.pure(Left(ExternalServiceError("This implementation don't use single get."))),
      getAllImpl = IO.delay(Right(List(Rate(ratePair, Price(BigDecimal(Random.nextDouble())), Timestamp.now))))
    )

    val provider = new CachedRatesProvider[IO](fakeProvider, ttlSeconds = -0)

    val result1 = provider.get(ratePair).unsafeRunSync()
    val result2 = provider.get(ratePair).unsafeRunSync()

    result2 shouldNot be (result1)
  }

  it should "return an error when the external provider fails" in {
    val fakeProvider = new TestRatesProvider[IO](
      getImpl = _ => IO.pure(Left(ExternalServiceError("This implementation don't use single get."))),
      getAllImpl = IO.pure(Left(ExternalServiceError("boom")))
    )
    val provider = new CachedRatesProvider[IO](fakeProvider)

    val result = provider.get(Rate.Pair(Currency.USD, Currency.JPY)).unsafeRunSync()

    result shouldBe Left(Error.ExternalServiceError("boom"))
  }

  "CachedRatesProvider.getAll" should "successfully get rate pairs" in {
    val now = Timestamp.now
    val ratePairs = Rate.Pair(Currency.USD, Currency.JPY)::Rate.Pair(Currency.EUR, Currency.JPY)::Nil
    val response = Rate(ratePairs.head, Price(BigDecimal(1)), now) :: Rate(ratePairs.last, Price(BigDecimal(2)), now) :: Nil

    val fakeProvider = new TestRatesProvider[IO](
      getImpl = _ => IO.pure(Left(ExternalServiceError(""))),
      getAllImpl = IO.pure(Right(response))
    )
    val provider = new CachedRatesProvider[IO](fakeProvider)

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
    val fakeProvider = new TestRatesProvider[IO](
      getImpl = _ => IO.pure(Left(ExternalServiceError("This implementation don't use single get."))),
      getAllImpl = IO.pure(Left(ExternalServiceError("boom")))
    )
    val provider = new CachedRatesProvider[IO](fakeProvider)

    val result = provider.getAll.unsafeRunSync()

    result shouldBe Left(Error.ExternalServiceError("boom"))
  }

  it should "refresh cache rate when stale" in {
    val ratePair = Rate.Pair(Currency.USD, Currency.JPY)
    val fakeProvider = new TestRatesProvider[IO](
      getImpl = _ => IO.pure(Left(ExternalServiceError("This implementation don't use single get."))),
      getAllImpl = IO.delay(Right(List(Rate(ratePair, Price(BigDecimal(Random.nextDouble())), Timestamp.now))))
    )

    val provider = new CachedRatesProvider[IO](fakeProvider, ttlSeconds = -0)

    val result1 = provider.getAll.unsafeRunSync()
    val result2 = provider.getAll.unsafeRunSync()

    result2 shouldNot be (result1)
  }

  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)

  final class TestRatesProvider[F[_]](
   getImpl: Rate.Pair => F[Error Either Rate],
   getAllImpl: F[Error Either List[Rate]]
  ) extends RatesProvider[F] {

    override def get(pair: Rate.Pair): F[Error Either Rate] = getImpl(pair)
    override def getAll: F[Error Either List[Rate]] = getAllImpl
  }

  // T0DO: Is it a good practice??
  implicit val oneFrameRateResponseEncoder: Encoder[OneFrameRateResponse] =
    deriveEncoder[OneFrameRateResponse]

}