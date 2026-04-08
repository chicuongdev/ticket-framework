# HCR Framework — Tiến độ & Kế hoạch

> **Cập nhật lần cuối:** 2026-04-05
>
> File này dùng để resume context nhanh giữa các session.
> Đọc file này TRƯỚC khi bắt đầu làm việc.

---

## Tổng quan dự án

**Framework:** HCR (High Concurrency Resource) — Spring Boot framework cho phân phát tài nguyên dưới tải cao (vé concert, phòng khách sạn, flash sale).

**Cấu trúc:** Maven multi-module, 12 module (parent pom + 11 child).
**Stack:** Java 17 + Spring Boot 3.2.5 + Lombok + Redisson + Resilience4j.

**Quy trình làm việc đã thống nhất:**
1. Implement từng module theo thứ tự dependency.
2. Mỗi module tạo kèm `GUIDE.md` ngay trong thư mục module — hướng dẫn thứ tự đọc code.
3. Strategies/decorators dùng `TransactionTemplate`, không dùng `@Transactional` (vì không phải Spring bean).

---

## Tiến độ hiện tại

### ✅ Module 01 — hcr-core (HOÀN THÀNH)

**Mục đích:** Định nghĩa "ngôn ngữ chung" cho toàn framework. Không có dependency vào module HCR khác.

**Các file đã implement:**

| File | Ghi chú |
|------|---------|
| `enums/OrderStatus.java` | State machine 6 trạng thái. `canTransitionTo()` + `isTerminal()`. |
| `enums/ResourceStatus.java` | ACTIVE, LOW_STOCK, DEPLETED, DEACTIVATED. `isAcceptingOrders()`. |
| `enums/FailureReason.java` | 8 lý do chuẩn hóa. Chú ý: `DUPLICATE_REQUEST` (không phải IDEMPOTENCY_CONFLICT), `RESERVATION_EXPIRED`. |
| `enums/ConsistencyLevel.java` | STRONG và EVENTUAL. |
| `domain/AbstractResource.java` | `markLowStock()`, `markDepleted()` là package-private. Hook `validate()`. |
| `domain/AbstractOrder.java` | `transitionTo()` package-private. Fields: `idempotencyKey`, `expiresAt`. |
| `domain/OrderRequest.java` | **Abstract class** (không phải concrete). Hook `validateRequest()`. |
| `domain/DomainEvent.java` | `eventId` (UUID auto), `eventType` (auto từ class name), `correlationId`, `retryCount`. |
| `result/ReservationResult.java` | Result Object pattern. Factory: `success()`, `insufficient()`, `error()`. `remainingAfter` chỉ có khi SUCCESS. |
| `result/ValidationResult.java` | `merge()` để gộp nhiều validation. `throwIfInvalid()`. Inner class `ValidationError`. |
| `result/InventorySnapshot.java` | `@Builder`. Field `source`: "redis" hoặc "database". `getDelta()`, `isConsistentWith()`. |
| `exception/FrameworkException.java` | Base exception. Fields: `reason`, `resourceId`, `orderId`. |
| `exception/InsufficientInventoryException.java` | Thêm `requestedQuantity`, `availableQuantity`. |
| `exception/PaymentException.java` | Phân biệt theo `reason`: PAYMENT_FAILED / TIMEOUT / UNKNOWN. |
| `exception/IdempotencyException.java` | Thêm `idempotencyKey`. |
| `exception/ValidationException.java` | Nhận `ValidationResult` vào constructor. |
| `exception/ReconciliationException.java` | Nghiêm trọng nhất — trigger alert. |

**GUIDE.md:** `hcr-core/GUIDE.md` ✅

---

### ✅ Module 02 — hcr-inventory (HOÀN THÀNH — Refactored v3)

**Mục đích:** Giải quyết oversell — 3 strategy với mức độ throughput và consistency khác nhau.
**Dependency:** `hcr-core`, `hcr-eventbus`.

**Refactor v2 (2026-04-05) — 2 thay đổi lớn:**

