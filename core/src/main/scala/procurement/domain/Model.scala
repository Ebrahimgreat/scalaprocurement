package procurement.domain

/** Roles that can sign off on purchase orders, from least to most senior. */
enum Role:
  case Manager, Director, Executive

  /** A more senior role can always approve on behalf of a junior one. */
  def canApprove(required: Role): Boolean = this.ordinal >= required.ordinal

enum Status:
  case Pending, Approved, Rejected, AwaitingOrderApproval, Ordered, Delivered

final case class Quotation(id: Long, vendor: String, unitPrice: BigDecimal, deliveryDays: Int):
  def totalFor(quantity: Int): BigDecimal = unitPrice * quantity

/** The aggregate: one purchase request and everything that happened to it. */
final case class PurchaseRequest(
    id: Long,
    department: String,
    item: String,
    quantity: Int,
    status: Status,
    quotes: List[Quotation],
    awardedQuoteId: Option[Long],
    requiredApprover: Option[Role],
    received: Int
):
  def awardedQuote: Option[Quotation] = awardedQuoteId.flatMap(id => quotes.find(_.id == id))
  def orderTotal: Option[BigDecimal]  = awardedQuote.map(_.totalFor(quantity))
  def outstanding: Int                = quantity - received

object PurchaseRequest:
  def fromNew(id: Long, req: NewRequest): PurchaseRequest =
    PurchaseRequest(id, req.department, req.item, req.quantity, Status.Pending, Nil, None, None, 0)

/** A validated request that has not been stored yet, so it has no id. */
final case class NewRequest private (department: String, item: String, quantity: Int)

object NewRequest:
  def validated(department: String, item: String, quantity: Int): Either[DomainError, NewRequest] =
    for
      d <- Validation.nonBlank("department", department)
      i <- Validation.nonBlank("item", item)
      q <- Validation.positive("quantity", quantity)
    yield NewRequest(d, i, q)
