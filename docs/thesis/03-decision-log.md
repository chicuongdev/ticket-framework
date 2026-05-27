# 03 — Decision Log (Sổ tay quyết định kỹ thuật)

> **Mục đích tài liệu:** Tài liệu này KHÔNG phải một chương báo cáo riêng. Nó là **chất liệu thô bổ trợ Chương 3 (Kiến trúc đề xuất)** — cung cấp phần *"tại sao chọn cái này thay vì cái kia"* mà code và class diagram không nói được. Khi prompt LLM viết Chương 3, đính kèm file này → các phần "Design rationale" của từng module sẽ giàu lập luận và có chiều sâu.
>
> **Cách dùng:**
> - Mỗi quyết định độc lập, có thể trích đoạn riêng để chèn vào báo cáo (ví dụ làm sub-section "Lý do thiết kế" trong mục giới thiệu của một module).
> - Mỗi quyết định có **mức độ tin cậy** (♦ = đã document trong code/CLAUDE.md, ♢ = suy luận của tác giả tài liệu, cần sinh viên xác nhận).
> - 18 quyết định chia thành 5 nhóm theo chủ đề.
>
> **Cấu trúc thống nhất mỗi quyết định:**
>
> ```
> ### [ID] — Câu hỏi
> ★ Mức độ tin cậy: ♦ / ♢
> Bối cảnh         — vấn đề đặt ra
> Các lựa chọn     — option đã cân nhắc, kèm ưu/nhược
> Quyết định       — chọn option nào
> Lý do            — vì sao chọn
> Trade-off        — chấp nhận đánh đổi gì
> Bằng chứng       — file:line, comment, hoặc reference
> ```

---

## Mục lục

**Nhóm A — Quyết định thiết kế tổng thể**
- A1. Tại sao có 3 chiến lược inventory (P1/P2/P3) thay vì 1?
- A2. Tại sao chọn Orchestration saga thay vì Choreography?
- A3. Tại sao P3 phải zero-DB-hit trong critical path?
- A4. Tại sao tách Reserve và Payment thành 2 transaction riêng biệt?

**Nhóm B — Quyết định về consistency & concurrency**
- B1. Tại sao dùng `TransactionTemplate` thay vì `@Transactional` trong strategies?
- B2. Tại sao P2 phải tạo transaction MỚI cho mỗi lần retry?
- B3. Tại sao dedup qua bảng `processed_events` thay vì điều kiện `WHERE available >= delta`?
- B4. Tại sao `reserveBatch()` phải sort key alphabet trong P1/P2?
- B5. Tại sao dùng Lua script cho Redis atomic operations thay vì `WATCH`/`MULTI`?

**Nhóm C — Quyết định về resilience & failure handling**
- C1. Tại sao `release()` của Circuit Breaker KHÔNG reject khi OPEN?
- C2. Tại sao `AsynchronousSagaOrchestrator` BẮT BUỘC `SagaStateRepository` (fail-fast tại boot)?
- C3. Tại sao reconciliation chạy `@Scheduled` ở mọi instance + distributed lock, thay vì leader election?
- C4. Tại sao chấp nhận at-least-once thay vì exactly-once?

**Nhóm D — Quyết định về tech stack & module boundary**
- D1. Tại sao chọn Spring Boot 3.2 (Java 17) thay vì Quarkus/Micronaut?
- D2. Tại sao chọn Redisson thay vì Lettuce/Jedis?
- D3. Tại sao tách thành 12 module Maven thay vì 1 fat jar?
- D4. Tại sao có 4 EventBus adapter thay vì cố định 1?

**Nhóm E — Quyết định chấp nhận trade-off đã biết**
- E1. Tại sao chấp nhận gap giữa Redis DECR và `EventBus.publish()` trong P3?
- E2. Tại sao Batch consumer ACK trước khi flush vào DB?

---

# NHÓM A — QUYẾT ĐỊNH THIẾT KẾ TỔNG THỂ

---

### A1 — Tại sao có 3 chiến lược inventory (P1/P2/P3) thay vì chỉ 1 chiến lược "tốt nhất"?

★ **Mức độ tin cậy:** ♦

**Bối cảnh.** Bài toán phân phát tài nguyên có giới hạn không có một "chiến lược đúng duy nhất" cho mọi use case. Đặt phòng khách sạn (vài trăm req/s, cần linearizable) khác hẳn flash sale (vài chục nghìn req/s, chấp nhận eventual). Nếu framework cố định một chiến lược, hoặc nó quá nặng cho use case nhỏ, hoặc nó không đủ throughput cho use case lớn.

**Các lựa chọn cân nhắc.**
- **Option 1 — Chỉ Pessimistic Lock (P1):** đơn giản, strong consistency. Nhược: throughput trần ~1000 req/s, không đáp ứng được flash sale.
- **Option 2 — Chỉ Redis Atomic (P3):** throughput cao nhất. Nhược: bắt buộc phải có Redis ngay cả cho hệ nhỏ; eventual consistency không phù hợp cho đặt phòng khách sạn (một booking có thể overlap với một booking khác).
- **Option 3 — Cung cấp 3 chiến lược, switchable qua config:** phức tạp hơn (phải đảm bảo cùng interface) nhưng cho developer chọn lớp use case của mình.

**Quyết định.** Option 3 — `InventoryStrategy` interface với 3 implementation P1, P2, P3. Switch qua YAML `hcr.inventory.strategy: pessimistic | optimistic | redis-atomic`.

**Lý do.**
1. Đây là **đóng góp khái niệm cốt lõi** của framework, phân biệt HCR với các framework saga đa năng (vốn không có inventory abstraction).
2. Cho phép developer thử nghiệm: cùng business code chạy lần lượt với 3 chiến lược, đo throughput, chọn cái phù hợp dựa trên dữ liệu thực.
3. Giảm rủi ro lock-in: khi traffic tăng, developer không phải viết lại — chỉ đổi 1 dòng config.

**Trade-off.** Phải duy trì 3 implementation thay vì 1. Mỗi implementation có quirk riêng (xem B1, B2). Toàn bộ test phải chạy với cả 3. Phức tạp tăng ~3× ở tầng inventory, nhưng đây là *complexity hiding* — phía developer chỉ thấy 1 interface.

**Bằng chứng.**
- `hcr-inventory/.../strategy/InventoryStrategy.java` (interface chuẩn).
- 3 implementation: `pessimistic/PessimisticLockStrategy`, `optimistic/OptimisticLockStrategy`, `redis/RedisAtomicStrategy`.
- `CLAUDE.md` mô tả bảng so sánh 3 strategy.
- `architecture.md` (root) — section "1.4. 3 Inventory Strategies".

---