**V1 — Bỏ bảng `hcr_inventory`, thao tác trực tiếp trên bảng developer:**
- Xóa `InventoryRecord` entity + `InventoryRecordRepository`.
- Thêm `AbstractInventoryEntity` (`@MappedSuperclass`) — developer extend thành entity của mình.
- 3 strategy dùng `EntityManager.find(entityClass, resourceId)` thay vì repository.
- P1: `entityManager.find(..., PESSIMISTIC_WRITE)`. P2: find + merge + flush (version check).
- Developer thấy đúng `available` khi query bảng của mình — không còn 2 bảng lệch nhau.

**V2 — P3 async persist dùng EventBus + eventId deduplication:**
- P3 `reserve()` publish qua `EventBus` (Kafka/RabbitMQ, persistent) thay vì Spring `@EventListener` (fire-and-forget).
- Consumer idempotency qua `hcr_processed_events` table + eventId, KHÔNG phải `WHERE available >= delta`.
- Lý do: `WHERE available >= delta` chỉ tránh trừ âm, KHÔNG tránh trừ 2 lần (available=100, reserve 2, redeliver → 98 → 96).
- Known limitation: gap Redis DECR ↔ EventBus.publish() → Reconciliation fix ≤ 5 phút.

**V3 — P3 bottleneck optimization (2026-04-05) — 3 fix loại bỏ DB khỏi critical path:**

**V3-Fix1 — `getLowStockThreshold()` đọc từ Redis thay vì DB:**
- **Trước:** Mỗi `reserve()` gọi `entityManager.find()` để đọc `lowStockThreshold` → 10,000 SELECT/s vô nghĩa.
- **Sau:** Cache threshold vào Redis key `hcr:inventory:threshold:{resourceId}` khi `initialize()`. `getLowStockThreshold()` đọc từ Redis.
- **Impact:** Loại bỏ hoàn toàn DB khỏi critical path — đúng với nguyên tắc thiết kế P3.

**V3-Fix2 — `release()` giảm từ 2 round-trip xuống 1:**
- **Trước:** Java GET `totalKey` (round-trip 1) → Lua script (round-trip 2).
- **Sau:** Lua script nhận `KEYS[2]` = total key, tự đọc `redis.call('GET', KEYS[2])` bên trong.
- **Impact:** Mỗi release giảm 1 network round-trip (~0.1-0.5ms mỗi request).

**V3-Fix3 — `reserveBatch()` dùng Redis pipeline:**
- **Trước:** N items = N round-trip tuần tự (mỗi call `reserve()` riêng lẻ).
- **Sau:** `redisTemplate.executePipelined()` gộp N Lua script thành 1 batch, xử lý kết quả + publish events sau.
- **Impact:** Batch 100 items: 100 round-trips → 1 round-trip.

**V3-Fix4 — Batch DB sync consumer (configurable):**
- **Trước:** Mỗi event = 1 transaction (INSERT dedup + UPDATE available). 10,000 req/s = 10,000 tx/s.
- **Sau:** Thêm `BatchInventoryPersistenceConsumer` — gom events cùng resourceId rồi flush 1 lần.
  VD: 1,000 reserve cùng resourceId → 1 transaction (1 UPDATE + 1,000 INSERT dedup).
- **Chọn mode qua config:** `hcr.inventory.persistence.mode=single|batch`. Default: `single`.
- **Batch config:** `batch-size` (default 500), `flush-interval-ms` (default 1000ms).
- **Fallback:** Nếu batch INSERT fail do duplicate eventId → tự fallback xử lý từng event.
- **Impact:** Giảm ~90-99% số transaction khi tải cao. Trade-off: DB lag tăng thêm ≤ flushIntervalMs.

**Các file đã implement:**

