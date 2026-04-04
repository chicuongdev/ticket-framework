# Hướng dẫn đọc code — hcr-core (Module 01)

> **Mục đích module:** Định nghĩa "ngôn ngữ chung" cho toàn bộ framework.
> Mọi module khác đều import từ đây. Không có dependency nào vào module HCR khác.

---

## Thứ tự đọc được đề xuất

### Bước 1 — Đọc Enums trước (không có dependency, đọc nhanh)

**1.1** `src/main/java/io/hrc/core/enums/OrderStatus.java`
- Đây là state machine của một order.
- Chú ý method `canTransitionTo()` — framework gọi method này trước mỗi lần đổi trạng thái order để ngăn transition sai.
- Chú ý method `isTerminal()` — CONFIRMED / CANCELLED / EXPIRED không thể đổi trạng thái nữa.

**1.2** `src/main/java/io/hrc/core/enums/ResourceStatus.java`
- Trạng thái của tài nguyên (khác với trạng thái order).
- Chú ý method `isAcceptingOrders()` — ACTIVE và LOW_STOCK vẫn nhận đặt hàng, DEPLETED và DEACTIVATED thì không.

**1.3** `src/main/java/io/hrc/core/enums/FailureReason.java`
- 8 lý do thất bại chuẩn hóa, dùng xuyên suốt toàn framework.
- Chú ý: `DUPLICATE_REQUEST` (không phải idempotency conflict), `RESERVATION_EXPIRED` (không phải resource deactivated).

**1.4** `src/main/java/io/hrc/core/enums/ConsistencyLevel.java`
- Chỉ có 2 giá trị: STRONG và EVENTUAL.
- Đọc Javadoc để hiểu cam kết của từng level trước khi đọc module Inventory.

---

### Bước 2 — Đọc Domain Objects (core abstraction)

**2.1** `src/main/java/io/hrc/core/domain/AbstractResource.java`
- Abstract class — developer phải extend, không được dùng trực tiếp.
- Chú ý các field framework quản lý: `resourceId`, `totalQuantity`, `availableQuantity`, `status`, `createdAt`, `updatedAt`.
- Chú ý các method `markLowStock()`, `markDepleted()` có visibility `package-private` — chỉ InventoryStrategy gọi được, developer không gọi trực tiếp.
- Hook `validate()` là nơi developer override để thêm business rule.

**2.2** `src/main/java/io/hrc/core/domain/AbstractOrder.java`
- Tương tự AbstractResource nhưng cho order.
- Chú ý field `idempotencyKey` — đây là chìa khóa chống duplicate request.
- Chú ý field `expiresAt` — Reconciliation dùng field này để tự cancel order hết hạn.
- Chú ý method `transitionTo()` có visibility `package-private` — gọi `canTransitionTo()` bên trong, throw `IllegalStateException` nếu transition sai.

**2.3** `src/main/java/io/hrc/core/domain/OrderRequest.java`
- Phân biệt với `AbstractOrder`: đây là input thô từ HTTP request, chưa được lưu DB.
- `AbstractOrder` là entity đã persist. `OrderRequest` là DTO vào Saga.
- Hook `validateRequest()` là nơi developer override để validate business rule của request.

**2.4** `src/main/java/io/hrc/core/domain/DomainEvent.java`
- Base class cho tất cả event publish lên Event Bus.
- Chú ý `eventType` được tự động set từ `getClass().getSimpleName()` — không cần set thủ công.
- Chú ý `correlationId` — field này được propagate từ Gateway qua tất cả event cùng request.
- Chú ý `retryCount` — framework tự tăng khi re-deliver, developer không set.

---

### Bước 3 — Đọc Result Objects (cách framework trả kết quả)

**3.1** `src/main/java/io/hrc/core/result/ReservationResult.java`
- Hiểu pattern: không throw exception cho expected outcome, dùng Result Object thay vào.
- Chú ý 3 factory methods: `success()`, `insufficient()`, `error()` — chỉ dùng các method này để tạo object.
- Chú ý `remainingAfter` chỉ có giá trị khi status = SUCCESS.

**3.2** `src/main/java/io/hrc/core/result/ValidationResult.java`
- Chú ý `ValidationError` inner class: field + message + rejectedValue.
- Chú ý method `merge()` — dùng để gộp validation từ nhiều tầng (framework + business rule).
- Chú ý method `throwIfInvalid()` — tiện lợi nhất khi dùng trong pipeline.
- Pattern dùng: `ValidationResult.ok().merge(validate1()).merge(validate2()).throwIfInvalid()`.

**3.3** `src/main/java/io/hrc/core/result/InventorySnapshot.java`
- Dùng `@Builder` — tạo bằng `InventorySnapshot.builder()...build()`.
- Chú ý field `source`: "redis" hoặc "database" — Reconciliation dùng để biết đang so sánh cái gì.
- Chú ý method `getDelta()` — trả về dương nếu snapshot này có nhiều hơn, âm nếu ít hơn.

---

### Bước 4 — Đọc Exceptions (cách framework báo lỗi)

**4.1** `src/main/java/io/hrc/core/exception/FrameworkException.java`
- Base exception. Chú ý 3 field: `reason` (FailureReason), `resourceId`, `orderId`.
- Developer catch một lần là xử lý được tất cả lỗi framework.

**4.2** Đọc lần lượt các subclass theo tên file:
- `InsufficientInventoryException` — có thêm `requestedQuantity` và `availableQuantity`.
- `PaymentException` — phân biệt theo `reason`: PAYMENT_FAILED / PAYMENT_TIMEOUT / PAYMENT_UNKNOWN.
- `IdempotencyException` — có thêm `idempotencyKey` để biết key nào bị duplicate.
- `ValidationException` — nhận `ValidationResult` vào constructor, tự build message từ danh sách lỗi.
- `ReconciliationException` — nghiêm trọng nhất, trigger alert, cần can thiệp thủ công.

---

## Sơ đồ dependency giữa các file trong module

```
ConsistencyLevel   OrderStatus   ResourceStatus   FailureReason
      │                 │               │               │
      └─────────────────┴───────────────┴───────────────┘
                                │
              ┌─────────────────┼──────────────────┐
              │                 │                  │
      AbstractResource    AbstractOrder       DomainEvent
              │                 │
              │          OrderRequest
              │
    ┌─────────┴──────────┐
    │                    │
ReservationResult  InventorySnapshot    ValidationResult
                                              │
                                       FrameworkException
                                              │
                          ┌───────────────────┼───────────────────┐
                          │                   │                   │
             InsufficientInventory...  PaymentException   ValidationException
             IdempotencyException      ReconciliationException
```

---

## Những điều cần nhớ sau khi đọc xong module này

1. **OrderStatus** có state machine — không thể nhảy tự do giữa các trạng thái.
2. **AbstractResource và AbstractOrder** đều là abstract — developer bắt buộc phải extend.
3. **OrderRequest ≠ AbstractOrder** — request là input, order là entity đã persist.
4. **FailureReason** là chuẩn hóa toàn framework — không dùng String tự định nghĩa.
5. **ValidationResult** có `merge()` — dùng khi cần gộp nhiều validation lại.
6. **InventorySnapshot.source** phân biệt "redis" vs "database" — quan trọng cho Reconciliation.
