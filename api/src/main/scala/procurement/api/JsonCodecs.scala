package procurement.api

import io.circe.*
import io.circe.generic.semiauto.*
import io.circe.syntax.*
import procurement.domain.*

final case class CreateRequestBody(department: String, item: String, quantity: Int)
final case class QuoteBody(vendor: String, unitPrice: BigDecimal, deliveryDays: Int)
final case class ApproveOrderBody(role: Role)
final case class ReceiptBody(quantity: Int)
final case class ErrorBody(error: String)

object JsonCodecs:
  given Encoder[Role] = Encoder.encodeString.contramap(_.toString)
  given Decoder[Role] = Decoder.decodeString.emap { s =>
    Role.values.find(_.toString.equalsIgnoreCase(s)).toRight(s"Unknown role: $s")
  }
  given Encoder[Status] = Encoder.encodeString.contramap(_.toString)

  given Decoder[CreateRequestBody] = deriveDecoder
  given Decoder[QuoteBody]         = deriveDecoder
  given Decoder[ApproveOrderBody]  = deriveDecoder
  given Decoder[ReceiptBody]       = deriveDecoder
  given Encoder[ErrorBody]         = deriveEncoder

  given Encoder[Quotation] = deriveEncoder

  /** Written by hand so the response also carries derived fields like the order total. */
  given Encoder[PurchaseRequest] = Encoder.instance { pr =>
    io.circe.Json.obj(
      "id"               -> pr.id.asJson,
      "department"       -> pr.department.asJson,
      "item"             -> pr.item.asJson,
      "quantity"         -> pr.quantity.asJson,
      "status"           -> pr.status.asJson,
      "quotes"           -> pr.quotes.asJson,
      "awardedQuote"     -> pr.awardedQuote.asJson,
      "orderTotal"       -> pr.orderTotal.asJson,
      "requiredApprover" -> pr.requiredApprover.asJson,
      "received"         -> pr.received.asJson,
      "outstanding"      -> pr.outstanding.asJson
    )
  }
