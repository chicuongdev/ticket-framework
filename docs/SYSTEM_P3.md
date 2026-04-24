# HCR Framework — P3 Full Stack (Redis + Kafka + PostgreSQL)

> File này mô tả toàn bộ hệ thống khi chạy **P3 (Redis Atomic)** với event bus Kafka
> và source DB PostgreSQL — cấu hình hiện tại của `hcr-sample`.
> Đọc xong là hiểu được: kiến trúc module, luồng data, logic xử lý, cách khởi động,
> và các vấn đề của hệ thống cũ đã được xử lý.

---

## 1. Stack tổng thể

```
┌────────────────┐     HTTP        ┌────────────────────────────┐
│  Client        │ ─────────────▶  │  hcr-sample (Spring Boot)  │
│ (curl/browser) │                 │   port 8080                │
└────────────────┘                 └──────┬─────────────────────┘
                                          │
             ┌────────────────────────────┼────────────────────────────┐
             │                            │                            │
             ▼                            ▼                            ▼
     ┌──────────────┐            ┌────────────────┐           ┌────────────────┐
     │    Redis     │            │     Kafka      │           │   PostgreSQL   │
     │ (inventory)  │            │  (event bus)   │           │ (source of     │
     │ source-of-   │◀──reconcile│                │──consume─▶│  truth for     │
     │ truth cho P3 │            │                │           │  DB lâu dài)   │
     └──────────────┘            └────────────────┘           └────────────────┘
             ▲                            ▲                            ▲
             └──── Lua DECRBY ────────────┘                            │
                                                                       │
                                          Prometheus ─────────▶ Grafana (dashboard)
                                                  scrape /actuator/prometheus
```

**Vai trò từng thành phần:**

| Component | Vai trò trong P3 | Port |
|-----------|------------------|------|
| Redis | **Source of truth** cho `available` — Lua script atomic DECRBY/INCRBY | 6379 |
| Kafka | Event bus persistent — publish `ResourceReservedEvent` → consumer sync DB | 9092 |
| PostgreSQL | Lưu order + `concert_tickets` (được sync lag từ Redis) + `hcr_processed_events` (idempotency) | 5432 |
| Prometheus | Scrape `/actuator/prometheus` mỗi 5s | 9090 |
| Grafana | Visualize metric | 3000 |

---

## 2. Cấu trúc module (12 module)

```
hcr-core          — Domain model chung (DomainEvent, enums, abstract entities, exceptions)
hcr-eventbus      — EventBus abstraction + InMemory/Kafka/RabbitMQ/RedisStream adapters
hcr-inventory     — 3 strategy (P1/P2/P3) + decorator (CircuitBreaker) + persistence consumer
hcr-payment       — PaymentGateway abstraction + TimeoutHandler + MockPaymentGateway
hcr-saga          — Saga orchestrator (sync/async)
hcr-gateway       — CorrelationIdFilter, Idempotency, RateLimit
hcr-reconciliation— Redis vs DB reconciliation, expired order cleanup
hcr-observability — FrameworkMetrics (Micrometer)
hcr-testing       — Test utilities
hcr-autoconfigure — Spring Boot auto-config (HcrAutoConfiguration + KafkaEventBusAutoConfiguration)
hcr-spring-boot-starter — Starter POM
hcr-sample        — Demo app (concert ticket booking)
```

Dependency flow: `core → eventbus/inventory/payment → saga → gateway/reconciliation → observability → testing → autoconfigure → starter → sample`.

---

## 3. Luồng dữ liệu end-to-end (1 request đặt vé)

### 3.1 Sơ đồ tổng thể

