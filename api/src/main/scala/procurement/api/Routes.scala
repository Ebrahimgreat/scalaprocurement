package procurement.api

import cats.effect.IO
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import procurement.api.JsonCodecs.given
import procurement.domain.{DomainError, PurchaseRequest}

object Routes:
  def apply(service: ProcurementService): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case GET -> Root / "health" =>
        Ok(io.circe.Json.obj("status" -> "ok".asJson))

      case req @ POST -> Root / "requests" =>
        for
          body   <- req.as[CreateRequestBody]
          result <- service.create(body.department, body.item, body.quantity)
          resp   <- respond(result, created = true)
        yield resp

      case GET -> Root / "requests" / LongVar(id) =>
        service.get(id).flatMap(respond(_))

      case POST -> Root / "requests" / LongVar(id) / "approve" =>
        service.approve(id).flatMap(respond(_))

      case POST -> Root / "requests" / LongVar(id) / "reject" =>
        service.reject(id).flatMap(respond(_))

      case req @ POST -> Root / "requests" / LongVar(id) / "quotes" =>
        req.as[QuoteBody]
          .flatMap(b => service.submitQuote(id, b.vendor, b.unitPrice, b.deliveryDays))
          .flatMap(respond(_))

      case POST -> Root / "requests" / LongVar(id) / "award" =>
        service.award(id).flatMap(respond(_))

      case req @ POST -> Root / "requests" / LongVar(id) / "order-approval" =>
        req.as[ApproveOrderBody].flatMap(b => service.approveOrder(id, b.role)).flatMap(respond(_))

      case req @ POST -> Root / "requests" / LongVar(id) / "receipts" =>
        req.as[ReceiptBody].flatMap(b => service.receive(id, b.quantity)).flatMap(respond(_))
    }

  private def respond(result: Either[DomainError, PurchaseRequest], created: Boolean = false): IO[Response[IO]] =
    result match
      case Right(pr) if created => Created(pr.asJson)
      case Right(pr)            => Ok(pr.asJson)
      case Left(error)          => errorResponse(error)

  /** Maps domain errors to HTTP status codes in one place. */
  private def errorResponse(error: DomainError): IO[Response[IO]] =
    val body = ErrorBody(error.message).asJson
    error match
      case DomainError.NotFound(_)                                  => NotFound(body)
      case DomainError.NotAuthorised(_, _)                          => Forbidden(body)
      case DomainError.InvalidInput(_, _) | DomainError.InvalidPolicy(_) => UnprocessableEntity(body)
      case _                                                        => Conflict(body)