### A2 — Tại sao chọn Orchestration saga thay vì Choreography?

★ **Mức độ tin cậy:** ♦

**Bối cảnh.** Saga có thể implement theo hai mô hình: **Orchestration** (một coordinator trung tâm điều phối các bước) hoặc **Choreography** (mỗi service phát/lắng nghe event, không có coordinator). Với 3 bước Reserve → Pay → Confirm, lựa chọn mô hình ảnh hưởng tới trace, debug, và độ phức tạp.

**Các lựa chọn cân nhắc.**
- **Option 1 — Choreography:** mỗi service publish event, service khác listen. Loose coupling cao, scale tốt. Nhược: khó debug khi flow fail (event đi đâu? bước nào lỗi?), khó xác định "saga đã hoàn thành chưa" khi không có coordinator.
- **Option 2 — Orchestration:** `AbstractSagaOrchestrator` gọi từng step theo thứ tự, lưu state trong `SagaContext`. Logic flow tập trung, dễ trace. Nhược: orchestrator có thể trở thành "god class" nếu flow dài hoặc nhiều nhánh.

**Quyết định.** Option 2 — Orchestration. `AbstractSagaOrchestrator` là Template Method, `process()` là final, subclass quyết định sync/async qua `executeFlow()`.

**Lý do.**
1. Flow của HCR cố định 3 bước (Reserve → Pay → Confirm) — không đủ phức tạp để cần choreography.
2. **Đề tài tốt nghiệp ưu tiên rõ ràng và verify được tính đúng đắn.** Compensation theo thứ tự ngược dễ chứng minh đúng khi tập trung trong 1 class.
3. Orchestration cho phép trace mỗi saga có một `correlationId` duy nhất, debug dễ hơn — đặc biệt quan trọng khi load test thấy lỗi.
4. State của saga được persist trong `SagaStateRepository` (P3) hoặc trong DB order (P1/P2) — có thể recover sau crash.

**Trade-off.** Coordinator là "single point of complexity" — bất kỳ thay đổi nào về flow phải sửa orchestrator. Tăng coupling giữa orchestrator và các step. Khắc phục: thiết kế `SagaStep` interface cho phép thêm step mới (chưa implement, ghi nhận trong roadmap).

**Bằng chứng.**
- `hcr-saga/.../orchestrator/AbstractSagaOrchestrator.java` — Template Method với 3 step `ReservationStep / PaymentStep / ConfirmationStep`.
- Javadoc mở đầu class: "Template Method base cho toan bo Saga flow … Framework-controlled (DO NOT override): process, retryPayment, adminCancel, getStatus."

---

### A3 — Tại sao P3 phải zero-DB-hit trong critical path?

★ **Mức độ tin cậy:** ♦

**Bối cảnh.** P3 (Redis Atomic) hứa hẹn throughput 5000–10000 req/s. Nhưng nếu critical path (từ HTTP request đến HTTP response) còn touch DB (vd: `INSERT order`, `SELECT user`), latency cộng dồn sẽ kéo throughput xuống mức P1/P2.

**Các lựa chọn cân nhắc.**
- **Option 1 — P3 vẫn ghi DB inline trong request** (vd: `INSERT order` lúc bắt đầu): đơn giản, đảm bảo có order ngay. Nhược: DB trở thành bottleneck → P3 không hơn P2 bao nhiêu.
- **Option 2 — P3 hoàn toàn không touch DB trong request, mọi DB write chuyển sang async qua EventBus consumer:** throughput cao nhất. Nhược: không có order trong DB ngay → API `getStatus(orderId)` phải fallback sang `SagaStateRepository` (Redis) trong vài giây đầu.

**Quyết định.** Option 2. Critical path P3 = chỉ Redis. DB write hoàn toàn async qua `InventoryPersistenceConsumer` / `BatchInventoryPersistenceConsumer`.

**Lý do.**
1. Đây là **thử nghiệm cốt lõi** mà đề tài muốn chứng minh: P3 có thể đạt > 5× throughput của P1.
2. Loại DB khỏi critical path đồng nghĩa **loại HikariCP connection pool** khỏi các nguồn contention — connection pool thường là bottleneck thật trong workload tải đỉnh.
3. P3 là *eventual consistency* theo định nghĩa — chấp nhận DB sync sau ≤ 5 phút là phù hợp với hợp đồng đã công bố với developer.

**Trade-off.**
- API `getStatus(orderId)` phải kiểm tra `SagaStateRepository` (Redis) trước, fallback sang DB sau — phức tạp hơn so với chỉ query DB.
- Có *consistency window* ≤ 5 phút mà DB chưa sync → reconciliation case 4 (UNPERSISTED_RESERVATION) phải cover.
- Reconciliation và observability dashboard phải tự xử lý "Redis là source of truth" cho P3.

**Bằng chứng.**
- `CLAUDE.md` — quy ước số 3: *"P3 critical path = zero DB hit — chỉ Redis. DB chỉ được access async qua EventBus consumer"*.
- `hcr-saga/.../orchestrator/async/AsynchronousSagaOrchestrator.java` — `executeFlow()` chỉ gọi `inventoryStrategy.reserve()` (Redis) + `sagaStateRepository.save()` (Redis) + `eventBus.publish()`. Không có DB write nào.

---

### A4 — Tại sao tách Reserve và Payment thành 2 transaction riêng biệt?

★ **Mức độ tin cậy:** ♦

**Bối cảnh.** Cách "an toàn nhất" theo bản năng là gói cả Reserve (giảm inventory) và Payment (gọi gateway) trong một DB transaction. Nhưng payment gateway bên ngoài có thể mất ≥ 30 giây để trả lời (ví dụ 3DS challenge). Nếu lock DB suốt thời gian đó, throughput chỉ còn vài chục req/s.

**Các lựa chọn cân nhắc.**
- **Option 1 — Reserve + Payment cùng 1 DB transaction:** atomic theo nghĩa ACID. Nhược: hold DB lock suốt 30s+. Throughput sụp.
- **Option 2 — Reserve commit ngay, Payment chạy ở transaction khác sau khi reserve thành công:** không hold DB lock. Nhược: nếu Payment fail, phải compensate (release inventory) thủ công.
- **Option 3 — Distributed transaction (XA/2PC) kết hợp DB và payment gateway:** không khả thi, payment gateway không hỗ trợ XA.

**Quyết định.** Option 2. Reserve commit, sau đó Payment chạy ở transaction khác. Nếu Payment fail → `compensate()` chạy `release()` ngược.

