# hcr-product — Concert Ticket Booking Microservices

Reference application chứng minh **HCR framework** (`io.hrc.*`) hoạt động trong môi trường microservices thật: 3 service độc lập, communicate qua Kafka, cùng chia sẻ Redis làm source-of-truth cho inventory, mỗi service một schema Postgres riêng.

Bài toán: **đặt vé concert dưới tải cao, tuyệt đối không được oversell**, kể cả khi 1000 người cùng bấm "Mua" trong vòng 1 giây.

---

## 1. Mục tiêu thiết kế

| Yêu cầu | Cách đáp ứng |
|---------|--------------|
| Zero oversell | Lua script `DECRBY` atomic trên Redis (P3 strategy) |
| Throughput cao | Critical path không chạm Postgres — Redis là source of truth |
| Decouple service | EventBus (Kafka) cho mọi giao tiếp giữa services |
| Khôi phục được sau crash | Reconciliation cycle quét DB mỗi 5 phút, fix 5 loại inconsistency |
| Idempotent client retry | `idempotencyKey` của user → DB unique index |
| Observability đầy đủ | Micrometer → Prometheus + Grafana, OpenTelemetry → Zipkin tracing |

Đây là **đồ án thesis-grade backend**: không có UI, không có auth — focus 100% vào correctness + performance dưới tải.

---

## 2. Kiến trúc tổng quan

```
                  ┌──────────────────────────────────────────────┐
                  │              Client (k6 load test)           │
                  └────────────────────┬─────────────────────────┘
                                       │ HTTP POST /orders
                                       ▼
       ┌──────────────────────────────────────────────────────────┐
       │ ms-order :8081                                           │
       │  ├─ OrderController                                      │
       │  ├─ TicketBookingOrchestrator (extends AsyncSagaOrch.)   │
       │  ├─ RedisSagaStateRepository                             │
       │  ├─ PaymentResultListener (Kafka consumer)               │
       │  └─ TicketReconciliationService (@Scheduled, 5 phút)     │
       │     PostgreSQL: order_db (ticket_orders, concert_tickets)│
       └────────┬───────────────────────────────────┬─────────────┘
                │ reserve()                         │ publish PaymentRequestedEvent
                ▼                                   ▼
       ┌─────────────────┐                ┌────────────────────────┐
       │  Redis (P3)     │                │  Kafka                 │
       │  - inventory    │                │  - hcr.payment.commands│
       │  - saga state   │                │  - hcr.payment.events  │
       │  - reconcile lock│               │  - hcr.inventory.events│
       └────────▲────────┘                └──┬─────────────────────┘
                │ initialize() at startup    │
                │                            │ subscribe
       ┌────────┴────────────────────┐       ▼
       │ ms-inventory :8082          │   ┌──────────────────────────┐
       │  ├─ InventoryConfiguration  │   │ ms-payment :8083         │
       │  ├─ RedisSeeder             │   │  ├─ PaymentRequestedList │
       │  └─ InventoryPersistenceCons│   │  ├─ PaymentGateway (mock)│
       │     PostgreSQL: inventory_db│   │  └─ persists PaymentAtt. │
       └─────────────────────────────┘   │     PostgreSQL: payment_db│
                                         └──────────────────────────┘
```

**Path A** (kiến trúc đã chọn): ms-order share **cùng Redis instance** với ms-inventory để có thể gọi `inventoryStrategy.reserve()` đồng bộ trong critical path. ms-inventory chỉ phụ trách:
1. Seed Redis từ Postgres lúc startup (`RedisSeeder`).
2. Lắng nghe `InventoryReservedEvent` / `InventoryReleasedEvent` để đồng bộ Postgres async (audit trail + reconciliation reference).

> Path B (pure async — ms-order publish "ReserveRequested" event và ms-inventory mới chạm Redis) đã được cân nhắc nhưng skip vì latency thêm 2 hop Kafka, không cần thiết khi 2 service nằm cùng datacenter.

---

## 3. Stack

