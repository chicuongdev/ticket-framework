# Hướng dẫn đọc code — hcr-inventory (Module 02)

> **Mục đích module:** Giải quyết bài toán cốt lõi — làm thế nào để nhiều người đồng thời
> đặt tài nguyên mà không bị oversell. Cung cấp 3 strategy với mức độ throughput và
> consistency khác nhau.
>
> **Dependency:** `hcr-core`, `hcr-eventbus`. Đọc `hcr-core/GUIDE.md` trước.

---

## B��c tranh tổng thể trước khi ��ọc code

```
                    Saga gọi qua interface
                            │
                    InventoryStrategy  ◄──────── interface duy nhất Saga biết
                            │
          ┌───────────��─────┼─────────────────┐
          │                 │                 ��
   Pessimistic         Optimistic          Redis
   LockStrategy       LockStrategy      AtomicStrategy
   (SELECT FOR          (version +         (Lua script
    UPDATE)              retry)             + async DB)
          │                 │                 │
          └─────────────────┴──��──────────────┘
                            │
                   có thể wrap bằng
                            │
             CircuitBreakerInventoryDecorator
```

**DB layer (P1/P2/P3):**
```
Developer's entity (extends AbstractInventoryEntity)
    ↕ EntityManager.find() / merge()
Strategy thao tác trực tiếp trên bảng developer — KHÔNG CÓ BẢNG RIÊNG CỦA FRAMEWORK
```

**Async DB sync (P3 only):**
```
RedisAtomicStrategy
    │ eventBus.publish() (Kafka/RabbitMQ — persistent, crash-safe)
    ▼
ResourceReservedEvent / ResourceReleasedEvent
    │ EventBus consumer
    ▼
InventoryPersistenceConsumer
    │ BEGIN TRANSACTION
    │   INSERT hcr_processed_events (eventId dedup)
    │   UPDATE developer_table SET available -= qty
    │ COMMIT → ACK
    ▼
Developer's table (EntityManager)
```

---

## Thứ tự đọc được đề xuất

### Bước 1 — Đọc Interface trước (hợp đồng toàn module)

**1.1** `src/main/java/io/hrc/inventory/strategy/InventoryStrategy.java`
- Đây là file quan trọng nhất của module — đọc kỹ trư��c khi đọc bất cứ thứ gì khác.
- Chú ý 4 nhóm method:
  - **Core**: `reserve()` và `release()` — hai thao tác chính.
  - **Query**: `getAvailable()`, `isAvailable()`, `getSnapshot()`.
  - **Management**: `initialize()`, `restock()`, `deactivate()`.
  - **Bulk**: `reserveBatch()`, `releaseBatch()` — cho flash sale nhiều sản phẩm.
- Chú ý `reserve()` trả về `ReservationResult` (không throw exception) — Result Object pattern.

---

### Bước 2 — Đọc AbstractInventoryEntity (developer extend class này)

**2.1** `src/main/java/io/hrc/inventory/entity/AbstractInventoryEntity.java`
- `@MappedSuperclass` — developer extend thành entity của mình:
  ```java
  @Entity
  @Table(name = "concert_tickets")
  public class ConcertTicket extends AbstractInventoryEntity {
      private String concertName;  // developer thêm field riêng
  }
  ```
- Framework thao tác trực tiếp trên field `available`, `total`, `version` của entity developer.
- **KHÔNG CÓ B���NG RIÊNG CỦA FRAMEWORK** — chỉ có bảng developer.
- Developer muốn tên cột khác? Dùng `@AttributeOverride` hoặc `@Column(name = "...")`.
- Chú ý `@Version` trên field `version` — P2 dùng, Hibernate tự kiểm tra khi update.

---

### Bước 3 — Đọc 3 Strategy theo thứ tự tăng dần độ phức tạp

**3.1** `src/main/java/io/hrc/inventory/strategy/pessimistic/PessimisticLockStrategy.java`
- Đơn giản nhất. Đọc method `reserve()` trước.
- Chú ý: dùng `entityManager.find(entityClass, resourceId, PESSIMISTIC_WRITE)` để lock row.
- Flow trong `transactionTemplate.execute()`:
  1. find with pessimistic lock → lock row trên bảng developer
  2. check `available >= quantity`
  3. update available → merge
  4. publish event
- Chú ý `reserveBatch()`: keys sort alphabet → chống deadlock.