**Lý do.**
1. **Định luật vàng của transaction trong hệ phân tán:** không hold DB lock trong khi gọi external service. Đây là kiến thức kinh điển (Pat Helland 2007 — *"Life beyond Distributed Transactions"*).
2. Đây cũng là cơ sở lý thuyết của Saga Pattern (Garcia-Molina 1987) — chuỗi local transaction + compensating action.
3. Saga compensate qua `release()` đã được test kỹ, idempotent, an toàn để retry.

**Trade-off.**
- Mất tính atomic theo nghĩa ACID. Có khoảnh khắc "đã reserve nhưng chưa pay" → khi đó có user khác thấy `available` đã giảm → có thể từ chối user khác trong khi cuối cùng order này lại bị cancel. Đây là *isolation anomaly* (Helland gọi là "lying about state").
- Khắc phục: `expiresAt` + reconciliation case 1 (STALE_PENDING) đảm bảo bounded compensation time.

**Bằng chứng.**
- `hcr-saga/.../orchestrator/AbstractSagaOrchestrator.java#executePaymentAndConfirmation()` — dùng `OrderAccessor.transitionTo(order, OrderStatus.RESERVED)` + `saveOrder(order)` xong mới gọi `paymentStep.execute()`.
- `hcr-saga/.../step/ReservationStep.java` và `PaymentStep.java` — mỗi step `compensate()` riêng biệt.
- `CLAUDE.md` ngầm thông qua "Reserve và payment la 2 transaction rieng biet".

---

# NHÓM B — QUYẾT ĐỊNH VỀ CONSISTENCY & CONCURRENCY

---

### B1 — Tại sao dùng `TransactionTemplate` thay vì `@Transactional` trong inventory strategies?

★ **Mức độ tin cậy:** ♦

**Bối cảnh.** Trong Spring, có hai cách quản lý transaction: annotation `@Transactional` (proxy AOP) hoặc programmatic `TransactionTemplate.execute(callback)`. `@Transactional` thường được ưa chuộng vì code sạch hơn.

**Các lựa chọn cân nhắc.**
- **Option 1 — `@Transactional` trên các method `reserve()`, `release()`:** code ngắn, idiomatic. Nhược: cần Spring proxy.
- **Option 2 — `TransactionTemplate.execute(...)`:** verbose hơn, nhưng không phụ thuộc proxy.

**Quyết định.** Option 2 — `TransactionTemplate` cho cả P1 và P2.

**Lý do.**
1. **Strategy được tạo bằng `new` trong `InventoryStrategyFactory.create(...)`, KHÔNG phải Spring bean.** Vì factory pattern cần khả năng tạo strategy với `circuitBreakerEnabled` argument tại runtime, không phải tại boot time.
2. Khi không phải Spring bean → AOP proxy không có tác dụng → `@Transactional` không có hiệu lực → code sẽ không có transaction nhưng vẫn compile/chạy → silent bug.
3. `TransactionTemplate` không cần proxy, hoạt động đúng dù instance được tạo bằng cách nào.

**Trade-off.** Code dài hơn (~5 dòng boilerplate per method). Đổi lại tránh được class lỗi rất khó debug ("sao transaction không hoạt động?").

**Bằng chứng.**
- `CLAUDE.md` — quy ước số 1: *"TransactionTemplate, KHONG dung @Transactional trong strategies (vi khong phai Spring bean, Factory tao bang `new`)"*.
- `hcr-inventory/.../factory/InventoryStrategyFactory.java#create()` — dùng `new PessimisticLockStrategy(...)`.
- `PessimisticLockStrategy.java` — field `private final TransactionTemplate tx`.

---

### B2 — Tại sao P2 phải tạo transaction MỚI cho mỗi lần retry, không retry trong cùng transaction?

★ **Mức độ tin cậy:** ♦

**Bối cảnh.** P2 (OptimisticLock) khi gặp `OptimisticLockException` phải retry. Cách đơn giản nhất: vòng `for (int i = 0; i < maxRetries; i++) { try { … } catch (OptimisticLockException e) { … } }` trong cùng method. Nhưng nếu method này chạy trong cùng `TransactionTemplate.execute()`, retry sẽ luôn fail.

**Các lựa chọn cân nhắc.**
- **Option 1 — Retry trong cùng transaction:** code ngắn. Nhược: Hibernate cache version cũ trong session — `EntityManager` không biết DB đã thay đổi → retry thấy version cũ → lại fail.
- **Option 2 — Mỗi retry là 1 `TransactionTemplate.execute()` riêng:** mỗi lần là một session/transaction hoàn toàn mới, đọc DB mới nhất.

**Quyết định.** Option 2. Vòng retry nằm *bên ngoài* `TransactionTemplate.execute()`, mỗi attempt là một transaction độc lập.

**Lý do.**
1. **Hibernate first-level cache:** `EntityManager` cache entity theo identity. Khi retry trong cùng session, `em.find()` trả về *bản cache cũ* với version cũ → update với version cũ → DB từ chối → vẫn fail.
2. Phải `em.refresh()` hoặc `em.clear()` để đọc lại — nhưng đây là pattern dễ quên và khó test.
3. Tách transaction theo retry attempt là pattern chuẩn của OCC (Kung 1981 — đọc lại data, validate, update).

**Trade-off.** Phải mở/đóng transaction nhiều lần khi contention cao → tăng overhead. Nhưng OCC đã giả định contention thấp; nếu contention quá cao, ta đã chọn nhầm strategy (nên dùng P1).

**Bằng chứng.**
- `CLAUDE.md` — quy ước số 2: *"P2 phai tao transaction moi moi retry (Hibernate cache version cu trong session)"*.
- `hcr-inventory/.../optimistic/OptimisticLockStrategy.java` — vòng `for` retry bao quanh `tx.execute(...)`, không nằm trong.

---

### B3 — Tại sao dedup ở consumer qua bảng `processed_events` thay vì điều kiện `WHERE available >= delta`?

★ **Mức độ tin cậy:** ♦

**Bối cảnh.** `InventoryPersistenceConsumer` xử lý `ResourceReservedEvent` (P3 async DB sync). Vì at-least-once delivery, consumer có thể nhận cùng event 2 lần. Cần chống double-decrement.

**Các lựa chọn cân nhắc.**
- **Option 1 — Điều kiện trong UPDATE:** `UPDATE inventory SET available = available - ? WHERE resource_id = ? AND available >= ?`. Nếu rowcount = 0 → đã decrement → skip.
- **Option 2 — Bảng `processed_events`:** `INSERT INTO processed_events (event_id) ON CONFLICT DO NOTHING` trước khi UPDATE. Nếu event_id đã tồn tại → skip.

**Quyết định.** Option 2 — bảng `processed_events` với `event_id` làm primary key.

**Lý do (đây là một quyết định subtle nhưng quan trọng).**

