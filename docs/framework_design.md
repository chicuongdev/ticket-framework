# HCR Framework — High Concurrency Resource Distribution Framework

> **Mục đích:** Tài liệu thiết kế chi tiết toàn bộ framework  
> **Phiên bản:** 0.1.0-SNAPSHOT  
> **Cập nhật:** 04/2026 — Revision 2 (cập nhật theo Critical Review)  
> **Tác giả:** SOICT — HUST

> **Thay đổi trong Revision 2:**
> - Thêm **Consistency Guarantees** (section 1.5) — định nghĩa rõ
>   consistency window cho P1/P2/P3
> - Thêm `correlationId` vào `DomainEvent` + `CorrelationIdFilter`
>   (Module 01, Module 10) — distributed tracing cơ bản
> - Thêm `EventBusCapabilities` (Module 05) — document sự khác biệt
>   4 adapter, cảnh báo capability mismatch
> - Strengthen `SagaStateRepository` (Module 03) — bắt buộc với async
>   mode, fail fast khi startup thiếu bean
> - Thêm `HcrActuatorEndpoint` (Module 10) — expose `/actuator/hcr`
>   để debug config đang active

---

## Mục lục

1. [Tổng quan](#1-tổng-quan)
   - 1.1 Bài toán framework giải quyết
   - 1.2 Luồng xử lý tổng quát
   - 1.3 Kiến trúc 3 layer
   - 1.4 Benchmark plan
   - 1.5 Consistency Guarantees *(Revision 2)*
2. [Module 01 — Core Domain](#2-module-01--core-domain)
3. [Module 02 — Inventory](#3-module-02--inventory)
4. [Module 03 — Saga Orchestration](#4-module-03--saga-orchestration)
5. [Module 04 — Payment](#5-module-04--payment)
6. [Module 05 — Event Bus](#6-module-05--event-bus)
7. [Module 06 — Gateway](#7-module-06--gateway)
8. [Module 07 — Reconciliation](#8-module-07--reconciliation)
9. [Module 08 — Observability](#9-module-08--observability)
10. [Module 09 — Testing Support](#10-module-09--testing-support)
11. [Module 10 — Auto Configuration](#11-module-10--auto-configuration)
12. [Mối quan hệ giữa các module](#12-mối-quan-hệ-giữa-các-module)
13. [Package Structure](#13-package-structure)

---

## 1. Tổng quan

### 1.1 Bài toán framework giải quyết

HCR Framework giải quyết bài toán:

> **"Phân phối tài nguyên có giới hạn cho nhiều người dùng đồng thời,
> đảm bảo không vượt quá tồn kho, dưới điều kiện tải cao"**

Các bài toán cùng cấu trúc mà framework có thể áp dụng:

| Bài toán | Tài nguyên | Giới hạn |
|----------|------------|----------|
| Phân phối vé concert | Số vé còn lại | Không oversell |
| Flash sale sản phẩm | Số sản phẩm | Không bán quá số lượng |
| Đặt phòng khách sạn | Số phòng trống | Không double booking |
| Đặt slot khám bệnh | Số slot bác sĩ | Không overbook |

### 1.2 Luồng xử lý tổng quát

Mọi bài toán đều có chung 4 bước cốt lõi:

```
1. RESERVE     → Giữ chỗ tạm thời (atomic, không oversell)
2. PROCESS     → Xử lý nghiệp vụ (thanh toán, xác nhận...)
3. CONFIRM     → Xác nhận chính thức / Rollback nếu fail
4. RECONCILE   → Dọn dẹp các case treo, đảm bảo consistency
```

### 1.3 Kiến trúc 3 layer

```
┌──────────────────────────────────────────────────────────────┐
│                      APPLICATION LAYER                        │
│           (developer implement — use case cụ thể)             │
│    ConcertTicketService  │  FlashSaleService  │  HotelService │
├──────────────────────────────────────────────────────────────┤
│                       FRAMEWORK LAYER                         │
│                   (HCR Framework — reusable)                  │
│                                                               │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌────────────────┐  │
│  │  Core    │ │Inventory │ │  Saga    │ │    Payment     │  │
│  │  Domain  │ │ Strategy │ │Orchestra-│ │    Gateway     │  │
│  │          │ │          │ │  tion    │ │                │  │
│  └──────────┘ └──────────┘ └──────────┘ └────────────────┘  │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌────────────────┐  │
│  │ Event    │ │ Gateway  │ │Reconcili-│ │Observability   │  │
│  │   Bus    │ │          │ │  ation   │ │                │  │
│  └──────────┘ └──────────┘ └──────────┘ └────────────────┘  │
│  ┌──────────┐ ┌──────────────────────────────────────────┐   │
│  │ Testing  │ │           Auto Configuration              │   │
│  │ Support  │ │                                           │   │
│  └──────────┘ └──────────────────────────────────────────┘   │
├──────────────────────────────────────────────────────────────┤
│                   INFRASTRUCTURE LAYER                        │
│              (pluggable — developer tự config)                │
│   PostgreSQL │ Redis │ Kafka │ RabbitMQ │ Redis Streams...    │
└──────────────────────────────────────────────────────────────┘
```

### 1.4 Benchmark plan

Framework sẽ được kiểm chứng qua 2 loại so sánh:

**So sánh 1 — Có framework vs Không có framework:**
- Cùng bài toán: Concert Ticket
- Đo: throughput, latency, correctness, developer effort (số dòng code)

**So sánh 2 — Reusability across use cases:**
- Use case 1: Concert Ticket (dùng framework)
- Use case 2: Flash Sale (dùng framework)
- Đo: thời gian implement, throughput, zero-oversell guarantee

---

### 1.5 Consistency Guarantees *(Revision 2)*

> **Đây là hợp đồng chính thức giữa framework và developer.**
> Đọc section này trước khi chọn strategy để biết mình đang chấp
> nhận mức độ consistency nào.

**Định nghĩa:**
- **Consistency Window:** Khoảng thời gian tối đa hệ thống có thể
  ở trạng thái không nhất quán sau một thao tác.
- **Source of Truth:** Nơi lưu trữ được coi là đúng tuyệt đối khi
  có conflict giữa các storage.
- **Reconciliation Guarantee:** Cam kết thời gian tối đa để
  Reconciliation phát hiện và sửa inconsistency.

**Consistency model theo strategy:**

| | P1 — Pessimistic | P2 — Optimistic | P3 — Redis Atomic |
|--|:-:|:-:|:-:|
| Mức consistency | **Strong** | **Strong** | **Eventual** |
| Consistency window | **0ms** | **0ms** | **< 1s** (normal) / **≤ 5 phút** (worst case) |
| Source of truth | PostgreSQL | PostgreSQL | **Redis** |
| Read stale data? | Không | Không | **Có** (DB có thể lag) |
| Reconciliation bắt buộc? | Không | Không | **Có** |
| Redis AOF bắt buộc? | Không | Không | **Có** |

**Consistency model cho Order State:**

| Saga Mode | Khi nào client biết kết quả? | Consistency |
|-----------|------------------------------|-------------|
| Synchronous (P1/P2) | Ngay trong HTTP response | Strong |
| Asynchronous (P3) | Sau poll hoặc webhook | Eventual ≤ vài giây |

**Consistency model cho Payment (mọi strategy):**

```
Bình thường:   charge() → kết quả ngay trong Saga flow
Timeout:       TimeoutHandler polling ≤ 30 giây
Worst case:    Reconciliation giải quyết trong ≤ 5 phút
```

**Hướng dẫn chọn:**
```
Cần Strong Consistency tuyệt đối?
  → Chọn P1 hoặc P2

Chấp nhận Eventual Consistency, ưu tiên throughput?
  → Chọn P3, BẮT BUỘC phải:
     ✓ Có Reconciliation chạy định kỳ
     ✓ Bật Redis AOF (appendfsync everysec)
     ✓ Đọc inventory realtime từ Redis (không từ DB)
```

---

## 2. Module 01 — Core Domain

### Vai trò

Định nghĩa "ngôn ngữ chung" của toàn bộ framework. Mọi module khác
đều xây dựng trên nền này. Đây là nền móng đảm bảo tính reusable —
nếu không có domain model chuẩn, các module không thể giao tiếp được
với nhau.

### Package

```
io.hcr.core.domain
```

### Classes & Interfaces

---

#### `AbstractResource` *(abstract class)*

Đại diện cho "tài nguyên có giới hạn" trong hệ thống.
Developer extend để thêm field riêng theo nghiệp vụ.

```java
public abstract class AbstractResource {

    // === FIELDS BẮT BUỘC (framework quản lý) ===
    private String resourceId;
    private long totalQuantity;
    private long availableQuantity;
    private ResourceStatus status;
    private Instant createdAt;
    private Instant updatedAt;

    // === METHODS ===
    public String getResourceId()
    public long getTotalQuantity()
    public long getAvailableQuantity()
    public ResourceStatus getStatus()
    public boolean isAvailable()
    public boolean isLowStock(long threshold)
    public boolean isDepleted()

    // Developer override nếu cần custom validation
    protected void validate()
}
```

---

#### `AbstractOrder` *(abstract class)*

Đại diện cho "yêu cầu đặt tài nguyên" — một đơn hàng trong hệ thống.

```java
public abstract class AbstractOrder {

    // === FIELDS BẮT BUỘC (framework quản lý) ===
    private String orderId;
    private String resourceId;
    private String requesterId;
    private int quantity;
    private OrderStatus status;
    private String idempotencyKey;
    private String failureReason;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant expiresAt;

    // === METHODS ===
    public String getOrderId()
    public String getResourceId()
    public String getRequesterId()
    public int getQuantity()
    public OrderStatus getStatus()
    public String getIdempotencyKey()
    public boolean isPending()
    public boolean isExpired()
    public boolean isTerminal()     // CONFIRMED hoặc CANCELLED
}
```

---

#### `OrderRequest` *(abstract class)*

Input vào Saga — request chưa được xử lý. Developer subclass để
thêm field nghiệp vụ.

```java
public abstract class OrderRequest {

    // === FIELDS BẮT BUỘC ===
    private String resourceId;
    private String requesterId;
    private int quantity;
    private String idempotencyKey;

    // === METHODS ===
    public String getResourceId()
    public String getRequesterId()
    public int getQuantity()
    public String getIdempotencyKey()

    // Developer override để validate input
    protected ValidationResult validateRequest()
}
```

---

#### `OrderStatus` *(enum)*

Chuẩn hóa toàn bộ lifecycle của 1 order trong framework.

```java
public enum OrderStatus {
    PENDING,        // vừa tạo, chưa xử lý
    RESERVED,       // đã giữ chỗ, chờ thanh toán
    CONFIRMED,      // hoàn thành — terminal state
    CANCELLED,      // đã hủy — terminal state
    EXPIRED,        // hết thời gian giữ chỗ — terminal state
    COMPENSATING    // đang rollback
}
```

---

#### `ResourceStatus` *(enum)*

Trạng thái của tài nguyên trong hệ thống.

```java
public enum ResourceStatus {
    ACTIVE,         // đang bán
    LOW_STOCK,      // sắp hết (dưới ngưỡng threshold)
    DEPLETED,       // hết hàng
    DEACTIVATED     // ngừng bán
}
```

---

#### `FailureReason` *(enum)*

Lý do fail chuẩn hóa — dùng trong order.failureReason và event.

```java
public enum FailureReason {
    INSUFFICIENT_INVENTORY,     // hết hàng
    PAYMENT_FAILED,             // thanh toán thất bại
    PAYMENT_TIMEOUT,            // thanh toán không phản hồi
    PAYMENT_UNKNOWN,            // không rõ kết quả thanh toán
    DUPLICATE_REQUEST,          // idempotency key đã tồn tại
    VALIDATION_FAILED,          // input không hợp lệ
    RESERVATION_EXPIRED,        // giữ chỗ hết hạn
    SYSTEM_ERROR                // lỗi hệ thống
}
```

---

#### `ReservationResult` *(class)*

Kết quả của thao tác reserve inventory.

```java
public class ReservationResult {

    private ReservationStatus status;   // SUCCESS / INSUFFICIENT / ERROR
    private String resourceId;
    private int requestedQuantity;
    private long remainingAfter;        // tồn kho sau khi reserve
    private Instant reservedAt;
    private String errorMessage;

    // Factory methods
    public static ReservationResult success(String resourceId, int qty, long remaining)
    public static ReservationResult insufficient(String resourceId, int qty)
    public static ReservationResult error(String resourceId, String message)

    public boolean isSuccess()
    public boolean isInsufficient()
    public boolean isError()
}
```

---

#### `DomainEvent` *(abstract class)*

Base class cho tất cả event trong hệ thống. Framework publish,
developer subscribe để xử lý.

```java
public abstract class DomainEvent {

    // === FIELDS BẮT BUỘC ===
    private String eventId;             // UUID, unique
    private String eventType;           // tên class của event
    private String resourceId;
    private String orderId;
    private Instant occurredAt;
    private int retryCount;             // số lần đã retry
    private String correlationId;       // [Revision 2] trace xuyên suốt request

    // === METHODS ===
    public String getEventId()
    public String getEventType()
    public String getResourceId()
    public String getOrderId()
    public Instant getOccurredAt()
    public int getRetryCount()
    public String getCorrelationId()    // [Revision 2]
    public DomainEvent incrementRetry()
}
```

> **correlationId — Revision 2:**
> Framework tự sinh và propagate correlationId từ HTTP request header
> `X-Correlation-ID` (hoặc tự sinh UUID nếu client không cung cấp).
> Mọi event từ cùng 1 request đều mang cùng correlationId — cho phép
> trace toàn bộ luồng xử lý qua log với `grep "correlationId=xxx"`.
> correlationId cũng được forward vào HTTP header khi gọi
> PaymentGateway.

---

#### `InventorySnapshot` *(class)*

Trạng thái inventory tại một thời điểm cụ thể. Dùng cho
monitoring và reconciliation.

```java
public class InventorySnapshot {

    private String resourceId;
    private long totalQuantity;
    private long availableQuantity;
    private long reservedQuantity;
    private long confirmedQuantity;
    private Instant snapshotAt;
    private String source;      // "redis" hoặc "database"

    // Methods
    public long getReservedQuantity()
    public long getConfirmedQuantity()
    public boolean isConsistentWith(InventorySnapshot other)
    public long getDelta(InventorySnapshot other)
}
```

---

#### `ValidationResult` *(class)*

Kết quả validation — chứa danh sách lỗi nếu có.

```java
public class ValidationResult {

    private boolean valid;
    private List<ValidationError> errors;

    // Factory methods
    public static ValidationResult ok()
    public static ValidationResult fail(String field, String message)

    // Methods
    public boolean isValid()
    public List<ValidationError> getErrors()
    public ValidationResult merge(ValidationResult other)
    public void throwIfInvalid()     // throw ValidationException nếu !valid
}
```

---

#### `FrameworkException` *(class)*

Base exception của framework. Developer có thể catch để xử lý.

```java
public class FrameworkException extends RuntimeException {
    private FailureReason reason;
    private String resourceId;
    private String orderId;

    // Subclasses
    // InsufficientInventoryException
    // PaymentException
    // IdempotencyException
    // ReconciliationException
}
```

---

## 3. Module 02 — Inventory

### Vai trò

Giải quyết bài toán cốt lõi — làm thế nào để nhiều người cùng lúc
đặt tài nguyên mà không bị oversell. Đây là trái tim của framework.
Đóng gói sẵn 3 strategy đã được kiểm chứng, developer chỉ cần chọn
qua config.

### Package

```
io.hcr.inventory
io.hcr.inventory.strategy
io.hcr.inventory.decorator
```

### Classes & Interfaces

---

#### `InventoryStrategy` *(interface — trung tâm module)*

Contract chuẩn mà tất cả strategy phải tuân theo. Mọi module khác
chỉ giao tiếp qua interface này.

```java
public interface InventoryStrategy {

    // === CORE OPERATIONS ===
    ReservationResult reserve(String resourceId, String requestId, int quantity)
    void release(String resourceId, String requestId, int quantity)

    // === QUERY ===
    long getAvailable(String resourceId)
    boolean isAvailable(String resourceId)
    boolean isAvailable(String resourceId, int quantity)
    InventorySnapshot getSnapshot(String resourceId)

    // === MANAGEMENT ===
    void initialize(String resourceId, long totalQuantity)
    void restock(String resourceId, long quantity)
    void deactivate(String resourceId)

    // === BULK OPERATIONS ===
    Map<String, ReservationResult> reserveBatch(Map<String, Integer> requests)
    void releaseBatch(Map<String, Integer> requests)

    // === MONITORING ===
    boolean isLowStock(String resourceId, long threshold)
    InventoryMetrics getMetrics(String resourceId)

    // Strategy identifier — dùng trong metrics và logging
    String getStrategyName()
}
```

---

#### `PessimisticLockStrategy` *(class — implements InventoryStrategy)*

SELECT FOR UPDATE trên PostgreSQL. Đảm bảo strong consistency.
Phù hợp với tải thấp đến trung bình.

```java
public class PessimisticLockStrategy implements InventoryStrategy {

    // Dependencies (inject qua constructor)
    private final DataSource dataSource;
    private final InventoryMetrics metrics;

    // Implement tất cả method của InventoryStrategy
    // Core: SELECT * FROM inventory WHERE resource_id=? FOR UPDATE
    //       → UPDATE inventory SET available = available - quantity
    //       → Rollback nếu available < quantity

    @Override
    public String getStrategyName() { return "pessimistic-lock"; }
}
```

---

#### `OptimisticLockStrategy` *(class — implements InventoryStrategy)*

Version field + retry với exponential backoff + jitter.
Phù hợp với tải trung bình, conflict rate thấp.

```java
public class OptimisticLockStrategy implements InventoryStrategy {

    private final DataSource dataSource;
    private final InventoryMetrics metrics;
    private final int maxRetries;           // default: 3
    private final long baseDelayMs;         // default: 100ms
    private final long maxDelayMs;          // default: 1000ms

    // Core: SELECT * FROM inventory WHERE resource_id=? (version=N)
    //       → UPDATE inventory SET available=available-qty, version=N+1
    //            WHERE resource_id=? AND version=N AND available>=qty
    //       → Nếu updated=0 → retry với exponential backoff + jitter

    @Override
    public String getStrategyName() { return "optimistic-lock"; }
}
```

---

#### `RedisAtomicStrategy` *(class — implements InventoryStrategy)*

Lua script DECR atomic trên Redis + async persistence qua Event Bus.
Loại bỏ DB khỏi critical path. Phù hợp với tải rất cao.

> **Revision 3 — Performance optimization (2026-04-05):**
> - `getLowStockThreshold()` đọc từ Redis key `hcr:inventory:threshold:{resourceId}`
>   thay vì `entityManager.find()` — loại bỏ hoàn toàn DB khỏi critical path.
> - `release()` giảm từ 2 round-trip xuống 1 — Lua script tự đọc `total` từ `KEYS[2]`.
> - `reserveBatch()` dùng `executePipelined()` — N items trong 1 round-trip thay vì N.

```java
public class RedisAtomicStrategy implements InventoryStrategy {

    private final StringRedisTemplate redisTemplate;
    private final EventBus eventBus;
    private final InventoryMetrics metrics;

    // Redis key layout:
    //   hcr:inventory:{resourceId}           — available quantity (source of truth)
    //   hcr:inventory:total:{resourceId}     — total quantity
    //   hcr:inventory:threshold:{resourceId} — lowStockThreshold (cached from DB)

    // reserve() critical path (zero DB hit):
    //   1. Lua script: GET + check + DECRBY (atomic, <1ms)
    //   2. eventBus.publish(ResourceReservedEvent) — persistent, async DB sync
    //   3. getLowStockThreshold() → Redis GET (NOT DB query)
    //   4. Spring event cho low stock / depleted notification

    // release() — 1 round-trip:
    //   Lua script nhận KEYS[1]=inventory key, KEYS[2]=total key
    //   → tự đọc total bên trong, không cần Java GET riêng

    // reserveBatch() — 1 round-trip cho N items:
    //   executePipelined() gộp N Lua script thành 1 batch

    @Override
    public String getStrategyName() { return "redis-atomic"; }
}
```

---

#### `CircuitBreakerInventoryDecorator` *(class — decorator)*

Wrap bất kỳ InventoryStrategy nào. Tự động mở circuit khi error
rate vượt ngưỡng. Developer không cần tự implement Circuit Breaker.

```java
public class CircuitBreakerInventoryDecorator implements InventoryStrategy {

    private final InventoryStrategy delegate;   // strategy được wrap
    private final CircuitBreaker circuitBreaker;
    private final InventoryMetrics metrics;

    // Mọi call đến delegate đều đi qua circuit breaker
    // Khi OPEN: trả về ReservationResult.error() ngay lập tức
    // Khi HALF_OPEN: cho phép 1 số request thử lại

    @Override
    public String getStrategyName() {
        return "circuit-breaker(" + delegate.getStrategyName() + ")";
    }
}
```

---

#### `InventoryStrategyFactory` *(class)*

Tạo đúng InventoryStrategy bean dựa trên config. Developer không
cần tự new object.

```java
public class InventoryStrategyFactory {

    // Tạo strategy theo config
    public InventoryStrategy create(HcrInventoryProperties properties)

    // Tạo strategy có Circuit Breaker wrap sẵn
    public InventoryStrategy createWithCircuitBreaker(
        HcrInventoryProperties properties,
        CircuitBreakerConfig cbConfig
    )

    // Register custom strategy (developer tự implement)
    public void registerCustomStrategy(String name, InventoryStrategy strategy)
}
```

---

#### `PersistenceConfig` + `PersistenceMode` *(class + enum — Revision 3)*

Cấu hình DB sync consumer cho P3. Chọn SINGLE hoặc BATCH mode.

```yaml
hcr:
  inventory:
    persistence:
      mode: batch              # single (default) | batch
      batch-size: 500          # flush khi buffer đạt N events
      flush-interval-ms: 1000  # flush theo interval dù chưa đủ batch-size
```

```java
public enum PersistenceMode { SINGLE, BATCH }

public class PersistenceConfig {
    private PersistenceMode mode;      // default: SINGLE
    private int batchSize;             // default: 500
    private long flushIntervalMs;      // default: 1000
}
```

---

#### `BatchInventoryPersistenceConsumer` *(class — Revision 3)*

Batch alternative cho `InventoryPersistenceConsumer`. Gom events cùng
resourceId rồi flush 1 transaction thay vì N transactions.

```java
public class BatchInventoryPersistenceConsumer {

    // EventHandler factories — tương tự InventoryPersistenceConsumer
    public EventHandler<ResourceReservedEvent> reservedHandler()
    public EventHandler<ResourceReleasedEvent> releasedHandler()

    // Flush triggers:
    //   1. Buffer đạt batchSize → flush resourceId đó
    //   2. Scheduler mỗi flushIntervalMs → flush ALL

    // 1 batch = 1 transaction:
    //   INSERT N rows hcr_processed_events (dedup)
    //   UPDATE available = available + totalDelta WHERE resource_id = ?

    // Nếu batch INSERT fail (duplicate eventId):
    //   → fallback từng event → skip duplicate, xử lý event mới

    // Graceful shutdown: flush remaining buffer
    public void shutdown()

    // Monitoring
    public int getPendingCount()
    public int getPendingCount(String resourceId)
}
```

**So sánh SINGLE vs BATCH:**

| | SINGLE | BATCH |
|--|--------|-------|
| Transactions/s | = số events/s | ÷ batchSize (vd: 10,000 → ~20) |
| DB lag thêm | 0 | ≤ flushIntervalMs |
| Complexity | Đơn giản | Buffer + scheduler + fallback |
| Khi nào dùng | Tải ≤ 5,000 req/s | Tải > 5,000 req/s |

---

#### `InventoryInitializer` *(class)*

Load initial stock từ DB lên Redis khi startup (cho RedisAtomicStrategy).
Đảm bảo consistency giữa DB và Redis.

```java
public class InventoryInitializer {

    // Chạy khi application start
    public void initialize(List<String> resourceIds)

    // Load 1 resource cụ thể
    public void initialize(String resourceId, long availableQuantity)

    // Reload tất cả — dùng sau khi reconciliation fix mismatch
    public void reloadAll()

    // Verify Redis đồng bộ với DB
    public boolean verify(String resourceId)
}
```

---

#### `InventoryMetrics` *(interface)*

Metrics collector cho inventory operations. Được inject vào tất cả
strategy để tự động track.

```java
public interface InventoryMetrics {

    void recordReserveAttempt(String resourceId, String strategy)
    void recordReserveSuccess(String resourceId, long durationMs)
    void recordReserveFailure(String resourceId, FailureReason reason)
    void recordReleaseSuccess(String resourceId)
    void recordOversellPrevented(String resourceId)
    void recordLowStock(String resourceId, long remaining)
    void recordDepleted(String resourceId)
    void updateAvailableGauge(String resourceId, long available)
}
```

---

## 4. Module 03 — Saga Orchestration

### Vai trò

Điều phối toàn bộ luồng xử lý một order — từ lúc nhận request đến
khi có kết quả cuối cùng. Đảm bảo nếu bất kỳ bước nào fail thì hệ
thống tự rollback về trạng thái nhất quán. Developer chỉ implement
nghiệp vụ cụ thể, framework lo toàn bộ flow.

### Package

```
io.hcr.saga
io.hcr.saga.step
io.hcr.saga.context
```

### Classes & Interfaces

---

#### `AbstractSagaOrchestrator` *(abstract class — trung tâm module)*

Chứa toàn bộ flow chuẩn. Developer extend và implement các
abstract method theo nghiệp vụ.

```java
public abstract class AbstractSagaOrchestrator<
        REQ extends OrderRequest,
        O extends AbstractOrder> {

    // Dependencies (framework inject)
    protected InventoryStrategy inventoryStrategy;
    protected PaymentGateway paymentGateway;
    protected EventBus eventBus;
    protected IdempotencyHandler idempotencyHandler;
    protected SagaMetrics metrics;

    // === MAIN FLOW (final — developer không override) ===
    public final O process(REQ request)
    public final O retryPayment(String orderId)
    public final O adminCancel(String orderId, String reason)
    public final OrderStatus getStatus(String orderId)
    public final O processPartial(REQ request)     // đặt một phần nếu thiếu

    // === DEVELOPER BẮT BUỘC IMPLEMENT ===
    protected abstract O createOrder(REQ request)
    protected abstract O findOrder(String orderId)
    protected abstract O saveOrder(O order)
    protected abstract PaymentRequest buildPaymentRequest(O order)
    protected abstract void onConfirmed(O order)
    protected abstract void onCancelled(O order, String reason)

    // === LIFECYCLE HOOKS (developer override nếu cần) ===
    protected void onReserving(O order) {}
    protected void onPaymentProcessing(O order) {}
    protected void onConfirming(O order) {}
    protected void onCancelling(O order) {}
    protected void onCompensating(O order) {}
    protected void onExpiring(O order) {}

    // === CONFIGURATION ===
    protected int getReservationTimeoutMinutes() { return 5; }
    protected boolean allowPartialFulfillment() { return false; }
}
```

---

#### `SynchronousSagaOrchestrator` *(abstract class)*

Flow đồng bộ — trả về kết quả ngay trong HTTP request.
Dùng khi business yêu cầu immediate confirmation.

```java
public abstract class SynchronousSagaOrchestrator<
        REQ extends OrderRequest,
        O extends AbstractOrder>
        extends AbstractSagaOrchestrator<REQ, O> {

    // Flow:
    // 1. Validate + Idempotency check
    // 2. createOrder() → save PENDING
    // 3. inventoryStrategy.reserve()   ← blocking
    //    → fail: cancel + return
    // 4. paymentGateway.charge()       ← blocking, có timeout
    //    → fail: release + cancel + return
    // 5. confirm + return 201

    // Developer chỉ cần implement abstract methods từ base class
    // Không cần viết thêm gì cho flow
}
```

---

#### `AsynchronousSagaOrchestrator` *(abstract class)*

Flow bất đồng bộ — trả về PENDING ngay, xử lý qua Event Bus.
Loại bỏ DB khỏi critical path. Dùng khi throughput là ưu tiên.

```java
public abstract class AsynchronousSagaOrchestrator<
        REQ extends OrderRequest,
        O extends AbstractOrder>
        extends AbstractSagaOrchestrator<REQ, O> {

    // Critical path (sync, nhanh):
    // 1. Validate + Idempotency check
    // 2. inventoryStrategy.reserve()   ← Redis DECR atomic
    // 3. eventBus.publish(OrderCreatedEvent)
    // 4. return 202 ACCEPTED

    // Async path (qua Event Bus):
    // OrderCreatedEvent → PaymentConsumer → charge()
    // PaymentResultEvent → ConfirmationConsumer → confirm/cancel

    // Developer thêm nếu cần
    protected abstract void onPaymentResultReceived(O order, PaymentResult result)
}
```

---

#### `SagaStep` *(interface)*

Đại diện cho 1 bước trong Saga. Framework compose các step thành
flow hoàn chỉnh.

```java
public interface SagaStep<O extends AbstractOrder> {

    // Thực thi bước này
    StepResult execute(SagaContext<O> context)

    // Hoàn tác nếu bước sau fail
    void compensate(SagaContext<O> context)

    // Tên bước — dùng trong logging và metrics
    String getStepName()

    // Bước này có thể retry không?
    boolean isRetryable()
}
```

---

#### `SagaContext` *(class)*

Truyền state giữa các step trong cùng 1 Saga execution.

```java
public class SagaContext<O extends AbstractOrder> {

    private O order;
    private ReservationResult reservationResult;
    private PaymentResult paymentResult;
    private List<String> completedSteps;
    private List<String> failedSteps;
    private Map<String, Object> metadata;    // developer thêm data tùy ý

    // Methods
    public O getOrder()
    public void setOrder(O order)
    public ReservationResult getReservationResult()
    public void setReservationResult(ReservationResult result)
    public PaymentResult getPaymentResult()
    public void setPaymentResult(PaymentResult result)
    public void addMetadata(String key, Object value)
    public <T> T getMetadata(String key, Class<T> type)
    public boolean hasCompletedStep(String stepName)
}
```

---

#### `ReservationStep` *(class — implements SagaStep)*

Step 1: gọi InventoryStrategy.reserve().
Compensate: gọi InventoryStrategy.release().

```java
public class ReservationStep<O extends AbstractOrder>
        implements SagaStep<O> {

    private final InventoryStrategy inventoryStrategy;

    @Override
    public StepResult execute(SagaContext<O> context)
    // → inventoryStrategy.reserve(resourceId, orderId, quantity)
    // → set context.reservationResult

    @Override
    public void compensate(SagaContext<O> context)
    // → inventoryStrategy.release(resourceId, orderId, quantity)

    @Override
    public String getStepName() { return "reservation"; }
}
```

---

#### `PaymentStep` *(class — implements SagaStep)*

Step 2: gọi PaymentGateway.charge().
Compensate: gọi PaymentGateway.refund().

```java
public class PaymentStep<O extends AbstractOrder>
        implements SagaStep<O> {

    private final PaymentGateway paymentGateway;

    @Override
    public StepResult execute(SagaContext<O> context)
    // → paymentGateway.charge(paymentRequest)
    // → set context.paymentResult

    @Override
    public void compensate(SagaContext<O> context)
    // → paymentGateway.refund(transactionId)

    @Override
    public String getStepName() { return "payment"; }
}
```

---

#### `ConfirmationStep` *(class — implements SagaStep)*

Step 3: update order status, publish event. Final step — không có
compensate.

```java
public class ConfirmationStep<O extends AbstractOrder>
        implements SagaStep<O> {

    private final EventBus eventBus;

    @Override
    public StepResult execute(SagaContext<O> context)
    // → order.setStatus(CONFIRMED)
    // → saveOrder(order)
    // → eventBus.publish(OrderConfirmedEvent)

    @Override
    public void compensate(SagaContext<O> context)
    // No-op — final step không compensate được

    @Override
    public String getStepName() { return "confirmation"; }
}
```

---

#### `StepResult` *(class)*

Kết quả của 1 SagaStep.

```java
public class StepResult {
    private StepStatus status;    // SUCCESS / FAILED / RETRY
    private String errorMessage;
    private FailureReason failureReason;

    public static StepResult success()
    public static StepResult failed(FailureReason reason, String message)
    public static StepResult retry(String reason)

    public boolean isSuccess()
    public boolean isFailed()
    public boolean shouldRetry()
}
```

---

#### `SagaStateRepository` *(interface)*

Lưu trạng thái Saga để resume nếu crash giữa chừng. Developer
implement với DB của họ.

```java
public interface SagaStateRepository<O extends AbstractOrder> {

    void save(SagaContext<O> context)
    Optional<SagaContext<O>> findByOrderId(String orderId)
    void delete(String orderId)
    List<SagaContext<O>> findByStatus(OrderStatus status)
}
```

> **[Revision 2] — BẮT BUỘC hay OPTIONAL:**
>
> | Saga Mode | SagaStateRepository | Hậu quả nếu thiếu |
> |-----------|--------------------|--------------------|
> | **sync** | Optional | Crash → client retry → tạo Saga mới → OK |
> | **async** | **BẮT BUỘC** | Crash giữa chừng → double charge hoặc double release |
>
> Framework **throw exception khi startup** nếu `hcr.saga.mode=async`
> mà không có SagaStateRepository bean:
> ```
> HcrFrameworkException: AsynchronousSaga requires a SagaStateRepository
> bean. Please implement SagaStateRepository<YourOrderType> and register
> it as a Spring bean.
> ```
>
> **Gợi ý implement đơn giản nhất:** Thêm column `saga_state` (JSONB)
> vào bảng orders, serialize SagaContext thành JSON.

---

## 5. Module 04 — Payment

### Vai trò

Abstract hóa việc tích hợp với payment gateway bên thứ 3. Xử lý
các tình huống phức tạp: timeout, no-response, late-success.
Giải quyết 2 tình huống từ meeting note:
- Tình huống A: gateway lỗi, không trả về kết quả
- Tình huống B: gateway thành công nhưng response bị mất

### Package

```
io.hcr.payment
io.hcr.payment.timeout
io.hcr.payment.model
```

### Classes & Interfaces

---

#### `PaymentGateway` *(interface — contract với bên thứ 3)*

```java
public interface PaymentGateway {

    // === CORE OPERATIONS ===
    PaymentResult charge(PaymentRequest request)
    PaymentResult queryStatus(String transactionId)    // giải quyết T/H A và B
    RefundResult refund(RefundRequest request)
    RefundResult partialRefund(String transactionId, long amount)

    // === PRE-AUTHORIZATION (giữ tiền trước, charge sau) ===
    AuthorizationResult preAuthorize(PaymentRequest request)
    PaymentResult capture(String authorizationId)
    void voidAuthorization(String authorizationId)

    // === HEALTH ===
    boolean isAvailable()
    GatewayHealth getHealth()

    // Gateway identifier — dùng trong metrics
    String getGatewayName()
}
```

---

#### `AbstractPaymentGateway` *(abstract class — implements PaymentGateway)*

Đóng gói: idempotency key, retry logic, timeout detection, polling.
Developer chỉ implement giao tiếp thực tế với gateway cụ thể.

```java
public abstract class AbstractPaymentGateway implements PaymentGateway {

    private final TimeoutHandler timeoutHandler;
    private final PaymentMetrics metrics;
    private final int maxRetries;
    private final long timeoutMs;

    // === FRAMEWORK LO PHẦN NÀY ===
    @Override
    public final PaymentResult charge(PaymentRequest request)
    // → kiểm tra idempotency (transactionId đã charge chưa?)
    // → gọi doCharge()
    // → nếu timeout → timeoutHandler.handle()
    // → record metrics

    // === DEVELOPER IMPLEMENT PHẦN NÀY ===
    protected abstract PaymentResult doCharge(PaymentRequest request)
    protected abstract PaymentResult doQuery(String transactionId)
    protected abstract RefundResult doRefund(RefundRequest request)
}
```

---

#### `TimeoutHandler` *(class)*

Xử lý khi payment gateway không trả về response trong thời gian
quy định. Tự động polling queryStatus().

```java
public class TimeoutHandler {

    private final PaymentGateway gateway;
    private final long pollingIntervalMs;   // default: 5000ms
    private final int maxPollingAttempts;   // default: 6 (= 30 giây)

    // Khi charge() timeout:
    // → Polling queryStatus() mỗi pollingIntervalMs
    // → Nếu tìm thấy kết quả → trả về PaymentResult thực tế
    // → Nếu hết maxPollingAttempts → trả về UNKNOWN
    // UNKNOWN → trigger ReconciliationService xử lý sau
    public PaymentResult handle(String transactionId)

    // Async version — không block thread
    public CompletableFuture<PaymentResult> handleAsync(String transactionId)
}
```

---

#### `PaymentRequest` *(class)*

Input chuẩn khi gọi payment gateway.

```java
public class PaymentRequest {

    private String transactionId;   // = orderId → idempotency key
    private long amount;
    private String currency;        // ISO 4217: "VND", "USD"...
    private String description;
    private Map<String, String> metadata;   // developer thêm tùy ý

    // Builder pattern
    public static Builder builder()
}
```

---

#### `PaymentResult` *(class)*

Output chuẩn từ payment gateway.

```java
public class PaymentResult {

    private PaymentStatus status;   // SUCCESS / FAILED / TIMEOUT / UNKNOWN
    private String transactionId;
    private String gatewayTransactionId;    // ID phía gateway
    private long amount;
    private Instant processedAt;
    private String errorCode;
    private String errorMessage;

    // Factory methods
    public static PaymentResult success(String txId, String gatewayTxId)
    public static PaymentResult failed(String txId, String errorCode)
    public static PaymentResult timeout(String txId)
    public static PaymentResult unknown(String txId)    // không rõ kết quả

    public boolean isSuccess()
    public boolean isFailed()
    public boolean isTimeout()
    public boolean isUnknown()      // trigger polling/reconciliation
}
```

---

#### `MockPaymentGateway` *(class — implements PaymentGateway)*

Dùng cho testing và benchmark. Có thể configure mọi tình huống.

```java
public class MockPaymentGateway extends AbstractPaymentGateway {

    private double successRate;         // default: 0.8 (80%)
    private long simulatedDelayMs;      // default: 100ms
    private double timeoutRate;         // default: 0.05 (5%)
    private double noResponseRate;      // default: 0.02 (2%) — Tình huống A
    private double lateSuccessRate;     // default: 0.03 (3%) — Tình huống B

    // Builder để configure từng scenario
    public static Builder builder()
    public MockPaymentGateway withSuccessRate(double rate)
    public MockPaymentGateway withTimeout(long delayMs)
    public MockPaymentGateway withNoResponse(double rate)    // Tình huống A
    public MockPaymentGateway withLateSuccess(double rate)   // Tình huống B

    @Override
    public String getGatewayName() { return "mock"; }
}
```

---

#### `GatewayHealth` *(class)*

Trạng thái health của payment gateway tại thời điểm query.

```java
public class GatewayHealth {

    private HealthStatus status;    // UP / DEGRADED / DOWN
    private double successRateLast5Min;
    private double avgLatencyMs;
    private int activeConnections;
    private Instant checkedAt;

    public boolean isHealthy()
    public boolean isDegraded()
    public boolean isDown()
}
```

---

## 6. Module 05 — Event Bus

### Vai trò

Abstract hóa việc publish/consume event giữa các component. Đảm
bảo at-least-once delivery mà không couple framework với bất kỳ
message queue cụ thể nào. Hỗ trợ 4 implementation: Kafka,
RabbitMQ, Redis Streams, InMemory.

### Package

```
io.hcr.eventbus
io.hcr.eventbus.adapter
io.hcr.eventbus.event
```

### Classes & Interfaces

---

#### `EventBus` *(interface — contract trung tâm)*

3 hành động cơ bản, không phụ thuộc implementation.

```java
public interface EventBus {

    // Publish 1 event
    void publish(DomainEvent event)

    // Publish với destination cụ thể
    void publish(DomainEvent event, EventDestination destination)

    // Publish có idempotency — không publish 2 lần cùng eventId
    void publishIdempotent(DomainEvent event, String idempotencyKey)

    // Publish batch
    void publishBatch(List<DomainEvent> events)

    // Subscribe theo loại event
    <E extends DomainEvent> void subscribe(
        Class<E> eventType,
        EventHandler<E> handler
    )

    // Unsubscribe
    <E extends DomainEvent> void unsubscribe(
        Class<E> eventType,
        EventHandler<E> handler
    )

    // [Revision 2] Lấy capabilities của adapter đang dùng
    EventBusCapabilities getCapabilities()
}
```

---

#### `EventBusCapabilities` *(class — Revision 2)*

Khai báo tường minh tính năng mà mỗi adapter hỗ trợ. Framework
log WARNING khi developer rely vào capability không được support —
tránh bug production do semantic differences giữa các adapter.

```java
public class EventBusCapabilities {

    private boolean supportsOrdering;       // đảm bảo thứ tự message
    private boolean supportsReplay;         // đọc lại message cũ
    private boolean supportsExactlyOnce;    // xử lý đúng 1 lần native
    private boolean supportsPartitioning;   // partition theo key
    private boolean isSynchronous;          // deliver trong cùng thread
    private boolean supportsDLQ;            // dead letter queue
    private boolean supportsMultiConsumer;  // nhiều consumer song song

    // Support matrix theo adapter:
    //                      Kafka    RabbitMQ  RedisStr  InMemory
    // supportsOrdering      ✓(part)  ✓(queue)  ✓(str)    ✓
    // supportsReplay        ✓        ✗         ✓         ✗
    // supportsExactlyOnce   ✓(idem)  ✗         ✗         ✓
    // supportsPartitioning  ✓        ✗         ✗         ✗
    // isSynchronous         ✗        ✗         ✗         ✓  ← NGUY HIỂM
    // supportsDLQ           ✓        ✓         limited   ✓
    // supportsMultiConsumer ✓        ✓         ✓         ✗

    // Factory methods — mỗi adapter tự khai báo capabilities
    public static EventBusCapabilities forKafka()
    public static EventBusCapabilities forRabbitMQ()
    public static EventBusCapabilities forRedisStream()
    public static EventBusCapabilities forInMemory()
}
```

> ⚠️ **Cảnh báo quan trọng:**
> `InMemoryEventBusAdapter` là synchronous + exactly-once.
> `KafkaEventBusAdapter` là asynchronous + at-least-once.
> Test với InMemory rồi deploy Kafka → behavior hoàn toàn khác.
> **Integration test nên dùng cùng loại adapter với production.**

---

#### `EventDestination` *(class)*

Abstraction cho topic/queue/exchange — không dùng tên cụ thể của
từng message queue.

```java
public class EventDestination {

    private final String name;      // tên logic
    // Adapter sẽ map name này sang:
    // Kafka     → Topic name
    // RabbitMQ  → Exchange + Routing Key
    // Redis     → Stream key
    // InMemory  → internal channel name

    // Factory methods
    public static EventDestination of(String name)
    public static EventDestination forEventType(Class<? extends DomainEvent> type)

    public String getName()
}
```

---

#### `EventHandler` *(interface)*

Developer implement để xử lý event. Acknowledgment là abstraction —
không phải Kafka-specific.

```java
public interface EventHandler<E extends DomainEvent> {

    // Xử lý event và ack khi xong
    void handle(E event, Acknowledgment ack)

    // Xử lý khi không ack được sau nhiều lần retry
    default void onDeadLetter(E event, Exception cause) {}
}
```

---

#### `Acknowledgment` *(interface)*

Abstraction cho việc xác nhận đã xử lý event — thay thế cho
Kafka-specific Acknowledgment.

```java
public interface Acknowledgment {

    // Xác nhận xử lý thành công — message không được gửi lại
    void acknowledge()

    // Từ chối — message sẽ được gửi lại (retry)
    void reject()

    // Từ chối và không retry — gửi vào dead letter
    void reject(boolean requeue)
}
```

---

#### `KafkaEventBusAdapter` *(class — implements EventBus)*

Adapter cho Apache Kafka. Default implementation.

```java
public class KafkaEventBusAdapter implements EventBus {

    private final KafkaTemplate<String, DomainEvent> kafkaTemplate;
    private final KafkaListenerContainerFactory listenerFactory;

    // Config (map từ HcrProperties):
    // acks=all, retries=3, enable.idempotence=true
    // ack-mode=manual_immediate
    // key-prefix cho topic name

    // publish() → KafkaTemplate.send(topicName, event)
    // subscribe() → dynamic listener registration
    // publishIdempotent() → Kafka idempotent producer (built-in)

    // EventDestination.name → Kafka topic name
    // (với prefix từ config, VD: "hcr.order-created")
}
```

---

#### `RabbitMQEventBusAdapter` *(class — implements EventBus)*

Adapter cho RabbitMQ.

```java
public class RabbitMQEventBusAdapter implements EventBus {

    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;

    // Config:
    // exchange: hcr.events (topic exchange)
    // routing key = EventDestination.name
    // queue: hcr.{destination}.queue
    // ack-mode: manual

    // publish() → rabbitTemplate.convertAndSend(exchange, routingKey, event)
    // subscribe() → dynamic queue binding + listener

    // publishIdempotent():
    // → Redis check eventId trước khi publish
    // → Nếu đã publish → skip (RabbitMQ không có native idempotent producer)
}
```

---

#### `RedisStreamEventBusAdapter` *(class — implements EventBus)*

Adapter cho Redis Streams. Phù hợp khi đã có Redis, không muốn
thêm dependency mới.

```java
public class RedisStreamEventBusAdapter implements EventBus {

    private final RedisTemplate<String, DomainEvent> redisTemplate;
    private final StreamOperations<String, String, DomainEvent> streamOps;

    // Config:
    // key-prefix: "hcr:stream:"
    // consumer-group: "hcr-consumers"
    // block-timeout: 2000ms

    // publish()    → XADD hcr:stream:{destination} * event
    // subscribe()  → XREADGROUP GROUP hcr-consumers consumer-{n}
    // acknowledge() → XACK hcr:stream:{destination} hcr-consumers messageId

    // publishIdempotent() → check eventId trước XADD
}
```

---

#### `InMemoryEventBusAdapter` *(class — implements EventBus)*

Dùng cho testing. Không cần infrastructure. Synchronous delivery.

```java
public class InMemoryEventBusAdapter implements EventBus {

    private final Map<Class<?>, List<EventHandler>> handlers;
    private final List<DomainEvent> publishedEvents;    // cho assertion trong test

    // publish() → gọi handler ngay lập tức (synchronous)
    // subscribe() → thêm vào handlers map
    // publishIdempotent() → check eventId trước khi gọi handler

    // Testing utilities
    public List<DomainEvent> getPublishedEvents()
    public <E extends DomainEvent> List<E> getPublishedEvents(Class<E> type)
    public void clearEvents()
    public int getPublishedCount(Class<? extends DomainEvent> type)
}
```

---

#### Domain Events *(framework cung cấp sẵn — developer subscribe)*

```java
// Inventory events
ResourceReservedEvent       // đã giữ chỗ thành công
ResourceReleasedEvent       // đã giải phóng chỗ
ResourceDepletedEvent       // hết tài nguyên
ResourceLowStockEvent       // sắp hết (dưới threshold)
ResourceRestockedEvent      // đã thêm tồn kho

// Order events
OrderCreatedEvent           // order vừa được tạo
OrderConfirmedEvent         // order hoàn thành
OrderCancelledEvent         // order bị hủy
OrderExpiredEvent           // giữ chỗ hết hạn

// Payment events
PaymentSucceededEvent       // thanh toán thành công
PaymentFailedEvent          // thanh toán thất bại
PaymentTimeoutEvent         // thanh toán không phản hồi → trigger polling
PaymentUnknownEvent         // không rõ kết quả → trigger reconciliation

// Reconciliation events
ReconciliationStartedEvent  // bắt đầu reconciliation cycle
ReconciliationFixedEvent    // đã fix 1 inconsistency
InventoryMismatchEvent      // phát hiện lệch giữa Redis và DB
```

---

## 7. Module 07 — Reconciliation

### Vai trò

Safety net — định kỳ quét và sửa các inconsistency mà real-time
flow không xử lý được. Đảm bảo eventual consistency trong mọi
tình huống crash. Giải quyết cả Tình huống A và B từ meeting note.

### Package

```
io.hcr.reconciliation
io.hcr.reconciliation.handler
```

### Classes & Interfaces

---

#### `AbstractReconciliationService` *(abstract class — trung tâm module)*

```java
public abstract class AbstractReconciliationService<O extends AbstractOrder> {

    // Dependencies (framework inject)
    protected PaymentGateway paymentGateway;
    protected InventoryStrategy inventoryStrategy;
    protected EventBus eventBus;
    protected ReconciliationMetrics metrics;

    // === FRAMEWORK CHẠY TỰ ĐỘNG (developer không gọi trực tiếp) ===
    @Scheduled
    public final void runReconciliation()
    // → chạy tất cả 5 case bên dưới
    // → distributed lock: đảm bảo chỉ 1 instance chạy tại 1 thời điểm

    // === 5 CASE FRAMEWORK TỰ PHÁT HIỆN ===

    // Case 1: Order PENDING quá lâu
    // → gọi paymentGateway.queryStatus() để verify
    // → Tình huống A (lỗi) → handleTimeout()
    // → Tình huống B (thành công muộn) → handleLatePaymentSuccess()
    protected abstract List<O> findStalePendingOrders(int timeoutMinutes)
    protected abstract void handleTimeout(O order)
    protected abstract void handleLatePaymentSuccess(O order, PaymentResult result)

    // Case 2: Redis lệch với DB
    protected abstract void handleInventoryMismatch(
        String resourceId,
        long redisCount,
        long dbCount
    )

    // Case 3: Order CONFIRMED nhưng DB chưa cập nhật inventory
    protected abstract List<O> findUnpersistedReservations()
    protected abstract void handleUnpersistedReservation(O order)

    // Case 4: Duplicate order
    protected abstract List<List<O>> findDuplicateOrders()
    protected abstract void handleDuplicateOrders(List<O> duplicates)

    // === CONFIGURATION ===
    protected int getTimeoutMinutes() { return 5; }
    protected long getScheduleDelayMs() { return 300_000L; }    // 5 phút
    protected long getInventoryMismatchThreshold() { return 0L; }
}
```

---

#### `InventoryReconciler` *(class)*

So sánh Redis inventory với DB inventory. Phát hiện và report
mismatch. Tự động fix nếu delta trong ngưỡng an toàn.

```java
public class InventoryReconciler {

    private final InventoryStrategy inventoryStrategy;
    private final ReconciliationMetrics metrics;

    public ReconciliationReport reconcile(String resourceId)
    public ReconciliationReport reconcileAll(List<String> resourceIds)

    // So sánh snapshot từ Redis vs DB
    public InventoryDelta compare(String resourceId)

    // Tự động fix nếu delta <= threshold
    public boolean autoFix(String resourceId, long delta)
}
```

---

#### `OrderReconciler` *(class)*

Quét order PENDING quá timeout. Gọi PaymentGateway.queryStatus()
để verify. Resolve Tình huống A và B.

```java
public class OrderReconciler<O extends AbstractOrder> {

    private final PaymentGateway paymentGateway;
    private final ReconciliationMetrics metrics;

    // Quét và classify stale orders
    public ReconciliationReport reconcile(List<O> staleOrders)

    // Verify 1 order cụ thể với payment gateway
    public PaymentVerificationResult verify(O order)
}
```

---

#### `ReconciliationResult` *(class)*

Kết quả sau mỗi lần chạy reconciliation cycle.

```java
public class ReconciliationResult {

    private int totalScanned;
    private int totalFixed;
    private int totalFailed;
    private Map<ReconciliationCase, Integer> fixedByCase;
    private List<String> errors;
    private Duration duration;
    private Instant runAt;

    public boolean hasErrors()
    public double getSuccessRate()
}
```

---

#### `ReconciliationCase` *(enum)*

```java
public enum ReconciliationCase {
    STALE_PENDING,              // order PENDING quá lâu
    LATE_PAYMENT_SUCCESS,       // tiền đã trừ nhưng order vẫn PENDING
    INVENTORY_MISMATCH,         // Redis lệch với DB
    UNPERSISTED_RESERVATION,    // CONFIRMED nhưng DB chưa cập nhật
    DUPLICATE_ORDER             // 2 order cùng idempotency key
}
```

---

## 8. Module 06 — Gateway

### Vai trò

Tầng bảo vệ đầu tiên. Mọi request đều đi qua đây trước khi vào
Saga Orchestrator. Đóng gói Rate Limiting, Idempotency,
Circuit Breaker, và Validation.

### Package

```
io.hcr.gateway
io.hcr.gateway.ratelimit
io.hcr.gateway.idempotency
```

### Classes & Interfaces

---

#### `FrameworkGateway` *(abstract class — entry point duy nhất)*

```java
public abstract class FrameworkGateway<
        REQ extends OrderRequest,
        O extends AbstractOrder> {

    // Pipeline: Validate → Idempotency → RateLimit → CB → Saga
    public final O submit(REQ request)

    // Developer implement validation nghiệp vụ
    protected abstract ValidationResult validateBusinessRules(REQ request)

    // Developer có thể override để customize pipeline
    protected boolean shouldRateLimit(REQ request) { return true; }
    protected String getRateLimitKey(REQ request) { return request.getRequesterId(); }
    protected String getIdempotencyKey(REQ request) { return request.getIdempotencyKey(); }
}
```

---

#### `RateLimiter` *(interface)*

```java
public interface RateLimiter {

    boolean tryAcquire(String key)
    boolean tryAcquire(String key, int permits)
    RateLimitResult tryAcquireWithInfo(String key)  // remaining + resetTime
    void configure(String key, long permitsPerSecond, long burstCapacity)
}
```

---

#### `RedisTokenBucketRateLimiter` *(class — implements RateLimiter)*

Token bucket trên Redis. Configurable per-resource, per-user.

```java
public class RedisTokenBucketRateLimiter implements RateLimiter {

    private final RedisTemplate redisTemplate;
    // Lua script atomic để đảm bảo không race condition
    // Config: permitsPerSecond, burstCapacity từ HcrProperties
}
```

---

#### `IdempotencyHandler` *(interface)*

```java
public interface IdempotencyHandler {

    boolean isDuplicate(String key)
    void markProcessed(String key, Object result)
    Optional<Object> getCachedResult(String key)
    void expire(String key)
}
```

---

#### `RedisIdempotencyHandler` *(class — implements IdempotencyHandler)*

```java
public class RedisIdempotencyHandler implements IdempotencyHandler {

    private final RedisTemplate redisTemplate;
    private final long ttlSeconds;    // default: 86400 (24h)
    private final String keyPrefix;   // default: "hcr:idempotency:"
}
```

---

#### `AbstractRequestValidator` *(abstract)*

```java
public abstract class AbstractRequestValidator<R extends OrderRequest> {

    // Framework lo: null check, format, required fields
    public final ValidationResult validate(R request)

    // Developer implement: business rules
    protected abstract ValidationResult validateBusinessRules(R request)
}
```

---

#### `RateLimitResult` *(class)*

```java
public class RateLimitResult {
    private boolean allowed;
    private long remainingPermits;
    private long resetAfterMs;
    private long limitPerSecond;
}
```

---

## 9. Module 08 — Observability

### Vai trò

Cung cấp visibility vào hệ thống — developer biết chuyện gì đang
xảy ra bên trong framework mà không cần tự viết metrics.
Observability là first-class citizen, không phải afterthought.

### Package

```
io.hcr.observability
io.hcr.observability.metrics
```

### Classes & Interfaces

---

#### `FrameworkMetrics` *(interface)*

```java
public interface FrameworkMetrics {

    // Inventory
    void recordReserveAttempt(String resourceId, String strategy)
    void recordReserveSuccess(String resourceId, long durationMs)
    void recordReserveFailure(String resourceId, FailureReason reason)
    void recordReleaseSuccess(String resourceId)
    void recordOversellPrevented(String resourceId)
    void updateAvailableGauge(String resourceId, long available)

    // Saga
    void recordSagaStarted(String resourceId)
    void recordSagaConfirmed(String resourceId, long durationMs)
    void recordSagaCancelled(String resourceId, String reason)
    void recordSagaCompensated(String resourceId, String reason)

    // Payment
    void recordPaymentAttempt(String gateway)
    void recordPaymentSuccess(String gateway, long durationMs)
    void recordPaymentFailure(String gateway, String errorCode)
    void recordPaymentTimeout(String gateway)
    void recordPaymentUnknown(String gateway)

    // Reconciliation
    void recordReconciliationRun(ReconciliationResult result)
    void recordInventoryMismatch(String resourceId, long delta)
    void recordFixedByCase(ReconciliationCase case_, int count)

    // Event Bus
    void recordEventPublished(String eventType, String adapter)
    void recordEventConsumed(String eventType, long durationMs)
    void recordEventFailed(String eventType, String reason)
}
```

---

#### `MicrometerFrameworkMetrics` *(class — implements FrameworkMetrics)*

Implement dùng Micrometer — tự động export sang Prometheus.
Developer không cần config gì thêm.

```java
public class MicrometerFrameworkMetrics implements FrameworkMetrics {

    private final MeterRegistry registry;

    // Tự động tạo các metric:
    // hcr_reservation_attempts_total (counter, tags: resourceId, strategy)
    // hcr_reservation_duration_ms (histogram)
    // hcr_inventory_available (gauge, tag: resourceId)
    // hcr_oversell_prevented_total (counter)
    // hcr_saga_duration_ms (histogram, tags: resourceId, outcome)
    // hcr_payment_duration_ms (histogram, tags: gateway, status)
    // hcr_reconciliation_fixed_total (counter, tag: case)
    // ... (tất cả metric định nghĩa trong interface)
}
```

---

#### `GrafanaDashboardTemplate` *(resource file)*

File JSON Grafana dashboard đi kèm framework. Developer import
vào Grafana là có dashboard ngay với tất cả panel cần thiết.

```
Panels:
  - Throughput (req/s) theo strategy
  - P50/P95/P99 latency
  - Oversell prevented count
  - Inventory available (realtime)
  - Saga success/cancel/compensate rate
  - Payment success rate + timeout rate
  - Reconciliation fixed count by case
  - Event bus publish/consume rate
  - Circuit breaker state
```

---

## 10. Module 09 — Testing Support

### Vai trò

Giúp developer test use case của họ dễ dàng — không cần setup
Kafka, Redis, DB thật khi viết unit test. Đây là thứ phân biệt
framework nghiêm túc với code thủ công.

### Package

```
io.hcr.test
```

### Classes & Interfaces

---

#### `FrameworkTestSupport` *(utility class)*

```java
public final class FrameworkTestSupport {

    // Tạo mock infrastructure
    public static InMemoryInventoryStrategy inMemoryInventory(long initialStock)
    public static MockPaymentGateway mockPayment(double successRate)
    public static InMemoryEventBusAdapter inMemoryEventBus()

    // Concurrency testing
    public static ConcurrencyTestResult simulateConcurrentRequests(
        AbstractSagaOrchestrator orchestrator,
        OrderRequest requestTemplate,
        int concurrentUsers,
        int totalRequests
    )

    // Assertions
    public static void assertNoOversell(String resourceId, InventoryStrategy strategy)
    public static void assertZeroOversell(ConcurrencyTestResult result)
    public static void assertThroughputAbove(ConcurrencyTestResult result, long minTps)
    public static void assertEventPublished(InMemoryEventBusAdapter bus,
                                            Class<? extends DomainEvent> eventType)
    public static void assertEventualConsistency(String orderId,
                                                  SagaStateRepository repository,
                                                  Duration timeout)
}
```

---

#### `InMemoryInventoryStrategy` *(class — implements InventoryStrategy)*

Inventory trong memory. Không cần Redis/DB. Thread-safe.

```java
public class InMemoryInventoryStrategy implements InventoryStrategy {

    private final ConcurrentHashMap<String, AtomicLong> inventory;

    // initialize() → put vào map
    // reserve()    → AtomicLong compareAndSet
    // release()    → AtomicLong getAndAdd

    // Testing utility
    public long getCurrentAvailable(String resourceId)
    public int getReserveCallCount(String resourceId)
    public int getOversellAttemptCount(String resourceId)

    @Override
    public String getStrategyName() { return "in-memory"; }
}
```

---

#### `ConcurrencyTestResult` *(class)*

Kết quả của simulateConcurrentRequests().

```java
public class ConcurrencyTestResult {

    private int totalRequests;
    private int successCount;
    private int failureCount;
    private int oversellCount;          // phải luôn = 0
    private long throughputTps;
    private long p50LatencyMs;
    private long p95LatencyMs;
    private long p99LatencyMs;
    private Duration totalDuration;
    private List<String> errors;

    public boolean hasOversell()
    public double getSuccessRate()
}
```

---

#### `FrameworkIntegrationTest` *(abstract class)*

Base class cho integration test của developer.

```java
@SpringBootTest
public abstract class FrameworkIntegrationTest<
        REQ extends OrderRequest,
        O extends AbstractOrder> {

    // Framework setup sẵn
    @Autowired protected InMemoryEventBusAdapter eventBus;
    @Autowired protected MockPaymentGateway mockPayment;
    @Autowired protected FrameworkTestSupport testSupport;

    // Developer implement
    protected abstract AbstractSagaOrchestrator<REQ, O> getOrchestrator()
    protected abstract REQ buildTestRequest(String resourceId, int quantity)
    protected abstract long getInitialStock()

    // Helper methods
    protected void givenAvailableStock(long quantity)
    protected void givenPaymentWillSucceed()
    protected void givenPaymentWillFail()
    protected void givenPaymentWillTimeout()    // Tình huống A
    protected void givenPaymentWillSucceedLate()  // Tình huống B
    protected void thenAssertNoOversell()
    protected void thenAssertEventPublished(Class<? extends DomainEvent> type)
}
```

---

## 11. Module 10 — Auto Configuration

### Vai trò

Giúp developer tích hợp framework vào Spring Boot project chỉ bằng
cách thêm dependency và viết config yaml. Hoạt động theo nguyên tắc
convention over configuration — đúng như Spring Boot.

### Package

```
io.hcr.autoconfigure
```

### Classes & Interfaces

---

#### `@EnableHighConcurrencyResource` *(annotation)*

Developer thêm vào main class là xong. Trigger toàn bộ
auto-configuration.

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(HcrAutoConfiguration.class)
public @interface EnableHighConcurrencyResource {
    // Không cần param — tất cả config qua yaml
}

// Cách dùng:
@SpringBootApplication
@EnableHighConcurrencyResource
public class ConcertTicketApplication { ... }
```

---

#### `HcrAutoConfiguration` *(class)*

Wire tất cả bean của framework. Load theo classpath.

```java
@Configuration
@ConditionalOnClass(AbstractSagaOrchestrator.class)
@EnableConfigurationProperties(HcrProperties.class)
public class HcrAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    InventoryStrategy inventoryStrategy(HcrProperties props)

    @Bean @ConditionalOnMissingBean
    EventBus eventBus(HcrProperties props)

    @Bean @ConditionalOnMissingBean
    PaymentGateway paymentGateway(HcrProperties props)

    @Bean
    FrameworkMetrics frameworkMetrics(MeterRegistry registry)

    @Bean
    AbstractReconciliationService reconciliationService(HcrProperties props)

    // [Revision 2] Beans mới
    @Bean
    CorrelationIdFilter correlationIdFilter()

    @Bean @ConditionalOnClass(Endpoint.class)
    HcrActuatorEndpoint hcrActuatorEndpoint(HcrProperties props, EventBus bus)
}
```

> **[Revision 2] Validation khi startup — Fail Fast:**
>
> | Điều kiện vi phạm | Exception được throw |
> |-------------------|----------------------|
> | saga.mode=async + thiếu SagaStateRepository bean | `HcrFrameworkException: AsyncSaga requires SagaStateRepository` |
> | strategy=redis-atomic + thiếu Redis connection | `HcrFrameworkException: RedisAtomicStrategy requires Redis` |
> | event-bus.type=kafka + thiếu Kafka connection | `HcrFrameworkException: KafkaEventBusAdapter requires Kafka` |
> | Capability mismatch (warning, không fail) | `[HCR-WARN] Capability mismatch: ...` |

---

#### `HcrActuatorEndpoint` *(class — Revision 2)*

Spring Boot Actuator endpoint expose tại `/actuator/hcr`. Developer
xem toàn bộ config đang active và trạng thái framework tại runtime.

```java
@Endpoint(id = "hcr")
public class HcrActuatorEndpoint {

    // GET /actuator/hcr
    @ReadOperation
    public Map<String, Object> info()
    // Response gồm: framework version, inventory strategy + consistency level,
    // saga mode + sagaStateRepositoryPresent, eventBus adapter + capabilities,
    // payment config, reconciliation lastRunAt + lastRunResult,
    // observability correlationIdPropagationEnabled

    // Chỉ expose qua management port
    // Disable: management.endpoint.hcr.enabled=false
}
```

---

#### `CorrelationIdFilter` *(class — Revision 2)*

Servlet filter tự động register. Propagate `correlationId` xuyên
suốt toàn hệ thống — từ HTTP request đến log, event, và payment call.

```java
public class CorrelationIdFilter implements Filter {

    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";

    // Pipeline:
    // 1. Lấy từ header X-Correlation-ID hoặc sinh UUID mới
    // 2. Set vào SLF4J MDC → mọi log tự động include
    // 3. Khi publish DomainEvent → gắn vào event.correlationId
    // 4. Khi gọi PaymentGateway → forward qua HTTP header
    // 5. Add vào HTTP response header → client trace được

    // Log tự động:
    // 2026-04-01 10:00:01 INFO [correlationId=abc-123] message...
    // grep "correlationId=abc-123" → trace toàn bộ 1 request
}
```

---

#### `HcrProperties` *(class)*

Map toàn bộ config yaml thành Java object. Fail fast nếu config sai.

```java
@ConfigurationProperties(prefix = "hcr")
@Validated
public class HcrProperties {

    private InventoryProperties inventory = new InventoryProperties();
    private SagaProperties saga = new SagaProperties();
    private PaymentProperties payment = new PaymentProperties();
    private EventBusProperties eventBus = new EventBusProperties();
    private GatewayProperties gateway = new GatewayProperties();
    private ReconciliationProperties reconciliation = new ReconciliationProperties();

    // Nested config classes
    public static class InventoryProperties {
        private String strategy = "pessimistic-lock";   // default
        private RedisProperties redis;
        private CircuitBreakerProperties circuitBreaker;
    }

    public static class EventBusProperties {
        private String type = "kafka";      // kafka | rabbitmq | redis-stream | in-memory
        private KafkaProperties kafka;
        private RabbitMQProperties rabbitmq;
        private RedisStreamProperties redisStream;
    }

    public static class PaymentProperties {
        private long timeoutMs = 30_000L;
        private int maxRetries = 3;
        private long pollingIntervalMs = 5_000L;
        private int maxPollingAttempts = 6;
    }

    public static class GatewayProperties {
        private RateLimiterProperties rateLimiter;
        private long idempotencyTtlSeconds = 86_400L;
    }

    public static class ReconciliationProperties {
        private int timeoutMinutes = 5;
        private long scheduleDelayMs = 300_000L;
        private long inventoryMismatchThreshold = 0L;
    }
}
```

---

#### Config yaml mẫu

```yaml
hcr:
  inventory:
    strategy: redis-atomic        # pessimistic-lock | optimistic-lock | redis-atomic
    circuit-breaker:
      enabled: true
      failure-rate-threshold: 50
      wait-duration-seconds: 60
    redis:
      key-prefix: "hcr:inventory:"

  saga:
    mode: async                   # sync | async
    reservation-timeout-minutes: 5
    allow-partial-fulfillment: false

  payment:
    timeout-ms: 30000
    max-retries: 3
    polling-interval-ms: 5000
    max-polling-attempts: 6

  event-bus:
    type: kafka                   # kafka | rabbitmq | redis-stream | in-memory
    kafka:
      bootstrap-servers: localhost:9092
      topic-prefix: "hcr."
    rabbitmq:
      host: localhost
      port: 5672
      exchange: hcr.events
    redis-stream:
      key-prefix: "hcr:stream:"
      consumer-group: "hcr-consumers"

  gateway:
    rate-limiter:
      enabled: true
      permits-per-second: 100
      burst-capacity: 200
    idempotency-ttl-seconds: 86400

  reconciliation:
    timeout-minutes: 5
    schedule-delay-ms: 300000
    inventory-mismatch-threshold: 0
```

---

## 12. Mối quan hệ giữa các module

```
                    ┌─────────────────────┐
                    │    Auto-Config       │
                    │ @EnableHCR + yaml    │
                    └──────────┬──────────┘
                               │ wire all beans
          ┌────────────────────┼────────────────────┐
          ▼                    ▼                    ▼
   ┌─────────────┐    ┌──────────────┐    ┌──────────────┐
   │   Gateway   │    │ Observability│    │   Testing    │
   │ (entry pt)  │    │  (metrics)   │    │  (support)   │
   └──────┬──────┘    └──────────────┘    └──────────────┘
          │ route (sau validate + idempotency + rate limit)
          ▼
   ┌──────────────────────────────────────┐
   │         Saga Orchestration           │
   │  (AbstractSagaOrchestrator)          │
   │  Sync variant | Async variant        │
   └──────┬───────────────────┬───────────┘
          │                   │
          ▼                   ▼
   ┌─────────────┐    ┌───────────────┐
   │  Inventory  │    │   Payment     │
   │  Strategy   │    │   Gateway     │
   │ (3 impls +  │    │ (abstract +   │
   │  decorator) │    │  timeout hdl) │
   └──────┬──────┘    └───────┬───────┘
          │                   │
          └─────────┬─────────┘
                    ▼
            ┌───────────────┐
            │   Event Bus   │
            │ (4 adapters)  │
            └───────┬───────┘
                    │
                    ▼
          ┌─────────────────┐
          │ Reconciliation  │
          │ (safety net,    │
          │  chạy ngầm)     │
          └─────────────────┘

Xuyên suốt tất cả:
          ┌───────────────┐
          │  Core Domain  │
          │ (ngôn ngữ     │
          │  chung)       │
          └───────────────┘
```

---

## 13. Package Structure

```
io.hcr/
├── core/
│   └── domain/
│       ├── AbstractResource.java
│       ├── AbstractOrder.java
│       ├── OrderRequest.java
│       ├── OrderStatus.java
│       ├── ResourceStatus.java
│       ├── FailureReason.java
│       ├── ReservationResult.java
│       ├── DomainEvent.java              ← [R2] + correlationId field
│       ├── InventorySnapshot.java
│       ├── ValidationResult.java
│       └── FrameworkException.java
│
├── inventory/
│   ├── InventoryStrategy.java
│   ├── InventoryMetrics.java
│   ├── InventoryInitializer.java
│   ├── InventoryStrategyFactory.java
│   ├── strategy/
│   │   ├── PessimisticLockStrategy.java
│   │   ├── OptimisticLockStrategy.java
│   │   └── RedisAtomicStrategy.java
│   └── decorator/
│       └── CircuitBreakerInventoryDecorator.java
│
├── saga/
│   ├── AbstractSagaOrchestrator.java
│   ├── SynchronousSagaOrchestrator.java
│   ├── AsynchronousSagaOrchestrator.java
│   ├── SagaMetrics.java
│   ├── step/
│   │   ├── SagaStep.java
│   │   ├── StepResult.java
│   │   ├── ReservationStep.java
│   │   ├── PaymentStep.java
│   │   └── ConfirmationStep.java
│   └── context/
│       ├── SagaContext.java
│       └── SagaStateRepository.java      ← [R2] BẮT BUỘC với async mode
│
├── payment/
│   ├── PaymentGateway.java
│   ├── AbstractPaymentGateway.java
│   ├── MockPaymentGateway.java
│   ├── PaymentMetrics.java
│   ├── model/
│   │   ├── PaymentRequest.java
│   │   ├── PaymentResult.java
│   │   ├── RefundRequest.java
│   │   ├── RefundResult.java
│   │   ├── AuthorizationResult.java
│   │   └── GatewayHealth.java
│   └── timeout/
│       └── TimeoutHandler.java
│
├── eventbus/
│   ├── EventBus.java                     ← [R2] + getCapabilities()
│   ├── EventBusCapabilities.java         ← [R2] MỚI
│   ├── EventHandler.java
│   ├── EventDestination.java
│   ├── Acknowledgment.java
│   ├── adapter/
│   │   ├── KafkaEventBusAdapter.java
│   │   ├── RabbitMQEventBusAdapter.java
│   │   ├── RedisStreamEventBusAdapter.java
│   │   └── InMemoryEventBusAdapter.java
│   └── event/
│       ├── ResourceReservedEvent.java
│       ├── ResourceReleasedEvent.java
│       ├── ResourceDepletedEvent.java
│       ├── ResourceLowStockEvent.java
│       ├── ResourceRestockedEvent.java
│       ├── OrderCreatedEvent.java
│       ├── OrderConfirmedEvent.java
│       ├── OrderCancelledEvent.java
│       ├── OrderExpiredEvent.java
│       ├── PaymentSucceededEvent.java
│       ├── PaymentFailedEvent.java
│       ├── PaymentTimeoutEvent.java
│       ├── PaymentUnknownEvent.java
│       ├── ReconciliationStartedEvent.java
│       ├── ReconciliationFixedEvent.java
│       └── InventoryMismatchEvent.java
│
├── gateway/
│   ├── FrameworkGateway.java
│   ├── AbstractRequestValidator.java
│   ├── ValidationResult.java
│   ├── RateLimitResult.java
│   ├── ratelimit/
│   │   ├── RateLimiter.java
│   │   └── RedisTokenBucketRateLimiter.java
│   └── idempotency/
│       ├── IdempotencyHandler.java
│       └── RedisIdempotencyHandler.java
│
├── reconciliation/
│   ├── AbstractReconciliationService.java
│   ├── ReconciliationCase.java
│   ├── ReconciliationResult.java
│   ├── handler/
│   │   ├── InventoryReconciler.java
│   │   └── OrderReconciler.java
│   └── ReconciliationMetrics.java
│
├── observability/
│   ├── FrameworkMetrics.java
│   └── metrics/
│       └── MicrometerFrameworkMetrics.java
│
├── test/
│   ├── FrameworkTestSupport.java
│   ├── FrameworkIntegrationTest.java
│   ├── ConcurrencyTestResult.java
│   └── InMemoryInventoryStrategy.java
│
└── autoconfigure/
    ├── EnableHighConcurrencyResource.java
    ├── HcrAutoConfiguration.java         ← [R2] + CorrelationIdFilter, HcrActuatorEndpoint beans
    ├── HcrActuatorEndpoint.java          ← [R2] MỚI — /actuator/hcr
    ├── CorrelationIdFilter.java          ← [R2] MỚI — propagate correlationId
    └── HcrProperties.java
```

> **[R2] = thêm/sửa trong Revision 2**

---

*Tài liệu này sẽ được cập nhật liên tục trong quá trình phát triển.*  
*Phiên bản hiện tại: 0.1.0-SNAPSHOT | Revision 2 | SOICT — HUST | 04/2026*
