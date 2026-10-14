package procurement.domain

class ApprovalPolicySpec extends munit.FunSuite:

  test("the default policy routes each total to the right role") {
    val policy = ApprovalPolicy.default
    assertEquals(policy.approverFor(999.99), None)
    assertEquals(policy.approverFor(1_000), Some(Role.Manager))
    assertEquals(policy.approverFor(9_999), Some(Role.Manager))
    assertEquals(policy.approverFor(10_000), Some(Role.Director))
    assertEquals(policy.approverFor(5_000_000), Some(Role.Executive))
  }

  test("bands can be given in any order") {
    val policy = ApprovalPolicy.from(
      List(PriceBand(500, None, Some(Role.Director)), PriceBand(0, Some(500), None))
    )
    assertEquals(policy.map(_.approverFor(600)), Right(Some(Role.Director)))
  }

  test("a policy with a gap between bands is rejected") {
    val result = ApprovalPolicy.from(
      List(PriceBand(0, Some(500), None), PriceBand(600, None, Some(Role.Manager)))
    )
    assert(result.isLeft)
  }

  test("a policy must start at zero and end open-ended") {
    assert(ApprovalPolicy.from(List(PriceBand(100, None, None))).isLeft)
    assert(ApprovalPolicy.from(List(PriceBand(0, Some(100), None))).isLeft)
    assert(ApprovalPolicy.from(Nil).isLeft)
  }

  test("a band must end after it starts") {
    val result = ApprovalPolicy.from(
      List(PriceBand(0, Some(0), None), PriceBand(0, None, Some(Role.Manager)))
    )
    assert(result.isLeft)
  }
