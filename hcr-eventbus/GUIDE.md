# Hướng dẫn đọc code — hcr-eventbus (Module 05)

> **Mục đích module:** Cung cấp abstraction layer cho messaging giữa các thành phần.
> Framework cam kết at-least-once delivery cho tất cả 4 adapter.
> Developer chỉ config loại adapter — code không thay đổi.
>
> **Dependency:** `hcr-core`. Đọc `hcr-core/GUIDE.md` trước.

---

## Bức tranh tổng thể trước khi đọc code

```
           Publisher (Saga, ReconciliationService...)
                          │
                    EventBus  ◄──── interface duy nhất publisher biết
                          │
         ┌────────────────┼────────────────┐
         │                │                │                │
   Kafka          RabbitMQ        RedisStream          InMemory
   Adapter         Adapter          Adapter             Adapter
   (production,    (general         (khi đã có          (unit test
    high-load)      purpose)         Redis)              only)
         │                │                │                │
         └────────────────┴────────────────┴────────────────┘
                          │
                  AbstractEventBusAdapter
                  (handler registry, dispatch, batch)
                          │
                  EventHandler<T>  ◄──── developer implement
                  handle(event, ack)
                          │
                  Acknowledgment
                  (acknowledge / reject / reject(false))
```

**Event routing:**
```
DomainEvent class → EventDestination.forEventType() → adapter-specific destination
OrderCreatedEvent → "order-created" → Kafka: "hcr.order-created"
                                    → RabbitMQ: routing-key "order-created"
                                    → Redis: "hcr:stream:order-created"
```

---

## Thứ tự đọc được đề xuất

### Bước 1 — Đọc các Interface và Value Object (hợp đồng của module)

**1.1** `src/main/java/io/hrc/eventbus/EventBus.java`
- Interface quan trọng nhất — đọc kỹ trước khi đọc bất cứ thứ gì khác.
- Chú ý 7 method:
  - `publish(event)` — routing tự động từ event type.
  - `publish(event, destination)` — override routing.
  - `publishIdempotent(event, key)` — đảm bảo không publish 2 lần cùng key.
  - `publishBatch(events)` — batch publish hiệu quả hơn.
  - `subscribe(eventType, handler)` — dynamic subscription.
  - `unsubscribe(eventType, handler)` — hủy đăng ký.
  - `getCapabilities()` — query capabilities của adapter đang dùng.

**1.2** `src/main/java/io/hrc/eventbus/EventHandler.java`
- Developer implement interface này để xử lý event.
- Chú ý 2 method:
  - `handle(event, ack)` — PHẢI idempotent (at-least-once delivery).
  - `onDeadLetter(event, cause)` — default no-op, override để alert.
- Chú ý: gọi `ack.acknowledge()` SAU KHI side effects đã persist.

**1.3** `src/main/java/io/hrc/eventbus/Acknowledgment.java`
- Abstraction che giấu broker-specific ack API.
- Chú ý 3 method:
  - `acknowledge()` — commit/ack/XACK tuỳ adapter.
  - `reject()` — retry (requeue=true).
  - `reject(false)` — dead letter (không retry nữa).

**1.4** `src/main/java/io/hrc/eventbus/EventDestination.java`
- Value object cho topic/queue/stream name — không dùng String trực tiếp.
- Chú ý 2 factory methods:
  - `of(name)` — tạo với tên cụ thể.
  - `forEventType(eventClass)` — tự derive từ class name (`OrderCreatedEvent` → `"order-created"`).

**1.5** `src/main/java/io/hrc/eventbus/EventBusCapabilities.java`
- Khai báo 7 capability flags cho mỗi adapter.
- Chú ý bảng capability — ghi nhớ: InMemory là synchronous + exactly-once,
  Kafka đầy đủ nhất, RabbitMQ không có replay, Redis Streams không có partitioning.
- Chú ý 4 static factory methods: `kafka()`, `rabbitMQ()`, `redisStream()`, `inMemory()`.

---

### Bước 2 — Đọc AbstractEventBusAdapter (base class)

**2.1** `src/main/java/io/hrc/eventbus/adapter/AbstractEventBusAdapter.java`
- Cung cấp shared logic cho tất cả adapters — đọc trước khi đọc bất kỳ adapter nào.
- Chú ý `handlerRegistry` — `ConcurrentHashMap<eventType, List<handler>>`.
- Chú ý `subscribe()` + `unsubscribe()` — default implementations dùng handler registry.
- Chú ý `dispatch(event, ack)` — gọi TẤT CẢ handlers đã đăng ký cho event type.
  Nếu không có handler nào → auto-acknowledge (không để message treo).
- Chú ý `warnIfNotSupported()` — log WARNING (không throw) khi capability mismatch.
- Chú ý: `publishBatch()` default chỉ gọi `publish()` từng cái — subclass có thể override.

---

### Bước 3 — Đọc 4 Adapter theo thứ tự tăng dần độ phức tạp

