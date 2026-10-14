package procurement.api

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import procurement.domain.*

/** SQL storage with doobie. Runs on H2 out of the box; the SQL is standard enough
  * to move to PostgreSQL by changing the driver, URL and id sequence syntax.
  */
final class DoobieRepository private (xa: Transactor[IO]) extends Repository[IO]:
  import DoobieRepository.*

  def create(req: NewRequest): IO[PurchaseRequest] =
    val insert =
      for
        id <- sql"SELECT NEXT VALUE FOR request_ids".query[Long].unique
        _ <- sql"""INSERT INTO purchase_requests (id, department, item, quantity, status, received)
                   VALUES ($id, ${req.department}, ${req.item}, ${req.quantity}, ${Status.Pending.toString}, 0)""".update.run
      yield PurchaseRequest.fromNew(id, req)
    insert.transact(xa)

  def find(id: Long): IO[Option[PurchaseRequest]] =
    val load =
      for
        row <- sql"""SELECT id, department, item, quantity, status, awarded_quote_id, required_approver, received
                     FROM purchase_requests WHERE id = $id""".query[RequestRow].option
        quotes <- sql"""SELECT quote_id, vendor, unit_price, delivery_days
                        FROM quotations WHERE request_id = $id ORDER BY quote_id""".query[QuoteRow].to[List]
      yield row.map(toDomain(_, quotes))
    load.transact(xa)

  /** Saves the whole aggregate in one transaction, so a request and its quotes never disagree. */
  def save(pr: PurchaseRequest): IO[Unit] =
    val write =
      for
        _ <- sql"""UPDATE purchase_requests
                   SET status = ${pr.status.toString},
                       awarded_quote_id = ${pr.awardedQuoteId},
                       required_approver = ${pr.requiredApprover.map(_.toString)},
                       received = ${pr.received}
                   WHERE id = ${pr.id}""".update.run
        _ <- sql"DELETE FROM quotations WHERE request_id = ${pr.id}".update.run
        _ <- pr.quotes.traverse_ { q =>
               sql"""INSERT INTO quotations (request_id, quote_id, vendor, unit_price, delivery_days)
                     VALUES (${pr.id}, ${q.id}, ${q.vendor}, ${q.unitPrice}, ${q.deliveryDays})""".update.run
             }
      yield ()
    write.transact(xa)

object DoobieRepository:
  private type RequestRow = (Long, String, String, Int, String, Option[Long], Option[String], Int)
  private type QuoteRow   = (Long, String, BigDecimal, Int)

  private def toDomain(row: RequestRow, quotes: List[QuoteRow]): PurchaseRequest =
    val (id, department, item, quantity, status, awarded, approver, received) = row
    PurchaseRequest(
      id = id,
      department = department,
      item = item,
      quantity = quantity,
      status = Status.valueOf(status),
      quotes = quotes.map((quoteId, vendor, price, days) => Quotation(quoteId, vendor, price, days)),
      awardedQuoteId = awarded,
      requiredApprover = approver.map(Role.valueOf),
      received = received
    )

  private val createSchema: ConnectionIO[Unit] =
    for
      _ <- sql"CREATE SEQUENCE IF NOT EXISTS request_ids START WITH 1".update.run
      _ <- sql"""CREATE TABLE IF NOT EXISTS purchase_requests (
                   id                BIGINT PRIMARY KEY,
                   department        VARCHAR(255) NOT NULL,
                   item              VARCHAR(255) NOT NULL,
                   quantity          INT NOT NULL,
                   status            VARCHAR(32) NOT NULL,
                   awarded_quote_id  BIGINT,
                   required_approver VARCHAR(32),
                   received          INT NOT NULL
                 )""".update.run
      _ <- sql"""CREATE TABLE IF NOT EXISTS quotations (
                   request_id    BIGINT NOT NULL REFERENCES purchase_requests(id),
                   quote_id      BIGINT NOT NULL,
                   vendor        VARCHAR(255) NOT NULL,
                   unit_price    DECIMAL(19, 2) NOT NULL,
                   delivery_days INT NOT NULL,
                   PRIMARY KEY (request_id, quote_id)
                 )""".update.run
    yield ()

  def h2Transactor(name: String): Transactor[IO] =
    Transactor.fromDriverManager[IO](
      driver = "org.h2.Driver",
      url = s"jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1",
      user = "sa",
      password = "",
      logHandler = None
    )

  def make(xa: Transactor[IO]): IO[DoobieRepository] =
    createSchema.transact(xa).as(new DoobieRepository(xa))
