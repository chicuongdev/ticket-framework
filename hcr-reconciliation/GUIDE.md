# hcr-reconciliation — Hướng dẫn đọc code

> Module 07 — Safety net: phát hiện và sửa 5 loại inconsistency định kỳ.
> BẮTBUỘC khi dùng P3. Tùy chọn khi dùng P1/P2 (chỉ để xử lý payment edge case).

---

## Đọc code theo thứ tự này

### Bước 1 — Hiểu 5 loại inconsistency

**`ReconciliationCase.java`**

5 enum value tương ứng 5 kịch bản crash trong distributed system:
- `STALE_PENDING` — payment gateway crash, order treo PENDING mãi
- `LATE_PAYMENT_SUCCESS` — tiền đã trừ nhưng order bị cancel nhầm (NGUY HIỂM)
- `INVENTORY_MISMATCH` — Redis AOF crash, inventory count bị lệch (P3 only)
- `UNPERSISTED_RESERVATION` — consumer crash sau ACK nhưng trước flush
- `DUPLICATE_ORDER` — idempotency layer bị bypass

---

### Bước 2 — Data classes

**`model/InventoryDelta.java`**

Kết quả so sánh Redis vs DB. Chú ý quy ước dấu của `delta`:
- `delta > 0`: Redis cao hơn DB → Redis bị lỗi (sau AOF restore) → cần fix Redis xuống
- `delta < 0`: DB cao hơn Redis → DB lag bình thường (P3 eventual) → không cần fix
- `delta == 0`: nhất quán

Factory method `InventoryDelta.of(resourceId, redis, db)` tính sẵn delta và hasMismatch.

**`model/PaymentVerificationResult.java`**

Kết quả gọi `paymentGateway.queryStatus()` cho 1 order. 3 convenience methods:
- `isPaymentSuccess()` → Case 2 (Late Payment Success): confirm lại
- `isPaymentFailed()` → Case 1 (Stale Pending): cancel
- `isPaymentUnresolvable()` → Case 1: cancel + alert manual review

**`model/ReconciliationResult.java`**

Tổng kết sau 1 reconciliation cycle. Được ghi vào metrics và expose qua `/actuator/hcr`.
`getSuccessRate()` = totalFixed / (totalFixed + totalFailed). 1.0 = clean cycle.

---

### Bước 3 — 2 Handler classes

**`inventory/InventoryReconciler.java`**

Chuyên xử lý Case 3 (INVENTORY_MISMATCH). Flow:

```
compare(resourceId)
  → GET hcr:inventory:{resourceId} từ Redis (raw StringRedisTemplate, không qua strategy)
  → entityManager.find(entityClass, resourceId) từ DB
  → trả về InventoryDelta

reconcile(resourceId)
  → compare() → nếu hasMismatch → handleMismatch()
      → publish InventoryMismatchEvent
      → nếu delta > 0 (Redis cao hơn DB) → gọi autoFix()
          → kiểm tra threshold: 0 = alert only, > 0 = auto-fix nếu delta <= threshold
          → fix = SET Redis key về giá trị DB trực tiếp
          → publish ReconciliationFixedEvent
      → nếu delta < 0 (DB lag) → skip (bình thường)
```

**Constructor cần**: inventoryStrategy, redisTemplate, entityManager, entityClass, eventBus, metrics, mismatchThreshold.

**`order/OrderReconciler.java`**

Chuyên xử lý Case 1+2 bằng cách verify payment. Flow:

```
reconcile(staleOrders, transactionIdExtractor)
  → với mỗi order: gọi verify(order, transactionId)

verify(order, transactionId)
  → paymentGateway.queryStatus(transactionId)
  → wrap vào PaymentVerificationResult
  → exception → verified=false (không throw, caller tự handle)
```

Chú ý: OrderReconciler chỉ **verify** — không **xử lý**. Action thực tế do
`AbstractReconciliationService` dispatch sang `handleTimeout()` hoặc `handleLatePaymentSuccess()`.

---

### Bước 4 — Core: AbstractReconciliationService

**`AbstractReconciliationService.java`**

Template Method pattern. Đọc theo thứ tự:

1. **Constructor**: 2 overloads — đầy đủ (có InventoryReconciler cho P3) và rút gọn (P1/P2)
2. **`runReconciliation()`** — `@Scheduled`, `final`:
   - Acquire Redisson distributed lock `hcr:reconciliation:lock` (tryLock không chờ)
   - Gọi 4 case runners: `runCase1And2`, `runCase3`, `runCase4`, `runCase5`
   - Build `ReconciliationResult`, ghi metrics, log
   - Release lock trong finally
3. **4 case runners** (private): mỗi case được bọc try/catch riêng, lỗi 1 case không stop các case khác
4. **9 abstract methods**: developer phải implement theo nghiệp vụ
5. **4 config overrides**: getTimeoutMinutes, getScheduleDelayMs, getInventoryMismatchThreshold, getResourceIdsToReconcile

