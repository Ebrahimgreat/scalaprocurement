package procurement.api

import java.util.UUID
import munit.CatsEffectSuite
import procurement.domain.*

/** Runs against a real (in-memory H2) database to check the SQL round-trips the aggregate. */
class DoobieRepositorySpec extends CatsEffectSuite:

  private def freshRepo = DoobieRepository.make(DoobieRepository.h2Transactor(s"test-${UUID.randomUUID()}"))

  private def unsafe[A](r: Either[DomainError, A]): A = r.fold(e => fail(e.message), identity)

  test("ids are assigned in sequence") {
    for
      repo <- freshRepo
      a    <- repo.create(unsafe(NewRequest.validated("IT", "Laptop", 1)))
      b    <- repo.create(unsafe(NewRequest.validated("IT", "Monitor", 2)))
    yield assertEquals((a.id, b.id), (1L, 2L))
  }

  test("a saved request with quotes and an award is loaded back unchanged") {
    for
      repo    <- freshRepo
      created <- repo.create(unsafe(NewRequest.validated("Science", "Microscope", 4)))
      updated = unsafe(
                  for
                    (a, _) <- Workflow.approve(created)
                    (b, _) <- Workflow.submitQuote(a, "Acme", BigDecimal("2400.50"), 7)
                    (c, _) <- Workflow.submitQuote(b, "Globex", 2300, 14)
                    (d, _) <- Workflow.award(c, ApprovalPolicy.default)
                  yield d
                )
      _      <- repo.save(updated)
      loaded <- repo.find(created.id)
    yield assertEquals(loaded, Some(updated))
  }

  test("finding a missing request returns None") {
    freshRepo.flatMap(_.find(42)).map(r => assertEquals(r, None))
  }
