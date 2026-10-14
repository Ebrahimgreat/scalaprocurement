package procurement.api

import cats.effect.{IO, IOApp}
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import procurement.domain.ApprovalPolicy

object Main extends IOApp.Simple:
  def run: IO[Unit] =
    for
      repo <- DoobieRepository.make(DoobieRepository.h2Transactor("procurement"))
      service = ProcurementService(repo, ApprovalPolicy.default, event => IO.println(s"[event] $event"))
      _ <- EmberServerBuilder
             .default[IO]
             .withHost(ipv4"0.0.0.0")
             .withPort(port"8080")
             .withHttpApp(Routes(service).orNotFound)
             .build
             .useForever
    yield ()