**Quan trọng khi implement:**
- `getPaymentTransactionId(order)` — thường là `order.getOrderId()`
- `getResourceIdsToReconcile()` — **bắt buộc override** nếu dùng P3
- `handleLatePaymentSuccess()` — xử lý cẩn thận: kiểm tra còn inventory không trước khi reconfirm
- `findUnpersistedReservations()` — query order CONFIRMED mà DB inventory vẫn chưa update

---

## ReconciliationMetrics interface

`ReconciliationMetrics.java` — 3 methods + `NO_OP` inner. Tương tự InventoryMetrics.
Được implement bởi `hcr-observability` module sau này.

---

## Cách developer sử dụng

```java
@Service
public class ConcertReconciliationService
        extends AbstractReconciliationService<ConcertOrder> {

    private final ConcertOrderRepository orderRepository;
    private final InventoryStrategy inventoryStrategy;

    public ConcertReconciliationService(
            PaymentGateway paymentGateway,
            InventoryStrategy inventoryStrategy,
            EventBus eventBus,
            RedissonClient redissonClient,
            StringRedisTemplate redisTemplate,
            EntityManager entityManager) {
        super(paymentGateway,
              // P3 → truyền InventoryReconciler. P1/P2 → truyền null.
              new InventoryReconciler(inventoryStrategy, redisTemplate, entityManager,
                                     ConcertTicket.class, eventBus,
                                     ReconciliationMetrics.NO_OP, 5L),
              eventBus, redissonClient, ReconciliationMetrics.NO_OP);
        this.orderRepository = orderRepository;
        this.inventoryStrategy = inventoryStrategy;
    }

    @Override
    protected List<ConcertOrder> findStalePendingOrders(int timeoutMinutes) {
        return orderRepository.findByStatusAndUpdatedAtBefore(
            OrderStatus.PENDING,
            Instant.now().minus(timeoutMinutes, ChronoUnit.MINUTES));
    }

    @Override
    protected String getPaymentTransactionId(ConcertOrder order) {
        return order.getOrderId(); // hoặc order.getPaymentTransactionId()
    }

    @Override
    protected void handleTimeout(ConcertOrder order) {
        order.setStatus(OrderStatus.CANCELLED);
        order.setFailureReason(FailureReason.PAYMENT_TIMEOUT);
        orderRepository.save(order);
        inventoryStrategy.release(order.getResourceId(), order.getOrderId(), order.getQuantity());
    }

    @Override
    protected void handleLatePaymentSuccess(ConcertOrder order, PaymentResult result) {
        // Cẩn thận: kiểm tra inventory còn không
        if (inventoryStrategy.isAvailable(order.getResourceId(), order.getQuantity())) {
            order.setStatus(OrderStatus.CONFIRMED);
            orderRepository.save(order);
            // gửi vé
        } else {
            // refund + email xin lỗi
            paymentGateway.refund(new RefundRequest(result.getGatewayTransactionId(), ...));
        }
    }

    @Override
    protected void handleInventoryMismatch(String resourceId, long redis, long db) {
        log.error("Inventory mismatch detected! resourceId={}, redis={}, db={}", resourceId, redis, db);
        // alert ops team, Slack notification, etc.
    }

    @Override
    protected List<ConcertOrder> findUnpersistedReservations() {
        return Collections.emptyList(); // implement nếu cần
    }

    @Override
    protected void handleUnpersistedReservation(ConcertOrder order) { /* ... */ }

    @Override
    protected List<List<ConcertOrder>> findDuplicateOrders() {
        return Collections.emptyList(); // implement nếu cần
    }

    @Override
    protected void handleDuplicateOrders(List<ConcertOrder> duplicates) { /* ... */ }

    @Override
    protected List<String> getResourceIdsToReconcile() {
        return concertRepository.findActiveResourceIds(); // P3 bắt buộc override
    }
}
```

---

## Config YAML

```yaml
hcr:
  reconciliation:
    schedule-delay-ms: 300000  # 5 phút (default)
    timeout-minutes: 5          # order PENDING quá 5 phút = stale (dùng ở AbstractSagaOrchestrator)
```

---

## Design decisions

| Quyết định | Lý do |
|-----------|-------|
| InventoryReconciler dùng StringRedisTemplate trực tiếp | Bypass strategy layer → đọc raw value, không bị cached hay transformed |
| autoFix chỉ fix khi delta > 0 | delta < 0 là DB lag bình thường (P3) — fix sẽ undo reservation hợp lệ |
| mismatchThreshold default = 0 | Alert-only mặc định. Developer tự quyết có nên auto-fix không |
| Distributed lock tryLock không chờ | Nếu instance khác đang chạy → skip, không block. Tránh pile-up khi cycle chậm |
| Case runner bọc try/catch riêng | Lỗi Case 3 không dừng Case 4+5. Mỗi case độc lập |
| InventoryReconciler là @Nullable | P1/P2 không cần. Null = skip Case 3 hoàn toàn |