Option 1 *có vẻ* đúng nhưng tạo race nguy hiểm khi consumer retry trùng với release đồng thời:

```
Time   T1 (consumer attempt 1)         T2 (consumer release)
t0     SELECT available = 5
t1     UPDATE … SET available = 4      
t2                                     UPDATE … SET available = 5 (release)
t3     [crash, retry]
t4     SELECT available = 5
t5     UPDATE … SET available = 4 ← OVERSELL! Bản chất là decrement 2 lần
```

`WHERE available >= delta` không đủ vì giữa hai lần consumer attempt, có thể có release đẩy `available` lên đủ để UPDATE thứ 2 thành công.

Option 2 hoàn toàn miễn nhiễm: `event_id` là duy nhất, INSERT thứ 2 fail bất kể trạng thái `available`.

**Trade-off.**
- Phải maintain bảng `processed_events` (tăng theo throughput) → cần `ProcessedEventsCleanupJob` xoá bản ghi cũ định kỳ.
- TTL cleanup phải > Kafka log retention để tránh: consumer rewind → event cũ + entry processed_events đã xoá → double process.

**Bằng chứng.**
- `CLAUDE.md` — quy ước số 4: *"Idempotency qua eventId (bang hcr_processed_events), KHONG phai WHERE available >= delta"*.
- `hcr-inventory/.../persistence/ProcessedEvent.java` — entity với `@Id eventId`.
- `hcr-inventory/.../persistence/InventoryPersistenceConsumer.java` — INSERT `processed_events` trước UPDATE inventory.

---

### B4 — Tại sao `reserveBatch()` phải sort key alphabet trong P1/P2?

★ **Mức độ tin cậy:** ♦

**Bối cảnh.** `reserveBatch(Map<String, Integer>)` cho phép đặt nhiều resource cùng lúc atomic (ví dụ: combo flash sale 3 sản phẩm). Trong P1 và P2, mỗi resource cần lock — nếu lock không theo thứ tự cố định, hai request đồng thời có thể deadlock.

**Tình huống deadlock cổ điển.**
```
Request A: reserveBatch(resource1, resource2)
Request B: reserveBatch(resource2, resource1)

Time   A                                B
t0     LOCK resource1                   LOCK resource2
t1     waiting for resource2            waiting for resource1
t∞     DEADLOCK
```

**Các lựa chọn cân nhắc.**
- **Option 1 — Lock theo thứ tự client gửi:** đơn giản, theo ý developer. Nhược: deadlock như trên.
- **Option 2 — Sort keys theo thứ tự alphabet trước khi lock:** thứ tự cố định toàn cục → request đồng thời lock theo cùng thứ tự → không deadlock.
- **Option 3 — Dùng global lock cho mọi batch:** đơn giản, không deadlock. Nhược: serialize toàn bộ batch operations → throughput sụp.

**Quyết định.** Option 2.

**Lý do.**
1. Đây là **kỹ thuật chuẩn của 2PL** trong literature (Bernstein 1981) — total order trên tài nguyên đủ để chống deadlock.
2. Sort key trong memory là $O(n \log n)$ với $n$ là số resource trong batch (thường ≤ 10), chi phí không đáng kể.
3. Không cần global lock → throughput cao trên các batch không trùng key.

**Trade-off.**
- Developer có thể bị bất ngờ nếu muốn lock theo thứ tự ưu tiên (ví dụ ưu tiên item đắt nhất). Nhưng "lock order = priority order" là pattern hiếm và có thể tự custom override.
- P3 không cần sort vì Lua script là single-threaded; nhưng để giữ API thống nhất, P3 vẫn nhận `Map` không yêu cầu thứ tự.

**Bằng chứng.**
- `CLAUDE.md` — quy ước số 6: *"reserveBatch() sort keys alphabet (P1) — chong deadlock"*.
- `hcr-inventory/.../strategy/InventoryStrategy.java` — javadoc của `reserveBatch()` ghi rõ: *"Để tránh deadlock (P1/P2): các key được lock theo thứ tự alphabet."*.

---

### B5 — Tại sao P3 dùng Lua script thay vì `WATCH`/`MULTI` (Redis transaction)?

★ **Mức độ tin cậy:** ♦

**Bối cảnh.** Redis có hai cơ chế thao tác atomic: (1) Lua script qua `EVAL` hoặc (2) optimistic transaction với `WATCH key + MULTI + EXEC`. Cả hai đều có thể implement reserve atomic.

**Các lựa chọn cân nhắc.**
- **Option 1 — `WATCH` + `MULTI`/`EXEC`:** standard Redis transaction. Nếu key bị thay đổi giữa WATCH và EXEC, EXEC fail. Phải retry tại client.
- **Option 2 — Lua script `EVAL`:** script chạy single-thread bên Redis server, atomic native, không retry.

**Quyết định.** Option 2 — Lua script.

**Lý do.**
1. **Atomic native, không retry:** WATCH/MULTI yêu cầu retry pattern phía client → tăng latency, tăng phức tạp client-side. Lua chạy 1 lần là xong.
2. **Network round-trip:** Lua chỉ 1 EVAL. WATCH/MULTI cần ít nhất 3 round trip (WATCH, MULTI/queue/EXEC). Latency tốt hơn ~3×.
3. **Atomic logic phức tạp:** `inventory_reserve.lua` phải GET, kiểm tra, DECRBY trong một bước. Lua diễn đạt tự nhiên; WATCH/MULTI chỉ làm được nếu logic đơn giản (chỉ ghi không đọc).
4. **Guard logic mạnh:** `inventory_release.lua` còn có guard chống vượt total — phải so sánh trước khi INCR. Lua làm được; WATCH/MULTI khó.

**Trade-off.**
- Lua script chiếm thời gian Redis single thread — script chậm sẽ block toàn bộ Redis. Khắc phục: script HCR rất ngắn (vài chục dòng), benchmarks < 1 ms.
- Phải maintain Lua code riêng — không debug được bằng debugger Java. Khắc phục: viết unit test Java gọi qua `RedissonClient.getScript().eval(...)`.

**Bằng chứng.**
- `hcr-inventory/src/main/resources/lua/inventory_reserve.lua` và `inventory_release.lua`.
- `hcr-inventory/.../strategy/redis/RedisAtomicStrategy.java` — load script qua `RedissonClient.getScript()`.

---

# NHÓM C — QUYẾT ĐỊNH VỀ RESILIENCE & FAILURE HANDLING

---

### C1 — Tại sao `release()` của Circuit Breaker KHÔNG reject khi OPEN?

★ **Mức độ tin cậy:** ♦

