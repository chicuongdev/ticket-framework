# HCR Product — Luồng hoạt động 3 prototype (P1 / P2 / P3)

> Mô tả chi tiết luồng xử lý `POST /orders` cho từng prototype, kèm **mọi case** có
> thể xảy ra (thành công, hết vé, payment fail, timeout, order kẹt, reconciliation).
> Đọc kèm `runbook-aws-ec2.md` (cách chạy) và `../../docs/SYSTEM_P3.md` (kiến trúc P3).

---

## 0. Bối cảnh

3 microservice + infra (Postgres / Redis / Kafka / Zipkin / Prometheus / Grafana):

| Service | Vai trò | Port | DB |
|---------|---------|------|-----|
| **ms-order** | HTTP entry point, saga orchestration, reconciliation | 8081 | `order_db` |
| **ms-inventory** | Seed Redis, sync `inventory_db` từ event (chỉ P3) | 8082 | `inventory_db` |
| **ms-payment** | **Service thanh toán DUY NHẤT** — tích hợp cổng thanh toán | 8083 | `payment_db` |

**Nguyên tắc sau refactor:** `ms-order` không còn payment gateway local. Cả 3 prototype
thanh toán qua `ms-payment` — P1/P2 gọi HTTP đồng bộ ngay trong request thread; P3 cũng
gọi HTTP `POST /payments` nhưng từ **background thread** (`AutoChargeInitiation`), không
chặn request — đó là điểm khác duy nhất.

| | **P1** | **P2** | **P3** |
|--|--|--|--|
| Inventory | Pessimistic `SELECT FOR UPDATE` | Optimistic `@Version` + retry | Redis Lua `DECRBY` |
| Saga | đồng bộ | đồng bộ | bất đồng bộ |
| Charge | HTTP `POST /payments` (request thread, blocking) | HTTP `POST /payments` (request thread, blocking) | HTTP `POST /payments` (background pool, non-blocking) |
| Inventory store | `order_db.concert_tickets` | `order_db.concert_tickets` | Redis (DB sync lag) |
| HTTP khi đặt OK | **201** CONFIRMED | **201** CONFIRMED | **202** RESERVED |

---

## 1. Khung chung — `POST /orders` (mọi prototype)

Trước khi vào saga, `OrderController` chặn duplicate bằng **idempotency claim** trên Redis:

```
 [Client] POST /orders {resourceId, requesterId, quantity, idempotencyKey}
    │
    ▼
 ┌──────────────────────────────────────────────────────────────┐
 │ OrderController.place()                                       │
 │                                                               │
 │  ① SETNX hcr:idempotency:{key} = "PROCESSING"  (TTL 24h)      │
 │     ├─ FALSE (key đã tồn tại) ──▶ handleDuplicate():          │
 │     │     • value == "PROCESSING" → HTTP 409 DUPLICATE_IN_FLIGHT
 │     │     • value == orderId      → trả status order (200)    │
 │     └─ TRUE  → giành được claim, đi tiếp                      │
 │                                                               │
 │  ② orchestrator.process(request)   ◀── rẽ nhánh P1/P2/P3      │
 │                                                               │
 │  ③ Phân loại kết quả:                                         │
 │     • order CANCELLED  → xoá claim → HTTP 422 (reason)        │
 │     • order CONFIRMED  → set claim=orderId → HTTP 201         │
 │     • order RESERVED   → set claim=orderId → HTTP 202         │
 │     • ValidationException → xoá claim → HTTP 400              │
 │     • FrameworkException  → xoá claim → HTTP 422              │
 │     • Exception khác      → xoá claim → HTTP 500 + requestId  │
 └──────────────────────────────────────────────────────────────┘
```

`orchestrator.process()` luôn gọi `validate → createOrder(PENDING) → executeFlow()`.
`executeFlow()` là điểm rẽ nhánh: **sync** (P1/P2) hay **async** (P3).

---

## 2. P1 — Pessimistic Lock (saga đồng bộ)

### 2.1. Sơ đồ tổng thể

