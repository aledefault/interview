package forex.programs.rates

import forex.services.rates.errors.{ Error => RatesServiceError }

object errors {

  sealed trait Error extends Exception
  object Error {
    final case class RateLookupFailed(msg: String) extends Error
    final case class ServiceUnavailable(msg: String) extends Error
    final case class UnsupportedRate(msg: String) extends Error
  }

  // TODO: We should log these errors and return a public error
  def toProgramError(error: RatesServiceError): Error = error match {
    case RatesServiceError.LookupFailed(msg) => Error.RateLookupFailed(msg)
    case RatesServiceError.ExternalServiceError(_) => Error.ServiceUnavailable("Service temporarily unavailable")
    case RatesServiceError.UnsupportedRate(msg) => Error.UnsupportedRate(msg)
  }
}