| File | Ghi chú |
|------|---------|
| `strategy/InventoryStrategy.java` | Interface chính. 4 nhóm method: Core, Query, Management, Bulk. `reserve()` trả `ReservationResult`. |
| `entity/AbstractInventoryEntity.java` | **MỚI.** `@MappedSuperclass`. Fields: resourceId, available, total, version, lowStockThreshold, updatedAt. Developer extend + thêm field riêng. |
| `strategy/pessimistic/PessimisticLockStrategy.java` | **Refactored.** `entityManager.find(entityClass, id, PESSIMISTIC_WRITE)`. `reserveBatch()` sort keys alphabet → chống deadlock. |
| `strategy/optimistic/OptimisticLockStrategy.java` | **Refactored.** EntityManager + `flush()` trigger version check sớm. Retry loop + exponential backoff + jitter. |
| `strategy/redis/RedisAtomicStrategy.java` | **Refactored v3.** Lua script. `eventBus.publish()` cho DB sync. Spring event cho low stock/depleted notification. **V3:** `getLowStockThreshold()` đọc từ Redis (không DB). `release()` 1 round-trip. `reserveBatch()` dùng pipeline. |
| `lua/inventory_reserve.lua` | Atomic GET + DECRBY. 3 return codes. |
| `lua/inventory_release.lua` | **Updated v3.** INCRBY + guard không vượt totalQuantity. Nhận `KEYS[2]` = total key → tự đọc total bên trong Lua (giảm 1 round-trip). |
| `decorator/CircuitBreakerState.java` | CLOSED, OPEN, HALF_OPEN. |
| `decorator/CircuitBreakerInventoryDecorator.java` | Decorator Pattern. `release()` không reject khi OPEN (tránh inventory leak). |
| `metrics/InventoryMetrics.java` | Interface 8 methods + `NO_OP` inner class. |
| `factory/InventoryStrategyFactory.java` | **Refactored.** Nhận EntityManager + entityClass. P3 yêu cầu EventBus bean. |
| `initializer/InventoryInitializer.java` | **Refactored.** JPQL generic: `"SELECT e FROM " + entityClass.getSimpleName()`. |
| `persistence/InventoryPersistenceConsumer.java` | **Refactored.** EventBus consumer + eventId dedup (hcr_processed_events). Mode: SINGLE — 1 event = 1 transaction. |
| `persistence/BatchInventoryPersistenceConsumer.java` | **MỚI (v3).** Mode: BATCH — gom events theo resourceId, flush theo batchSize hoặc interval. Fallback sang single khi duplicate. |
| `persistence/PersistenceMode.java` | **MỚI (v3).** Enum: SINGLE / BATCH. |
| `persistence/PersistenceConfig.java` | **MỚI (v3).** Config: mode, batchSize (default 500), flushIntervalMs (default 1000ms). |
| `persistence/ProcessedEvent.java` | **MỚI.** Entity cho bảng `hcr_processed_events`. Lưu eventId + eventType + processedAt. |
| `persistence/ProcessedEventRepository.java` | **MỚI.** JPA repository cho ProcessedEvent. |
| `event/Resource*Event.java` | 5 events: Reserved, Released, Depleted, LowStock, Restocked. Extend `DomainEvent`. Reserved/Released publish qua EventBus (P3), còn lại Spring internal. |

**Đã xóa:**
- ~~`entity/InventoryRecord.java`~~ — thay bằng `AbstractInventoryEntity`.
- ~~`repository/InventoryRecordRepository.java`~~ — thay bằng `EntityManager`.

**GUIDE.md:** `hcr-inventory/GUIDE.md` ✅ (updated)

---

### ✅ Module 05 — hcr-eventbus (HOÀN THÀNH)

**Mục đích:** Abstraction layer cho messaging. At-least-once delivery. 4 adapter.
**Dependency:** `hcr-core`.

**Các file đã implement:**

