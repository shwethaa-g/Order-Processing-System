# SCHEMA.md — Database Schema (authoritative)

DB: PostgreSQL, database name `order_system`.
JPA `ddl-auto: update` is used for hackathon speed (documented shortcut —
not for production).

## Table: `products`
| Column          | Type          | Notes                                  |
|-----------------|---------------|-----------------------------------------|
| id              | BIGSERIAL PK  |                                          |
| name            | VARCHAR(100)  | NOT NULL                                |
| sku             | VARCHAR(50)   | UNIQUE, NOT NULL                        |
| price           | NUMERIC(10,2) | NOT NULL                                |
| stock_quantity  | INTEGER       | NOT NULL, CHECK (stock_quantity >= 0)   |
| version         | INTEGER       | for optional optimistic-lock fallback   |
| created_at      | TIMESTAMP     | default now()                           |
| updated_at      | TIMESTAMP     | updated on stock change                 |

`stock_quantity >= 0` is a DB-level CHECK constraint as a last-resort backstop
— the application-level pessimistic lock is the primary guarantee, this is
defense in depth.

## Table: `orders`
| Column          | Type          | Notes                                              |
|-----------------|---------------|-----------------------------------------------------|
| id              | BIGSERIAL PK  |                                                      |
| customer_name   | VARCHAR(100)  | NOT NULL                                            |
| product_id      | BIGINT FK     | references products(id)                             |
| quantity        | INTEGER       | NOT NULL, CHECK (quantity > 0)                      |
| status          | VARCHAR(20)   | enum: PENDING, PROCESSING, COMPLETED, FAILED, DEAD_LETTER |
| retry_count     | INTEGER       | default 0                                           |
| failure_reason  | VARCHAR(255)  | nullable — e.g. "insufficient stock"                |
| created_at      | TIMESTAMP     | default now()                                       |
| updated_at      | TIMESTAMP     | updated on every status change                      |

### Status lifecycle (must be enforced in code, not just documented)
```
PENDING -> PROCESSING -> COMPLETED
PENDING -> PROCESSING -> FAILED -> (retry, retry_count++)
                                 -> PROCESSING (retry attempt)
FAILED (retry_count >= 3) -> DEAD_LETTER
```
An order must end in exactly one of `COMPLETED` or `DEAD_LETTER`, or be
transiently in `PENDING`/`PROCESSING`/`FAILED` (awaiting retry).

## Constants
- `MAX_RETRIES = 3` (bounded retry — after 3 failed attempts, order moves to
  `DEAD_LETTER`)
- Thread pool size: 8 (configurable via `application.yml`,
  `order.executor.pool-size`)

## Indexes
- `orders(status)` — dashboard polls filter/group by status frequently.
- `orders(product_id)` — retry worker looks up by product.

## Seed data (Phase 0)
Insert 4–5 products with deliberately small `stock_quantity` (5–10 units
each) so an oversell attempt is trivial to demonstrate live.
