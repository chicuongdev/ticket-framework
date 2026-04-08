# hcr-saga — Hướng dẫn đọc code

> **Vai trò:** Điều phối toàn bộ luồng xử lý order — từ reserve inventory
> đến payment đến confirm. Tự rollback (compensate) nếu bất kỳ bước nào fail.
>
> **Dependency:** hcr-core, hcr-inventory, hcr-payment, hcr-eventbus (tất cả đã hoàn thành).

---

## Thứ tự đọc (quan trọng)

### 1. `step/StepResult.java` — Kết quả mỗi bước

Đọc file này đầu tiên vì mọi SagaStep đều trả về StepResult.

- 3 trạng thái: `SUCCESS`, `FAILED`, `RETRY`
- `FAILED` kèm `FailureReason` (từ hcr-core) + error message
- Factory methods: `success()`, `failed(reason, message)`, `retry(reason)`

### 2. `step/SagaStep.java` — Interface mỗi bước trong Saga

Contract cho 1 bước:
- `execute(context)` → thực thi, trả `StepResult`
- `compensate(context)` → hoàn tác nếu bước **sau** fail
- `getStepName()` → tên bước (dùng trong log + compensation loop)
- `isRetryable()` → bước có thể retry không

**Nguyên tắc:** `compensate()` KHÔNG ĐƯỢC throw exception — log error
rồi để Reconciliation xử lý.

### 3. `context/SagaContext.java` — Mang state giữa các bước

Tránh query DB lặp lại — mỗi bước đọc/ghi qua context:

| Field | Ai set? | Ai đọc? |
|-------|---------|---------|
| `order` | Orchestrator | Tất cả steps |
| `reservationResult` | ReservationStep | PaymentStep (nếu cần) |
| `paymentResult` | PaymentStep | ConfirmationStep, Orchestrator |
| `completedSteps` | Orchestrator | Compensation loop (đọc ngược) |
| `correlationId` | Orchestrator | ConfirmationStep (gắn vào event) |
| `metadata` | Developer (tùy ý) | Developer |

Trong async mode, `SagaContext` được serialize → lưu vào `SagaStateRepository`
để survive crash.

### 4. `repository/SagaStateRepository.java` — Persist saga state

Interface cho developer implement:

```java
void save(SagaContext<O> context)
Optional<SagaContext<O>> findByOrderId(String orderId)
void delete(String orderId)
List<SagaContext<O>> findByStatus(OrderStatus status)
```

| Saga Mode | Bắt buộc? | Lý do |
|-----------|-----------|-------|
| **Sync (P1/P2)** | Optional | Crash → client retry → Saga mới → OK |
| **Async (P3)** | **BẮT BUỘC** | Crash giữa Reserve và Payment → double charge risk |

Framework throw exception khi startup nếu async mode mà không có bean.

**Gợi ý implement:** Redis-backed (giữ DB ngoài critical path) hoặc
JSONB column trong bảng orders.

### 5. `step/ReservationStep.java` — Step 1: Giữ chỗ inventory

```
execute:    inventoryStrategy.reserve(resourceId, orderId, quantity)
            → set context.reservationResult
compensate: inventoryStrategy.release(resourceId, orderId, quantity)
            → nếu release fail: log error, Reconciliation fix
```

- Không retryable — reserve fail thường do hết hàng, retry vô nghĩa
- Compensate bọc try-catch — **không bao giờ throw** (tránh inventory leak)

### 6. `step/PaymentStep.java` — Step 2: Thanh toán

```
execute:    paymentGateway.charge(paymentRequest)
            → set context.paymentResult
compensate: paymentGateway.refund(refundRequest)
            → chỉ refund khi payment đã SUCCESS
            → nếu payment FAILED/TIMEOUT/UNKNOWN: no-op (không có gì để refund)
```

- `paymentRequestBuilder` (Function) nhận từ orchestrator — map tới
  abstract method `buildPaymentRequest(order)`
- `charge()` là `final` trong `AbstractPaymentGateway` — đã xử lý
  timeout detection + retry bên trong
- **refund() KHÔNG retry** (double refund nguy hiểm hơn refund failed)

**4 kết quả từ charge():**

