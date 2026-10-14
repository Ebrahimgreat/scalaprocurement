# Procurement Workflow Service

A backend service in **Scala 3** that runs an organisation's purchasing process: a department requests something, vendors quote, the best quote is picked, the order is routed to the right approver by its value, and goods are received (including partial deliveries) until the request is fulfilled.

It's based on a procurement system I originally built in Laravel and Nuxt for my university, rebuilt here with a functional design.

**Stack:** Scala 3, cats-effect, http4s (Ember), circe, doobie, H2, munit, GitHub Actions

## The workflow

```
Pending ──approve──▶ Approved ──quotes──▶ award ──▶ AwaitingOrderApproval ──approve order──▶ Ordered ──receipts──▶ Delivered
   │                                          │
   └──reject──▶ Rejected                      └── below the threshold: auto-approved ──▶ Ordered
```

Who approves an order depends on its total, using configurable **price bands**:

| Order total        | Approver          |
|--------------------|-------------------|
| under 1,000        | none (automatic)  |
| 1,000 to 9,999     | Manager           |
| 10,000 to 99,999   | Director          |
| 100,000 and above  | Executive         |

A more senior role can always approve on behalf of a junior one.

## Design decisions

- **Pure domain core.** All business rules live in `core` as pure functions with no dependencies. Each workflow step takes a request and returns `Either[DomainError, (PurchaseRequest, List[DomainEvent])]`, so every rule is tested without a database or server.
- **Errors as values.** Invalid input, out-of-order steps and permission problems are modelled as a `DomainError` enum instead of exceptions, then mapped to HTTP status codes in one place (404, 403, 409, 422).
- **Domain events.** Every state change emits events (`QuoteAwarded`, `OrderApprovalRequested`, `GoodsReceived`, ...). The service publishes them after saving, so notifications or audit logging can be added without touching the workflow.
- **Approval rules as data.** `ApprovalPolicy` is built from a list of price bands and validated on construction (must start at 0, no gaps or overlaps, open-ended last band). An invalid policy cannot exist.
- **Swappable storage.** A small `Repository` trait with two implementations: an in-memory one (`Ref`) for fast tests, and a doobie/SQL one that saves each request and its quotes in a single transaction.

## Project layout

```
core/   pure domain: model, workflow, approval policy, errors, events
api/    http4s routes, service layer, repositories (in-memory and doobie), Main
```

## Running it

Requires JDK 17+ and [sbt](https://www.scala-sbt.org/).

```bash
sbt test        # run all tests
sbt api/run     # start the server on http://localhost:8080
```

## Try it

```bash
# 1. Create a request
curl -X POST localhost:8080/requests -H 'Content-Type: application/json' \
  -d '{"department":"Science","item":"Microscope","quantity":4}'

# 2. Approve it
curl -X POST localhost:8080/requests/1/approve

# 3. Vendors quote
curl -X POST localhost:8080/requests/1/quotes -H 'Content-Type: application/json' \
  -d '{"vendor":"Acme","unitPrice":2400,"deliveryDays":7}'
curl -X POST localhost:8080/requests/1/quotes -H 'Content-Type: application/json' \
  -d '{"vendor":"Globex","unitPrice":2300,"deliveryDays":14}'

# 4. Award: picks Globex (4 x 2,300 = 9,200) and routes it to a Manager
curl -X POST localhost:8080/requests/1/award

# 5. Manager approves the order
curl -X POST localhost:8080/requests/1/order-approval -H 'Content-Type: application/json' \
  -d '{"role":"Manager"}'

# 6. Goods arrive in two deliveries
curl -X POST localhost:8080/requests/1/receipts -H 'Content-Type: application/json' -d '{"quantity":3}'
curl -X POST localhost:8080/requests/1/receipts -H 'Content-Type: application/json' -d '{"quantity":1}'

# 7. Check the final state
curl localhost:8080/requests/1
```

## API

| Method | Path                              | Body                                        |
|--------|-----------------------------------|---------------------------------------------|
| GET    | `/health`                         |                                             |
| POST   | `/requests`                       | `{department, item, quantity}`              |
| GET    | `/requests/{id}`                  |                                             |
| POST   | `/requests/{id}/approve`          |                                             |
| POST   | `/requests/{id}/reject`           |                                             |
| POST   | `/requests/{id}/quotes`           | `{vendor, unitPrice, deliveryDays}`         |
| POST   | `/requests/{id}/award`            |                                             |
| POST   | `/requests/{id}/order-approval`   | `{role}` (Manager, Director or Executive)   |
| POST   | `/requests/{id}/receipts`         | `{quantity}`                                |

## Tests

- `core`: workflow rules and approval policy validation (pure unit tests)
- `api`: HTTP routes end to end against the in-memory repository, and the SQL repository against a real in-memory H2 database

## Next steps

- Optimistic locking (a version column) so two concurrent updates to the same request can't overwrite each other
- PostgreSQL with Flyway migrations instead of in-memory H2
- Authentication, so the approver role comes from the caller's identity instead of the request body
- Publishing domain events to a queue (for example Kafka) instead of logging them