| File | Ghi chú |
|------|---------|
| `EventBus.java` | Interface 7 methods: publish, publishIdempotent, publishBatch, subscribe, unsubscribe, getCapabilities. |
| `EventHandler.java` | Generic `handle(event, ack)`. `onDeadLetter()` default no-op. PHẢI idempotent. |
| `Acknowledgment.java` | `acknowledge()`, `reject()`, `reject(boolean)`. |
| `EventBusCapabilities.java` | 7 capability flags. 4 static factories: `kafka()`, `rabbitMQ()`, `redisStream()`, `inMemory()`. |
| `EventDestination.java` | `of(name)`, `forEventType(class)` → CamelCase → kebab-case. |
| `adapter/AbstractEventBusAdapter.java` | Handler registry, `dispatch()`, `publishBatch()` default, `warnIfNotSupported()`. |
| `adapter/inmemory/InMemoryEventBusAdapter.java` | Synchronous. Testing utils: `getPublishedEvents()`, `clearEvents()`, `getPublishedCount()`. |
| `adapter/kafka/KafkaEventBusAdapter.java` | `resourceId` làm partition key. Idempotent producer (không cần Redis check). |
| `adapter/rabbitmq/RabbitMQEventBusAdapter.java` | Redis SETNX cho idempotent publish. `basicAck` / `basicNack`. |
| `adapter/redis/RedisStreamEventBusAdapter.java` | XADD/XACK/PEL. `reject(false)` → `.dlq` stream. |
| `event/order/Order*.java` | 4 events: Created, Confirmed, Cancelled (có FailureReason + quantity), Expired (có expiredAt). |
| `event/payment/Payment*.java` | 4 events: Succeeded (amount, currency), Failed, Timeout (waitedFor), Unknown (rawResponse). |
| `event/reconciliation/*.java` | 3 events: Started, Fixed, InventoryMismatch (redisAvailable, dbAvailable, delta). |

**Lưu ý thiết kế quan trọng:**
- Inventory events (ResourceReservedEvent...) là **Spring internal events** → nằm trong `hcr-inventory`, KHÔNG phải `hcr-eventbus`.
- EventBus events là **external broker events** (Kafka/RabbitMQ/Redis/InMemory).

**GUIDE.md:** `hcr-eventbus/GUIDE.md` ✅

---

## Kế hoạch các module còn lại

### Thứ tự implement (theo dependency)

```
hcr-core ──► hcr-inventory ──► (done)
hcr-core ──► hcr-eventbus  ──► (done)
hcr-core ──► hcr-payment   ──► (done)
                    │
                    ▼
             hcr-saga (cần core + inventory + payment + eventbus)
                    │
             ┌──────┴──────┐
             ▼             ▼
        hcr-gateway   hcr-reconciliation
             │
             ▼
        hcr-observability
             │
             ▼
        hcr-testing ──► hcr-autoconfigure ──► hcr-spring-boot-starter ──► hcr-sample
```

---

### ✅ Module 04 — hcr-payment (HOÀN THÀNH)

**Mục đích:** Abstract hóa payment gateway. Xử lý timeout (T/H A: gateway crash) và lost response (T/H B: charge OK nhưng response mất).
**Dependency:** `hcr-core`.

**Các file đã implement:**

| File | Ghi chú |
|------|---------|
| `model/PaymentStatus.java` | Enum 4 trạng thái: SUCCESS, FAILED, TIMEOUT, UNKNOWN. `isResolved()` cho TimeoutHandler. |
| `model/HealthStatus.java` | Enum 3 trạng thái: UP, DEGRADED, DOWN. Dùng trong GatewayHealth. |
| `model/PaymentRequest.java` | Input cho `charge()`. `transactionId` = idempotency key. Builder pattern. |
| `model/PaymentResult.java` | Result Object pattern. Factory: `success()`, `failed()`, `timeout()`, `unknown()`. |
| `model/RefundRequest.java` | Input cho `refund()`. Cần `gatewayTransactionId` từ PaymentResult gốc. |
| `model/RefundResult.java` | Result Object cho refund. 4 status: SUCCESS, FAILED, PENDING, UNKNOWN. |
| `model/AuthorizationResult.java` | Kết quả pre-authorize. 3 status: AUTHORIZED, DECLINED, UNKNOWN. Có `expiresAt`. |
| `model/GatewayHealth.java` | Health snapshot: status, successRate, latency, connections. Static factories. |
| `gateway/PaymentGateway.java` | Interface chính. 3 nhóm: Core (charge, query, refund), Pre-Auth, Health. |
| `gateway/AbstractPaymentGateway.java` | Template Method. `charge()` final: timeout detection → retry → logging. `refund()` KHÔNG retry. |
| `handler/TimeoutHandler.java` | Polling `queryStatus()`. 5s interval × 6 attempts = 30s. Sync `handle()` + async `handleAsync()`. |
| `gateway/mock/MockPaymentGateway.java` | Testing gateway. `transactionLog` lưu kết quả → queryStatus trả đúng. Config: successRate, timeoutRate, noResponseRate, lateSuccessRate. |