| PaymentStatus | StepResult | Saga action |
|---------------|------------|-------------|
| SUCCESS | `success()` | Tiếp tục confirm |
| FAILED | `failed(PAYMENT_FAILED)` | Compensate + cancel |
| TIMEOUT | `failed(PAYMENT_TIMEOUT)` | Compensate + cancel |
| UNKNOWN | `failed(PAYMENT_UNKNOWN)` | Compensate + cancel, Reconciliation xử lý sau |

### 7. `step/ConfirmationStep.java` — Step 3: Publish event

```
execute:    eventBus.publish(OrderConfirmedEvent)
compensate: no-op (final step — không thể hoàn tác confirmation)
```

- State transition (RESERVED → CONFIRMED) và `saveOrder()` do **orchestrator**
  xử lý TRƯỚC khi gọi step này
- Step chỉ chịu trách nhiệm publish event

### 8. `orchestrator/AbstractSagaOrchestrator.java` — TRUNG TÂM MODULE

**Đọc kỹ file này nhất.** Template Method pattern điều phối toàn bộ flow.

#### Methods `final` — developer KHÔNG override:

| Method | Mô tả |
|--------|-------|
| `process(request)` | Flow chính: validate → create order → delegate subclass |
| `retryPayment(orderId)` | Retry payment cho order RESERVED |
| `adminCancel(orderId, reason)` | Cancel thủ công + compensate nếu cần |
| `getStatus(orderId)` | Check SagaStateRepository trước, fallback findOrder |

#### Methods developer BẮT BUỘC implement:

| Method | Mô tả |
|--------|-------|
| `createOrder(request)` | Tạo order entity từ request (set orderId = UUID) |
| `findOrder(orderId)` | Load order từ DB |
| `saveOrder(order)` | Persist order xuống DB |
| `buildPaymentRequest(order)` | Build PaymentRequest từ order |
| `onConfirmed(order)` | Callback sau khi confirm thành công |
| `onCancelled(order, reason)` | Callback sau khi cancel |

#### Lifecycle hooks (optional override):

```
onReserving → onPaymentProcessing → onConfirming → [onConfirmed]
                                                  → [onCancelling] → [onCancelled]
                                    → [onCompensating]
```

#### Compensation loop:

```java
// Chạy NGƯỢC danh sách completedSteps
for (int i = completed.size() - 1; i >= 0; i--) {
    String stepName = completed.get(i);
    switch (stepName) {
        case "payment"     -> paymentStep.compensate(context);     // refund
        case "reservation" -> reservationStep.compensate(context); // release
    }
}
```

#### cancelOrder flow:

```
RESERVED → COMPENSATING → CANCELLED  (nếu đang RESERVED)
PENDING  → CANCELLED                 (nếu đang PENDING)
+ markFailedWith(reason)
+ saveOrder
+ publish OrderCancelledEvent
+ onCancelled callback
+ cleanup SagaStateRepository
```

#### OrderAccessor — bridge pattern:

`AbstractOrder.transitionTo()` là package-private trong `io.hrc.core.domain`.
Saga nằm ở `io.hrc.saga` → không gọi được trực tiếp.

`OrderAccessor` (trong cùng package với AbstractOrder) là cầu nối:
```java
OrderAccessor.transitionTo(order, OrderStatus.RESERVED);
OrderAccessor.markFailedWith(order, FailureReason.PAYMENT_FAILED);
```

### 9. `orchestrator/sync/SynchronousSagaOrchestrator.java` — P1/P2

Flow đồng bộ — trả kết quả ngay trong HTTP request:

```
process(request)
  │
  ├─ validate + createOrder (PENDING)
  ├─ saveOrder (PENDING)              ← DB write #1
  │
  ├─ ReservationStep.execute()        ← DB lock (P1) hoặc version check (P2)
  │   └─ fail? → cancelOrder, return
  ├─ transition PENDING → RESERVED
  ├─ saveOrder (RESERVED)             ← DB write #2
  │
  ├─ PaymentStep.execute()            ← blocking, có timeout handler
  │   └─ fail? → compensate(release) → cancelOrder, return
  │
  ├─ transition RESERVED → CONFIRMED
  ├─ saveOrder (CONFIRMED)            ← DB write #3
  ├─ onConfirmed callback
  ├─ ConfirmationStep (publish event)
  │
  └─ return order (CONFIRMED)         → HTTP 201
```