| Layer | Component |
|-------|-----------|
| Runtime | Java 17, Spring Boot 3.2.5 |
| Build | Maven multi-module (parent: `hcr-product/pom.xml`) |
| Storage | PostgreSQL 15 (3 schemas), Redis 7 (Redisson client) |
| Messaging | Apache Kafka 7.6 (KRaft mode — không cần Zookeeper) |
| Observability | Micrometer + Prometheus + Grafana, Zipkin (Brave bridge) |
| Load test | k6 |
| Framework | `io.hrc.*` artifacts (built locally trước, install vào local Maven repo) |

---

## 4. Module layout

```
hcr-product/
├─ pom.xml                       — parent POM (independent từ hcr-parent)
├─ ms-shared/                    — DTO + custom event chia sẻ giữa các service
│   └─ src/main/java/io/hrc/product/shared/
│       ├─ dto/
│       │   ├─ PlaceTicketOrderRequest.java   ← request body cho POST /orders
│       │   └─ TicketOrderResponse.java       ← response cho POST/GET
│       └─ event/
│           └─ PaymentRequestedEvent.java     ← extends DomainEvent
│
├─ ms-inventory/    :8082        — Persistence consumer + Redis seeder
│   └─ src/main/java/io/hrc/product/inventory/
│       ├─ MsInventoryApplication.java
│       ├─ config/
│       │   ├─ InventoryConfiguration.java    ← khai báo InventoryStrategy bean
│       │   └─ RedisSeeder.java               ← warm-up Redis từ Postgres lúc boot
│       ├─ domain/ConcertTicket.java          ← entity inventory (có concert_name, venue, event_date)
│       └─ repository/ConcertTicketRepository.java
│
├─ ms-payment/      :8083        — Gateway adapter, idempotent payment
│   └─ src/main/java/io/hrc/product/payment/
│       ├─ MsPaymentApplication.java
│       ├─ listener/PaymentRequestedListener.java   ← subscribe → charge → publish 4 outcome events
│       ├─ domain/PaymentAttempt.java               ← PK = orderId, idempotency
│       ├─ domain/PaymentAttemptStatus.java         ← PENDING/SUCCEEDED/FAILED/TIMEOUT/UNKNOWN
│       └─ repository/PaymentAttemptRepository.java
│
├─ ms-order/        :8081        — HTTP entry, orchestrator, reconciliation
│   └─ src/main/java/io/hrc/product/order/
│       ├─ MsOrderApplication.java                  ← @EnableScheduling
│       ├─ controller/OrderController.java          ← POST /orders, GET /orders/{id}
│       ├─ saga/
│       │   ├─ TicketBookingOrchestrator.java       ← extends AsynchronousSagaOrchestrator
│       │   └─ RedisSagaStateRepository.java        ← saga state lưu Redis, TTL 1h
│       ├─ listener/PaymentResultListener.java      ← subscribe 4 payment events
│       ├─ reconciliation/TicketReconciliationService.java  ← extends AbstractReconciliationService
│       ├─ config/OrderConfiguration.java           ← khai báo InventoryStrategy + InventoryReconciler bean
│       ├─ domain/
│       │   ├─ TicketOrder.java       ← entity order chính (extends AbstractOrder)
│       │   ├─ ConcertTicket.java     ← local catalog (chỉ dùng giá vé)
│       │   └─ TicketRequest.java     ← input request (extends OrderRequest)
│       └─ repository/{TicketOrder,ConcertTicket}Repository.java
│
├─ infra/
│   ├─ docker-compose.yml              ← Postgres, Redis, Kafka, Zipkin, Prometheus, Grafana
│   └─ observability/
│       ├─ postgres-init/01-create-databases.sql
│       ├─ prometheus.yml              ← scrape 3 service ports
│       └─ grafana/provisioning/datasources/datasource.yml
│
└─ load-tests/
    ├─ README.md
    └─ k6/{burst,sustained,oversell-check}.js + lib/common.js
```

---

## 5. Đường đi chi tiết của 1 đơn hàng (golden path)