```
 [Client]
    │ POST /tickets/book
    │ {resourceId, requesterId, quantity, buyerEmail}
    ▼
 ┌──────────────────────┐
 │ TicketController     │
 └──────┬───────────────┘
        │ orchestrator.process(request)
        ▼
 ┌──────────────────────────────────────────────────────────┐
 │ SynchronousSagaOrchestrator                              │
 │   1. validate(request)                                   │
 │   2. check idempotencyKey                                │
 │   3. reserve(resourceId, qty)  ◀── Inventory Strategy    │
 │   4. createOrder()                                       │
 │   5. charge(paymentRequest)    ◀── Payment Gateway       │
 │   6. confirmOrder()                                      │
 │   7. save order → onConfirmed()                          │
 │      hoặc rollback: release() + onCancelled() nếu fail   │
 └────────┬─────────────────────────────────────────────────┘
          │
          ▼
 ┌──────────────────────────────────────────────────────────┐
 │ RedisAtomicStrategy.reserve()                            │
 │                                                          │
 │  ① redisTemplate.execute(reserveScript, key, qty)        │
 │     ── Lua atomic:                                       │
 │        GET key                                           │
 │        if available < qty → return -2                    │
 │        DECRBY key qty   → return remaining               │
 │                                                          │
 │  ② eventBus.publish(ResourceReservedEvent)               │
 │     ── Kafka send → topic "hcr.resource-reserved"        │
 │        header X-Event-Type=ResourceReservedEvent         │
 │                                                          │
 │  ③ return ReservationResult.success(remaining)           │
 └────┬─────────────────────────────────────────────────────┘
      │ (event đã vào Kafka, nhưng DB chưa update)
      │
      ▼
 [Kafka broker] → topic "hcr.resource-reserved"
      │
      ▼
 ┌──────────────────────────────────────────────────────────┐
 │ KafkaEventBusListener                                    │
 │  @KafkaListener(topicPattern="hcr\\..*")                 │
 │                                                          │
 │  ① đọc header X-Event-Type                               │
 │  ② adapter.onKafkaMessage(payload, eventType, ack)       │
 │     ── EventTypeRegistry.lookup("ResourceReservedEvent") │
 │     ── ObjectMapper.readValue(payload, Class)            │
 │     ── adapter.dispatch(event, ack)                      │
 └────────┬─────────────────────────────────────────────────┘
          │
          ▼
 ┌──────────────────────────────────────────────────────────┐
 │ InventoryPersistenceConsumer.reservedHandler             │
 │  (subscribe qua InitializingBean.afterPropertiesSet)     │
 │                                                          │
 │  transactionTemplate.execute:                            │
 │    INSERT INTO hcr_processed_events(event_id,...)        │
 │      — nếu duplicate → DataIntegrityViolation → skip+ack │
 │    UPDATE concert_tickets                                │
 │      SET available_quantity = available - qty            │
 │      WHERE resource_id = ?                               │
 │                                                          │
 │  kafkaAck.acknowledge()  ── commit offset                │
 └──────────────────────────────────────────────────────────┘
```

### 3.2 Hai transaction tách rời — tại sao?

**Critical path** (HTTP response):
1. Lua DECRBY Redis → < 1ms
2. Publish Kafka (producer buffer, không chờ ack broker đồng bộ) → < 1ms
3. Mock payment → 0ms
4. Save order to Postgres → vài ms

**Async path** (sau khi trả HTTP):
- Consumer poll Kafka → update Postgres `concert_tickets.available_quantity`

