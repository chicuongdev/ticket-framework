# hcr-reconciliation — Module Architecture

## Module Purpose

**Safety net** của framework — chạy ngầm theo lịch (`@Scheduled`) để phát hiện và sửa chữa 5 loại inconsistency mà critical path có thể bỏ sót.

**Nguyên tắc cốt lõi:** reconciliation **gọi `paymentGateway.queryStatus()`** (HTTP sang ms-payment / cổng thanh toán thật) để xác định trạng thái payment — KHÔNG đọc DB local. Lý do: DB chỉ là audit log của ms-payment, cổng thanh toán mới là source of truth thực sự (Tình huống B: tiền đã trừ thật nhưng response mất → DB ghi FAILED nhưng gateway có SUCCESS record).


| # | Case | Mô tả | Áp dụng cho |
|---|---|---|---|
| 1 | **STALE_PENDING** | Order ở `PENDING` quá `getTimeoutMinutes()` (gateway không phản hồi hoặc consumer chậm) | All |
| 2 | **LATE_PAYMENT_SUCCESS** | Tiền đã trừ nhưng order bị cancel nhầm do timeout — phải refund hoặc unconfirm | All |
| 3 | **INVENTORY_MISMATCH** | Redis available ≠ DB available — Redis crash recovery, batch consumer mất data | **P3 only** |
| 4 | **UNPERSISTED_RESERVATION** | Order CONFIRMED nhưng DB inventory chưa giảm (event mất giữa Redis DECR và publish) | **P3 only** |
| 5 | **DUPLICATE_ORDER** | Hai+ order có cùng `idempotencyKey` (race rất hiếm) | All |

`AbstractReconciliationService<O>` là Template Method — `runReconciliation()` là `@Scheduled` final, developer implement các method abstract để cung cấp DB query cụ thể (vì framework không biết schema của developer).

Chỉ **1 instance được làm việc tại một thời điểm** nhờ Redisson distributed lock `hcr:reconciliation:lock`.

Phụ thuộc: `hcr-core`, `hcr-inventory`, `hcr-payment`, `hcr-eventbus` (publish `ReconciliationStartedEvent`, `ReconciliationFixedEvent`, `InventoryMismatchEvent`).

## Class / Structure Diagram (Mermaid Class)

```mermaid
classDiagram
    direction TB

    class AbstractReconciliationService~O~ {
      <<abstract>>
      -static String LOCK = "hcr:reconciliation:lock"
      #PaymentGateway paymentGateway
      #EventBus eventBus
      #ReconciliationMetrics metrics
      -OrderReconciler~O~ orderReconciler
      -InventoryReconciler inventoryReconciler   //nullable
      -RedissonClient redissonClient
      +runReconciliation() void  *@Scheduled final*
      #findStalePendingOrders(timeout)* List~O~
      #getPaymentTransactionId(O)* String
      #handleTimeout(O)* void
      #handleLatePaymentSuccess(O, PaymentResult)* void
      #handleUnresolvedPayment(O, PaymentVerificationResult)* void
      #handleInventoryMismatch(resourceId, redis, db)* void
      #findUnpersistedReservations()* List~O~
      #handleUnpersistedReservation(O)* void
      #findDuplicateOrders()* List~List~O~~
      #handleDuplicateOrders(List~O~)* void
      #getTimeoutMinutes() int
      #getScheduleDelayMs() long
      #getInventoryMismatchThreshold() long
      #getResourceIdsToReconcile() List~String~
    }

    class OrderReconciler~O~ {
      -PaymentGateway gateway
      -ReconciliationMetrics metrics
      +reconcileStalePendings(orders, handler) ReconciliationResult
      -classifyAndDispatch(O) ReconciliationCase
    }

    class InventoryReconciler {
      -InventoryStrategy strategy
      -RedissonClient redisson
      +compareRedisVsDb(resourceId) InventoryDelta
      +reconcileBatch(List~String~ ids) ReconciliationResult
    }

    class ReconciliationCase {
      <<enum>>
      STALE_PENDING
      LATE_PAYMENT_SUCCESS
      INVENTORY_MISMATCH
      UNPERSISTED_RESERVATION
      DUPLICATE_ORDER
    }

    class ReconciliationResult {
      <<value object>>
      +Map~ReconciliationCase, Integer~ casesFound
      +Map~ReconciliationCase, Integer~ casesFixed
      +int errorCount
      +Duration durationMs
      +List~String~ messages
    }

    class InventoryDelta {
      <<value object>>
      +String resourceId
      +long redisAvailable
      +long dbAvailable
      +long delta
      +boolean withinThreshold(long t)
    }

    class PaymentVerificationResult {
      <<value object>>
      +PaymentResult paymentResult
      +Instant verifiedAt
      +boolean isPaymentSuccess()
      +boolean isPaymentFailed()
      +boolean isPaymentUnresolvable()
      —— wrap PaymentResult tu gateway, expose 3 query helper<br/>tuong ung 3 nhanh runCase1And2
    }

    class ReconciliationMetrics {
      +recordCycleStarted()
      +recordCaseFound(case)
      +recordCaseFixed(case)
      +recordCycleCompleted(durationMs)
    }

    AbstractReconciliationService o-- OrderReconciler
    AbstractReconciliationService o-- InventoryReconciler : optional (P3)
    AbstractReconciliationService o-- ReconciliationMetrics
    AbstractReconciliationService ..> ReconciliationCase
    AbstractReconciliationService ..> ReconciliationResult
    OrderReconciler ..> PaymentVerificationResult
    InventoryReconciler ..> InventoryDelta

    AbstractReconciliationService <|-- DeveloperReconciliationService : developer extends
```