**DB trong critical path** — mỗi request 3 DB writes + 1 DB lock.
Throughput bị giới hạn bởi DB.

### 10. `orchestrator/async/AsynchronousSagaOrchestrator.java` — P3

**Critical path (sync, nhanh — KHÔNG có DB):**

```
process(request)
  │
  ├─ validate + createOrder (PENDING, in-memory)
  │
  ├─ ReservationStep.execute()        ← Redis DECR atomic (<1ms)
  │   └─ fail? → cancel (in-memory), return
  ├─ transition PENDING → RESERVED
  │
  ├─ sagaStateRepository.save()       ← có thể Redis-backed
  ├─ eventBus.publish(OrderCreatedEvent)  ← Kafka/RabbitMQ
  │
  └─ return order (RESERVED)          → HTTP 202 ACCEPTED
```

**Async path (qua EventBus consumers):**

```
OrderCreatedEvent
  │
  └─ PaymentConsumer
       ├─ paymentGateway.charge()
       └─ orchestrator.handlePaymentResult(orderId, result)
            │
            ├─ SUCCESS:
            │   ├─ transition RESERVED → CONFIRMED
            │   ├─ saveOrder (lần đầu hit DB!)     ← DB write #1
            │   ├─ onConfirmed callback
            │   ├─ publish OrderConfirmedEvent
            │   └─ cleanup SagaStateRepository
            │
            └─ FAILED/TIMEOUT/UNKNOWN:
                ├─ compensate (release inventory)
                ├─ cancelOrder + saveOrder           ← DB write #1
                ├─ publish OrderCancelledEvent
                └─ cleanup SagaStateRepository
```

**Điểm then chốt:** DB chỉ bị hit SAU KHI payment xong (async).
Critical path = Redis + EventBus = 5,000–10,000 req/s.

**SagaStateRepository bắt buộc** — nếu thiếu, constructor throw:
```
FrameworkException: AsynchronousSaga requires a SagaStateRepository bean.
```

---

## So sánh Sync vs Async

| | Sync (P1/P2) | Async (P3) |
|--|:---:|:---:|
| HTTP response | 201 (CONFIRMED) | 202 (RESERVED) |
| DB trong critical path | **Có** (3 writes) | **Không** |
| Payment trong critical path | **Có** (blocking) | **Không** (async) |
| SagaStateRepository | Optional | **Bắt buộc** |
| Client biết kết quả | Ngay lập tức | Poll hoặc webhook |
| Throughput | ~1,000–5,000 req/s | ~5,000–10,000 req/s |

---

## Ví dụ developer sử dụng (Sync)

```java
public class ConcertTicketSaga
        extends SynchronousSagaOrchestrator<BookTicketRequest, ConcertOrder> {

    private final ConcertOrderRepository orderRepo;

    public ConcertTicketSaga(InventoryStrategy inventory,
                              PaymentGateway payment,
                              EventBus eventBus,
                              ConcertOrderRepository orderRepo) {
        super(inventory, payment, eventBus);
        this.orderRepo = orderRepo;
    }

    @Override
    protected ConcertOrder createOrder(BookTicketRequest request) {
        return new ConcertOrder(
            UUID.randomUUID().toString(),
            request.getResourceId(),
            request.getRequesterId(),
            request.getQuantity(),
            request.getIdempotencyKey()
        );
    }

    @Override
    protected ConcertOrder findOrder(String orderId) {
        return orderRepo.findById(orderId).orElse(null);
    }

    @Override
    protected ConcertOrder saveOrder(ConcertOrder order) {
        return orderRepo.save(order);
    }

    @Override
    protected PaymentRequest buildPaymentRequest(ConcertOrder order) {
        return PaymentRequest.builder()
            .transactionId(order.getOrderId())
            .amount(order.getTotalAmount())
            .currency("VND")
            .description("Vé concert: " + order.getResourceId())
            .build();
    }

    @Override
    protected void onConfirmed(ConcertOrder order) {
        // Gửi email xác nhận, update analytics...
    }

    @Override
    protected void onCancelled(ConcertOrder order, String reason) {
        // Gửi email thông báo hủy...
    }
}
```