```
 orchestrator.process()  [TicketBookingSyncOrchestrator.executeFlow]
    │
    │  saveOrder(PENDING) → order_db.ticket_orders
    ▼
 ┌─────────────────────────────────────────────────────────────┐
 │ Step 1 — RESERVE  [PessimisticLockStrategy]                  │
 │   TransactionTemplate:                                       │
 │     SELECT * FROM concert_tickets                            │
 │       WHERE resource_id=? FOR UPDATE     ◀── khoá dòng       │
 │     if available_quantity < qty → ReservationResult.insufficient
 │     UPDATE concert_tickets                                   │
 │       SET available_quantity = available_quantity - qty      │
 │     COMMIT                               ◀── nhả khoá        │
 └───────┬─────────────────────────────────────────────────────┘
         │
         ├─ insufficient ──▶ cancelOrder(INSUFFICIENT_INVENTORY)
         │                   order = CANCELLED → return  ▶ HTTP 422
         │
         ▼ success → order = RESERVED, saveOrder
 ┌─────────────────────────────────────────────────────────────┐
 │ Step 2 — CHARGE  [PaymentStep → RemotePaymentGateway]        │
 │   POST http://ms-payment:8083/payments                       │
 │     {orderId, resourceId, amount, currency}                  │
 │        │                                                     │
 │        ▼  ms-payment: PaymentController → PaymentProcessing   │
 │           Service.charge() → paymentGateway.charge()          │
 │           (idempotent theo orderId trong payment_attempts)    │
 │        ◀── PaymentResult {SUCCESS | FAILED | TIMEOUT | UNKNOWN}
 └───────┬─────────────────────────────────────────────────────┘
         │
         ├─ FAILED / UNKNOWN ─▶ compensate: release() trả vé về
         │                       order_db → cancelOrder → CANCELLED
         │                       ▶ HTTP 422
         │
         ▼ SUCCESS
 ┌─────────────────────────────────────────────────────────────┐
 │ Step 3 — CONFIRM                                             │
 │   order = CONFIRMED → saveOrder                              │
 │   publish OrderConfirmedEvent                                │
 └───────┬─────────────────────────────────────────────────────┘
         ▼
   return order (CONFIRMED)  ▶ HTTP 201
```

### 2.2. Các case chi tiết

**Case P1-A — Happy path (đặt vé thành công)**
1. Claim OK → `process()`.
2. `SELECT FOR UPDATE` khoá dòng `concert_tickets`, `available ≥ qty` → `UPDATE available -= qty` → COMMIT. Order → `RESERVED`.
3. `POST /payments` → ms-payment charge → `SUCCESS`.
4. Order → `CONFIRMED`, lưu DB, publish `OrderConfirmedEvent`.
5. **HTTP 201**, body `status=CONFIRMED`.

**Case P1-B — Hết vé (`available < qty`)**
1. `SELECT FOR UPDATE` thấy `available_quantity < qty` → `ReservationResult.insufficient`.
2. `cancelOrder(INSUFFICIENT_INVENTORY)` → order `CANCELLED`. **Không** charge.
3. Controller thấy `CANCELLED` → **HTTP 422** `{error: INSUFFICIENT_INVENTORY}`, xoá claim.
4. Zero-oversell được bảo đảm bởi khoá bi quan: hai request đồng thời bị tuần tự hoá tại `FOR UPDATE`.

**Case P1-C — Payment bị từ chối (`FAILED`)**
1. Reserve OK → order `RESERVED`.
2. `POST /payments` → ms-payment gateway trả `FAILED` (vd thẻ bị từ chối).
3. `compensate()` → `release()` cộng trả `available_quantity += qty` vào `order_db`.
4. `cancelOrder(PAYMENT_FAILED)` → order `CANCELLED`.
5. **HTTP 422** `{error: PAYMENT_FAILED}`. Vé đã được trả lại kho.

**Case P1-D — Charge timeout / không rõ kết quả (`UNKNOWN`)**
1. Reserve OK → `RESERVED`.
2. `POST /payments` bị timeout (ms-payment chậm / mạng lỗi). `RemotePaymentGateway`
   **không kết luận ngay** — poll `GET /payments/{orderId}` tối đa 3 lần × 1s.
   - Nếu poll thấy `SUCCESS` → xử lý như Case P1-A (HTTP 201).
   - Nếu poll thấy `FAILED` → như Case P1-C (HTTP 422).
   - Hết 3 lần vẫn chưa rõ → trả `UNKNOWN`.