**Vì sao tách?**
- Nếu update Postgres trong critical path → khi Postgres chậm/lock thì toàn bộ booking dừng → mất throughput.
- Redis DECRBY là atomic → zero-oversell ngay ở Redis. DB chỉ là bản sao cho query lâu dài + reconciliation.
- Nếu crash giữa Redis DECR và Kafka publish → event mất → reconciliation service (chạy định kỳ 5') phát hiện Redis.available != DB.available_quantity và fix.

---

## 4. Logic các bước xử lý chính

### 4.1 Reserve (Lua script)

**Code:** `hcr-inventory/src/main/resources/lua/inventory_reserve.lua`

```lua
local available = tonumber(redis.call('GET', key))
if available == nil then return -1 end          -- chưa init
if available < quantity then return -2 end      -- không đủ
return redis.call('DECRBY', key, quantity)      -- trả remaining
```

**Vì sao dùng Lua:** Redis single-threaded, Lua chạy atomic trong 1 script. Nếu dùng 2 command rời (`GET` rồi `DECRBY`), giữa 2 command có client khác chen vào → race condition → oversell.

**Return convention:**
- `>= 0`: remaining sau DECRBY
- `-1`: key chưa init (lỗi cấu hình — cần `initialize()` trước)
- `-2`: `INSUFFICIENT_INVENTORY` (bình thường khi hết vé)

### 4.2 Release (Lua script)

**Code:** `hcr-inventory/src/main/resources/lua/inventory_release.lua`

```lua
local newAvailable = redis.call('INCRBY', key, quantity)
if totalQuantity and newAvailable > totalQuantity then
    redis.call('SET', key, totalQuantity)       -- guard double-release
    return totalQuantity
end
return newAvailable
```

**Vì sao guard `> totalQuantity`:** Chống lỗi logic double-release gây `available > total` (nghĩa là "còn nhiều hơn tổng" — vô lý).

### 4.3 EventBus publish — Kafka adapter

**Code:** `hcr-eventbus/src/main/java/io/hrc/eventbus/adapter/kafka/KafkaEventBusAdapter.java`

```java
public void publish(DomainEvent event, EventDestination destination) {
    String topic = topicPrefix + destination.getName();    // "hcr.resource-reserved"
    String partitionKey = event.getResourceId();           // ordering per resource
    String payload = objectMapper.writeValueAsString(event);

    ProducerRecord record = new ProducerRecord(topic, null, partitionKey, payload);
    record.headers().add(new RecordHeader(HEADER_EVENT_TYPE,
            event.getEventType().getBytes(UTF_8)));

    kafkaTemplate.send(record);
}
```

**Config producer** (set trong `KafkaEventBusAutoConfiguration`):
- `acks=all` — không mất message khi broker fail
- `enable.idempotence=true` — không duplicate khi producer retry
- `retries=3`

**Partitioning theo `resourceId`:** Tất cả event cùng 1 resource đi vào cùng 1 partition → ordering guarantee (reserve phải đến consumer TRƯỚC release cho cùng resource).

### 4.4 EventBus consume — Jackson + EventTypeRegistry

Consumer cần biết deserialize payload JSON thành class nào. Không reflect được từ simple name vì trùng tên giữa package → dùng **registry cache sẵn khi startup**.

**Code:** `hcr-eventbus/src/main/java/io/hrc/eventbus/EventTypeRegistry.java`

```java
// Startup: scan classpath, tìm tất cả concrete class extends DomainEvent
scanner.addIncludeFilter(new AssignableTypeFilter(DomainEvent.class));
// Cache: simpleName → Class<? extends DomainEvent>

// Runtime: lookup("ResourceReservedEvent") → ResourceReservedEvent.class
```

**Flow deserialize:**
1. `KafkaEventBusListener` nhận `ConsumerRecord`
2. Đọc header `X-Event-Type` = "ResourceReservedEvent"
3. `adapter.onKafkaMessage(payload, "ResourceReservedEvent", kafkaAck)`
4. `eventTypeRegistry.lookup(...)` → `Class<ResourceReservedEvent>`
5. `objectMapper.readValue(payload, class)` → event object
6. `adapter.dispatch(event, ack)` → gọi handlers đã subscribe

### 4.5 Idempotency — dedup event

**Code:** `hcr-inventory/src/main/java/io/hrc/inventory/persistence/InventoryPersistenceConsumer.java`

```java
private boolean processIdempotent(String eventId, String eventType, Runnable action) {
    try {
        transactionTemplate.execute(status -> {
            processedEventRepository.save(new ProcessedEvent(eventId, eventType));
            action.run();
            return null;
        });
        return true;
    } catch (DataIntegrityViolationException e) {
        return false;   // eventId đã INSERT trước → skip
    }
}
```

**Vì sao dùng eventId thay vì `WHERE available >= delta`:**
- Kafka at-least-once → cùng 1 event có thể được deliver 2 lần (network retry).
- Nếu check `WHERE available >= delta` → không idempotent vì `available` thay đổi giữa các lần retry.
- `INSERT INTO hcr_processed_events(event_id)` với `PRIMARY KEY(event_id)` → lần 2 sẽ throw `DataIntegrityViolation` → transaction rollback → DB không bị update kép.

**Điều kiện bảo toàn `eventId` qua Kafka:**
`DomainEvent.eventId` được **non-final** (Jackson set lại từ payload khi deserialize) → consumer side thấy cùng eventId với producer side.

### 4.6 Saga synchronous flow

**Code:** `SynchronousSagaOrchestrator.process()` — logic chung, gọi các abstract:

```
1. validate(request)                    — business rule
2. existing = findByIdempotencyKey()    — nếu có → return (dedup)
3. reservation = inventory.reserve()    — Redis DECRBY atomic
   nếu INSUFFICIENT → throw InsufficientInventoryException
4. order = createOrder(request)         — developer tạo TicketOrder
5. paymentResult = payment.charge()     — gọi PaymentGateway
   nếu FAIL → inventory.release() + throw PaymentFailedException
6. confirmOrder(order)                  — set status=CONFIRMED
7. save + return                        — Postgres order table
```

**Compensation** (rollback) tự động khi step 5 fail: Lua INCRBY Redis + publish `ResourceReleasedEvent` → consumer sync DB.

### 4.7 Sample seed: `RedisInventorySeeder`

Khi app start với `strategy=redis-atomic`, bean `RedisInventorySeeder` (chỉ active với `@ConditionalOnProperty`) đọc tất cả `ConcertTicket` từ DB rồi `inventoryStrategy.initialize()` vào Redis (set `hcr:inventory:{resourceId}` + `hcr:inventory:total:{resourceId}`).

**Vì sao cần:** Data seed ở `data.sql` chỉ vào Postgres, Redis rỗng lúc start → nếu skip step này, mọi `reserve()` sẽ return `-1` (key chưa init).

---

## 5. Cấu hình quan trọng (`application.yml`)

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/hcr_sample
    username: hcr
    password: hcr
  jpa:
    hibernate: { ddl-auto: create-drop }     # dev: DROP + CREATE mỗi lần start
    defer-datasource-initialization: true    # data.sql chạy sau Hibernate
  data:
    redis: { host: localhost, port: 6379 }
  kafka:
    bootstrap-servers: localhost:9092

hcr:
  inventory:
    strategy: redis-atomic                   # P3
  event-bus:
    type: kafka                              # KafkaEventBusAutoConfiguration kích hoạt
    kafka:
      bootstrap-servers: localhost:9092
      topic-prefix: "hcr."
  payment:
    mock-enabled: true                       # MockPaymentGateway — CHỈ DÙNG DEV
  saga:
    mode: sync
```

**Switch sang P1/P2:** chỉ đổi `hcr.inventory.strategy: pessimistic-lock` hoặc `optimistic-lock` (trong trường hợp đó không cần Redis/Kafka infra để chạy — Lettuce và Spring Kafka lazy khởi tạo, không fail-fast).

---

## 6. Hướng dẫn khởi động (mỗi lần)

### 6.1 Lần đầu / sau khi `git pull`

```cmd
cd /d "C:\Users\Admin\Documents\HUST\2025.2\io.hrc"
mvn clean install -DskipTests
```

### 6.2 Start infrastructure

```cmd
docker compose -f hcr-sample\docker-compose.yml up -d
```

Đợi tất cả container `healthy` (Kafka thường mất 20-30s):

```cmd
docker ps
```

Cột `STATUS` phải là `Up X seconds (healthy)` cho `redis`, `postgres`, `kafka`.

### 6.3 Start app

```cmd
mvn -pl hcr-sample spring-boot:run
```

**Log cần thấy khi start OK:**
- `[EventTypeRegistry] Registered N event types: [...ResourceReservedEvent...]`
- `[HCR] EventBus: Kafka (bootstrap=localhost:9092, topic-prefix=hcr.)`
- `[P3-Consumer] Subscribed to ResourceReservedEvent + ResourceReleasedEvent`
- `[Seeder] Initialized Redis: resourceId=concert-2026-06-15, available=100`
- `Started SampleApplication in X.X seconds`

### 6.4 Smoke test (CMD mới)

**Book 1 vé:**
```cmd
curl -X POST http://localhost:8080/tickets/book -H "Content-Type: application/json" -d "{\"resourceId\":\"concert-2026-06-15\",\"requesterId\":\"user-1\",\"quantity\":2,\"buyerEmail\":\"a@b.com\"}"
```

Kỳ vọng: HTTP 201 + `{"orderId":"...","status":"CONFIRMED",...}`.

**Check Redis giảm đúng:**
```cmd
for /f %i in ('docker ps -qf name=redis') do docker exec %i redis-cli GET hcr:inventory:concert-2026-06-15
```
→ "98" (100 ban đầu - 2 vừa đặt).

**Check Kafka nhận event:**
```cmd
for /f %i in ('docker ps -qf name=kafka') do docker exec %i kafka-console-consumer --bootstrap-server localhost:9092 --topic hcr.resource-reserved --from-beginning --max-messages 1 --property print.headers=true
```

**Check Postgres đã sync (chờ 1-2s sau request):**
```cmd
for /f %i in ('docker ps -qf name=postgres') do docker exec %i psql -U hcr -d hcr_sample -c "SELECT resource_id, available_quantity FROM concert_tickets;"
```
→ `available_quantity = 98`.

### 6.5 Grafana dashboard

- http://localhost:3000 (admin/admin)
- Datasource Prometheus đã auto-wire
- Query thử: `rate(http_server_requests_seconds_count[1m])`, `hcr_inventory_reserve_success_total`

### 6.6 Shutdown

```cmd
docker compose -f hcr-sample\docker-compose.yml down
```

Thêm `-v` nếu muốn xóa volume (cluster ID mới cho Kafka):
```cmd
docker compose -f hcr-sample\docker-compose.yml down -v
```

---

## 7. Vấn đề tồn đọng của hệ thống cũ & cách đã xử lý

### 7.1 P3 consumer không được wire vào EventBus

**Vấn đề:** `InventoryPersistenceConsumer` là Spring bean nhưng **chưa bao giờ gọi `eventBus.subscribe(...)`** → event publish ra EventBus không có handler nào consume → DB không bao giờ được sync khỏi Redis → silent data loss.

**Cách xử lý:** Implement `InitializingBean` + `DisposableBean`:
- `afterPropertiesSet()` → tự động subscribe 2 handler (`ResourceReservedEvent`, `ResourceReleasedEvent`)
- `destroy()` → unsubscribe khi bean shutdown (tránh leak handler khi hot-reload)
- File: `hcr-inventory/.../InventoryPersistenceConsumer.java`

### 7.2 BatchInventoryPersistenceConsumer không có graceful shutdown

**Vấn đề:** Batch mode gom event vào buffer, flush theo interval 1s. Khi app shutdown giữa chừng → buffer chưa flush → event mất → DB lag so với Redis (phải chờ reconciliation 5').

**Cách xử lý:** Fold `shutdown()` logic vào `DisposableBean.destroy()`:
- Unsubscribe khỏi EventBus (không nhận event mới)
- Flush buffer còn lại vào DB
- Shutdown scheduler với `awaitTermination(5s)`
- File: `hcr-inventory/.../BatchInventoryPersistenceConsumer.java`

### 7.3 `MockPaymentGateway` là silent production danger

**Vấn đề:** Auto-config wire `MockPaymentGateway` bất cứ khi nào không có `PaymentGateway` bean nào khác → deploy production mà quên khai báo gateway thật → app chạy với **mock payment luôn thành công 80%** → mất tiền thật.

**Cách xử lý:** Thêm `@ConditionalOnProperty("hcr.payment.mock-enabled")` mặc định `false`:
- Dev bật mock: `hcr.payment.mock-enabled: true`
- Production: không bật → nếu thiếu `PaymentGateway` bean thật → fail-fast startup
- File: `hcr-autoconfigure/.../HcrAutoConfiguration.java` + `HcrProperties.PaymentProperties`

### 7.4 `KafkaEventBusAdapter` serialize/deserialize chỉ là placeholder

**Vấn đề cũ:**
- `serializeEvent()` emit 3 field (eventId, eventType, correlationId) — thiếu payload business (resourceId, quantity...)
- `deserializeEvent()` `return null` — consumer không thể reconstruct event
- Không có consumer nào được wire (`@KafkaListener`) → toàn bộ consume path chết

**Cách xử lý:**
1. Replace bằng Jackson full serialization (`ObjectMapper` + `JavaTimeModule` + `ParameterNamesModule`)
2. Thêm header `X-Event-Type` = simple class name → consumer biết deserialize ra Class nào
3. Tạo `EventTypeRegistry` classpath-scan → map `simpleName → Class`
4. Tạo `KafkaEventBusListener` với `@KafkaListener(topicPattern = "hcr\\..*")` → route tất cả topic về `adapter.dispatch()`
5. Bundle thành `KafkaEventBusAutoConfiguration` — kích hoạt khi `hcr.event-bus.type=kafka`
6. Thêm `-parameters` flag vào compiler plugin → Jackson biết constructor param names (cần thiết để deserialize event có final fields)

### 7.5 `DomainEvent.eventId` final → Jackson deserialize sinh UUID mới

**Vấn đề:** `eventId` và `occurredAt` là `final` + có initializer → khi Jackson gọi no-arg constructor rồi set field qua setter, **setter không tồn tại cho field final** → eventId giữ nguyên UUID mới sinh khi deserialize, không phải UUID gốc. Hậu quả: idempotency dedup sai (`hcr_processed_events` thấy eventId "mới" mỗi lần retry → INSERT duplicate data).

**Cách xử lý:** Bỏ `final` khỏi 3 field: `eventId`, `eventType`, `occurredAt` → Lombok `@Setter` sinh setter → Jackson set được giá trị gốc từ payload.
- File: `hcr-core/.../DomainEvent.java`
- Trade-off: runtime về mặt lý thuyết có thể mutate. Grep verify không có code nào làm thế (chỉ `incrementRetryCount()` mutate `retryCount`).

### 7.6 Friction khi switch strategy (Cách C)

**Vấn đề cũ:** Đổi từ P1 sang P3 cần sửa nhiều file (pom.xml, config bean, compose, ...) → developer nhầm "framework chưa hoàn thiện".

**Cách xử lý — "Cách C" (Lazy Redis):**
- Sample luôn include `spring-boot-starter-data-redis` (Lettuce client lazy → không connect khi không dùng)
- `SampleConfiguration` inject `@Autowired(required = false) StringRedisTemplate` → null khi không có Redis, có giá trị khi Redis up
- Bean `InventoryPersistenceConsumer` dùng `@ConditionalOnExpression` kích hoạt cả theo `strategy` lẫn `persistence.mode`
- **Kết quả:** Switch P1 ↔ P3 giờ chỉ **1 dòng YAML** (`hcr.inventory.strategy`)

### 7.7 H2 in-memory không phản ánh production

**Vấn đề cũ:** `jdbc:h2:mem:...` — mất data khi restart, không test được hành vi concurrency / constraint đúng như Postgres.

**Cách xử lý:** Switch sample sang PostgreSQL production-like:
- Compose: thêm service `postgres:15-alpine`
- `application.yml`: `jdbc:postgresql://localhost:5432/hcr_sample`, dialect `PostgreSQLDialect`
- pom: `postgresql` runtime, H2 xuống `scope=test`
- data.sql giữ nguyên (syntax tương thích)

### 7.8 Event bus in-memory ≠ production

**Vấn đề cũ:** Sample dùng `InMemoryEventBusAdapter` (synchronous, in-process) → test không phản ánh at-least-once + partitioning + consumer lag của Kafka.

**Cách xử lý:**
- Compose: thêm service Kafka KRaft mode (no Zookeeper)
- `hcr.event-bus.type=kafka` → `KafkaEventBusAutoConfiguration` wire producer/consumer đầy đủ (acks=all, idempotence, manual ack)
- Mọi event publish qua Kafka thật → consumer `@KafkaListener` dispatch về handler cũ (không cần sửa consumer code)

---

## 8. Known limitations (chấp nhận)

- **P3 gap:** Giữa Redis DECR success và Kafka publish — nếu crash ở giữa, event mất. Reconciliation service fix trong ≤ 5 phút.
- **Batch consumer ACK trước flush:** Nếu crash giữa ACK và flush, data loss. Reconciliation fix.
- **Sample chạy sync saga:** Async saga cần `SagaStateRepository` + async orchestrator — chưa dùng ở sample.
- **Mock payment mặc định bật ở `hcr-sample`:** CHỈ dùng dev. Production phải `hcr.payment.mock-enabled: false` + khai báo `@Bean PaymentGateway` thật.
