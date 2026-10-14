package procurement.domain

/** A price range [from, until) and the role that must approve orders inside it.
  * `until = None` means open-ended; `approver = None` means auto-approved.
  */
final case class PriceBand(from: BigDecimal, until: Option[BigDecimal], approver: Option[Role])

/** Approval rules are data, not code: an organisation can change its spending limits
  * without a deploy. The constructor is private so an invalid policy cannot exist.
  */
final class ApprovalPolicy private (val bands: List[PriceBand]):
  def approverFor(total: BigDecimal): Option[Role] =
    bands.find(b => total >= b.from && b.until.forall(total < _)).flatMap(_.approver)

object ApprovalPolicy:
  /** Bands must start at zero, have no gaps or overlaps, and end open-ended. */
  def from(bands: List[PriceBand]): Either[DomainError, ApprovalPolicy] =
    val sorted = bands.sortBy(_.from)
    def fail(reason: String)  = Left(DomainError.InvalidPolicy(reason))

    val firstGap = sorted.zip(sorted.drop(1)).collectFirst {
      case (a, b) if !a.until.contains(b.from) => s"band starting at ${a.from} does not end where the next band starts"
    }

    if sorted.isEmpty then fail("at least one band is required")
    else if sorted.head.from != BigDecimal(0) then fail("the first band must start at 0")
    else if sorted.exists(b => b.until.exists(_ <= b.from)) then fail("every band must end after it starts")
    else if firstGap.isDefined then fail(firstGap.get)
    else if sorted.last.until.isDefined then fail("the last band must be open-ended")
    else Right(new ApprovalPolicy(sorted))

  val default: ApprovalPolicy =
    from(
      List(
        PriceBand(0, Some(1_000), None),
        PriceBand(1_000, Some(10_000), Some(Role.Manager)),
        PriceBand(10_000, Some(100_000), Some(Role.Director)),
        PriceBand(100_000, None, Some(Role.Executive))
      )
    ).fold(e => throw IllegalStateException(e.message), identity)