**3.2** `src/main/java/io/hrc/inventory/strategy/optimistic/OptimisticLockStrategy.java`
- Phức tạp hơn P1 vì có retry loop.
- Ch�� ý `doReserveInTransaction()` — transaction mới mỗi retry (tránh Hibernate cache version cũ).
- Chú ý `entityManager.flush()` sau merge — trigger version check ngay, bắt conflict sớm.
- Chú ý `computeBackoff()` — exponential backoff + random jitter.

**3.3** `src/main/java/io/hrc/inventory/strategy/redis/RedisAtomicStrategy.java`
- Phức tạp nhất. 2 thay đổi quan trọng:
  - **V1:** Dùng EntityManager thay vì InventoryRecordRepository.
  - **V2A:** Dùng EventBus (persistent) thay Spring Event (fire-and-forget) cho DB sync.
- `reserve()` flow: Lua script → `eventBus.publish()` (cho DB sync) → Spring event (cho low stock notification).
- Known limitation: gap giữa Redis DECR thành công và EventBus.publish() → Reconciliation fix ≤ 5 phút.

---

### Bước 4 — Đọc Lua Scripts (cơ chế atomic của P3)

**4.1** `src/main/resources/lua/inventory_reserve.lua`
- Cốt lõi P3. Redis đảm bảo script chạy atomically — zero oversell.
- 3 return codes: `>= 0` (remaining), `-1` (chưa init), `-2` (insufficient).

**4.2** `src/main/resources/lua/inventory_release.lua`
- Guard: sau INCRBY nếu > totalQuantity → set về totalQuantity (chống double-release).

---

### Bước 5 — Đọc Decorator (Circuit Breaker)

**5.1** `src/main/java/io/hrc/inventory/decorator/CircuitBreakerInventoryDecorator.java`
- Decorator Pattern — wrap bất kỳ strategy nào.
- `release()` khi CB OPEN: KHÔNG reject — tránh inventory leak.

---

### Bước 6 — Đọc các file hỗ trợ

**6.1** `src/main/java/io/hrc/inventory/factory/InventoryStrategyFactory.java`
- Nhận `EntityManager` + `entityClass` (thay vì InventoryRecordRepository).
- P3 yêu cầu cả `EventBus` bean.
- `registerCustomStrategy()` — developer inject strategy riêng.

**6.2** `src/main/java/io/hrc/inventory/initializer/InventoryInitializer.java`
- Chỉ dùng cho P3. Dùng JPQL generic: `"SELECT e FROM " + entityClass.getSimpleName()`.
- `verify()` so sánh Redis vs DB.

**6.3** `src/main/java/io/hrc/inventory/persistence/InventoryPersistenceConsumer.java`
- **Thay đổi lớn:** Dùng EventBus consumer + eventId deduplication.
- Idempotency: INSERT `hcr_processed_events` + UPDATE available trong cùng 1 transaction.
  Nếu eventId trùng → DataIntegrityViolationException → skip UPDATE → ACK.
- **Tại sao `WHERE available >= delta` KHÔNG đủ:** available=100, reserve 2, redeliver → 98 → 96 (trừ 2 lần!).

**6.4** `src/main/java/io/hrc/inventory/persistence/ProcessedEvent.java`
- Entity cho bảng `hcr_processed_events` — chỉ lưu eventId + eventType + processedAt.

---

## Những điều cần nhớ sau khi đ���c xong module này

1. **Saga chỉ biết `InventoryStrategy` interface** — không biết đang dùng P1/P2/P3.
2. **KHÔNG CÓ BẢNG RIÊNG CỦA FRAMEWORK** — strategy thao tác trực tiếp trên bảng developer qua EntityManager.
3. **Developer extend `AbstractInventoryEntity`** và thêm field nghiệp vụ riêng.
4. **P1/P2 dùng `TransactionTemplate`** trực tiếp, không dùng `@Transactional`.
5. **P2 phải t��o transaction mới mỗi retry** + flush() để trigger version check sớm.
6. **P3 dùng EventBus** (persistent) cho DB sync, KHÔNG dùng Spring @EventListener (fire-and-forget).
7. **Idempotency thật sự qua eventId** (bảng `hcr_processed_events`), không phải `WHERE available >= delta`.
8. **CB `release()` không reject khi OPEN** — để tránh inventory leak.