**Bối cảnh.** Circuit Breaker chuẩn (theo Nygard 2007) reject mọi call khi state OPEN — fail-fast để bảo vệ dependency yếu. Nhưng `CircuitBreakerInventoryDecorator` của HCR có một ngoại lệ: `release()` luôn cho qua kể cả khi OPEN.

**Bối cảnh nghiệp vụ.** `release()` là *compensating action* — gọi khi payment fail hoặc admin cancel. Nó "trả lại" inventory đã giữ chỗ. Nếu không thực hiện được → inventory bị **leak vĩnh viễn**.

**Các lựa chọn cân nhắc.**
- **Option 1 — Treat `release()` như mọi call khác, reject khi OPEN:** đúng pattern CB sách giáo khoa. Nhược: inventory leak khi DB/Redis đang flaky đúng lúc cần release.
- **Option 2 — `release()` luôn cho qua delegate, kể cả khi OPEN:** trái pattern. Nhưng đảm bảo release luôn cố gắng thực hiện.
- **Option 3 — Queue release request và retry sau khi CB CLOSED:** lý tưởng. Phức tạp; cần persistent queue.

**Quyết định.** Option 2 cho v1. Option 3 ghi nhận trong roadmap.

**Lý do.**
1. **Hậu quả của việc bỏ release nghiêm trọng hơn nhiều so với việc gọi DB/Redis khi nó đang yếu.** Bỏ release = leak vĩnh viễn (bug khó phát hiện). Gọi DB yếu = thêm 1 call timeout (recovery sau).
2. Reconciliation **không cứu được leak** vì reconciliation chỉ phát hiện sai lệch trong các order rõ ràng, không biết "có ai đó định release nhưng đã bị reject".
3. Pattern này là **business overrides pattern** — chấp nhận lệch khỏi sách giáo khoa khi semantic nghiệp vụ yêu cầu.

**Trade-off.**
- Khi DB/Redis sụp, vẫn tiếp tục bắn `release()` calls → tăng tải lên dependency yếu. Khắc phục: log warning + metric `inventory.release.cb_open_passthrough` để monitor.
- Pattern này phải được document rõ → developer mới đọc code không ngạc nhiên ("sao CB này có exception?").

**Bằng chứng.**
- `CLAUDE.md` — quy ước số 5: *"CB release() khong reject khi OPEN — tranh inventory leak"*.
- `hcr-inventory/.../decorator/CircuitBreakerInventoryDecorator.java` — method `release()` không có CB check.

---

### C2 — Tại sao `AsynchronousSagaOrchestrator` BẮT BUỘC `SagaStateRepository` (fail-fast tại boot)?

★ **Mức độ tin cậy:** ♦

**Bối cảnh.** `SynchronousSagaOrchestrator` (P1/P2) lưu state trong DB order trực tiếp — không cần `SagaStateRepository`. `AsynchronousSagaOrchestrator` (P3) trả HTTP 202 ngay sau Reserve, payment chạy sau qua EventBus → cần lưu state ở đâu đó để consumer biết phải làm gì.

**Các lựa chọn cân nhắc.**
- **Option 1 — `SagaStateRepository` là optional. Nếu null → không persist state.**: linh hoạt. Nhược: nếu app restart giữa Reserve và payment, mọi saga in-flight bị mất → tiền có thể đã trừ nhưng order không được confirm.
- **Option 2 — `SagaStateRepository` là bắt buộc. Constructor throw nếu null.**: cứng nhắc. Đảm bảo developer không vô tình quên.

**Quyết định.** Option 2 — `AsynchronousSagaOrchestrator` constructor throw `FrameworkException(SYSTEM_ERROR)` nếu `sagaStateRepository == null`.

**Lý do.**
1. **Fail-fast at boot tốt hơn fail at runtime.** Nếu phát hiện thiếu lúc app start → developer biết ngay. Nếu để tới khi crash giữa Reserve và payment → bug âm thầm, mất tiền thật.
2. **Async saga không có `SagaStateRepository` = sai về căn bản** — không phải optional, là core requirement.
3. Tốt hơn là báo lỗi rõ với hint cụ thể: *"Please implement SagaStateRepository<YourOrderType> and register it as a Spring bean."*

**Trade-off.**
- Developer phải tự implement `SagaStateRepository<O>`. Khắc phục: ship sẵn `RedissonSagaStateRepository` mặc định trong starter (chưa có, ghi nhận trong roadmap).
- Test phải mock `SagaStateRepository` cho mọi unit test của AsyncSaga.

**Bằng chứng.**
- `hcr-saga/.../orchestrator/async/AsynchronousSagaOrchestrator.java` — constructor có check:
  ```java
  if (sagaStateRepository == null) {
      throw new FrameworkException(
          FailureReason.SYSTEM_ERROR, null, null,
          "AsynchronousSaga requires a SagaStateRepository bean. " +
          "Please implement SagaStateRepository<YourOrderType> and register " +
          "it as a Spring bean.");
  }
  ```

---

### C3 — Tại sao reconciliation chạy `@Scheduled` ở mọi instance + distributed lock, thay vì leader election?

★ **Mức độ tin cậy:** ♦

**Bối cảnh.** Khi deploy nhiều app instance, chỉ 1 instance được làm reconciliation tại 1 thời điểm. Có hai pattern chính: **leader election** (chỉ 1 instance chạy `@Scheduled`) hoặc **all run + distributed lock** (mọi instance chạy nhưng chỉ 1 acquire lock được làm việc).

**Các lựa chọn cân nhắc.**
- **Option 1 — Leader election** (vd: ZooKeeper, etcd, Spring Cloud Bus): chỉ 1 instance chạy scheduled → đơn giản, không có race. Nhược: thêm dependency (ZK/etcd), phức tạp deploy.
- **Option 2 — Distributed lock per cycle** (Redisson `RLock`): mọi instance chạy `@Scheduled`, nhưng tryLock với short waitTime → chỉ 1 instance acquire được làm việc, các instance khác skip cycle.

**Quyết định.** Option 2. Lock key `hcr:reconciliation:lock`, `tryLock(30s wait, 60s lease)`.

**Lý do.**
1. **Không thêm dependency mới.** HCR đã yêu cầu Redis (cho rate limit, idempotency, P3) → tận dụng cho lock.
2. **Auto-failover.** Nếu instance đang giữ lock crash, lock TTL hết hạn → instance khác acquire ngay cycle sau. Leader election cần re-elect, có thể chậm hơn.
3. **Code đơn giản** — chỉ cần Redisson `RLock`, không cần subscribe/unsubscribe leader event.
4. **Phù hợp với reconciliation** vì mỗi cycle là độc lập, không cần leader liên tục.