**GUIDE.md:** `hcr-payment/GUIDE.md` ✅

**Lưu ý thiết kế quan trọng:**
- `charge()` là `final` trong AbstractPaymentGateway → pipeline timeout/retry luôn chạy đúng.
- `refund()` KHÔNG retry → double refund nguy hiểm hơn refund failed.
- MockPaymentGateway: T/H A không lưu log (queryStatus → UNKNOWN), T/H B lưu SUCCESS rồi throw timeout (queryStatus → SUCCESS).

**Fix phụ:** Thêm `hcr-spring-boot-starter` vào `dependencyManagement` trong parent pom.xml (thiếu version gây build error).

---

### 🔲 Module 03 — hcr-saga (TIẾP THEO)

**Dependency:** `hcr-core`, `hcr-inventory`, `hcr-payment`, `hcr-eventbus` (tất cả đã hoàn thành).

**Thành phần cần implement:**
- `SagaOrchestrator` — interface chung
- `SyncSagaOrchestrator` — xử lý trong 1 request thread
- `AsyncSagaOrchestrator` — publish event → consumer xử lý
- `SagaStateRepository` — bắt buộc khi dùng async mode
- Steps: `ReserveStep`, `PaymentStep`, `ConfirmStep`, `CompensationStep`
- `SagaContext` — chứa state xuyên suốt 1 saga execution
- `GUIDE.md`

---

### 🔲 Module 06 — hcr-gateway

**Dependency:** `hcr-core`, `hcr-saga`.

**Thành phần cần implement:**
- `ResourceOrderController` — entry point HTTP
- `IdempotencyHandler` — check duplicate request
- `RateLimiter` + `RedisTokenBucketRateLimiter`
- `CorrelationIdFilter` — inject correlationId vào MDC
- `GUIDE.md`

---

### 🔲 Module 07 — hcr-reconciliation

**Dependency:** `hcr-core`, `hcr-inventory`, `hcr-eventbus`.

**Thành phần cần implement:**
- `ReconciliationService` — scheduler chạy định kỳ
- `InventoryReconciler` — so sánh Redis vs DB (P3 only), tự fix mismatch
- `ExpiredOrderReconciler` — tìm order quá `expiresAt`, cancel + release
- `GUIDE.md`

---

### 🔲 Module 08 — hcr-observability

**Dependency:** `hcr-core` (+ optional Micrometer).

**Thành phần cần implement:**
- `FrameworkMetrics` — interface tổng hợp metrics
- `MicrometerFrameworkMetrics` — implementation dùng Micrometer
- `NoOpFrameworkMetrics` — default khi không có Micrometer
- `GUIDE.md`

---

### 🔲 Module 09 — hcr-testing

**Dependency:** hầu hết các module.

**Thành phần cần implement:**
- `HcrTestBuilder` — fluent builder cho test setup
- `AbstractIntegrationTest` — base class với InMemory infrastructure sẵn
- `FrameworkTestSupport` — helper methods
- `GUIDE.md`

---

### 🔲 Module 10 — hcr-autoconfigure

**Dependency:** tất cả module trên.

**Thành phần cần implement:**
- `HcrProperties` — map toàn bộ `hcr.*` config từ YAML
- `HcrAutoConfiguration` — tạo tất cả beans tự động
- `HcrActuatorEndpoint` — expose `/actuator/hcr` để debug
- Fail-fast validation khi startup thiếu config
- `GUIDE.md`