```
[1] Client → POST /orders {resourceId, requesterId, quantity, idempotencyKey}
[2] OrderController → TicketBookingOrchestrator.process(request)
       │
       ├─ AsyncSagaOrch.validateAndBuildContext()    — kiểm tra duplicate idempotencyKey
       ├─ TicketBookingOrch.createOrder()            — load price từ ConcertTicket catalog
       │                                                set TicketOrder.totalAmount, currency
       ├─ inventoryStrategy.reserve()                — ATOMIC Lua DECRBY trên Redis
       │                                                key: hcr:inventory:{resourceId}
       │                                                fail nhanh nếu < quantity → HTTP 422
       ├─ orderRepository.save(order, status=RESERVED)
       ├─ sagaStateRepository.save(context)          — JSON vào Redis hcr:saga:state:{orderId}, TTL 1h
       ├─ eventBus.publish( buildOrderCreatedEvent(context) )
       │                       └→ TicketBookingOrch override để trả về PaymentRequestedEvent
       │                            (mang amount + currency, framework's event không có)
       └─ return order — controller trả HTTP 202 ACCEPTED + body { orderId, status: RESERVED, ... }

[3] Kafka topic hcr.payment.commands
       ▼
[4] ms-payment.PaymentRequestedListener.process(event)
       ├─ Tra PaymentAttempt theo orderId — nếu đã resolved → skip (idempotent)
       ├─ paymentGateway.charge(request)             — mock gateway, có random fail/timeout
       └─ publish 1 trong 4:
           - PaymentSucceededEvent
           - PaymentFailedEvent
           - PaymentTimeoutEvent
           - PaymentUnknownEvent

[5] Kafka topic hcr.payment.events
       ▼
[6] ms-order.PaymentResultListener.handle(event, ack)
       └─ orchestrator.handlePaymentResult(orderId, PaymentResult)
            │
            ├─ tải lại SagaContext từ Redis
            ├─ result.isSuccess()  → onConfirmed()
            │                          - status = CONFIRMED
            │                          - delete saga state khỏi Redis
            │                          - record metrics: hcr_saga_confirmed_total
            └─ result.isFailed/Timeout/Unknown
                                   → compensate()
                                          - inventoryStrategy.release() — trả lại Redis
                                          - status = CANCELLED, failureReason = ...
                                          - delete saga state

[7] Client poll: GET /orders/{orderId} → {status: CONFIRMED|CANCELLED, ...}
```

**Quan trọng:** Reserve và payment là **2 transaction tách rời**. DB không bị lock suốt thời gian thanh toán → throughput cao hơn nhiều so với P1/P2 (truyền thống).

---

## 6. Các quyết định kiến trúc cốt lõi

### 6.1 Tại sao P3 (Redis Atomic) cho microservices?

Bảng so sánh trong `CLAUDE.md`:

| | P1 Pessimistic | P2 Optimistic | **P3 Redis Atomic** |
|--|:-:|:-:|:-:|
| Throughput | ~1k req/s | 1-5k req/s | **5-10k req/s** |
| DB trong critical path | Có | Có | **Không** |
| Consistency | Strong | Strong | Eventual (≤5min worst case) |

P3 đánh đổi consistency tức thời lấy throughput. Reconciliation cycle bù lại bằng cách quét DB định kỳ phát hiện và fix mọi lệch lạc.

### 6.2 Tại sao async saga (Kafka) thay vì REST gọi trực tiếp ms-payment?

- **Decouple**: ms-order không cần biết ms-payment ở đâu, chạy hay không.
- **Resilience**: ms-payment crash → event nằm trong Kafka đợi consumer dậy.
- **Observability**: mọi event đều log được, replay được, audit được.
- **Backpressure**: tự nhiên — consumer xử lý theo nhịp riêng, ms-order không bị block.

### 6.3 Tại sao saga state lưu Redis (không phải DB)?

- Ghi DB sẽ kéo Postgres vào critical path → mất ưu thế P3.
- Saga sống ngắn (≤ vài phút): TTL Redis 1h là quá đủ.
- Crash recovery: reconciliation tự dọn order RESERVED quá lâu, không cần persisted saga.

### 6.4 Hook `buildOrderCreatedEvent()` — tại sao lại có?

Framework's `AsyncSagaOrchestrator.executeFlow()` mặc định publish `OrderCreatedEvent` với chỉ `quantity`. Nhưng ms-payment cần biết **`amount` + `currency`** để charge đúng — nó không có context bảng giá vé.

