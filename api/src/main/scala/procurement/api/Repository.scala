package procurement.api

import cats.effect.{IO, Ref}
import procurement.domain.{NewRequest, PurchaseRequest}

/** Storage for purchase requests. Two implementations: in-memory for tests and
  * a doobie/SQL one for running the service.
  */
trait Repository[F[_]]:
  def create(req: NewRequest): F[PurchaseRequest]
  def find(id: Long): F[Option[PurchaseRequest]]
  def save(pr: PurchaseRequest): F[Unit]

final class InMemoryRepository private (state: Ref[IO, InMemoryRepository.State]) extends Repository[IO]:
  def create(req: NewRequest): IO[PurchaseRequest] =
    state.modify { s =>
      val pr = PurchaseRequest.fromNew(s.nextId, req)
      (InMemoryRepository.State(s.nextId + 1, s.requests.updated(pr.id, pr)), pr)
    }

  def find(id: Long): IO[Option[PurchaseRequest]] = state.get.map(_.requests.get(id))

  def save(pr: PurchaseRequest): IO[Unit] =
    state.update(s => s.copy(requests = s.requests.updated(pr.id, pr)))

object InMemoryRepository:
  final case class State(nextId: Long, requests: Map[Long, PurchaseRequest])

  def make: IO[InMemoryRepository] =
    Ref.of[IO, State](State(1, Map.empty)).map(new InMemoryRepository(_))