### `runReconciliation()` flow

```mermaid
flowchart TD
    Sched[Spring @Scheduled fires<br/>fixedDelay = scheduleDelayMs] --> Lock{Redisson tryLock<br/>30s wait}
    Lock -- not acquired --> Skip[Skip cycle: another instance running]
    Lock -- acquired --> Pub1[publish ReconciliationStartedEvent]
    Pub1 --> Case12[Case 1+2: STALE_PENDING / LATE_PAYMENT_SUCCESS]
    Case12 -.-> Q1[findStalePendingOrders]
    Case12 -.-> Q2[paymentGateway.queryStatus]
    Case12 --> Case3{inventoryReconciler != null?}
    Case3 -- no --> Case4
    Case3 -- yes --> Case3R[Case 3: INVENTORY_MISMATCH<br/>compareRedisVsDb across resourceIds]
    Case3R --> Case4[Case 4: UNPERSISTED_RESERVATION<br/>findUnpersistedReservations]
    Case4 --> Case5[Case 5: DUPLICATE_ORDER<br/>findDuplicateOrders]
    Case5 --> Pub2[publish ReconciliationFixedEvent per fix]
    Pub2 --> Met[metrics.recordCycleCompleted]
    Met --> Unlock[unlock]
```

### 5 case → handler mapping

