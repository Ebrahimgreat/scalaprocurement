package procurement.domain

import DomainEvent.*
import Status.*

/** The procurement workflow as pure functions.
  *
  * Each step takes the current request and returns either an error or the updated
  * request plus the events it produced. No IO happens here, which makes every rule
  * trivial to unit test.
  *
  * Pending -> Approved -> (quotes) -> AwaitingOrderApproval -> Ordered -> Delivered
  *        \-> Rejected                \-> Ordered (auto-approved below the threshold)
  */
object Workflow:
  type Result = Either[DomainError, (PurchaseRequest, List[DomainEvent])]

  def approve(pr: PurchaseRequest): Result =
    requireStatus(pr, Pending, "approve the request")
      .map(_ => (pr.copy(status = Approved), List(RequestApproved(pr.id))))

  def reject(pr: PurchaseRequest): Result =
    requireStatus(pr, Pending, "reject the request")
      .map(_ => (pr.copy(status = Rejected), List(RequestRejected(pr.id))))

  def submitQuote(pr: PurchaseRequest, vendor: String, unitPrice: BigDecimal, deliveryDays: Int): Result =
    for
      _     <- requireStatus(pr, Approved, "submit a quote")
      v     <- Validation.nonBlank("vendor", vendor)
      price <- Validation.positive("unitPrice", unitPrice)
      days  <- Validation.positive("deliveryDays", deliveryDays)
      _     <- Either.cond(!pr.quotes.exists(_.vendor.equalsIgnoreCase(v)), (), DomainError.DuplicateVendor(v))
    yield
      val nextId = pr.quotes.map(_.id).maxOption.getOrElse(0L) + 1
      (pr.copy(quotes = pr.quotes :+ Quotation(nextId, v, price, days)), List(QuoteSubmitted(pr.id, v)))

  /** Picks the best quote and routes the order to the right approver by its total. */
  def award(pr: PurchaseRequest, policy: ApprovalPolicy): Result =
    for
      _    <- requireStatus(pr, Approved, "award the order")
      best <- bestQuote(pr.quotes).toRight(DomainError.NoQuotes)
    yield
      val total   = best.totalFor(pr.quantity)
      val awarded = pr.copy(awardedQuoteId = Some(best.id))
      val audit   = QuoteAwarded(pr.id, best.vendor, total)

      policy.approverFor(total) match
        case Some(role) =>
          (awarded.copy(status = AwaitingOrderApproval, requiredApprover = Some(role)),
           List(audit, OrderApprovalRequested(pr.id, role)))
        case None =>
          (awarded.copy(status = Ordered), List(audit, OrderPlaced(pr.id)))

  def approveOrder(pr: PurchaseRequest, role: Role): Result =
    for
      _        <- requireStatus(pr, AwaitingOrderApproval, "approve the order")
      required <- pr.requiredApprover.toRight(DomainError.InvalidTransition(pr.status, "approve the order"))
      _        <- Either.cond(role.canApprove(required), (), DomainError.NotAuthorised(role, required))
    yield (pr.copy(status = Ordered), List(OrderPlaced(pr.id)))

  /** Deliveries can arrive in parts; the request closes once everything has arrived. */
  def receive(pr: PurchaseRequest, quantity: Int): Result =
    for
      _ <- requireStatus(pr, Ordered, "receive goods")
      n <- Validation.positive("quantity", quantity)
      _ <- Either.cond(n <= pr.outstanding, (), DomainError.OverDelivery(pr.outstanding, n))
    yield
      val updated = pr.copy(received = pr.received + n)
      if updated.outstanding == 0 then
        (updated.copy(status = Delivered), List(GoodsReceived(pr.id, n, 0), RequestFulfilled(pr.id)))
      else (updated, List(GoodsReceived(pr.id, n, updated.outstanding)))

  /** Cheapest unit price wins; faster delivery breaks a tie. */
  def bestQuote(quotes: List[Quotation]): Option[Quotation] =
    quotes.minByOption(q => (q.unitPrice, q.deliveryDays))

  private def requireStatus(pr: PurchaseRequest, expected: Status, action: String): Either[DomainError, Unit] =
    Either.cond(pr.status == expected, (), DomainError.InvalidTransition(pr.status, action))
