# PLAN.md — Order Processing System (3-Hour Build)

## Goal
A working demo where:
1. Multiple orders can be submitted concurrently.
2. Inventory is never oversold, even under concurrent load.
3. Failed orders (out-of-stock) retry a bounded number of times, then move to
   a dead-letter state.
4. A live dashboard shows order status and inventory in real time (polling).
5. Everything persists to local PostgreSQL.

## Tech Stack (locked — do not change without confirmation)
- Java 17, Spring Boot 3.3.x
- Dependencies: Spring Web, Spring Data JPA, Spring Validation, Spring Retry,
  PostgreSQL Driver, Lombok
- PostgreSQL (local install, not Docker)
- React 18 via Vite + Axios, plain CSS (no UI kit — no time to configure one)
- Build tools: Maven (backend), npm (frontend)

## Scope Decisions (documented, not hidden)
- **DLQ = a status, not a broker.** `orders.status = 'DEAD_LETTER'` after
  retries are exhausted. Real DLQ semantics (separate queue/topic) are out of
  scope for 3 hours. `GET /api/orders/dlq` filters on this status.
- **Concurrency simulation** happens via a fixed thread pool
  (`ExecutorService`, 8–10 threads) that each order submission is dispatched
  to. Actual oversell-prevention comes from pessimistic DB row locking, not
  from the thread pool itself.
- **No auth, no multi-tenant concerns, no payment integration.**

## Phase-by-Phase (3 hours)

### Phase 0 — Setup (0:00–0:15)
- [ ] Create Postgres DB `order_system`, user/password.
- [ ] `spring init` project with the dependencies above (or start.spring.io).
- [ ] `npm create vite@latest dashboard -- --template react`, install axios.
- [ ] Confirm DB connection from Spring Boot (`application.yml`) works.
- [ ] Create tables per SCHEMA.md (JPA `ddl-auto: update` is fine for hackathon
      speed — do not use in production, this is a documented shortcut).

### Phase 1 — Backend core (0:15–1:30)
- [ ] `Product` entity + repository (SCHEMA.md).
- [ ] `Order` entity + repository (SCHEMA.md).
- [ ] `ProductRepository.findByIdForUpdate()` using
      `@Lock(LockModeType.PESSIMISTIC_WRITE)`.
- [ ] `OrderService.submitOrder(...)`:
  - Wraps stock check + decrement + order row creation in one
    `@Transactional` method.
  - Locks the product row first, checks `stock_quantity >= requestedQty`.
  - If sufficient: decrement stock, set order status `COMPLETED`.
  - If insufficient: set order status `FAILED`, increment `retry_count`.
- [ ] `OrderExecutorConfig`: a `ThreadPoolTaskExecutor` bean (8–10 threads)
      that order submissions are dispatched onto, so multiple orders hit the
      locking logic concurrently.
- [ ] Retry logic: a scheduled task (`@Scheduled(fixedDelay=...)`) or a
      `BlockingQueue` consumer that picks up `FAILED` orders with
      `retry_count < MAX_RETRIES` (3), re-attempts `submitOrder`, and on final
      failure sets status to `DEAD_LETTER` with a `failure_reason`.
- [ ] REST controllers per API.md.
- [ ] Seed 4–5 products with small stock counts (e.g. 5–10 units) so
      oversell attempts are easy to trigger live in the demo.

### Phase 2 — Prove the concurrency guarantee (1:30–2:00) — DONE
- [x] Satisfied by `OrderServiceConcurrencyTest` (built and run as part of
      Phase 1 to verify `OrderService.submitOrder`'s locking logic), a JUnit
      test using `ExecutorService` + `CountDownLatch` — no separate
      script/Postman runner needed.
- [x] Verified: 10 concurrent order requests against a product seeded with 5
      units of stock resolved to exactly 5 `COMPLETED` and 5 `FAILED`, ending
      stock at 0 — never negative, never oversold.
- [x] This is the strongest talking point for judges — logged via the test
      run output (see backend git history for the verification commit).

### Phase 3 — Dashboard (2:00–2:45)
- [ ] `GET /api/orders` and `GET /api/products` consumed via Axios.
- [ ] Poll both every 2–3 seconds (`setInterval` in a `useEffect`).
- [ ] Orders table: id, customer, product, qty, status (color-coded badge —
      green=COMPLETED, yellow=PENDING/PROCESSING, orange=FAILED,
      red=DEAD_LETTER), retry_count.
- [ ] Inventory table: product name, stock remaining, low-stock highlight.
- [ ] A "Submit test orders" button that fires a burst of concurrent requests
      from the browser (for live demo drama — shows the dashboard updating
      in real time as locking resolves the race).

### Phase 4 — Polish + demo prep (2:45–3:00)
- [ ] README: what it does, how to run it, the concurrency guarantee, the
      documented DLQ scope decision.
- [ ] Confirm nothing is broken with a fresh restart of both services.
- [ ] Prepare a 2-minute narration: problem → concurrency risk → locking
      solution → retry/DLQ → live dashboard proof.

## Stretch (only if time remains)
- Manual "retry from DLQ" button/endpoint.
- WebSocket push instead of polling.
- Docker Compose for judges to run it (skip given RAM constraints unless
  judges specifically need a one-command run).

## Explicit non-goals for this build
- Authentication/authorization
- Payment processing
- Horizontal scaling / distributed locking
- Automated CI pipeline