3. `UNKNOWN` bị coi là không thành công → `compensate` (release) + `cancelOrder` → **HTTP 422**.
4. ⚠ **Rủi ro tồn dư:** nếu ms-payment *thực sự* đã charge thành công nhưng cả 3 lần
   poll đều chưa kịp thấy → order bị CANCELLED oan (khách mất tiền, không có vé).
   Cơ chế poll thu hẹp cửa sổ này; reconciliation **không** bắt được vì chỉ quét
   order `RESERVED/PENDING` (đã CANCELLED thì bỏ qua). Đây là giới hạn chấp nhận của
   saga đồng bộ — xem mục 6.

**Case P1-E — Duplicate request (cùng `idempotencyKey`)**
- Request thứ 2 tới khi request 1 còn chạy → claim = `"PROCESSING"` → **HTTP 409**.
- Request thứ 2 tới sau khi request 1 xong → claim = `orderId` → trả status order hiện tại (**HTTP 200**).

---

## 3. P2 — Optimistic Lock (saga đồng bộ)

Luồng **giống hệt P1** (Step 1 reserve → Step 2 charge → Step 3 confirm, cùng các case
A–E, cùng HTTP code). Khác **duy nhất ở cơ chế Step 1 RESERVE**:

```
 ┌─────────────────────────────────────────────────────────────┐
 │ Step 1 — RESERVE  [OptimisticLockStrategy]                   │
 │                                                              │
 │   attempt = 1..maxRetries:                                   │
 │     ┌─ Transaction MỚI mỗi vòng (Hibernate cache version) ─┐ │
 │     │  SELECT available_quantity, version                  │ │
 │     │    FROM concert_tickets WHERE resource_id=?           │ │
 │     │  if available < qty → return insufficient             │ │
 │     │  UPDATE concert_tickets                               │ │
 │     │    SET available_quantity = available - qty,          │ │
 │     │        version = version + 1                          │ │
 │     │    WHERE resource_id=? AND version = {đã đọc}          │ │
 │     │  rows updated == 0 ? → version đã đổi (request khác    │ │
 │     │                        chen vào) → RETRY vòng mới     │ │
 │     │  rows updated == 1 ? → COMMIT, reserve thành công      │ │
 │     └───────────────────────────────────────────────────────┘ │
 └─────────────────────────────────────────────────────────────┘
```

### Case riêng của P2

**Case P2-F — Version conflict → retry**
1. Hai request đọc cùng `version = v`.
2. Request 1 `UPDATE ... WHERE version = v` → thành công, version → `v+1`.
3. Request 2 `UPDATE ... WHERE version = v` → **0 dòng** (version giờ là `v+1`).
4. Request 2 mở **transaction mới** (bắt buộc — Hibernate cache version cũ trong session
   cũ), đọc lại `version = v+1`, thử lại.
5. Lặp tới khi thành công hoặc hết `maxRetries`:
   - Thành công trong giới hạn retry → tiếp Step 2 (giống P1-A).
   - Hết retry vẫn conflict → `ReservationResult` lỗi → `cancelOrder` → **HTTP 422**.
6. Khác P1: không khoá dòng → throughput cao hơn khi ít tranh chấp, nhưng tốn retry
   khi tranh chấp gay gắt (vé sắp hết).

> P2 vẫn zero-oversell: điều kiện `WHERE version = {đã đọc}` đảm bảo chỉ một request
> ghi đè được trên cùng một phiên bản dòng — request thua phải đọc lại số `available` mới.

---

## 4. P3 — Redis Atomic (saga bất đồng bộ)

P3 tách **critical path** (trả HTTP nhanh, trên request thread) khỏi **payment path**
(charge HTTP sang ms-payment chạy trên **background pool**). P3 KHÔNG còn dùng Kafka
cho payment trigger — chỉ còn 1 event duy nhất là `ResourceReservedEvent` (DB sync).

### 4.1. Critical path — `POST /orders` → HTTP 202

