# hcr-reconciliation

> Drift detection + auto-fix giữa Redis và Postgres, cleanup order treo.

---

## 1. Vai trò trong framework

`hcr-reconciliation` là **safety net** chạy nền — tuần hoàn so sánh trạng thái giữa các source và sửa drift. Đây là module bù lại cho **trade-off eventual consistency của P3** và mọi crash window khác trong hệ thống bất đồng bộ.

Hai loại reconciliation:

1. **Inventory** — Redis vs Postgres `available_quantity` lệch → sync lại
2. **Order** — order RESERVED quá `expiresAt` → cancel + release inventory; order PENDING không có saga state → resume hoặc cleanup

Cam kết SLA: **drift fix ≤ 5 phút** (cycle interval mặc định 60s).

---

## 2. Tại sao cần module này?

Async system có nhiều "crash window" mà framework không thể atomic 100%:

| Crash window | Hậu quả nếu không fix | HCR fix qua |
|--------------|----------------------|-------------|
| Redis `DECRBY` thành công nhưng `EventBus.publish()` fail | Slot mất, không bao giờ persist xuống DB | `InventoryReconciler` so sánh Redis vs DB |
| Batch persistence ACK trước flush, crash giữa | Data loss tại DB nhưng Redis đã trừ | `InventoryReconciler` |
| Order RESERVED nhưng payment timeout không phản hồi | Order kẹt PENDING, slot không release | `OrderReconciler` cancel khi expired |
| Saga crash giữa reserve và publish | Order PENDING, không có event | `OrderReconciler` quét pending + verify |
| Payment gateway trả UNKNOWN | Không biết succeed hay fail | `verifyPayment()` query lại gateway |

Không có reconciliation, framework chỉ "đa số đúng". Có reconciliation, framework cam kết **eventually consistent ≤ 5 phút**.

---

## 3. Nguyên lý thiết kế

| Nguyên lý | Áp dụng |
|-----------|---------|
| **Template Method** | `AbstractReconciliationService` — schedule + lock + metric; subclass điền compare + fix |
| **Compare-then-Fix** | Đọc snapshot 2 nguồn, tính delta, fix theo policy (Redis là truth cho available, DB là truth cho metadata) |
| **Distributed Lock** | Chỉ 1 instance chạy reconciliation tại 1 thời điểm (Redisson lock) |
| **Idempotent Fix** | Mỗi cycle có thể chạy lại an toàn — fix đã apply không gây ảnh hưởng |
| **Bounded SLA** | `hcr.reconciliation.interval=60s` — dev biết drift max là 60s |
| **Case classification** | `ReconciliationCase` enum chuẩn hoá 8 case có thể gặp → metric chia theo case |
| **Manual escalation** | Drift quá ngưỡng → throw `ReconciliationException` → trigger alert, không tự fix |
| **Event publish** | `ReconciliationStartedEvent`, `Fixed`, `InventoryMismatchEvent` để observability |

---

## 4. Class diagram

```mermaid
classDiagram
    direction TB

    class AbstractReconciliationService {
        <<abstract>>
        #ReconciliationMetrics metrics
        #EventBus eventBus
        #RedissonClient redisson
        +reconcile() ReconciliationResult
        +detectDrift()* List
        +applyFix(case)*
    }

    class InventoryReconciler {
        -InventoryStrategy redisInventory
        -EntityManager db
        +detectDrift() — compare Redis vs DB
        +applyFix(InventoryDelta)
    }

    class OrderReconciler {
        -PaymentGateway gateway
        +verify(order, txId) PaymentVerificationResult
        —— call gateway.queryStatus() → HTTP sang ms-payment
    }

    class ReconciliationCase {
        <<enumeration>>
        STALE_PENDING
        LATE_PAYMENT_SUCCESS
        INVENTORY_MISMATCH
        UNPERSISTED_RESERVATION
        DUPLICATE_ORDER
    }

    class ReconciliationResult {
        +int casesFound
        +int casesFixed
        +int casesEscalated
        +Map~ReconciliationCase,Integer~ byCase
        +Duration duration
    }

    class InventoryDelta {
        +String resourceId
        +int redisAvailable
        +int dbAvailable
        +int delta
        +ReconciliationCase classify()
    }

    class PaymentVerificationResult {
        +PaymentResult paymentResult
        +Instant verifiedAt
        +boolean isPaymentSuccess()
        +boolean isPaymentFailed()
        +boolean isPaymentUnresolvable()
    }

    class AbstractReconciliationServiceHooks {
        <<abstract methods>>
        #handleTimeout(O order)
        #handleLatePaymentSuccess(O, PaymentResult)
        #handleUnresolvedPayment(O, PaymentVerificationResult)
        #handleInventoryMismatch(resourceId, redis, db)
        #handleUnpersistedReservation(O)
        #handleDuplicateOrders(List~O~)
    }

    class ReconciliationMetrics {
        +recordCycle(long, ReconciliationResult)
        +recordCase(ReconciliationCase)
        +recordEscalation()
    }

    class ReconciliationException {
        <<from hcr-core>>
    }

    InventoryReconciler --|> AbstractReconciliationService
    OrderReconciler --|> AbstractReconciliationService
    AbstractReconciliationService ..> ReconciliationResult
    AbstractReconciliationService ..> ReconciliationMetrics
    AbstractReconciliationService ..> ReconciliationException
    InventoryReconciler ..> InventoryDelta
    InventoryDelta ..> ReconciliationCase
    OrderReconciler ..> PaymentVerificationResult
    OrderReconciler ..> ReconciliationCase

    class EventBus { <<from hcr-eventbus>> }
    class InventoryStrategy { <<from hcr-inventory>> }
    AbstractReconciliationService ..> EventBus
    InventoryReconciler ..> InventoryStrategy
```