Solution: thêm `protected DomainEvent buildOrderCreatedEvent(SagaContext)` vào framework với default trả về `OrderCreatedEvent`. `TicketBookingOrchestrator` override để trả `PaymentRequestedEvent` (giàu thông tin hơn). Zero breaking change cho user khác của framework.

### 6.5 Database-per-service

3 schema trong cùng 1 instance Postgres (`order_db`, `inventory_db`, `payment_db`). Service A không được query DB của service B. Tách instance khi scale ra production.

---

## 7. Reconciliation — safety net 5 case

`TicketReconciliationService` extend `AbstractReconciliationService<TicketOrder>` của framework, chạy mỗi 5 phút (`@Scheduled` với distributed lock Redisson — chỉ 1 instance chạy):

| Case | Phát hiện | Cách fix trong ms-order |
|------|-----------|-------------------------|
| 1. STALE_PENDING | order RESERVED/PENDING quá 5 phút | Query gateway: nếu không thấy thành công → release Redis + cancel order |
| 2. LATE_PAYMENT_SUCCESS | gateway báo SUCCESS sau khi đã timeout | Confirm order, **không** release inventory (tiền đã trừ) |
| 3. INVENTORY_MISMATCH | Redis ≠ DB inventory | **Skip** trong ms-order — ms-inventory tự reconcile (delegated, ms-order không own bảng) |
| 4. UNPERSISTED_RESERVATION | order CONFIRMED, DB inventory chưa update | **Skip** — delegated to ms-inventory |
| 5. DUPLICATE_ORDER | 2+ order cùng `idempotencyKey` | Giữ CONFIRMED > RESERVED > PENDING, cancel + release Redis cho phần còn lại |

Lý do skip case 3-4: ms-order không có quyền sửa `inventory_db` (đó là DB của ms-inventory). ms-inventory có reconciliation cycle riêng cho 2 case này.

---

## 8. Cách chạy

### 8.1 Prerequisites

- JDK 17+
- Maven 3.9+
- Docker Desktop (cho infra)
- (Optional) k6 để chạy load test: `winget install k6` hoặc `brew install k6`

### 8.2 Build framework trước (chạy 1 lần duy nhất)

`hcr-product` phụ thuộc các artifact `io.hrc:hcr-*` — phải install vào local Maven repo trước:

```bash
# Tại root io.hrc/
mvn clean install -DskipTests
```

### 8.3 Up infra

```bash
cd hcr-product/infra
docker compose up -d

# Đợi healthy (~30 giây)
docker compose ps
```

Up xong sẽ có:
- Postgres `localhost:5432` (user/pass: `hcr/hcr`, 3 DB: `order_db`, `inventory_db`, `payment_db`)
- Redis `localhost:6379`
- Kafka `localhost:9092`
- Zipkin `http://localhost:9411`
- Prometheus `http://localhost:9090`
- Grafana `http://localhost:3000` (admin/admin)

### 8.4 Build product

```bash
cd hcr-product
mvn clean package -DskipTests
```

### 8.5 Chạy 3 service (mỗi cái một terminal)

```bash
# Terminal 1
java -jar ms-inventory/target/ms-inventory-1.0.0-SNAPSHOT.jar

# Terminal 2
java -jar ms-payment/target/ms-payment-1.0.0-SNAPSHOT.jar

# Terminal 3 (start cuối cùng — sau khi ms-inventory đã seed Redis)
java -jar ms-order/target/ms-order-1.0.0-SNAPSHOT.jar
```

Hoặc `mvn spring-boot:run` trong từng module nếu đang dev.

### 8.6 Smoke test

**Linux / macOS / Git Bash:**
```bash
# Đặt 1 vé
curl -X POST http://localhost:8081/orders \
  -H 'Content-Type: application/json' \
  -d '{
    "resourceId": "concert-001",
    "requesterId": "user-001",
    "quantity": 1,
    "idempotencyKey": "smoke-001"
  }'
# → 202 ACCEPTED + { "orderId": "...", "status": "RESERVED", ... }

# Poll status (đợi vài giây cho payment xử lý xong)
curl http://localhost:8081/orders/{orderId}
```

