# 2-Minute Demo Narration

## 1. The problem (≈20s)

"Say you're running an order system where multiple customers can buy the
same product at the same time. If two people both check 'is there stock
left?' at the same instant, and both get 'yes,' you can end up selling
more units than you actually have — that's overselling, and it's a real
bug in naive e-commerce systems under load."

## 2. The concurrency risk, shown live (≈25s)

Point at the dashboard's Inventory table — one product with a small stock
count (5-10 units).

"Here's a product with only [N] units in stock. I'm going to fire a burst
of concurrent order requests at it — more requests than we have stock for
— all at once, from the browser."

Click **"Submit test orders"** with that product selected, burst size ≥
stock count.

"Watch the Orders table update in real time as these resolve."

## 3. The locking solution (≈35s)

"Every one of those requests hit the same code path: before touching
stock, we lock that product's row in Postgres — a `SELECT ... FOR UPDATE` —
so only one request at a time can actually check-and-decrement stock for
that product. Every other concurrent request for the same product just
waits its turn. Once the lock is released, the next one sees the *real*,
updated stock number — not a stale one. That's what stops overselling: not
luck, not application-level flags, an actual database-level lock."

Point at the Inventory table: stock landed at exactly 0, never negative.
Point at the Orders table: some COMPLETED, some FAILED/DEAD_LETTER — total
completed quantity never exceeds what we started with.

"And this isn't just a claim — there's an automated test that proves it:
10 concurrent requests against 5 units of stock, every single run, ends
with exactly 5 completed and stock at exactly zero."

## 4. Retry / DLQ (≈25s)

"The orders that failed because of insufficient stock aren't just dropped.
A background scheduler retries each one — up to 3 attempts — in case stock
frees up. If it still fails after 3 tries, it moves to a `DEAD_LETTER`
status with a reason attached, so nothing silently disappears. That's our
dead-letter queue for this build — deliberately a status flag, not a full
Kafka/RabbitMQ setup, since that's out of scope for a 3-hour build, not a
shortcut we're hiding."

Point at a `DEAD_LETTER` badge in the Orders table, and optionally hit
`GET /api/orders/dlq`.

## 5. Live dashboard, wrap-up (≈15s)

"Everything you just saw — the race, the resolution, the retries — the
dashboard picked up live, polling the API every couple seconds, no manual
refresh. That's the whole loop: concurrent submission, guaranteed-safe
locking, bounded retry with a dead-letter fallback, and live visibility
into all of it."

---

**Total: ~2 minutes.** Rehearse the click-and-wait for step 2 beforehand —
the burst resolves in well under a second, but leave a beat of silence so
judges can watch the table actually update rather than talking over it.
