package procurement.api

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.implicits.*
import procurement.domain.ApprovalPolicy

class RoutesSpec extends CatsEffectSuite:

  private val makeApp: IO[HttpApp[IO]] =
    InMemoryRepository.make.map { repo =>
      Routes(ProcurementService(repo, ApprovalPolicy.default, _ => IO.unit)).orNotFound
    }

  private def post(app: HttpApp[IO], path: String, body: Json = Json.obj()): IO[Response[IO]] =
    app.run(Request[IO](Method.POST, Uri.unsafeFromString(path)).withEntity(body))

  private def field(resp: Response[IO], name: String): IO[Json] =
    resp.as[Json].map(_.hcursor.downField(name).focus.getOrElse(Json.Null))

  test("health check responds") {
    makeApp.flatMap(_.run(Request[IO](Method.GET, uri"/health"))).map(r => assertEquals(r.status, Status.Ok))
  }

  test("a request goes from creation to full delivery") {
    for
      app <- makeApp
      created <- post(app, "/requests", Json.obj(
                   "department" -> "Science".asJson, "item" -> "Microscope".asJson, "quantity" -> 4.asJson))
      _ = assertEquals(created.status, Status.Created)

      _ <- post(app, "/requests/1/approve")
      _ <- post(app, "/requests/1/quotes", Json.obj(
             "vendor" -> "Acme".asJson, "unitPrice" -> 2400.asJson, "deliveryDays" -> 7.asJson))
      _ <- post(app, "/requests/1/quotes", Json.obj(
             "vendor" -> "Globex".asJson, "unitPrice" -> 2300.asJson, "deliveryDays" -> 14.asJson))

      awarded  <- post(app, "/requests/1/award")
      status   <- field(awarded, "status")
      approver <- field(awarded, "requiredApprover")
      total    <- field(awarded, "orderTotal")
      _ = assertEquals(status, "AwaitingOrderApproval".asJson)
      _ = assertEquals(approver, "Manager".asJson)
      _ = assertEquals(total.asNumber.flatMap(_.toBigDecimal), Some(BigDecimal(9200)))

      _         <- post(app, "/requests/1/order-approval", Json.obj("role" -> "manager".asJson))
      _         <- post(app, "/requests/1/receipts", Json.obj("quantity" -> 3.asJson))
      delivered <- post(app, "/requests/1/receipts", Json.obj("quantity" -> 1.asJson))
      finalStatus <- field(delivered, "status")
    yield assertEquals(finalStatus, "Delivered".asJson)
  }

  test("unknown requests return 404") {
    makeApp.flatMap(app => post(app, "/requests/99/approve")).map(r => assertEquals(r.status, Status.NotFound))
  }

  test("invalid input returns 422") {
    for
      app  <- makeApp
      resp <- post(app, "/requests", Json.obj(
                "department" -> "".asJson, "item" -> "Desk".asJson, "quantity" -> 1.asJson))
    yield assertEquals(resp.status, Status.UnprocessableEntity)
  }

  test("an out-of-order step returns 409") {
    for
      app  <- makeApp
      _    <- post(app, "/requests", Json.obj(
                "department" -> "IT".asJson, "item" -> "Laptop".asJson, "quantity" -> 1.asJson))
      resp <- post(app, "/requests/1/award")
    yield assertEquals(resp.status, Status.Conflict)
  }

  test("a junior role cannot approve a large order") {
    for
      app <- makeApp
      _   <- post(app, "/requests", Json.obj(
               "department" -> "IT".asJson, "item" -> "Servers".asJson, "quantity" -> 10.asJson))
      _   <- post(app, "/requests/1/approve")
      _   <- post(app, "/requests/1/quotes", Json.obj(
               "vendor" -> "Initech".asJson, "unitPrice" -> 5000.asJson, "deliveryDays" -> 10.asJson))
      _    <- post(app, "/requests/1/award")
      resp <- post(app, "/requests/1/order-approval", Json.obj("role" -> "Manager".asJson))
    yield assertEquals(resp.status, Status.Forbidden)
  }
