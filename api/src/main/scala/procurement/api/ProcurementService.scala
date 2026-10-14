package procurement.api

import cats.effect.IO
import cats.syntax.all.*
import procurement.domain.*

/** Glues the pure workflow to storage and side effects:
  * load the request, apply a workflow step, save it, then publish its events.
  */
final class ProcurementService(
    repo: Repository[IO],
    policy: ApprovalPolicy,
    publish: DomainEvent => IO[Unit]
):
  type Outcome = IO[Either[DomainError, PurchaseRequest]]

  def create(department: String, item: String, quantity: Int): Outcome =
    NewRequest.validated(department, item, quantity) match
      case Left(error) => IO.pure(Left(error))
      case Right(req)  => repo.create(req).map(Right(_))

  def get(id: Long): Outcome = repo.find(id).map(_.toRight(DomainError.NotFound(id)))

  def approve(id: Long): Outcome = transition(id)(Workflow.approve)
  def reject(id: Long): Outcome  = transition(id)(Workflow.reject)
  def award(id: Long): Outcome   = transition(id)(Workflow.award(_, policy))

  def submitQuote(id: Long, vendor: String, unitPrice: BigDecimal, deliveryDays: Int): Outcome =
    transition(id)(Workflow.submitQuote(_, vendor, unitPrice, deliveryDays))

  def approveOrder(id: Long, role: Role): Outcome = transition(id)(Workflow.approveOrder(_, role))
  def receive(id: Long, quantity: Int): Outcome   = transition(id)(Workflow.receive(_, quantity))

  private def transition(id: Long)(step: PurchaseRequest => Workflow.Result): Outcome =
    get(id).flatMap {
      case Left(error) => IO.pure(Left(error))
      case Right(pr) =>
        step(pr) match
          case Left(error) => IO.pure(Left(error))
          case Right((updated, events)) =>
            repo.save(updated) *> events.traverse_(publish).as(Right(updated))
    }
