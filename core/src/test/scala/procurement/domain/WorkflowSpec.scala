package procurement.domain

import DomainEvent.*

class WorkflowSpec extends munit.FunSuite:

  private val newRequest =
    PurchaseRequest.fromNew(1, NewRequest.validated("Science", "Microscope", 4).toOption.get)

  /** Runs a step and fails the test if it returns an error. */
  extension (r: Workflow.Result)
    private def ok: (PurchaseRequest, List[DomainEvent]) =
      r.fold(e => fail(s"expected success, got: ${e.message}"), identity)

  private def approvedWithQuotes(quotes: (String, BigDecimal, Int)*): PurchaseRequest =
    quotes.foldLeft(Workflow.approve(newRequest).ok._1) { case (pr, (vendor, price, days)) =>
      Workflow.submitQuote(pr, vendor, price, days).ok._1
    }

  test("a new request starts pending and can be approved") {
    val (approved, events) = Workflow.approve(newRequest).ok
    assertEquals(approved.status, Status.Approved)
    assertEquals(events, List(RequestApproved(1)))
  }

  test("a rejected request cannot be approved") {
    val (rejected, _) = Workflow.reject(newRequest).ok
    assertEquals(
      Workflow.approve(rejected),
      Left(DomainError.InvalidTransition(Status.Rejected, "approve the request"))
    )
  }

  test("quotes cannot be submitted before the request is approved") {
    assert(Workflow.submitQuote(newRequest, "Acme", 100, 7).isLeft)
  }

  test("the same vendor cannot quote twice, ignoring case") {
    val pr = approvedWithQuotes(("Acme", 100, 7))
    assertEquals(Workflow.submitQuote(pr, "ACME", 90, 5), Left(DomainError.DuplicateVendor("ACME")))
  }

  test("quotes are validated") {
    val pr = Workflow.approve(newRequest).ok._1
    assert(Workflow.submitQuote(pr, " ", 100, 7).isLeft)
    assert(Workflow.submitQuote(pr, "Acme", 0, 7).isLeft)
    assert(Workflow.submitQuote(pr, "Acme", 100, 0).isLeft)
  }

  test("the cheapest quote wins and faster delivery breaks a tie") {
    val pr = approvedWithQuotes(("Slow", 200, 30), ("Fast", 200, 5), ("Pricey", 250, 1))
    assertEquals(Workflow.bestQuote(pr.quotes).map(_.vendor), Some("Fast"))
  }

  test("awarding needs at least one quote") {
    val pr = Workflow.approve(newRequest).ok._1
    assertEquals(Workflow.award(pr, ApprovalPolicy.default), Left(DomainError.NoQuotes))
  }

  test("a small order is placed without manual approval") {
    // 4 x 200 = 800, below the 1,000 threshold
    val (ordered, events) = Workflow.award(approvedWithQuotes(("Acme", 200, 7)), ApprovalPolicy.default).ok
    assertEquals(ordered.status, Status.Ordered)
    assertEquals(ordered.requiredApprover, None)
    assertEquals(events, List(QuoteAwarded(1, "Acme", 800), OrderPlaced(1)))
  }

  test("a large order is routed to the approver for its price band") {
    // 4 x 2,400 = 9,600, inside the Manager band
    val (awaiting, events) = Workflow.award(approvedWithQuotes(("Acme", 2400, 7)), ApprovalPolicy.default).ok
    assertEquals(awaiting.status, Status.AwaitingOrderApproval)
    assertEquals(awaiting.requiredApprover, Some(Role.Manager))
    assertEquals(awaiting.orderTotal, Some(BigDecimal(9600)))
    assert(events.contains(OrderApprovalRequested(1, Role.Manager)))
  }

  test("only a senior enough role can approve an order") {
    // 4 x 5,000 = 20,000, inside the Director band
    val awaiting = Workflow.award(approvedWithQuotes(("Acme", 5000, 7)), ApprovalPolicy.default).ok._1
    assertEquals(
      Workflow.approveOrder(awaiting, Role.Manager),
      Left(DomainError.NotAuthorised(Role.Manager, Role.Director))
    )
    assertEquals(Workflow.approveOrder(awaiting, Role.Executive).ok._1.status, Status.Ordered)
  }

  test("partial deliveries keep the order open until everything arrives") {
    val ordered = Workflow.award(approvedWithQuotes(("Acme", 200, 7)), ApprovalPolicy.default).ok._1

    val (partial, firstEvents) = Workflow.receive(ordered, 3).ok
    assertEquals(partial.status, Status.Ordered)
    assertEquals(partial.outstanding, 1)
    assertEquals(firstEvents, List(GoodsReceived(1, 3, 1)))

    val (done, lastEvents) = Workflow.receive(partial, 1).ok
    assertEquals(done.status, Status.Delivered)
    assertEquals(lastEvents, List(GoodsReceived(1, 1, 0), RequestFulfilled(1)))
  }

  test("more goods than ordered cannot be received") {
    val ordered = Workflow.award(approvedWithQuotes(("Acme", 200, 7)), ApprovalPolicy.default).ok._1
    assertEquals(Workflow.receive(ordered, 5), Left(DomainError.OverDelivery(4, 5)))
  }