**Windows CMD** (escape dấu `"` bằng `\"`, payload phải nằm trên 1 dòng):
```bat
curl -X POST http://localhost:8081/orders ^
  -H "Content-Type: application/json" ^
  -d "{\"resourceId\":\"concert-001\",\"requesterId\":\"user-001\",\"quantity\":1,\"idempotencyKey\":\"smoke-001\"}"

curl http://localhost:8081/orders/<orderId>
```

**Windows PowerShell — cách 1: dùng `curl.exe` (binary built-in của Win10/11)**

> ⚠️ Trong PowerShell, `curl` (không có `.exe`) là **alias của `Invoke-WebRequest`** — cmdlet này có `-H` = `-Headers` đòi Dictionary, không phải String. Gõ `curl -H "..."` sẽ lỗi `Cannot convert ... to System.Collections.IDictionary`. Phải gọi rõ `curl.exe` để dùng curl thật, hoặc dùng cách 2.

```powershell
curl.exe -X POST http://localhost:8081/orders `
  -H "Content-Type: application/json" `
  -d "{\""resourceId\"":\""concert-001\"",\""requesterId\"":\""user-001\"",\""quantity\"":1,\""idempotencyKey\"":\""smoke-001\""}"

curl.exe http://localhost:8081/orders/<orderId>
```

Trong PS quote khá rườm rà. Nếu chạy 1 dòng (không xuống dòng), có thể đơn giản hơn:
```powershell
curl.exe -X POST http://localhost:8081/orders -H "Content-Type: application/json" -d '{\"resourceId\":\"concert-001\",\"requesterId\":\"user-001\",\"quantity\":1,\"idempotencyKey\":\"smoke-001\"}'
```

**Windows PowerShell — cách 2: dùng `Invoke-RestMethod` (idiomatic, không cần escape quote)**

```powershell
$body = @{
    resourceId     = "concert-001"
    requesterId    = "user-001"
    quantity       = 1
    idempotencyKey = "smoke-001"
} | ConvertTo-Json

Invoke-RestMethod -Method Post -Uri http://localhost:8081/orders `
    -ContentType "application/json" `
    -Body $body
# → object với property orderId, status, totalAmount...

# Poll status — thay <orderId> bằng giá trị thật
Invoke-RestMethod -Uri http://localhost:8081/orders/<orderId>
```

**Kết quả mong đợi:**
- Lần 1 (POST): HTTP 202, body có `orderId`, `status: "RESERVED"`, `totalAmount: 500000.00`
- Đợi 2-3 giây cho ms-payment xử lý
- Lần 2 (GET): `status: "CONFIRMED"` (~80% trường hợp với mock gateway, success rate 80%) hoặc `CANCELLED` nếu mock fail/timeout

### 8.7 Load test

Xem chi tiết `load-tests/README.md`. Quick run:

```bash
# Smoke (30s, 5 VU)
k6 run load-tests/k6/oversell-check.js

# Burst — verify zero oversell trên concert-003 (500 vé), 1000 VU
k6 run load-tests/k6/burst.js

# Sustained — soak 5 phút, 200 VU
k6 run load-tests/k6/sustained.js
```

#### ⚠️ HIỂU ĐÚNG INVARIANT ZERO-OVERSELL

**HTTP 202 count CÓ THỂ > 500 mà vẫn KHÔNG oversell.** Đây là behavior đúng, không phải bug:

| Cycle | Trạng thái Redis | HTTP 202 cộng dồn |
|-------|------------------|-------------------|
| 1. 500 reserves đầu | available = 0 | 500 |
| 2. Mock payment fail (~10%) → compensate → INCRBY | available > 0 | 500 |
| 3. Reserve mới chiếm slot vừa release | available = 0 | 500 + N |

→ Tổng 202 = 500 + N (N = số reserves đã rotate qua compensate cycle). Burst test thường thấy 550-650 reserves "thành công" → ~50-150 trong số đó cuối cùng là `CANCELLED` trong DB (do payment fail).

**Invariant zero-oversell ĐÚNG (verify SAU test):**