**Trade-off.**
- Worst case: 2 instance acquire lock cùng lúc do clock skew/Redlock weakness (Kleppmann 2016) → 2 cycle reconciliation chạy đồng thời. Nhưng vì reconciliation idempotent (qua `processed_events` dedup), không gây hại — chỉ tốn thêm 1 cycle CPU.
- Không có fencing token → không an toàn 100% theo lý thuyết. HCR chấp nhận vì chi phí thực tế thấp.

**Bằng chứng.**
- `hcr-reconciliation/.../AbstractReconciliationService.java`:
  ```java
  private static final String DISTRIBUTED_LOCK_KEY = "hcr:reconciliation:lock";

  @Scheduled(fixedDelayString = "${hcr.reconciliation.schedule-delay-ms:300000}")
  public final void runReconciliation() {
      RLock lock = redissonClient.getLock(DISTRIBUTED_LOCK_KEY);
      if (!lock.tryLock(30, 60, TimeUnit.SECONDS)) {
          log.debug("Skip cycle: another instance running");
          return;
      }
      try { /* … 5 cases … */ } finally { lock.unlock(); }
  }
  ```

---

### C4 — Tại sao chấp nhận at-least-once thay vì exactly-once?

★ **Mức độ tin cậy:** ♦

**Bối cảnh.** Kafka từ phiên bản 0.11 cung cấp Exactly-Once Semantics (EoS) qua transactional producer + consumer. Có thể đảm bảo mỗi event được xử lý đúng 1 lần. HCR chọn không dùng EoS.

**Các lựa chọn cân nhắc.**
- **Option 1 — Kafka Exactly-Once:** lý tưởng. Nhược: throughput giảm ~30%, cấu hình phức tạp, chỉ Kafka hỗ trợ (RabbitMQ và Redis Streams thì không).
- **Option 2 — At-least-once + dedup ở consumer (qua bảng `processed_events`):** simple, hoạt động trên mọi adapter, chi phí dedup thấp.

**Quyết định.** Option 2.

**Lý do.**
1. **HCR có 4 EventBus adapter** (Kafka, RabbitMQ, Redis Streams, In-memory). Chỉ Kafka có EoS native → nếu chọn EoS, các adapter khác không tương thích → mâu thuẫn với tính abstraction.
2. **Pattern at-least-once + dedup là chuẩn industry.** Hầu như mọi hệ thống lớn (Stripe, Uber, Netflix) đều dùng pattern này thay vì EoS.
3. **Throughput penalty của EoS** (~30% theo benchmark Confluent) là quá lớn cho một framework hứa "high concurrency".
4. **Dedup cost rẻ:** một INSERT vào `processed_events` (UUID PK) có chi phí ~0.1ms — không đáng kể so với event processing thật.

**Trade-off.**
- Phải thiết kế consumer idempotent — đây là yêu cầu được nhấn mạnh trong contract của `EventHandler` interface.
- Bảng `processed_events` tăng theo throughput — cần `ProcessedEventsCleanupJob` xoá định kỳ.

**Bằng chứng.**
- `hcr-eventbus/.../EventBus.java` javadoc: *"Delivery guarantee: At-least-once — consumer có thể nhận cùng event nhiều hơn 1 lần, nên mọi EventHandler PHẢI implement idempotency."*.
- 4 adapter implementation đều dùng at-least-once.

---

# NHÓM D — QUYẾT ĐỊNH VỀ TECH STACK & MODULE BOUNDARY

---

### D1 — Tại sao chọn Spring Boot 3.2 (Java 17) thay vì Quarkus / Micronaut?

★ **Mức độ tin cậy:** ♢ *(suy luận, sinh viên xác nhận)*

**Bối cảnh.** Có 3 lựa chọn cho Java backend framework hiện đại: Spring Boot, Quarkus (Red Hat), Micronaut (Object Computing).

**Các lựa chọn cân nhắc.**
- **Spring Boot 3.2:** mature, ecosystem lớn nhất, hầu hết developer Việt Nam quen thuộc. Nhược: startup chậm, RAM lớn.
- **Quarkus:** GraalVM native, startup ms, RAM thấp. Nhược: ecosystem nhỏ hơn, một số thư viện phổ biến (Redisson, Resilience4j) cần adapter.
- **Micronaut:** AOT compilation, performance tốt. Nhược: ecosystem nhỏ nhất.

**Quyết định.** Spring Boot 3.2.5 + Java 17.

**Lý do.**
1. **Đồ án tốt nghiệp** — ưu tiên framework mà GVHD và sinh viên đều quen, dễ debug, dễ tài liệu hoá.
2. **Ecosystem chín muồi:** Resilience4j, Redisson, Micrometer đều có Spring Boot starter sẵn → giảm thời gian wiring.
3. **Java 17 LTS** — đủ hiện đại (records, pattern matching, sealed classes) mà vẫn có support dài hạn cho production.
4. **Mục tiêu là chứng minh framework concept**, không phải tối ưu micro-latency. Spring Boot đủ nhanh.

**Trade-off.**
- Startup ~5s, RAM ~256MB. Không lý tưởng cho serverless/scale-to-zero.
- Khi cần native, phải migrate sang Spring AOT (Spring Boot 3 hỗ trợ GraalVM) — dự phòng trong tương lai.

**Bằng chứng.**
- `pom.xml` (root) — `spring-boot-starter-parent 3.2.5`, Java 17.

---

### D2 — Tại sao chọn Redisson thay vì Lettuce / Jedis làm Redis client?

★ **Mức độ tin cậy:** ♢

**Bối cảnh.** 3 Java Redis client phổ biến: Jedis (cũ, blocking), Lettuce (mới, non-blocking, default trong Spring Data Redis), Redisson (high-level, đầy đủ utilities).

**Các lựa chọn cân nhắc.**
- **Lettuce:** default trong Spring Boot. Async API tốt. Nhược: distributed lock, RScript, Streams API ít trừu tượng — phải tự implement.
- **Redisson:** API mức cao — `RLock`, `RScript`, `RStream`, `RMap`, … hoạt động ngay. Tốc độ tương đương Lettuce.
- **Jedis:** legacy, blocking. Không khuyến nghị cho hệ thống mới.

**Quyết định.** Redisson 3.27.2.

**Lý do.**
1. **Distributed lock built-in** — `RLock` implementation Redlock chuẩn, có lease, có watchdog tự renew. Reconciliation cần lock này → dùng Redisson.
2. **`RScript` API** — load Lua script qua classpath, cache script SHA, gọi bằng `script.eval()` rất gọn. P3 dùng nhiều.
3. **Redis Streams support** — `RStream` cho `RedisStreamEventBusAdapter` đơn giản hơn nhiều so với Lettuce thuần.
4. **Spring Boot starter sẵn** — `redisson-spring-boot-starter` auto-configure.

