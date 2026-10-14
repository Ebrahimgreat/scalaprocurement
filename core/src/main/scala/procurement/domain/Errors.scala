package procurement.domain

enum DomainError:
  case NotFound(id: Long)
  case InvalidInput(field: String, reason: String)
  case InvalidTransition(from: Status, action: String)
  case NoQuotes
  case DuplicateVendor(vendor: String)
  case NotAuthorised(role: Role, required: Role)
  case OverDelivery(outstanding: Int, attempted: Int)
  case InvalidPolicy(reason: String)

  def message: String = this match
    case NotFound(id)                   => s"Purchase request $id was not found"
    case InvalidInput(field, reason)    => s"$field $reason"
    case InvalidTransition(from, action) => s"Cannot $action while the request is $from"
    case NoQuotes                       => "Cannot award an order before any quotes are submitted"
    case DuplicateVendor(vendor)        => s"$vendor has already submitted a quote"
    case NotAuthorised(role, required)  => s"$role cannot approve an order that needs $required approval"
    case OverDelivery(outstanding, n)   => s"Received $n but only $outstanding are outstanding"
    case InvalidPolicy(reason)          => s"Invalid approval policy: $reason"

object Validation:
  def nonBlank(field: String, value: String): Either[DomainError, String] =
    val trimmed = value.trim
    Either.cond(trimmed.nonEmpty, trimmed, DomainError.InvalidInput(field, "must not be blank"))

  def positive(field: String, value: Int): Either[DomainError, Int] =
    Either.cond(value > 0, value, DomainError.InvalidInput(field, "must be greater than zero"))

  def positive(field: String, value: BigDecimal): Either[DomainError, BigDecimal] =
    Either.cond(value > 0, value, DomainError.InvalidInput(field, "must be greater than zero"))
