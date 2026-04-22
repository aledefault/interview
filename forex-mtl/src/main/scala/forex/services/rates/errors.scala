package forex.services.rates

object errors {

  sealed trait Error
  object Error {
    final case class LookupFailed(msg: String) extends Error
    final case class UnsupportedRate(msg: String) extends Error
    final case class ExternalServiceError(msg: String) extends Error
  }

}