---

## 5. Thành phần chính

| Package | Thành phần | Vai trò |
|---------|-----------|---------|
| `(root)` | `AbstractReconciliationService`, `ReconciliationCase`, `ReconciliationMetrics` | Base + enum case + metric |
| `inventory` | `InventoryReconciler` | So sánh Redis vs DB |
| `order` | `OrderReconciler` | Cleanup expired / stuck order |
| `model` | `InventoryDelta`, `PaymentVerificationResult`, `ReconciliationResult` | DTO |

---

## 6. Policy fix

### 6.1. Case 1+2 — STALE_PENDING / LATE_PAYMENT_SUCCESS (3 nhánh)

Reconciliation **gọi `paymentGateway.queryStatus()`** để hỏi cổng thanh toán (qua HTTP sang ms-payment), **KHÔNG đọc DB** — vì DB chỉ là audit log, cổng thanh toán mới là source of truth cho `actualStatus`.

| `PaymentVerificationResult` | Hook gọi | Hành vi |
|---|---|---|
| `isPaymentSuccess()` (Case 2 LATE_PAYMENT_SUCCESS) | `handleLatePaymentSuccess(order, result)` | Tiền đã trừ — order `RESERVED` → `CONFIRMED`. **KHÔNG release inventory** |
| `isPaymentFailed()` (Case 1 STALE_PENDING) | `handleTimeout(order)` | Cổng xác nhận thất bại — `release()` trả vé + order → `CANCELLED` |
| `isPaymentUnresolvable()` (UNKNOWN/TIMEOUT/không hỏi được gateway) | `handleUnresolvedPayment(order, verification)` | **CHỈ log — KHÔNG cancel.** Giữ order `RESERVED`, cycle reconciliation sau verify lại |

> **Cải tiến framework so với phiên bản trước:** `runCase1And2` cũ chỉ có 2 nhánh (SUCCESS → confirm, *mọi nhánh khác* → cancel) → huỷ oan order mà payment chỉ đang chậm. Nay có 3 nhánh, nhánh thứ 3 là **abstract hook** (`handleUnresolvedPayment`) — product tự quyết logic: skip, alert, hay force-cancel sau N cycle.

### 6.2. Case 3/4/5 — inventory + duplicate

| Case | Áp dụng | Fix |
|------|---------|-----|
| **INVENTORY_MISMATCH** (Case 3) | P3 only | So sánh Redis vs DB `available`; lệch > threshold → alert hoặc auto-fix theo policy `inventory-mismatch.direction` |
| **UNPERSISTED_RESERVATION** (Case 4) | P3 only | Order CONFIRMED nhưng DB inventory chưa giảm → re-publish `ResourceReservedEvent` với cùng `eventId` (consumer dedup, không double-decrement) |
| **DUPLICATE_ORDER** (Case 5) | All | Group by `idempotencyKey`. Giữ order ưu tiên cao nhất (CONFIRMED > RESERVED > PENDING), cancel + refund phần còn lại |

---

## 7. Liên kết

- Chi tiết đọc code → [`GUIDE.md`](GUIDE.md)
- Thiết kế tổng → [`../docs/framework_design.md`](../docs/framework_design.md) §8