```mermaid
sequenceDiagram
    participant ARS as AbstractReconciliationService
    participant Pay as PaymentGateway
    participant DB as PostgreSQL
    participant Redis as Redis
    participant Bus as EventBus

    rect rgb(254,242,242)
        Note over ARS: Case 1 + Case 2 — STALE_PENDING / LATE_PAYMENT_SUCCESS<br/>(3 nhánh)
        ARS->>DB: findStalePendingOrders(timeoutMin)
        loop for each PENDING/RESERVED order
            ARS->>Pay: queryStatus(txId)  *HTTP → ms-payment*
            Pay-->>ARS: PaymentVerificationResult(paymentResult)
            alt isPaymentSuccess()  (Case 2)
                ARS->>ARS: handleLatePaymentSuccess(order, result)
                Note over ARS: Tiền đã trừ → CONFIRMED, KHÔNG release
            else isPaymentFailed()  (Case 1)
                ARS->>ARS: handleTimeout(order)
                Note over ARS: Cổng xác nhận thất bại → release + CANCELLED
            else isPaymentUnresolvable()  (UNKNOWN / TIMEOUT / không hỏi được)
                ARS->>ARS: handleUnresolvedPayment(order, verification)
                Note over ARS: CHỈ log — KHÔNG cancel.<br/>Giữ order RESERVED, cycle sau verify lại.<br/>Lưới chốt cuối: order.expiresAt
            end
        end
    end

    rect rgb(254,243,199)
        Note over ARS: Case 3 — INVENTORY_MISMATCH (P3 only)
        loop for each resourceId
            ARS->>Redis: GET hcr:inventory:{id}
            ARS->>DB: SELECT available WHERE resource_id=?
            alt |delta| > threshold
                ARS->>ARS: handleInventoryMismatch
                ARS->>Bus: publish InventoryMismatchEvent
            end
        end
    end

    rect rgb(220,252,231)
        Note over ARS: Case 4 — UNPERSISTED_RESERVATION
        ARS->>DB: findUnpersistedReservations()
        loop
            ARS->>ARS: handleUnpersistedReservation (publish ResourceReservedEvent lại)
        end
    end

    rect rgb(224,231,255)
        Note over ARS: Case 5 — DUPLICATE_ORDER
        ARS->>DB: findDuplicateOrders() (group by idempotencyKey)
        loop each group with size > 1
            ARS->>ARS: handleDuplicateOrders(list)
        end
    end

    ARS->>Bus: publish ReconciliationFixedEvent (per fix)
```

## Capabilities (Provided to Devs)

| Capability | API | Khi dùng |
|---|---|---|
| Implement reconciliation service của project | `class TicketReconciliation extends AbstractReconciliationService<TicketOrder>` | Sample app |
| Auto schedule | `@Scheduled(fixedDelayString = "${hcr.reconciliation.schedule-delay-ms:300000}")` đã có sẵn trên `runReconciliation()` | Không cần tự đặt cron |
| Distributed lock | `Redisson` lock — tự động trong framework | Deploy nhiều instance không cần config thêm |
| 5 case detection | `OrderReconciler` + `InventoryReconciler` | Developer chỉ cần cung cấp query (find + handle methods) |
| **3-nhánh** quyết định Case 1+2 | abstract `handleUnresolvedPayment(O, PaymentVerificationResult)` | Product quyết định policy khi cổng thanh toán trả UNKNOWN/TIMEOUT — skip (default), alert, hoặc force-cancel sau N cycle |
| Tuỳ chỉnh interval | `getScheduleDelayMs()` override hoặc property `hcr.reconciliation.schedule-delay-ms` | Default 5 phút |
| Tuỳ chỉnh threshold mismatch | `getInventoryMismatchThreshold()` (default 0 = alert only) | Tránh false positive trong khi async sync đang catching up |
| Skip Case 3 (P1/P2) | Truyền `inventoryReconciler = null` cho constructor | DB là source-of-truth duy nhất |
| Resource list cho Case 3 | `getResourceIdsToReconcile()` — bắt buộc override khi P3 | Nếu trả empty list → skip Case 3 |
| Metrics | `ReconciliationMetrics` ghi `casesFound/casesFixed/duration` per cycle | Grafana dashboard `hcr_reconciliation_*` |
| Event hooks | Subscribe `ReconciliationStartedEvent`, `ReconciliationFixedEvent`, `InventoryMismatchEvent` | Alerting, audit log |

### Cấu hình điển hình

```yaml
hcr:
  reconciliation:
    enabled: true
    schedule-delay-ms: 300000           # 5 phút
    timeout-minutes: 5                  # PENDING quá 5 phút → STALE
    inventory-mismatch:
      threshold: 0                      # 0 = alert only
      auto-fix: false
    distributed-lock:
      lease-seconds: 60
      wait-seconds: 30
```

## To-Do / Detailed Implementation