```
 orchestrator.process()  [TicketBookingOrchestrator.executeFlow — async]
    │
    ▼
 ┌─────────────────────────────────────────────────────────────┐
 │ RESERVE  [RedisAtomicStrategy] — Lua atomic                  │
 │   EVAL inventory_reserve.lua hcr:inventory:{resourceId} qty  │
 │     local a = GET key                                        │
 │     if a == nil      → return -1   (chưa seed)               │
 │     if a < qty       → return -2   (hết vé)                  │
 │     return DECRBY key qty          (remaining)               │
 └───────┬─────────────────────────────────────────────────────┘
         │
         ├─ -1 (chưa seed) → lỗi cấu hình → cancelOrder → HTTP 422
         ├─ -2 (hết vé)    → cancelOrder(INSUFFICIENT) → HTTP 422
         │
         ▼ remaining ≥ 0
 ┌─────────────────────────────────────────────────────────────┐
 │  • lưu SagaContext (RESERVED) vào SagaStateRepository (Redis)│
 │  • publish ResourceReservedEvent  → Kafka (ms-inventory sync)│
 │  • paymentInitiationStrategy.onResourceReserved(order, req)  │
 │       └─ AutoChargeInitiation: executor.execute(task)        │
 │              └─ task chạy trên background pool (non-blocking)│
 └───────┬─────────────────────────────────────────────────────┘
         ▼
   return order (RESERVED)  ▶ HTTP 202    ◀── charge MỚI submit, chưa chạy
```

### 4.2. Payment path — chạy trên background pool

```
 [Background pool: auto-charge-1 .. auto-charge-N]
    │
    ▼
 ┌─────────────────────────────────────────────────────────────┐
 │ AutoChargeInitiation.chargeAndDispatch()                     │
 │   → paymentGateway.charge(request)                           │
 │        = RemotePaymentGateway.charge()                       │
 │             → POST http://ms-payment:8083/payments           │
 │                  ├─ ms-payment: PaymentProcessingService     │
 │                  │     • idempotent theo orderId (PK)        │
 │                  │     • gateway.charge() → cổng thanh toán  │
 │                  └─ trả HTTP response (SUCCESS/FAILED/...)   │
 │   → outcomeHandler.accept(orderId, PaymentResult)            │
 │       [policy ở OrderConfiguration]                          │
 │       SUCCESS / FAILED → orchestrator.handlePaymentResult()  │
 │       TIMEOUT / UNKNOWN → log + giữ RESERVED (reconciliation)│
 └───────┬─────────────────────────────────────────────────────┘
         ▼
 ┌─────────────────────────────────────────────────────────────┐
 │ orchestrator.handlePaymentResult(orderId, result)            │
 │   SUCCESS → CONFIRM: order CONFIRMED, lưu order_db lần đầu  │
 │   FAILED  → compensate: release() Redis → CANCELLED         │
 └─────────────────────────────────────────────────────────────┘
```

> **Khác biệt thiết kế cũ:** trước đây `PaymentRequestedEvent` đi qua Kafka, ms-payment
> consume và publish lại `PaymentSucceeded/Failed/Timeout/Unknown`, ms-order's
> `PaymentResultListener` consume rồi mới gọi `handlePaymentResult` — **2 hop Kafka**
> chỉ để loop kết quả về chính ms-order. Sau refactor: 1 HTTP roundtrip + direct call.

### 4.3. Các case chi tiết

**Case P3-A — Happy path**
1. `POST /orders` → Lua `DECRBY` thành công → order `RESERVED`. **HTTP 202** trả ngay.
2. Background `auto-charge-X` thread: `POST /payments` → ms-payment charge → `SUCCESS`.
3. `outcomeHandler` → `handlePaymentResult` → order `CONFIRMED`, lần đầu ghi `order_db`.
4. Client `GET /orders/{id}` thấy `CONFIRMED` (trước đó đọc từ SagaState = `RESERVED`).

**Case P3-B — Hết vé**
1. Lua trả `-2` → `cancelOrder(INSUFFICIENT_INVENTORY)` → **HTTP 422**. Redis không bị trừ.

**Case P3-C — Payment thất bại (async)**
1. `RESERVED` + HTTP 202.
2. Background thread: ms-payment charge → `FAILED`.
3. `outcomeHandler` → `handlePaymentResult` → `compensate`: Lua `INCRBY` trả vé về Redis
   → order `CANCELLED`.
4. Client `GET /orders/{id}` → `CANCELLED` + `failureReason`.

**Case P3-D — Payment timeout / unknown → order kẹt `RESERVED`**
1. `RESERVED` + HTTP 202.
2. Background thread: `RemotePaymentGateway.charge()` timeout, poll status vẫn không rõ
   → trả `PaymentResult.timeout()` (hoặc `unknown()`).