---

### 🔲 Module 11 — hcr-spring-boot-starter

**Dependency:** `hcr-autoconfigure`.

**Thành phần:** Chỉ là wrapper POM + `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

---

### 🔲 Module 12 — hcr-sample

**Dependency:** `hcr-spring-boot-starter`.

**Thành phần:** Demo app concert ticket booking — minh họa cách dùng framework từ góc độ developer.

---

## Quyết định thiết kế đã thống nhất (không thay đổi)

| Quyết định | Lý do |
|-----------|-------|
| `TransactionTemplate` thay vì `@Transactional` trong strategies | Strategies được tạo bằng `new` trong Factory (không phải Spring bean) → Spring không tạo AOP proxy → `@Transactional` bị bỏ qua hoàn toàn dù không báo lỗi. `TransactionTemplate` được inject vào constructor → gọi `.execute()` trực tiếp trong code → transaction hoạt động đúng. |
| P2 tạo transaction mới mỗi retry | Nếu dùng lại transaction cũ, Hibernate cache version cũ → fail mãi |
| ~~Inventory events là Spring internal events~~ | ~~Tránh circular dependency~~ **THAY ĐỔI v2:** P3 DB sync dùng EventBus (persistent), notification events (low stock/depleted) vẫn Spring internal. Dependency hcr-inventory → hcr-eventbus là one-way, không circular. |
| `release()` CB không reject khi OPEN | Reject release → inventory leak → Reconciliation phải fix |
| `reserveBatch()` sort keys alphabet | Tất cả thread lock theo cùng thứ tự → không deadlock |
| Lua script cho P3 | Redis đảm bảo atomic GET + DECRBY → zero oversell |
| **Bỏ bảng `hcr_inventory` (v2)** | Framework thao tác trực tiếp trên bảng developer qua EntityManager. Developer extend `AbstractInventoryEntity` (@MappedSuperclass). 1 bảng = 1 source of truth, không bao giờ lệch. |
| **EventBus cho P3 DB sync (v2)** | Spring `@EventListener` là in-memory fire-and-forget — crash trước khi consumer xử lý → event mất vĩnh viễn. EventBus (Kafka/RabbitMQ) persistent message, auto-redeliver. |
| **eventId dedup thay vì WHERE available >= delta (v2)** | `WHERE available >= delta` chỉ tránh trừ âm, KHÔNG tránh trừ 2 lần. Idempotency thật sự cần check eventId qua bảng `hcr_processed_events`. |
| **Gap Redis DECR ↔ EventBus.publish() (v2)** | Known limitation — nếu crash ở giữa, Reconciliation fix ≤ 5 phút. Chấp nhận trong scope này. |
| **P3 lowStockThreshold cache trong Redis (v3)** | `getLowStockThreshold()` trước đây query DB mỗi request → phá vỡ nguyên tắc "DB không nằm trong critical path". Giờ cache vào `hcr:inventory:threshold:{resourceId}` khi `initialize()`. |
| **P3 release Lua script tự đọc total (v3)** | Trước: Java GET total riêng (round-trip 1) rồi truyền vào Lua (round-trip 2). Giờ Lua nhận `KEYS[2]` = total key, tự `redis.call('GET', KEYS[2])`. Giảm 1 round-trip. |
| **P3 reserveBatch dùng pipeline (v3)** | Trước: N items = N round-trip tuần tự. Giờ `executePipelined()` gộp thành 1 batch. |
| **P3 batch DB sync configurable (v3)** | SINGLE mode giữ nguyên cho backward-compat + low-traffic use case. BATCH mode cho high-traffic: gom events theo resourceId, flush theo batchSize hoặc interval. Fallback sang single khi duplicate. ACK ngay khi buffer (không chờ flush) — chấp nh���n data loss nếu crash giữa ACK và flush, Reconciliation sẽ fix. |