| Hạng mục | Trạng thái | Ghi chú |
|---|---|---|
| `AbstractReconciliationService` template + `@Scheduled` | ✅ Implemented | |
| Redisson distributed lock | ✅ Implemented | |
| `OrderReconciler` (Case 1+2) | ✅ Implemented | Query `paymentGateway.queryStatus()` (HTTP sang ms-payment), không đọc DB |
| **3-nhánh decision trong `runCase1And2`** | ✅ Implemented | SUCCESS → confirm, FAILED → cancel, **UNRESOLVED → `handleUnresolvedPayment` (abstract)** — bỏ behavior cũ "huỷ oan order chỉ đang chậm" |
| `PaymentVerificationResult` với `isPaymentSuccess/Failed/Unresolvable` | ✅ Implemented | Wrap `PaymentResult` từ gateway, expose 3 helper phân loại |
| `InventoryReconciler` (Case 3) | ✅ Implemented | Optional, null khi P1/P2 |
| Case 4 / Case 5 abstract hooks | ✅ Implemented | |
| `ReconciliationMetrics` | ✅ Implemented | |
| Publish `ReconciliationFixedEvent` per fix | ✅ Implemented | |
| Auto resourceIds discovery | ❌ Chưa | Hiện developer phải override `getResourceIdsToReconcile()`. **TODO:** scan `AbstractInventoryEntity` table tự động |
| Pagination cho `findStalePendingOrders` | ⚠️ Cần verify | Nếu có 100k order PENDING (sự cố lớn), một query không giới hạn sẽ OOM. **TODO:** chunked query (LIMIT 1000) |
| Backoff khi gateway down | ⚠️ Partial | Khi `paymentGateway.queryStatus()` throw, hiện cycle break. **TODO:** circuit breaker cho gateway calls + skip Case 1+2 nếu gateway DOWN |
| Auto-fix Case 3 (đẩy Redis về đồng bộ DB) | ⚠️ Partial | Hiện default chỉ alert. Cần policy rõ: ưu tiên Redis (P3 source of truth) hay DB? Doc đang nghiêng về Redis. **TODO:** flag `inventory-mismatch.direction: redis-to-db | db-to-redis` |
| DLQ replay job | ❌ Chưa | Khi consumer fail event quá retry, event vào DLQ. **TODO:** scheduled job đọc DLQ → re-publish |
| Reconciliation history | ❌ Chưa | Lưu lịch sử cycle vào DB `hcr_reconciliation_runs` để audit |
| Manual trigger API | ❌ Chưa | Admin endpoint `/admin/reconcile/run` để chạy ngay không chờ schedule |

### Logic chi tiết cần implement

1. **Distributed lock fairness:**
   - Hiện dùng `tryLock(waitTime, leaseTime, TimeUnit)`. Khi instance nào đó bị "đói" (luôn miss lock), không ai phát hiện. **TODO:** alert nếu một instance không acquire trong N cycle liên tiếp (có thể clock skew).
2. **Case 4 logic chi tiết:**
   - "Unpersisted reservation" = order trong DB ở trạng thái CONFIRMED nhưng `concert_tickets.available` chưa giảm tương ứng (vì `ResourceReservedEvent` mất).
   - Cách phát hiện: query `WHERE EXISTS (SELECT 1 FROM orders WHERE order.confirmed_at < NOW - 1m AND NOT EXISTS (SELECT 1 FROM processed_events WHERE event_id = order.reservation_event_id))`.
   - Cách fix: re-publish `ResourceReservedEvent` với cùng `eventId` → consumer dedup không double-decrement.
3. **Case 5 (DUPLICATE_ORDER) policy:**
   - Khi tìm được nhiều order cùng `idempotencyKey`, KHÔNG được tự ý cancel ngẫu nhiên. Chính sách: **giữ order CONFIRMED, cancel + refund các order còn lại**. Nếu cả nhiều cùng CONFIRMED → manual intervention (alert + ghi vào audit log).
4. **Threshold cho `INVENTORY_MISMATCH`:**
   - Default 0 quá nghiêm trọng nếu batch consumer flush lag. **TODO:** dynamic threshold = max(10, 1% of total).
