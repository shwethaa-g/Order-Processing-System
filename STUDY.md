# STUDY.md — From-Scratch Explainer

This walks through every core requirement of this project: what it means,
why it's needed *here*, and the actual code in this repo that implements
it. Read each section once, and you should be able to explain that piece
to a judge in your own words.

---

## 1. Concurrent order processing using a thread pool

**What it means.** Normally, a web server handles each incoming request
more or less independently — but if you just let every order submission
run to completion on whatever thread received the HTTP request, you don't
get a controlled way to simulate "many customers ordering at once." A
thread pool is a fixed set of worker threads that jobs get handed to; if
you submit 10 jobs to an 8-thread pool, 8 run immediately and the other 2
wait for a thread to free up.

**Why it's needed here.** The whole point of this project is to prove that
inventory *doesn't* get oversold *even when* many orders for the same
product arrive at the same moment. To prove that, we need to actually
create that concurrent situation — one order's HTTP request shouldn't have
to wait for the previous order to fully process before the next one even
starts being handled. Dispatching each order onto a shared thread pool is
what makes "10 orders processed at effectively the same time" a real,
reproducible condition instead of something that only maybe happens under
production load.

**The code.**

`backend/src/main/java/com/orderprocess/config/OrderExecutorConfig.java`
defines the pool:

```java
@Configuration
public class OrderExecutorConfig {

    @Value("${order.executor.pool-size}")
    private int poolSize;

    @Bean
    public TaskExecutor orderTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("order-exec-");
        executor.initialize();
        return executor;
    }
}
```

`backend/src/main/java/com/orderprocess/controller/OrderController.java`
hands each order off to it instead of processing inline:

```java
@PostMapping
public ResponseEntity<OrderCreateResponse> createOrder(@Valid @RequestBody OrderRequest request) {
    Order order = orderService.createPendingOrder(request.customerName(), request.productId(), request.quantity());
    Long orderId = order.getId();
    orderTaskExecutor.execute(() -> orderService.submitOrder(orderId));
    return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(new OrderCreateResponse(order.getId(), order.getStatus().name()));
}
```

Note the order is *created* (as `PENDING`) synchronously, so we have an id
to hand back immediately, but the actual stock-checking work
(`submitOrder`) is handed to the pool and runs later, on a worker thread —
that's why the response comes back `202 Accepted` with status `PENDING`
rather than waiting for the outcome.

---

## 2. Proper locking/concurrency control

