# Order Processing System

A hackathon-scoped demo of an order intake system that guarantees inventory
is never oversold under concurrent load, retries transient failures a
bounded number of times, and shows live order/inventory status on a
polling dashboard. See `PLAN.md`, `SCHEMA.md`, and `API.md` for the full
spec this was built against.

## What it does

- Accepts order submissions concurrently (an 8-thread pool dispatches each
  one) and guarantees the total quantity sold for a product never exceeds
  its stock, and stock never goes negative.
- Failed orders (insufficient stock) retry up to 3 times on a scheduled
  poller, then flip to a `DEAD_LETTER` status.
- A React dashboard polls orders and inventory every 2.5s and shows both
  live, plus a button to fire a burst of concurrent test orders for a live
  demo of the locking guarantee.

## Tech stack

- Backend: Java 17, Spring Boot 3.3.13, Spring Data JPA, PostgreSQL
- Frontend: React 18 (Vite), Axios, plain CSS
- Build: Maven (via the bundled wrapper, `mvnw`), npm

## How to run

### Prerequisites

- PostgreSQL running locally with a database `order_system` and a role
  `orderapp` (password `orderapp123` in this dev setup) that owns it.
- **JDK 17** installed. This machine's system default is JDK 24, so the
  backend needs `JAVA_HOME` pointed at a JDK 17 install specifically for
  every build/run — it does **not** work on the system default. In this
  environment that path is:
  ```
  C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot
  ```
- Node.js / npm for the dashboard.

### Backend

From `backend/`, with `JAVA_HOME` set to your JDK 17 install:

```bash
# PowerShell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
.\mvnw.cmd spring-boot:run

# git-bash / WSL
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"
./mvnw spring-boot:run
```

Runs on `http://localhost:8080`. On first boot with an empty `products`
table it seeds 5 demo products (small stock counts, 5-10 units each) so an
oversell attempt is trivial to trigger. Table creation is handled by
`ddl-auto: update` — no manual migration step.

To run the backend test suite (includes the concurrency and retry/DLQ
proofs — see below):

```bash
./mvnw test
```

### Dashboard

From `dashboard/`:

```bash
npm install   # first time only
npm run dev
```

Runs on `http://localhost:5173` and expects the backend at `localhost:8080`
(CORS is configured for that origin specifically).

## The concurrency guarantee, explained simply

Every order submission runs through one method
(`OrderService.submitOrder`, see `backend/.../service/OrderService.java`)
that:

1. Locks the target product's row in the database (`SELECT ... FOR UPDATE`,
   via `ProductRepository.findByIdForUpdate`) — this blocks any other
   concurrent order for the *same product* from reading stock until this
   one finishes.
2. Checks, under that lock, whether there's enough stock.
3. If yes: decrements stock and completes the order, then releases the
   lock. If no: fails the order (see retry/DLQ below) and releases the
   lock without touching stock.

Because the check and the decrement happen inside the same lock, two
concurrent requests can never both see "enough stock" for the last unit —
the second one waits until the first has already committed its decrement,
then sees the true, updated number. A database-level
`CHECK (stock_quantity >= 0)` constraint on `products` is a defense-in-depth
backstop, not the primary mechanism.

This isn't just asserted — `OrderServiceConcurrencyTest` fires 10 concurrent
order requests at a product seeded with 5 units of stock and asserts
exactly 5 complete, the rest fail, and stock lands at exactly 0, never
negative. The dashboard's "Submit test orders" button demonstrates the same
guarantee live, against the real running system, for a demo.

## DLQ scope decision

This project's "dead-letter queue" is **a status value, not a message
broker**. There is no Kafka/RabbitMQ topic involved — a failed order simply
carries `status = DEAD_LETTER` once it has failed 3 times
(`order.retry.max-retries`), with `failure_reason` set to explain why. A
`@Scheduled` poller (`OrderRetryScheduler`) re-attempts `FAILED` orders
every 5 seconds (`order.retry.fixed-delay-ms`) until they either succeed or
exhaust their retries. This is a documented, deliberate scope decision for
the 3-hour build — not a corner cut to hide.

## Project layout

```
backend/    Spring Boot API (Java 17 / Boot 3.3.13)
dashboard/  React dashboard (Vite)
PLAN.md, SCHEMA.md, API.md, CLAUDE.md   the authoritative spec this was built against
STUDY.md    from-scratch explainer of every requirement, in plain language + real code
```
