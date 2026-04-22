package forex

import cats.effect.{Concurrent, Timer}
import forex.config.ApplicationConfig
import forex.http.rates.RatesHttpRoutes
import forex.programs.rates.Rates
import forex.services.rates.{RatesProviders, RatesProvider}
import org.http4s._
import org.http4s.client.Client
import org.http4s.implicits._
import org.http4s.server.middleware.{AutoSlash, Timeout}

class Module[F[_]: Concurrent: Timer](config: ApplicationConfig, httpClient: Client[F]) {

  private val ratesProvider: RatesProvider[F] = RatesProviders.cached(RatesProviders.live(httpClient, config))
  private val ratesProgram: Rates[F] = programs.rates.RatesProgram(ratesProvider)
  private val ratesHttpRoutes: HttpRoutes[F] = new RatesHttpRoutes(ratesProgram).routes

  type PartialMiddleware = HttpRoutes[F] => HttpRoutes[F]
  type TotalMiddleware   = HttpApp[F] => HttpApp[F]

  private val routesMiddleware: PartialMiddleware = {
    { http: HttpRoutes[F] =>
      AutoSlash(http)
    }
  }

  private val appMiddleware: TotalMiddleware = { http: HttpApp[F] =>
    Timeout(config.http.timeout)(http)
  }

  private val http: HttpRoutes[F] = ratesHttpRoutes

  val httpApp: HttpApp[F] = appMiddleware(routesMiddleware(http).orNotFound)

}