3. `outcomeHandler` **chỉ log** — **không** dispatch sang `handlePaymentResult`. Order
   **ở yên `RESERVED`**.
   - Lý do: payment chỉ đang *chậm*, có thể vẫn thành công. Cancel vội = huỷ oan.
4. Order được **reconciliation** xử lý sau (mục 5).

**Case P3-E — Dual-write gap (crash giữa DECRBY và publish/submit)**
1. Lua `DECRBY` thành công nhưng tiến trình crash **trước** khi `ResourceReservedEvent`
   publish xong, hoặc trước khi `executor.execute(charge task)` chạy.
2. Redis đã trừ vé. Tuỳ điểm crash:
   - Trước `executor.execute()`: không ai charge → order treo ở `RESERVED` (SagaState).
     Reconciliation bắt được (mục 5).
   - Sau `executor.execute()` nhưng task chưa chạy xong: task **mất** khi JVM crash
     (in-memory pool, không persist). Cùng nhánh xử lý như trên.
3. Lệch Redis ↔ `inventory_db` do thiếu `ResourceReservedEvent` → reconciliation
   `INVENTORY_MISMATCH` (Case 3) phát hiện trong ≤ 5 phút.

**Case P3-F — Duplicate request**: giống P1-E (claim Redis).

### 4.4. DB sync (P3) — bất đồng bộ

`order_db` chỉ được ghi khi order `CONFIRMED`. `inventory_db.concert_tickets` được
`ms-inventory` cập nhật lag qua `ResourceReservedEvent`/`ResourceReleasedEvent`.
**Source of truth cho P3 là Redis**, không phải DB.

---

## 5. Reconciliation — chạy ở `ms-order` (chung cho mọi prototype)

`@Scheduled` mỗi 5 phút (`hcr.reconciliation.schedule-delay-ms`), distributed lock
Redisson đảm bảo 1 instance chạy. Quan trọng nhất: **Case 1/2 — order kẹt**.

```
 runReconciliation() → runCase1And2()
    │
    │  findStalePendingOrders(): order ở RESERVED/PENDING quá timeout (mặc định 5')
    ▼
 ┌─────────────────────────────────────────────────────────────┐
 │ Với mỗi order kẹt:                                           │
 │   OrderReconciler.verify()                                   │
 │     → paymentGateway.queryStatus(orderId)                    │
 │        = RemotePaymentGateway → GET ms-payment:8083/payments/{id}
 │        → ms-payment: PaymentProcessingService.queryStatus()  │
 │            → paymentGateway.queryStatus()  ◀── HỎI CỔNG       │
 │                                              THANH TOÁN      │
 └───────┬─────────────────────────────────────────────────────┘
         │
         ├─ SUCCESS  → handleLatePaymentSuccess(): order → CONFIRMED
         │             (Case 2 LATE_PAYMENT_SUCCESS — tiền đã trừ, cấp vé)
         │
         ├─ FAILED   → handleTimeout(): release() trả vé + order → CANCELLED
         │             (Case 1 STALE_PENDING — cổng xác nhận thất bại)
         │
         └─ UNKNOWN / TIMEOUT / không gọi được ms-payment
                     → handleUnresolvedPayment(): CHỈ log — KHÔNG cancel.
                       Order ở yên, cycle reconciliation sau verify lại.
```

> **Cải tiến framework:** trước đây `runCase1And2` chỉ có 2 nhánh (SUCCESS→confirm,
> *còn lại*→cancel) → huỷ oan order mà payment chỉ đang chậm. Nay có nhánh thứ 3
> `handleUnresolvedPayment` (abstract hook — product tự quyết). Xem
> `AbstractReconciliationService` trong `hcr-reconciliation`.

### Các case reconciliation

**Case R-1 — Order kẹt, cổng trả `SUCCESS` (Tình huống B)**
Payment đã thành công nhưng outcome event bị mất (P3) / response bị mất (P1/P2).
→ `handleLatePaymentSuccess` → order `RESERVED` → `CONFIRMED`. Khách có vé.

**Case R-2 — Order kẹt, cổng trả `FAILED`**
→ `handleTimeout` → `release()` trả vé về kho (Redis cho P3) → order `CANCELLED`.

