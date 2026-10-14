package procurement.domain

/** Every state change produces events, so side effects (notifications, audit logs)
  * can react to what happened without being coupled to the workflow itself.
  */
enum DomainEvent:
  case RequestApproved(requestId: Long)
  case RequestRejected(requestId: Long)
  case QuoteSubmitted(requestId: Long, vendor: String)
  case QuoteAwarded(requestId: Long, vendor: String, total: BigDecimal)
  case OrderApprovalRequested(requestId: Long, approver: Role)
  case OrderPlaced(requestId: Long)
  case GoodsReceived(requestId: Long, quantity: Int, outstanding: Int)
  case RequestFulfilled(requestId: Long)