**3.1** `src/main/java/io/hrc/eventbus/adapter/inmemory/InMemoryEventBusAdapter.java`
- Đơn giản nhất. Đọc trước để hiểu flow chung.
- Chú ý: synchronous delivery — handler được gọi ngay trong thread của `publish()`.
- Chú ý inner class `InMemoryAcknowledgment` — `acknowledge()` là no-op.
- Chú ý 4 testing utilities (dùng trong test code):
  - `getPublishedEvents()` / `getPublishedEvents(type)` — assert events đã publish.
  - `clearEvents()` — reset state giữa các test case.
  - `getPublishedCount(type)` — đếm events theo loại.
- **CẢNH BÁO:** Đọc comment đầu file — synchronous InMemory vs async Kafka behavior khác nhau.

**3.2** `src/main/java/io/hrc/eventbus/adapter/kafka/KafkaEventBusAdapter.java`
- Chú ý `publish()` — dùng `resourceId` làm partition key (ordering per resource).
- Chú ý `publishIdempotent()` — Kafka idempotent producer, không cần Redis check thêm.
- Chú ý inner class `KafkaAcknowledgment`:
  - `acknowledge()` → `kafkaAck.acknowledge()` (commitSync).
  - `reject(false)` → ack message + publish lên DLT (Dead Letter Topic).
- Chú ý comment về serialization — placeholder, production dùng Jackson/Avro.

**3.3** `src/main/java/io/hrc/eventbus/adapter/rabbitmq/RabbitMQEventBusAdapter.java`
- Chú ý `publishIdempotent()` — khác Kafka: check Redis `SETNX` trước khi publish.
  Lý do: RabbitMQ không có native idempotent producer.
- Chú ý inner class `RabbitAcknowledgment`:
  - `acknowledge()` → `basicAck()`.
  - `reject(true)` → `basicNack(requeue=true)`.
  - `reject(false)` → `basicNack(requeue=false)` → RabbitMQ routing sang DLX.

**3.4** `src/main/java/io/hrc/eventbus/adapter/redis/RedisStreamEventBusAdapter.java`
- Phức tạp nhất. Đọc comment đầu file để nắm các Redis commands.
- Chú ý `publish()` — dùng `XADD` với map fields.
- Chú ý `publishIdempotent()` — check Redis `SETNX` trước XADD.
- Chú ý inner class `RedisStreamAcknowledgment`:
  - `acknowledge()` → `XACK` (message rời khỏi PEL).
  - `reject(true)` → không XACK → message ở lại PEL → re-deliver sau timeout.
  - `reject(false)` → XACK + publish vào `.dlq` stream.

---

### Bước 4 — Đọc Domain Events (nhanh)

Đọc theo nhóm trong `src/main/java/io/hrc/eventbus/event/`:

**Nhóm Order Events** (`event/order/`):
- `OrderCreatedEvent` — async saga only, mang `requesterId` + `quantity`.
- `OrderConfirmedEvent` — order hoàn thành, mang `requesterId` + `quantity`.
- `OrderCancelledEvent` — hủy order, mang `reason` (FailureReason) + `quantity` cần release.
- `OrderExpiredEvent` — hết hạn giữ chỗ, mang `expiredAt` + `quantity` cần release.

**Nhóm Payment Events** (`event/payment/`):
- `PaymentSucceededEvent` — mang `transactionId`, `amount`, `currency`.
- `PaymentFailedEvent` — mang `gatewayMessage` (để debug), reason = PAYMENT_FAILED.
- `PaymentTimeoutEvent` — mang `waitedFor` duration, reason = PAYMENT_TIMEOUT.
- `PaymentUnknownEvent` — mang `rawGatewayResponse`, reason = PAYMENT_UNKNOWN.

**Nhóm Reconciliation Events** (`event/reconciliation/`):
- `ReconciliationStartedEvent` — mang `reconciliationType`, `itemCount`.
- `ReconciliationFixedEvent` — mang `fixType`, `description`.
- `InventoryMismatchEvent` — P3 only, mang `redisAvailable`, `dbAvailable`, `delta`.

---

## Những điều cần nhớ sau khi đọc xong module này

1. **At-least-once delivery** — mọi `EventHandler.handle()` PHẢI idempotent.
2. **InMemory là synchronous, 3 adapter còn lại là asynchronous** — behavior khác nhau.
3. **`publishIdempotent()` implement khác nhau**: Kafka dùng idempotent producer,
   RabbitMQ và Redis dùng Redis `SETNX` check.
4. **`release()` CB behavior**: EventBus không liên quan, đây là InventoryStrategy.
5. **`EventDestination.forEventType()`** — convention: `OrderCreatedEvent` → `"order-created"`.
6. **`AbstractEventBusAdapter.dispatch()`**: nếu không có handler nào → auto-acknowledge.
7. **`InventoryMismatchEvent`** chỉ có ý nghĩa khi dùng P3 (RedisAtomicStrategy).
8. **`OrderCancelledEvent` và `OrderExpiredEvent`** đều mang `quantity` — consumer cần để release inventory.