**Trade-off.**
- Redisson là dependency lớn (~1MB) so với Lettuce. Không quan trọng cho server-side.
- API riêng — code không portable sang ứng dụng dùng Lettuce. Khắc phục: chỉ dùng Redisson trong implementation, abstraction (`InventoryStrategy`, `RateLimiter`, `EventBus`) không expose Redisson type ra ngoài.

**Bằng chứng.**
- `pom.xml` — `redisson-spring-boot-starter`.
- `RedisAtomicStrategy.java` dùng `RScript`.
- `RedisTokenBucketRateLimiter.java` dùng `RScript` cho token bucket Lua.
- `AbstractReconciliationService.java` dùng `RLock`.

---

### D3 — Tại sao tách thành 12 module Maven thay vì 1 fat jar?

★ **Mức độ tin cậy:** ♦

**Bối cảnh.** HCR có thể đóng gói thành 1 module duy nhất `hcr-framework` chứa tất cả code. Thực tế chia thành 12 module với dependency rõ ràng giữa các module.

**Các lựa chọn cân nhắc.**
- **Option 1 — 1 fat module:** developer thêm 1 dependency, có ngay tất cả. Build nhanh hơn (no Maven module overhead).
- **Option 2 — Multi-module với dependency rõ ràng:** developer chỉ pull những module thực sự dùng (vd: nếu không cần Reconciliation, không pull `hcr-reconciliation`). Build chậm hơn, complexity cao hơn nhưng có giá trị kiến trúc.

**Quyết định.** Option 2 — 12 module.

**Lý do.**
1. **Tách bạch concern rõ ràng** — `hcr-core` chỉ shared types, `hcr-eventbus` chỉ pub/sub, … Mỗi module có Javadoc, có contract.
2. **Phụ thuộc trực quan:** ai phụ thuộc ai được Maven enforce. Không có chuyện `hcr-core` phụ thuộc `hcr-saga` (vi phạm quy ước "core là foundation").
3. **Developer có thể chọn lọc:** một app nhỏ có thể chỉ pull `hcr-core + hcr-inventory` (P1 only) mà không pull EventBus.
4. **Spring Boot starter pattern** — `hcr-spring-boot-starter` là meta-package gộp tất cả qua `hcr-autoconfigure`. Developer thường chỉ thêm 1 dependency này.
5. **Hiệu quả cho thesis-level:** kiến trúc module rõ ràng dễ trình bày, dễ vẽ dependency graph.

**Trade-off.**
- Build time tăng — Maven phải compile 12 module thay vì 1. Khắc phục: cache Maven local, build chỉ module thay đổi.
- Phải maintain 12 `pom.xml` — khắc phục bằng `dependencyManagement` ở root.

**Bằng chứng.**
- `pom.xml` (root) liệt kê 12 module.
- `architecture.md` (root) — section "1.3. Module Dependency Graph" minh hoạ.

---

### D4 — Tại sao có 4 EventBus adapter (Kafka, RabbitMQ, Redis Streams, In-memory) thay vì cố định 1?

★ **Mức độ tin cậy:** ♦

**Bối cảnh.** Một framework có thể "chỉ hỗ trợ Kafka" để giảm độ phức tạp. HCR chọn 4 adapter switchable.

**Các lựa chọn cân nhắc.**
- **Option 1 — Chỉ Kafka:** đơn giản nhất, Kafka phổ biến. Nhược: bắt buộc developer phải có Kafka cluster — nặng cho dev/test, không phù hợp với team chỉ có Redis sẵn.
- **Option 2 — Pluggable adapter (4 implementation):** linh hoạt nhất. Phức tạp hơn về design.

**Quyết định.** Option 2.

**Lý do mỗi adapter tồn tại.**
- **In-memory:** unit test, local dev, sample app khởi động không cần broker.
- **Kafka:** production high-throughput, replay, partition.
- **RabbitMQ:** một số team Việt Nam đã có sẵn Rabbit cluster → không muốn deploy thêm Kafka.
- **Redis Streams:** team chỉ muốn 1 stack (Redis cho cả cache + queue) → Redis Streams đủ tốt cho throughput trung bình.

**Lý do framework-level.**
1. **Tránh lock-in.** Developer có thể bắt đầu với Redis Streams (single-stack), sau đó migrate sang Kafka chỉ bằng đổi 1 dòng `hcr.event-bus.type`.
2. **Demo flexibility** trong thesis — chứng minh kiến trúc abstraction tốt.
3. **`EventBusCapabilities` interface** cho phép code conditional dựa trên capability thực tế (vd: chỉ replay nếu adapter hỗ trợ).

**Trade-off.**
- Phải maintain 4 implementation. Test coverage phải cover cả 4.
- API phải lowest-common-denominator của 4 broker — không expose Kafka-specific feature như partition assignment.

**Bằng chứng.**
- `hcr-eventbus/.../adapter/` chứa 4 sub-package.
- `EventBusCapabilities.java` — interface để query capability.

---

# NHÓM E — QUYẾT ĐỊNH CHẤP NHẬN TRADE-OFF ĐÃ BIẾT

---

### E1 — Tại sao chấp nhận gap giữa Redis DECR và `EventBus.publish()` trong P3?

★ **Mức độ tin cậy:** ♦

**Bối cảnh.** Trong P3 critical path, code chạy theo thứ tự:
```
1. inventoryStrategy.reserve() → Redis DECR (atomic)
2. eventBus.publish(ResourceReservedEvent) → Kafka send
3. saveOrder() / sagaStateRepository.save()
```

Nếu app crash giữa bước 1 và bước 2, **event bị mất**: Redis đã giảm nhưng DB không biết → eventually inconsistency âm thầm.

**Các lựa chọn cân nhắc.**
- **Option 1 — Đảm bảo atomic giữa Redis và Kafka:** phải dùng outbox pattern hoặc 2PC. Outbox pattern cần ghi vào DB trước → mâu thuẫn với A3 (P3 zero-DB-hit).
- **Option 2 — Bỏ qua gap, có reconciliation cứu:** chấp nhận window 5 phút inconsistency.
- **Option 3 — Redis Streams làm transactional log:** ghi vào Redis Stream cùng atomic transaction với DECR. Nhược: ràng buộc EventBus phải là Redis Streams.

**Quyết định.** Option 2 — chấp nhận gap, dùng reconciliation case 4 (UNPERSISTED_RESERVATION) cứu.