1. **DB invariant:** `CONFIRMED + RESERVED ≤ 500`
   ```bash
   docker exec hcr-postgres psql -U hcr -d order_db -c \
     "SELECT status, COUNT(*) FROM ticket_orders WHERE resource_id='concert-003' GROUP BY status;"
   ```

2. **Redis invariant:** `CONFIRMED + Redis_available = total = 500`
   ```bash
   docker exec hcr-redis redis-cli GET hcr:inventory:concert-003
   # Cộng với CONFIRMED count phải = 500
   ```

`burst.js` đã được cấu hình:
- `http_req_failed: rate<0.01` — chỉ tính 5xx + connection errors (422 KHÔNG bị flag là failed nhờ `http.setResponseCallback`).
- `errors: rate<0.01` — custom counter cho mọi response không phải 202/422/409.

### 8.8 Reset giữa các lần test

**LUÔN dùng đúng quy trình dưới đây**, KHÔNG bao giờ `redis-cli SET hcr:inventory:*` thủ công (sẽ phá guard của `release.lua` vì thiếu key `hcr:inventory:total:*`).

```bash
# 1. Wipe Redis (xoá tất cả inventory keys, saga state, idempotency claims)
docker exec hcr-redis redis-cli FLUSHALL

# 2. Wipe orders + payment audit trong cả 3 DB
docker exec hcr-postgres psql -U hcr -d order_db     -c "DELETE FROM ticket_orders;"
docker exec hcr-postgres psql -U hcr -d payment_db   -c "DELETE FROM payment_attempts;"
docker exec hcr-postgres psql -U hcr -d inventory_db -c "DELETE FROM hcr_processed_events;"

# 3. Reset available_quantity về full trong inventory_db
#    (do persistence consumer đã decrement available qua các test trước)
docker exec hcr-postgres psql -U hcr -d inventory_db -c \
  "UPDATE concert_tickets SET available_quantity = total_quantity, version = version + 1;"

# 4. Restart ms-inventory để RedisSeeder warm Redis lại từ Postgres
#    (Ctrl+C terminal đang chạy ms-inventory, rồi chạy lại jar)
java -jar ms-inventory/target/ms-inventory-1.0.0-SNAPSHOT.jar

# 5. Verify Redis state TRƯỚC khi load test
docker exec hcr-redis redis-cli GET "hcr:inventory:concert-003"        # → 500
docker exec hcr-redis redis-cli GET "hcr:inventory:total:concert-003"  # → 500
```

`RedisSeeder` là **idempotent** — chỉ initialize key chưa tồn tại. Nếu sau FLUSHALL bạn restart ms-inventory mà không cần wipe gì khác thì Seeder sẽ tự seed lại từ DB (nhưng vẫn nên TRUNCATE order tables để bắt đầu sạch).

---

## 9. Quan sát hệ thống

### 9.1 Prometheus metrics (mỗi service expose `/actuator/prometheus`)

Metrics quan trọng (do framework cung cấp qua `FrameworkMetrics`):

| Metric | Ý nghĩa |
|--------|---------|
| `hcr_inventory_reserve_attempts_total` | Tổng lần thử reserve |
| `hcr_inventory_reserve_failures_total{reason}` | Lý do fail (`OUT_OF_STOCK`, `LOCK_TIMEOUT`...) |
| `hcr_saga_started_total` | Saga bắt đầu |
| `hcr_saga_confirmed_total` | Saga thành công |
| `hcr_saga_cancelled_total{reason}` | Saga cancel + lý do |
| `hcr_saga_compensated_total` | Số lần compensate |
| `hcr_payment_attempts_total` | Lượt charge gateway |
| `hcr_payment_results_total{outcome}` | Outcome của charge |
| `hcr_event_bus_published_total{type}` | Số event publish theo loại |
| `hcr_event_bus_consumed_total{type}` | Số event consume theo loại |
| `hcr_reconciliation_runs_total` | Số cycle reconcile đã chạy |
| `hcr_reconciliation_fixed_total{case}` | Số fix theo từng case |

### 9.2 Grafana

Truy cập http://localhost:3000 (admin/admin). Datasource Prometheus + Zipkin đã được provision sẵn. Tự build dashboard hoặc import JSON.

### 9.3 Zipkin tracing

