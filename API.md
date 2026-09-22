# API.md — REST Contract (authoritative)

Base path: `/api`
Format: JSON. No auth for this build.

## Products / Inventory

### `GET /api/products`
Returns all products with current stock.
```json
[
  { "id": 1, "name": "Wireless Mouse", "sku": "WM-001", "price": 499.00, "stockQuantity": 7 }
]
```

### `GET /api/products/{id}`
Single product, same shape as above. 404 if not found.

## Orders

### `POST /api/orders`
Submit a new order. Dispatched to the thread pool for concurrent processing.
Request:
```json
{ "customerName": "jjk", "productId": 1, "quantity": 2 }
```
Response (`202 Accepted` — processing is async):
```json
{ "id": 42, "status": "PENDING" }
```
Validation: `quantity > 0`, `productId` must exist. 400 on validation failure.

### `GET /api/orders`
List all orders, most recent first. Supports optional `?status=` filter
(e.g. `?status=DEAD_LETTER`).
```json
[
  {
    "id": 42,
    "customerName": "jjk",
    "productId": 1,
    "quantity": 2,
    "status": "COMPLETED",
    "retryCount": 0,
    "failureReason": null,
    "createdAt": "2026-09-22T10:00:00",
    "updatedAt": "2026-09-22T10:00:01"
  }
]
```

### `GET /api/orders/{id}`
Single order, same shape as above. 404 if not found.

### `GET /api/orders/dlq`
Shorthand for `GET /api/orders?status=DEAD_LETTER`. Returns dead-lettered
orders with `failureReason` populated.

### `POST /api/orders/{id}/retry` (stretch — only if time remains)
Manually re-queues a `DEAD_LETTER` order for one more attempt, resetting
`retry_count` to 0. Returns `202 Accepted`. 409 if order is not in
`DEAD_LETTER` state.

## Status codes used
- `200` — successful read
- `202` — order accepted for async processing
- `400` — validation error (bad payload)
- `404` — resource not found
- `409` — invalid state transition (e.g. retrying a non-DLQ order)

## Notes for Claude Code
- `POST /api/orders` returns before processing completes — the dashboard
  discovers the real outcome via polling `GET /api/orders`. Do not make this
  endpoint synchronous; that defeats the concurrency demo.
- Do not add endpoints beyond what's listed here without confirming first —
  see CLAUDE.md.