**Case R-3 — Order kẹt, cổng trả `UNKNOWN/TIMEOUT`**
Payment vẫn chưa ngã ngũ. → `handleUnresolvedPayment` → **bỏ qua cycle này**,
giữ order `RESERVED`. Cycle sau (5' sau) verify lại. Lưới chốt cuối: `order.expiresAt`.

**Case R-4 — Không gọi được ms-payment**
`RemotePaymentGateway.queryStatus` bắt exception → trả `UNKNOWN` → đi vào R-3 (skip,
không huỷ oan order).

**Case R-5 — INVENTORY_MISMATCH (chỉ P3)**
`ms-inventory` reconcile riêng Redis ↔ `inventory_db`; `ms-order` chỉ alert (mục 4.4).

---

## 6. Bảng tổng hợp HTTP code & case

| Tình huống | P1 | P2 | P3 |
|------------|:--:|:--:|:--:|
| Đặt vé thành công | 201 CONFIRMED | 201 CONFIRMED | 202 RESERVED → CONFIRMED (async) |
| Hết vé | 422 INSUFFICIENT | 422 INSUFFICIENT | 422 INSUFFICIENT |
| Payment FAILED | 422 (đồng bộ) | 422 (đồng bộ) | 202 rồi → CANCELLED (async) |
| Payment timeout/unknown | 422 sau poll (rủi ro huỷ oan) | 422 sau poll | 202 → kẹt RESERVED → reconciliation |
| Duplicate đang xử lý | 409 | 409 | 409 |
| Duplicate đã xong | 200 (status order) | 200 | 200 |
| Validate sai | 400 | 400 | 400 |
| Lỗi hệ thống | 500 + requestId | 500 | 500 |

### Giới hạn chấp nhận (known limitations)

- **P1/P2 — huỷ oan khi charge `UNKNOWN`:** saga đồng bộ phải kết luận ngay trong
  request; nếu poll 3× vẫn chưa rõ → cancel. Nếu ms-payment thực ra đã charge →
  khách mất tiền, order CANCELLED. Reconciliation không cứu được (chỉ quét
  `RESERVED/PENDING`). Cơ chế poll thu hẹp cửa sổ.
- **P3 — dual-write gap:** crash giữa Redis `DECRBY` và Kafka `publish` → event mất.
  Reconciliation sửa trong ≤ 5 phút (Case P3-E / R-5).
- **P3 — order kẹt `RESERVED`:** payment timeout/unknown giữ order chờ; nếu
  reconciliation mãi không kết luận được → `order.expiresAt` là lưới chốt cuối.

---

## 7. Cách chạy load test (Windows / PowerShell)

3 kịch bản k6 đặt trong `hcr-product/load-tests/k6/`:

| Script | Mục tiêu | Concert | Pattern | Thời lượng |
|---|---|---|---|---|
| `oversell-check.js` | Smoke — verify endpoint + idempotency | concert-002 (5000 vé) | 5 VU constant | 30s |
| `burst.js` | Spike — verify zero-oversell | concert-003 (**500 vé**) | 0→1000 RPS ramp | 40s |
| `sustained.js` | Soak — đo p95/p99 ổn định | concert-001 (10000 vé) | 200 VU constant | 5 phút |

### 7.1. Chuẩn bị

```powershell
winget install k6 --source winget                    # cài k6 nếu chưa có
docker compose -f hcr-product\infra\docker-compose.yml up -d
java -jar hcr-product\ms-payment\target\ms-payment-1.0.0-SNAPSHOT.jar     # terminal A
java -jar hcr-product\ms-inventory\target\ms-inventory-1.0.0-SNAPSHOT.jar # terminal B (đợi log "Seeded")
java -jar hcr-product\ms-order\target\ms-order-1.0.0-SNAPSHOT.jar         # terminal C
```

### 7.2. Reset state — LUÔN làm trước mỗi lần test

```powershell
docker exec hcr-redis redis-cli FLUSHALL
docker exec hcr-postgres psql -U hcr -d order_db     -c "DELETE FROM ticket_orders;"
docker exec hcr-postgres psql -U hcr -d payment_db   -c "DELETE FROM payment_attempts;"
docker exec hcr-postgres psql -U hcr -d inventory_db -c "DELETE FROM hcr_processed_events;"
docker exec hcr-postgres psql -U hcr -d inventory_db -c "UPDATE concert_tickets SET available_quantity = total_quantity, version = version + 1;"
# Restart ms-inventory (Ctrl+C terminal B, chạy lại) — Seeder warm lại Redis
docker exec hcr-redis redis-cli GET "hcr:inventory:concert-003"   # phải = 500
```

> ⚠ KHÔNG `redis-cli SET hcr:inventory:*` thủ công — phá guard `release.lua`.
> Cách an toàn duy nhất: `FLUSHALL` + restart Seeder.

### 7.3. Chạy & verify

```powershell
k6 run hcr-product\load-tests\k6\oversell-check.js   # smoke
k6 run hcr-product\load-tests\k6\burst.js            # burst (reset trước!)
k6 run hcr-product\load-tests\k6\sustained.js        # soak (reset trước!)
```

**Verify zero-oversell SAU burst (quan trọng nhất):**
```powershell
docker exec hcr-postgres psql -U hcr -d order_db -c "SELECT status, COUNT(*) FROM ticket_orders WHERE resource_id='concert-003' GROUP BY status;"
docker exec hcr-redis redis-cli GET "hcr:inventory:concert-003"
# Invariant: CONFIRMED_count + Redis_available = 500
```

**HTTP 202/201 count CÓ THỂ > 500 mà KHÔNG oversell** — do compensate-retry cycle (payment fail → release Redis → request kế reserve lại slot). Verify zero-oversell qua **DB/Redis state**, KHÔNG qua HTTP success count.

### 7.4. Dấu vết riêng cần kiểm tra sau refactor (auto-charge HTTP, bỏ Kafka event)

```powershell
# Topic PaymentRequestedEvent KHÔNG có message mới
docker exec hcr-kafka kafka-console-consumer --bootstrap-server localhost:9092 `
    --topic hcr.payment-requested --from-beginning --max-messages 1 --timeout-ms 3000
# → timeout (không có message) là đúng

# Log ms-order có thread auto-charge-N chạy gateway call
# → grep "auto-charge" trong terminal ms-order

# Số SUCCESS trong payment_attempts ≈ số CONFIRMED orders
docker exec hcr-postgres psql -U hcr -d payment_db -c "SELECT status, COUNT(*) FROM payment_attempts GROUP BY status;"
```

### 7.5. Đọc kết quả burst test

Output mẫu của `burst.js` khi chạy đúng (concert-003 có 500 vé):

```
=== BURST TEST SUMMARY ===
Total requests:      ~9000
Accepted (201/202):  500-700        ← > 500 do compensate-retry
Rejected (422):      ~8000+         ← Redis hết vé → fail nhanh
Real errors:         0              ← không có 5xx/connection failed
```

**Cảnh báo k6 thường gặp:**

| Hiện tượng | Nguyên nhân | Phản ứng |
|---|---|---|
| `WARN Insufficient VUs, reached 1500 active VUs and cannot initialize more` | k6 không đẩy đủ tốc độ vì mỗi iteration kéo dài hơn dự kiến → cần thêm VU để giữ rate | **Không phải lỗi correctness.** Tăng `maxVUs` trong `burst.js` nếu cần đẩy thật 1000 RPS, hoặc chấp nhận spike thấp hơn |
| `thresholds on 'http_req_duration{status:202}' have been crossed` | p95/p99 latency của HTTP 202 vượt budget (500ms/1000ms) | Threshold là mục tiêu performance, **không phải invariant correctness**. Trên máy local laptop khi 3 service + DB + Kafka cùng cạnh tranh CPU, p95 ~600-900ms là bình thường |

**Phân biệt fail correctness vs fail budget:**
- ❌ Fail correctness (BUG): `CONFIRMED + Redis_available ≠ 500`, hoặc `Real errors > 0`, hoặc có order CONFIRMED mà không có `payment_attempts` SUCCESS.
- ⚠ Fail budget (TUNING): threshold latency crossed nhưng invariant DB/Redis vẫn đúng — đây là dấu hiệu cần tune (tăng Tomcat thread, tăng auto-charge pool, hoặc giảm RPS test).

### 7.6. Quan sát live
- Grafana http://localhost:3000 (admin/admin) — JVM heap, HTTP latency histogram
- Zipkin http://localhost:9411 — pick 1 trace `ms-order → ms-payment`, **KHÔNG còn span Kafka publish/consume cho payment** (chỉ còn HTTP span)
- Prometheus http://localhost:9090 — `hcr_payment_attempts_total`, `ms_order_orchestrator_duration_ms`
```