Mở http://localhost:9411 sau khi chạy 1 vài request. Pick 1 trace để xem flow:
- `ms-order` HTTP span → publish Kafka span → `ms-payment` consume span → publish payment outcome → `ms-order` consume span → DB update.

Sampling đã đặt 100% (`management.tracing.sampling.probability: 1.0`) cho dev. Trên prod giảm xuống 0.1.

---

## 10. Thứ tự đọc code đề xuất

Mới đến project lần đầu, đọc theo trình tự dưới đây sẽ hiểu nhanh nhất:

1. **`hcr-product/pom.xml`** — hiểu cấu trúc Maven và dependency manag.
2. **`infra/docker-compose.yml`** — hiểu các thành phần hạ tầng.
3. **`ms-shared/`** — bắt đầu từ shared types để biết "ngôn ngữ chung":
   - `dto/PlaceTicketOrderRequest.java` + `dto/TicketOrderResponse.java`
   - `event/PaymentRequestedEvent.java`
4. **`ms-order/`** (entry point — hiểu được thì mọi thứ rõ):
   - `controller/OrderController.java` — request đi vào đâu
   - `domain/{TicketOrder,TicketRequest,ConcertTicket}.java` — domain model
   - `saga/TicketBookingOrchestrator.java` — quan trọng nhất, các hook override
   - `saga/RedisSagaStateRepository.java` — saga state ở đâu
   - `listener/PaymentResultListener.java` — payment outcome quay về như nào
   - `config/OrderConfiguration.java` — bean wiring
   - `reconciliation/TicketReconciliationService.java` — safety net
5. **`ms-payment/`** (lightweight, đọc nhanh):
   - `listener/PaymentRequestedListener.java` — toàn bộ business logic ở đây
   - `domain/PaymentAttempt.java` — idempotency model
6. **`ms-inventory/`** (lightweight nhất):
   - `config/RedisSeeder.java` — warm-up Redis
   - `config/InventoryConfiguration.java` — wiring strategy + persistence consumer
7. **Framework** — sau khi hiểu product, đọc framework để hiểu các abstract class:
   - `hcr-saga/.../AbstractSagaOrchestrator.java` + `AsynchronousSagaOrchestrator.java`
   - `hcr-inventory/.../strategy/redis/RedisAtomicStrategy.java` + 2 file Lua
   - `hcr-reconciliation/.../AbstractReconciliationService.java`
   - `hcr-eventbus/.../adapter/kafka/KafkaEventBusAdapter.java`

---

## 11. Known limitations

- **Reservation timeout = 5 phút** (hardcode trong reconciliation default). Order RESERVED quá 5 phút sẽ bị reconciliation cancel — dù payment có đang xử lý. Sửa: override `getTimeoutMinutes()` trong `TicketReconciliationService`.
- **Mock payment gateway** — `MockPaymentGateway` random success/fail/timeout. Để tích hợp gateway thật (Stripe, VNPay...), cần implement `PaymentGateway` interface và disable mock qua `hcr.payment.mock-enabled: false`.
- **Khi P3 ms-order crash giữa Redis DECR và publish event** → event mất, vé bị "treo" trong Redis. Reconciliation sẽ phát hiện trong vòng ≤ 5 phút (case 1) và release.
- **Saga state TTL Redis = 1h** — nếu reconciliation bị down liên tục > 1h, sẽ mất saga state. Trong trường hợp đó: order CONFIRMED nhưng inventory rò rỉ. Mitigation: monitor `hcr_reconciliation_runs_total` không được dừng.
- **Không có authentication** — mọi request đều được nhận. Production cần thêm gateway auth layer (Spring Cloud Gateway / Kong / nginx).
- **Không có rate limit** — `hcr.gateway.rate-limiter.enabled: false`. Bật khi cần.

---

## 12. Tài liệu liên quan

- `../CLAUDE.md` — context tổng quan framework HCR
- `../docs/framework_design.md` — thiết kế chi tiết framework
- `../docs/PROGRESS.md` — log tiến độ + các quyết định thiết kế
- `load-tests/README.md` — chi tiết k6 scripts
- Mỗi module framework có `GUIDE.md` riêng — đọc khi cần đi sâu vào logic của module đó