**Lý do.**
1. **Giữ A3 (zero-DB-hit) là ưu tiên cao hơn**. Outbox pattern phá vỡ điều này.
2. **Window 5 phút là acceptable** với hợp đồng eventual consistency của P3.
3. **Reconciliation đã thiết kế cho case này** — case 4 phát hiện order CONFIRMED nhưng DB inventory chưa giảm, re-publish event với cùng eventId → consumer dedup không double-decrement.
4. **Crash giữa bước 1 và 2 là hiếm** — chỉ xảy ra trong thời gian rất ngắn.

**Trade-off.**
- Có một consistency window không nhỏ (≤ 5 phút). Trong window này, dashboard có thể hiển thị số liệu sai.
- Phải document rõ cho developer dùng P3.
- Không phù hợp cho use case yêu cầu strong consistency (developer chọn P1/P2 thay).

**Bằng chứng.**
- `CLAUDE.md` — section "Known limitations": *"P3 gap: Giua Redis DECR thanh cong va EventBus.publish() — neu crash o giua, event mat. Reconciliation fix ≤ 5 phut."*

---

### E2 — Tại sao Batch consumer ACK trước khi flush vào DB?

★ **Mức độ tin cậy:** ♦

**Bối cảnh.** `BatchInventoryPersistenceConsumer` gom event theo `resourceId`, flush 1 transaction cho cả batch để tăng throughput. Theo at-least-once chuẩn, ACK phải sau khi DB commit. HCR ngược lại — ACK ngay khi enqueue, flush sau ≤ 1 giây.

**Các lựa chọn cân nhắc.**
- **Option 1 — ACK sau flush (chuẩn at-least-once):** an toàn 100%. Nhược: throughput sụp vì broker phải hold message trong buffer cho đến khi batch full.
- **Option 2 — ACK trước flush:** throughput cao. Nhược: nếu crash giữa ACK và flush → mất batch event đã ACK.

**Quyết định.** Option 2.

**Lý do.**
1. **Throughput requirement** của P3 batch mode > 5000 events/s. Option 1 không đạt được vì broker overhead quá lớn.
2. **Reconciliation case 3 (INVENTORY_MISMATCH) cover trường hợp này.** So sánh Redis vs DB sau crash → phát hiện DB thiếu → fix bằng cách điều chỉnh Redis hoặc DB theo policy.
3. **Crash window thực tế rất nhỏ** — flush mỗi ≤ 1 giây. Worst case mất 1 giây events.
4. Batch mode là **opt-in** (`hcr.inventory.persistence.mode: batch`) — developer biết chấp nhận trade-off khi bật.

**Trade-off.**
- Không an toàn theo nghĩa at-least-once chuẩn — đây là chấp nhận có ý thức.
- Reconciliation phải có khả năng so sánh Redis vs DB hiệu quả → đã có `InventoryReconciler.compareRedisVsDb()`.
- Default mode là `single` (an toàn hơn). Batch chỉ nên bật khi đã hiểu rõ.

**Bằng chứng.**
- `CLAUDE.md` — Known limitations: *"Batch consumer ACK truoc flush — neu crash giua ACK va flush, data loss. Reconciliation fix."*
- `hcr-inventory/.../persistence/PersistenceConfig.java` — default `mode = single`.
- `hcr-inventory/.../persistence/BatchInventoryPersistenceConsumer.java`.

---

# Phụ lục — Cách dùng file này khi viết Chương 3

## 1. Mapping decision → mục trong báo cáo

| Decision | Đặt vào mục nào của Chương 3 |
|---|---|
| A1 (3 strategies) | 3.1 (Tổng quan) — phần giới thiệu InventoryStrategy abstraction |
| A2 (Orchestration) | 3.2.5 (hcr-saga) — phần "Lý do chọn orchestration" |
| A3 (Zero-DB-hit P3) | 3.2.3 (hcr-inventory) — phần đặc điểm P3, hoặc 3.1 luồng async |
| A4 (Tách 2 transaction) | 3.1 (Tổng quan) — sequence diagram, hoặc 3.2.5 hcr-saga |
| B1 (TransactionTemplate) | 3.2.3 (hcr-inventory) — phần implementation note |
| B2 (P2 retry tx mới) | 3.2.3 (hcr-inventory) — phần "Implementation P2" |
| B3 (processed_events dedup) | 3.2.3 (hcr-inventory) — phần Persistence consumer |
| B4 (sort key alphabet) | 3.2.3 (hcr-inventory) — phần reserveBatch |
| B5 (Lua vs WATCH) | 3.2.3 (hcr-inventory) — phần "Implementation P3" |
| C1 (release không reject CB OPEN) | 3.2.3 (hcr-inventory) — phần Decorator |
| C2 (Async saga bắt buộc Repo) | 3.2.5 (hcr-saga) — phần Async orchestrator |
| C3 (Reconciliation distributed lock) | 3.2.7 (hcr-reconciliation) |
| C4 (At-least-once) | 3.2.2 (hcr-eventbus) — phần delivery semantics |
| D1 (Spring Boot) | 3.1 (Tổng quan) — Tech stack |
| D2 (Redisson) | 3.1 (Tổng quan) — Tech stack |
| D3 (12 modules) | 3.1 (Tổng quan) — Module structure |
| D4 (4 EventBus adapters) | 3.2.2 (hcr-eventbus) |
| E1 (P3 gap) | 3.2.7 (hcr-reconciliation) — case 4, hoặc 3.4 Limitations |
| E2 (Batch ACK trước flush) | 3.2.3 (hcr-inventory) — Persistence mode, hoặc 3.4 Limitations |

## 2. Prompt mẫu khi nhờ LLM viết một sub-section

> *"Dùng nội dung trong `architecture.md` của hcr-inventory + decision A1, A3, B3, B5, C1 từ `03-decision-log.md` để viết mục 3.2.3 'Module hcr-inventory' của báo cáo. Độ dài 4-5 trang. Cấu trúc: vai trò trong hệ thống → class diagram → 3 chiến lược (P1/P2/P3) chi tiết → các quyết định kỹ thuật quan trọng (lấy từ decision log). Văn phong học thuật, có dẫn nguồn tới Chương 2 khi nhắc đến lý thuyết."*

## 3. Decision có mức tin cậy ♢ cần xác nhận

Sinh viên nên review và confirm/sửa lại hai decision sau (phần "Lý do" có thể có động cơ khác mà chỉ sinh viên biết):

- **D1** — chọn Spring Boot vs Quarkus/Micronaut.
- **D2** — chọn Redisson vs Lettuce/Jedis.

Các decision còn lại (mức ♦) đã được tài liệu hoá rõ trong `CLAUDE.md`, javadoc, hoặc cấu trúc code — rationale có thể trích dẫn trực tiếp.

---

> **Hết file 03.** &nbsp;·&nbsp; Tiếp theo: `04-evaluation.md` (template Chương 4 — sẽ điền số liệu khi load test xong).