**What it means.** When multiple threads can read and write the same piece
of data (here: a product's stock count), you need a rule for who gets to
touch it, and when, so two threads can't both act on the same stale value.
A *pessimistic row lock* (`SELECT ... FOR UPDATE` in SQL) is the database
saying: "I am locking this specific row. Any other transaction that wants
to touch this row has to wait until I'm done (commit or rollback)."

**Why it's needed here.** Without a lock, two threads could both read
"stock = 1" for the same product at nearly the same instant, both decide
"there's enough," and both decrement it — selling 2 units of a product
that only had 1. A row lock makes that literally impossible: whichever
thread gets there first holds the lock until it finishes updating the row,
and the second thread is forced to wait and then sees the *already
updated* number.

**The code.**

`backend/src/main/java/com/orderprocess/repository/ProductRepository.java`
— the `@Lock` annotation is what turns this into a real `SELECT ... FOR
UPDATE` under the hood:

```java
public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findAllByOrderByIdAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);
}
```

`backend/src/main/java/com/orderprocess/service/OrderService.java` is
where the lock actually gets acquired and held — note the whole method is
`@Transactional`, so the lock stays held for the entire check-then-write:

```java
@Transactional
public void submitOrder(Long orderId) {
    Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

    order.setStatus(OrderStatus.PROCESSING);
    orderRepository.save(order);

    Long productId = order.getProduct().getId();
    Product product = productRepository.findByIdForUpdate(productId)
            .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

    if (product.getStockQuantity() >= order.getQuantity()) {
        // ... decrement stock, complete the order
```

The moment `findByIdForUpdate` runs, Postgres locks that product's row.
Nobody else can lock the same row until this transaction commits (when the
method returns) or rolls back. That's the entire mechanism — no manual
flags, no in-memory mutex, no "check again just in case."

---

## 3. Preventing inventory from going negative

**What it means.** The system needs a hard guarantee that
`stock_quantity` never drops below zero, no matter what — not "usually
doesn't," but *can't*.

**Why it's needed here.** This is the actual business rule the whole
project exists to prove. There are two layers: the primary guarantee (the
lock, above, means we only ever decrement stock while holding an
up-to-date, locked view of it, so the application logic simply never
issues a decrement that would go negative) and a defense-in-depth backstop
at the database level in case the application logic ever had a bug.

**The code.**

The application-level check, inside the locked section of
`OrderService.submitOrder`:

```java
if (product.getStockQuantity() >= order.getQuantity()) {
    product.setStockQuantity(product.getStockQuantity() - order.getQuantity());
    productRepository.save(product);
    order.setStatus(OrderStatus.COMPLETED);
    ...
```

We only ever subtract when we've just confirmed, under the lock, that
there's enough. The database-level backstop, in
`backend/src/main/java/com/orderprocess/model/Product.java`:

```java
@Entity
@Table(name = "products")
@Check(constraints = "stock_quantity >= 0")
public class Product {
    ...
    @Column(name = "stock_quantity", nullable = false)
    private Integer stockQuantity;
```

`@Check` makes Hibernate create a real Postgres `CHECK` constraint on the
table. Even if some future code path tried to write a negative value, the
database itself would reject the write. This was proven under real
concurrent load by `OrderServiceConcurrencyTest` (see section 6) — 10
concurrent requests against 5 units of stock, run repeatedly, always land
at exactly 0, never negative.

---

## 4. Handling failed orders (out-of-stock)

**What it means.** Not every order can succeed — if the requested quantity
exceeds what's in stock at the moment the lock is acquired, the order has
to be marked as failed rather than silently succeeding or crashing the
request.

**Why it's needed here.** Customers need visibility into what happened,
and the system needs a well-defined status to hang the retry logic off of.
"Failed" here specifically means "insufficient stock" — not a network
error or a bug — so it's captured with a clear reason and a path forward
(retry).

**The code.**

The `else` branch of `OrderService.submitOrder` (same method as above,
continued):

```java
} else {
    int nextRetryCount = order.getRetryCount() + 1;
    order.setRetryCount(nextRetryCount);
    order.setFailureReason(INSUFFICIENT_STOCK_REASON);

    if (nextRetryCount >= maxRetries) {
        order.setStatus(OrderStatus.DEAD_LETTER);
        ...
    } else {
        order.setStatus(OrderStatus.FAILED);
        ...
    }
}

orderRepository.save(order);
```

`INSUFFICIENT_STOCK_REASON` is a constant at the top of the class:
`private static final String INSUFFICIENT_STOCK_REASON = "insufficient stock";`
— that string ends up in the order's `failure_reason` column, visible via
the API and the dashboard.

---

## 5. Dead-letter queue for failed orders

**What it means.** A "dead-letter queue" (DLQ) is a common pattern for
messages/jobs that failed and shouldn't just be discarded, but also
shouldn't be retried forever — they get moved somewhere separate so a
human (or a report) can see what didn't make it through, and why.

**Why it's needed here.** Real DLQs are usually implemented with a message
broker (Kafka, RabbitMQ) — a separate queue/topic that failed messages get
published to. That's explicitly out of scope for this 3-hour build (see
`PLAN.md`'s scope decisions). Instead, the DLQ here is just a fourth order
status, `DEAD_LETTER` — a documented simplification, not a corner cut
being hidden.

**The code.**

The status itself, in
`backend/src/main/java/com/orderprocess/model/OrderStatus.java`:

```java
public enum OrderStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    DEAD_LETTER
}
```

The transition into it (from section 4's code, in `OrderService`) happens
the moment `retry_count` reaches the configured max:

```java
if (nextRetryCount >= maxRetries) {
    order.setStatus(OrderStatus.DEAD_LETTER);
```

And a dedicated read endpoint,
`backend/src/main/java/com/orderprocess/controller/OrderController.java`:

```java
@GetMapping("/dlq")
public List<OrderResponse> getDeadLetterOrders() {
    return orderRepository.findByStatusOrderByCreatedAtDesc(OrderStatus.DEAD_LETTER).stream()
            .map(this::toResponse)
            .toList();
}
```

---

## 6. Bounded retry mechanism

**What it means.** Instead of giving up on a failed order immediately, or
retrying it forever, the system tries again a *fixed* number of times,
with some delay between attempts, before giving up for good (into the
DLQ). "Bounded" is the key word — the retry count has a hard ceiling.

**Why it's needed here.** Out-of-stock is often *transient* — another
order might complete or fail, or a restock might happen, between attempts.
A single failed check shouldn't be the end of the story. But retrying
forever would mean an order for a permanently-out-of-stock product sits
"in limbo" forever, which is worse than a clear dead-letter status.

**The code.**

`backend/src/main/java/com/orderprocess/service/OrderRetryScheduler.java`
— `@Scheduled` makes Spring call this method automatically on a timer, no
manual triggering needed:

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderRetryScheduler {

    private final OrderRepository orderRepository;
    private final OrderService orderService;

    @Value("${order.retry.max-retries}")
    private int maxRetries;

    @Scheduled(fixedDelayString = "${order.retry.fixed-delay-ms:5000}")
    public void retryFailedOrders() {
        List<Order> retryable = orderRepository.findByStatusAndRetryCountLessThan(OrderStatus.FAILED, maxRetries);
        for (Order order : retryable) {
            try {
                orderService.submitOrder(order.getId());
            } catch (Exception e) {
                log.error("Retry attempt threw unexpectedly for order {}", order.getId(), e);
            }
        }
    }
}
```

Every 5 seconds (`order.retry.fixed-delay-ms` in `application.yml`), this
finds every `FAILED` order whose `retry_count` is still under the max (3,
`order.retry.max-retries`) and re-runs the exact same locked
`submitOrder` logic from section 2 on it. If it fails again, `retryCount`
increments again (back in `OrderService`); once that hits 3, the very next
failure flips it to `DEAD_LETTER` instead of `FAILED`, so this scheduler
naturally stops picking it up. `@EnableScheduling` on `BackendApplication`
is what turns `@Scheduled` on at all — without it, this method would just
never run.

---

## 7. React dashboard displaying live order status

**What it means.** The frontend needs to show the current state of every
order, and that view needs to update on its own as orders resolve —
without the user manually refreshing the page.

**Why it's needed here.** The whole demo relies on *watching* orders
change status in real time (PENDING → PROCESSING → COMPLETED/FAILED →
maybe DEAD_LETTER) as the backend processes them concurrently. A static
page that only shows a snapshot at load time wouldn't demonstrate
anything.

**The code.**

Polling, in `dashboard/src/App.jsx` — `setInterval` inside a `useEffect`
re-fetches both orders and products every 2.5 seconds, for as long as the
component is mounted:

```jsx
useEffect(() => {
    let cancelled = false;

    const poll = () => {
      Promise.all([fetchOrders(), fetchProducts()])
        .then(([ordersData, productsData]) => {
          if (cancelled) return;
          setOrders(ordersData);
          setProducts(productsData);
          setLastUpdated(new Date());
          setError(null);
        })
        .catch((err) => {
          if (cancelled) return;
          setError(err.message);
        });
    };

    poll();
    const intervalId = setInterval(poll, POLL_INTERVAL_MS);

    return () => {
      cancelled = true;
      clearInterval(intervalId);
    };
  }, []);
```

Rendering, with a color-coded status badge, in
`dashboard/src/components/OrdersTable.jsx` and
`dashboard/src/components/StatusBadge.jsx`:

```jsx
// OrdersTable.jsx (one row)
<tr key={order.id}>
  <td>{order.id}</td>
  <td>{order.customerName}</td>
  <td>{productNameById.get(order.productId) ?? `#${order.productId}`}</td>
  <td>{order.quantity}</td>
  <td><StatusBadge status={order.status} /></td>
  <td>{order.retryCount}</td>
</tr>
```

```jsx
// StatusBadge.jsx
const STATUS_CLASS = {
  COMPLETED: 'badge-green',
  PENDING: 'badge-yellow',
  PROCESSING: 'badge-yellow',
  FAILED: 'badge-orange',
  DEAD_LETTER: 'badge-red',
};

function StatusBadge({ status }) {
  const className = STATUS_CLASS[status] ?? 'badge-gray';
  return <span className={`badge ${className}`}>{status}</span>;
}
```

Because `App.jsx` re-fetches every 2.5s and passes fresh `orders` down as
props, React re-renders this table automatically whenever a status
changes — no manual DOM manipulation needed, that's just how React works.

---

## 8. React dashboard displaying current inventory

**What it means.** Alongside orders, the dashboard needs to show current
stock per product, updating live, with some visual signal when stock is
getting low or hits zero.

**Why it's needed here.** Watching the Inventory table's stock number tick
down to exactly 0 (and never below) *while* the Orders table shows some
requests completing and others failing is the visual proof of the
concurrency guarantee — it's the same polling mechanism as section 7,
pointed at the products endpoint instead.

**The code.**

`dashboard/src/components/InventoryTable.jsx`:

```jsx
const LOW_STOCK_THRESHOLD = 3;

function InventoryTable({ products }) {
  return (
    <table className="data-table">
      ...
      <tbody>
        {products.map((product) => {
          const isOut = product.stockQuantity === 0;
          const isLow = !isOut && product.stockQuantity <= LOW_STOCK_THRESHOLD;
          const rowClass = isOut ? 'row-out-of-stock' : isLow ? 'row-low-stock' : '';
          return (
            <tr key={product.id} className={rowClass}>
              <td>{product.name}</td>
              <td>{product.sku}</td>
              <td>{Number(product.price).toFixed(2)}</td>
              <td>
                {product.stockQuantity}
                {isOut && <span className="stock-tag stock-tag-out">OUT</span>}
                {isLow && <span className="stock-tag stock-tag-low">LOW</span>}
              </td>
            </tr>
          );
        })}
      ...
```

Same data source as the orders table (`App.jsx`'s polled `products`
state), just a different table and different highlight rules: yellow row
+ "LOW" tag at ≤3 units, red row + "OUT" tag at exactly 0.

---

## 9. Persisting orders/inventory using PostgreSQL

**What it means.** All order and product data needs to survive a server
restart — it has to live in a real database, not just in memory.

**Why it's needed here.** Beyond just "data shouldn't vanish," this is
what makes the row-level lock in section 2 possible at all — `SELECT ...
FOR UPDATE` is a *database* feature. An in-memory data structure would
need its own hand-rolled locking (a mutex/synchronized block), which is
exactly the kind of "roll your own concurrency primitive" this project
avoids by using Postgres's real transactional guarantees instead.

**The code.**

The entities that map to tables, e.g.
`backend/src/main/java/com/orderprocess/model/Order.java`:

```java
@Entity
@Table(name = "orders", indexes = {
        @Index(name = "idx_orders_status", columnList = "status"),
        @Index(name = "idx_orders_product_id", columnList = "product_id")
})
@Check(constraints = "quantity > 0")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_name", nullable = false, length = 100)
    private String customerName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;
    ...
```

`@Entity` + `@Table` + `@Id`/`@GeneratedValue` are what tell Spring Data
JPA (via Hibernate) to create and manage a real `orders` table for this
class — every `orderRepository.save(...)` call throughout this codebase is
an actual SQL `INSERT`/`UPDATE` against Postgres, not an in-memory list.

The connection itself, in `backend/src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/order_system
    username: orderapp
    password: orderapp123
  jpa:
    hibernate:
      ddl-auto: update
```

`ddl-auto: update` is a hackathon-speed shortcut (documented in
`SCHEMA.md`/`CLAUDE.md` as not-for-production): instead of writing manual
SQL migration scripts, Hibernate looks at the `@Entity` classes on every
boot and creates/updates the actual Postgres tables/columns/constraints/
indexes to match them automatically — which is exactly how the
`stock_quantity >= 0` check constraint, the `orders(status)` index, and
the `product_id` foreign key all ended up in the real database without
anyone hand-writing SQL for them.
