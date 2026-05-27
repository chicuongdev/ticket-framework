# HCR Framework — Edge Cases Đã Xử Lý

> Tài liệu tổng hợp các edge case đã được phân tích, thiết kế, và xử lý trong framework HCR.
> Phục vụ phần "Xử lý các tình huống đặc biệt" trong báo cáo đồ án.
>
> Cập nhật: 2026-05-01
> Nguồn: `edge_cases_notes.txt` + audit codebase ngày 2026-05-01

---

## Tổng quan

Framework HCR phải đảm bảo **zero oversell** trong môi trường phân tán có nhiều nguồn lỗi: crash, network partition, message loss, race condition. Tài liệu này liệt kê **20 edge case** đã được nhận diện, phân tích và xử lý, được phân loại theo module:

| Nhóm | Số case | Trạng thái |
|------|:-:|------------|
| Saga Orchestration (S) | 5 | ✅ Đã xử lý |
| Inventory (I) | 5 | ✅ 4/5 đã xử lý, 1 pending implement |
| EventBus (E) | 4 | ✅ 3/4 đã xử lý, 1 pending implement |
| Gateway (G) | 3 | ✅ Đã xử lý |
| Reconciliation (R) | 3 | ✅ 2/3 đã xử lý, 1 đang thiết kế |
| **Tổng** | **20** | ✅ 19/20 đã xử lý, 1 đang thiết kế (R1) |

---

## Nhóm S — Saga Orchestration

### [S1] Step thất bại giữa chừng

**Vấn đề / rủi ro**
Trong Saga có nhiều bước (Reserve → Charge → Confirm), nếu bước N thành công nhưng bước N+1 thất bại, hệ thống có thể rơi vào trạng thái không nhất quán: ví dụ tồn kho đã giảm nhưng tiền chưa trừ.

**Giải pháp áp dụng**
Saga Orchestrator thực thi **compensating transaction** theo thứ tự ngược lại các bước đã hoàn thành. Mỗi `SagaStep` định nghĩa cặp `execute()` + `compensate()`. Khi step thất bại, orchestrator gọi `compensate()` trên các step đã thành công trước đó.

**Bằng chứng implementation**
- `hcr-saga/src/main/java/io/hrc/saga/orchestrator/AbstractSagaOrchestrator.java`
- `hcr-saga/src/main/java/io/hrc/saga/step/SagaStep.java`

---

### [S2] Compensating transaction cũng thất bại

**Vấn đề / rủi ro**
Khi đã rơi vào kịch bản phải compensate, có thể chính bước compensate cũng thất bại (ví dụ: payment đã trừ tiền, cố gọi inventory để restore nhưng Inventory Service đang down). Nếu không xử lý, hệ thống mất luôn cả 2 capability — tồn kho sai và tiền sai.

**Giải pháp áp dụng**
Persist `SagaState` vào DB với trạng thái `COMPENSATING_FAILED`. Reconciliation Module quét định kỳ các saga ở trạng thái này và retry compensate khi service phục hồi.

**Bằng chứng implementation**
- `hcr-saga/src/main/java/io/hrc/saga/state/SagaState.java`
- `hcr-reconciliation/src/main/java/io/hrc/reconciliation/AbstractReconciliationService.java`

---

### [S3] Saga Orchestrator crash giữa chừng

**Vấn đề / rủi ro**
JVM crash khi đang thực thi giữa các bước → mất trạng thái in-memory → không biết đã hoàn thành đến đâu khi restart.

**Giải pháp áp dụng**
`SagaState` được persist vào DB **trước khi** thực thi step tiếp theo. Khi service khởi động lại, Reconciliation đọc các SagaState dở dang và resume hoặc rollback đúng điểm dừng.

**Quan trọng:** thứ tự là `persist → execute`, không phải `execute → persist`. Persist trước đảm bảo recovery có đủ thông tin.

**Bằng chứng implementation**
- `hcr-saga/src/main/java/io/hrc/saga/orchestrator/AbstractSagaOrchestrator.java`

---

### [S4] Timeout từ một step (đặc biệt: payment)

**Vấn đề / rủi ro**
Payment gateway không trả lời trong thời gian quy định. Nếu coi là **failure** và gọi compensate, có nguy cơ thực ra payment đã thành công ở phía gateway → khách bị trừ tiền nhưng đơn bị hủy. Nếu coi là **success**, có nguy cơ giao dịch chưa hoàn thành.

**Giải pháp áp dụng**
- Mỗi `SagaStep` có `timeoutMs` riêng + `retryPolicy`.
- Phân biệt rõ 3 trạng thái: `SUCCESS` / `FAILED` / `UNKNOWN` (timeout). Khi `UNKNOWN`, không tự động compensate — chuyển sang `OrderReconciler` query lại payment gateway sau.
- Mọi retry dùng cùng **idempotency key** để gateway không charge 2 lần.

**Bằng chứng implementation**
- `hcr-payment/src/main/java/io/hrc/payment/gateway/PaymentResult.java` — enum `PaymentStatus { SUCCESS, FAILED, UNKNOWN }`
- `hcr-payment/src/main/java/io/hrc/payment/handler/TimeoutHandler.java`
- `hcr-reconciliation/src/main/java/io/hrc/reconciliation/order/OrderReconciler.java`

---

### [SA1] Saga-level timeout (toàn bộ saga)

**Vấn đề / rủi ro**
Saga bắt đầu khi vẫn còn vé, nhưng chạy quá lâu (do nhiều retry hoặc network slow) đến khi step cuối thì điều kiện đã thay đổi. Cần một timeout toàn bộ saga, không chỉ per-step.

**Giải pháp áp dụng**
`AbstractSagaOrchestrator` set `order.expiresAt = now + reservationTimeout` ngay khi bắt đầu. Mỗi step trước khi thực thi check `order.isExpired()` → nếu hết hạn thì hủy + rollback ngay. Reconciliation định kỳ quét các order quá hạn để cleanup.

Default timeout: 5 phút (configurable).

**Bằng chứng implementation**
- `hcr-saga/src/main/java/io/hrc/saga/orchestrator/AbstractSagaOrchestrator.java:124`
- `hcr-core/src/main/java/io/hrc/core/AbstractOrder.java` — field `expiresAt`

---

## Nhóm I — Inventory

### [I1] Redis crash giữa Lua DECR và publish event

**Vấn đề / rủi ro**
Trong P3, sau khi Lua script DECR Redis thành công nhưng trước khi `EventBus.publish()` được gọi, nếu service crash → event mất → DB không bao giờ biết về reservation này → tồn kho ảo.

**Giải pháp áp dụng**
Framework HCR **chấp nhận gap** này và xử lý qua **Reconciliation periodic** thay vì dùng Outbox pattern phức tạp.

Lý do chọn cách này:
- Outbox pattern bắt buộc thêm DB write trong critical path (mất ý nghĩa của P3 — "DB ngoài critical path")
- Reconciliation cycle (default 60s, configurable) phát hiện được mismatch trong tối đa **5 phút**
- Acceptable trong workload concert ticket / flash sale (tolerance > vài phút)

**Bằng chứng implementation**
- `hcr-inventory/src/main/java/io/hrc/inventory/strategy/redis/RedisAtomicStrategy.java:114-117`
- `hcr-reconciliation/src/main/java/io/hrc/reconciliation/inventory/InventoryReconciler.java`
- Documented gap trong `CLAUDE.md` § "Known limitations"

---

### [I2] Message broker (Kafka) down khi cần publish

**Vấn đề / rủi ro**
Sau khi Redis DECR thành công, nếu Kafka down, `publish()` thất bại liên tục. Nếu retry vô tận → request hang. Nếu fallback ghi DB trực tiếp → DB bị overload (đánh mất ý nghĩa P3).

**Giải pháp áp dụng**
- **Circuit Breaker** trip khi tỷ lệ publish failure cao → từ chối request mới (fail-fast) thay vì để queue tích tụ.
- **KHÔNG fallback** sang DB write trực tiếp.
- Khi Kafka phục hồi, EventBus retry tự động + reconciliation phát hiện và fix các reservation chưa được persist.

**Bằng chứng implementation**
- `hcr-inventory/src/main/java/io/hrc/inventory/decorator/CircuitBreakerInventoryDecorator.java`
- `hcr-eventbus/src/main/java/io/hrc/eventbus/adapter/KafkaEventBusAdapter.java`

---

### [I3] Optimistic Lock retry loop vô tận (P2)

**Vấn đề / rủi ro**
Trong P2 (Optimistic Lock với `@Version`), khi contention cao, nhiều transaction có thể conflict liên tục → retry vô tận → throughput sụp.

**Giải pháp áp dụng**
- `maxRetries` (default: 3) — vượt ngưỡng → trả `OptimisticLockExhaustedException` rõ ràng.
- **Exponential backoff** giữa các lần retry (50ms → 100ms → 200ms) — giảm hot loop.
- Mỗi retry tạo **transaction mới** (Hibernate cache version cũ trong session, phải clear).
- Document rõ trong README: P2 KHÔNG phù hợp với high-contention workload (>1000 req/s vào cùng resource).

**Bằng chứng implementation**
- `hcr-inventory/src/main/java/io/hrc/inventory/strategy/optimistic/OptimisticLockStrategy.java`

---

### [I4] Tồn kho âm do race condition trong P3

**Vấn đề / rủi ro**
Nếu lập trình viên tự implement P3 sai cách (vd. `GET stock` → check `> 0` → `DECR`), nhiều request đồng thời có thể pass check và đều decrement → tồn kho âm → oversell.

**Giải pháp áp dụng**
Framework **bắt buộc** dùng **Lua script** `EVAL` cho operation reserve — Lua script chạy atomic trong Redis (single-threaded). Check + decrement trong cùng 1 invocation:

```lua
local available = tonumber(redis.call('GET', KEYS[1]))
if available == nil or available < quantity then
    return -1
end
return redis.call('DECRBY', KEYS[1], quantity)
```

Đây là enforcement ở mức framework — developer dùng `RedisAtomicStrategy` không có cách bypass.

**Bằng chứng implementation**
- `hcr-inventory/src/main/resources/lua/inventory_reserve.lua`
- `hcr-inventory/src/main/resources/lua/inventory_release.lua`
- `hcr-inventory/src/main/java/io/hrc/inventory/strategy/redis/RedisAtomicStrategy.java`

---

## Nhóm E — EventBus

### [E1] Message được deliver nhiều lần (at-least-once)

**Vấn đề / rủi ro**
Hầu hết message broker (Kafka, RabbitMQ) đảm bảo at-least-once: nếu consumer crash trước khi ACK, broker resend. Nếu consumer xử lý lần 2 mà không có biện pháp, sẽ dẫn đến duplicate update (tồn kho giảm 2 lần, tiền trừ 2 lần).

**Giải pháp áp dụng**
**Idempotent Consumer pattern** dựa trên `eventId`:

```sql
CREATE TABLE hcr_processed_events (
    event_id VARCHAR(64) PRIMARY KEY,
    processed_at TIMESTAMP
);
```

Mỗi consumer trước khi xử lý sẽ INSERT `eventId` vào bảng. Nếu trùng → bỏ qua, ACK ngay.

**Bằng chứng implementation**
- `hcr-inventory/src/main/java/io/hrc/inventory/persistence/InventoryPersistenceConsumer.java:162-173`
- `hcr-inventory/src/main/java/io/hrc/inventory/persistence/BatchInventoryPersistenceConsumer.java:244-264`

---

### [E2] Consumer xử lý xong nhưng ghi event_id thất bại (partial state)

**Vấn đề / rủi ro**
Nếu UPDATE inventory thành công nhưng INSERT vào `hcr_processed_events` thất bại → lần retry sau, message lại được xử lý → UPDATE 2 lần.

**Giải pháp áp dụng**
**Same-transaction guarantee**: UPDATE inventory và INSERT processed_events nằm trong **CÙNG MỘT** `@Transactional` block. Nếu INSERT fail → UPDATE rollback theo. Atomic — không có partial state.

```java
transactionTemplate.execute(status -> {
    insertProcessedEvent(eventId);   // dedup check
    updateInventoryAvailable(resourceId, delta);
    return null;
});
```

Đây là implementation của E1 — E2 là edge case con của E1, được giải quyết tự động.

**Bằng chứng implementation**
- Cùng files với [E1]

---

### [E3] Message đến sai thứ tự (out-of-order)

**Vấn đề / rủi ro**
Trong môi trường phân tán, event có thể đến không đúng thứ tự thời gian. Ví dụ `INVENTORY_RESTORED` có thể đến trước `INVENTORY_RESERVED` của cùng order → consumer xử lý sai logic.

**Giải pháp áp dụng**
- Khi dùng **Kafka**: partition key = `resourceId` đảm bảo events của cùng resource đi qua **cùng partition** → thứ tự được đảm bảo bởi broker.
- Khi dùng **InMemory**: single-threaded consumer → thứ tự đảm bảo native.
- Document rõ: framework giả định broker đảm bảo per-key ordering. Nếu dùng broker không đảm bảo (vd. RabbitMQ với multiple consumer same queue), developer phải tự handle.

**Bằng chứng implementation**
- `hcr-eventbus/src/main/java/io/hrc/eventbus/adapter/KafkaEventBusAdapter.java` — partition key strategy

---

### [EA2] Poison message — Dead Letter Queue

**Vấn đề / rủi ro**
Nếu một message bị malformed hoặc gây exception mỗi lần xử lý, broker sẽ retry vô tận → consumer bị block, queue tích tụ, các message sau không được xử lý.

**Giải pháp áp dụng**
Mỗi adapter EventBus có **Dead Letter Queue** mechanism:
- **InMemory**: `deadLetterMap` lưu các message thất bại sau N retry
- **Kafka**: log + skip (option: route to `*.dlq` topic)
- **RabbitMQ**: requeue với retry count, sau N lần → publish vào DLQ exchange
- **RedisStream**: route vào `{stream}.dlq`

Operator có thể inspect DLQ qua actuator endpoint hoặc Grafana dashboard.

**Bằng chứng implementation**
- `hcr-eventbus/src/main/java/io/hrc/eventbus/adapter/InMemoryEventBusAdapter.java:41-42`
- `hcr-eventbus/src/main/java/io/hrc/eventbus/adapter/RedisStreamEventBusAdapter.java:207-216`
- `hcr-eventbus/src/main/java/io/hrc/eventbus/adapter/RabbitMQEventBusAdapter.java:180-182`

---

## Nhóm G — Gateway / Protection

### [G1] Duplicate request — phạm vi của Idempotency Key

**Vấn đề / rủi ro**
Phân biệt rõ idempotency key chống được gì và **không** chống được gì:
- ✅ Chống: client retry tự động (cùng UUID cho cùng ý định)
- ❌ KHÔNG chống: user double-click thật → 2 UUID khác nhau → 2 request hợp lệ

Nếu hiểu sai và kỳ vọng idempotency key giải quyết double-click, sẽ dẫn đến quyết định thiết kế sai.

**Giải pháp áp dụng**
- Document rõ scope trong javadoc của `IdempotencyHandler`: "cùng ý định = cùng key. Retry = cùng key. Yêu cầu mới = key mới."
- Double-click là **vấn đề UX** → frontend phải disable button sau lần bấm đầu, KHÔNG phải responsibility của framework.
- Framework chỉ cam kết: same `Idempotency-Key` header → trả về cùng response (cached).

**Bằng chứng implementation**
- `hcr-gateway/src/main/java/io/hrc/gateway/idempotency/IdempotencyHandler.java:20-22` — javadoc
- `hcr-gateway/src/main/java/io/hrc/gateway/idempotency/RedisIdempotencyHandler.java`

---

### [G2] Circuit Breaker false positive (trip nhầm)

**Vấn đề / rủi ro**
Latency spike nhỏ trong vài request có thể làm Circuit Breaker tính tỷ lệ failure cao và mở mạch không cần thiết → từ chối traffic hợp lệ.

**Giải pháp áp dụng**
Cấu hình Resilience4j với:
- `minimumNumberOfCalls`: ≥ 100 — chỉ bắt đầu tính rate sau khi đã có đủ samples
- `slidingWindowSize`: 100-500 — sliding window đủ lớn để spike nhỏ không làm trip
- `failureRateThreshold`: 50% — không nhạy quá

Default được provide trong `HcrAutoConfiguration` để developer không cần tự tune.

**Bằng chứng implementation**
- `hcr-autoconfigure/src/main/java/io/hrc/autoconfigure/HcrAutoConfiguration.java`
- `hcr-inventory/src/main/java/io/hrc/inventory/decorator/CircuitBreakerInventoryDecorator.java`

---

### [GA2] Idempotency key TTL

**Vấn đề / rủi ro**
- TTL quá ngắn: client retry sau 1 ngày → key đã expire → request được xử lý lại như mới → double charge.
- TTL quá dài: Redis memory phình to với hàng triệu key cũ → cost cao.

**Giải pháp áp dụng**
Default TTL = **24 giờ** — đủ cho 99.9% retry scenario thực tế. Có thể override qua property `hcr.gateway.idempotency.ttl`.

Tài liệu hóa cho developer: nếu app yêu cầu retry sau > 24h, phải tăng TTL hoặc dùng cơ chế khác (DB-backed idempotency).

**Bằng chứng implementation**
- `hcr-gateway/src/main/java/io/hrc/gateway/idempotency/RedisIdempotencyHandler.java:44-45`

---

## Nhóm R — Reconciliation

### [R2] Reconciliation chạy đồng thời với giao dịch đang xử lý

**Vấn đề / rủi ro**
Reconciliation đang sửa Redis trong khi một request thật đang DECR Redis cùng lúc → race condition → giá trị sai.

**Giải pháp áp dụng**
- **Distributed lock** (Redisson `RLock`) acquire trước khi reconciler chạy. Giải phóng sau khi xong.
- Lock scope: per cycle (toàn bộ resource list trong 1 lần chạy). Đủ để bảo vệ vì reconciler là single-instance.
- Lock có timeout để không deadlock vĩnh viễn nếu reconciler crash.

**Bằng chứng implementation**
- `hcr-reconciliation/src/main/java/io/hrc/reconciliation/AbstractReconciliationService.java:151-166`

---

### [R1] Phân biệt release hợp lệ vs lỗi thật ⏳

**Vấn đề / rủi ro**
Reconciliation hiện tại chỉ so sánh snapshot Redis vs DB. Một mismatch có thể là:
- Lag P3 bình thường (release hợp lệ, consumer chưa kịp catch up) → KHÔNG fix
- Bug thật (DECR Redis không có event tương ứng) → PHẢI fix + alert

→ Cách so sánh tĩnh **không phân biệt được** → có khả năng false negative (bỏ qua bug thật) hoặc false positive (fix sai release hợp lệ).

**Giải pháp đang thiết kế**
**Event log replay**: lưu mọi inventory event vào `hcr_inventory_log`. Reconciler replay log từ snapshot gần nhất để tính giá trị Redis kỳ vọng, so với Redis thực tế. Mismatch persistent qua N round → bug thật.

Status: design complete, chưa implement.

**Tham khảo**
- Design doc: [`r1_event_log_design.md`](r1_event_log_design.md)
- Implementation hiện tại (sẽ thay): `hcr-reconciliation/src/main/java/io/hrc/reconciliation/inventory/InventoryReconciler.java:95`

---

## Nhóm bổ sung — Phòng ngừa lan rộng

### [EA1] Bảng `hcr_processed_events` phình to

**Vấn đề / rủi ro**
Bảng dedup tăng tuyến tính theo throughput. Sau vài tháng, query check duplicate chậm dần → consumer chậm → backlog tích tụ → DB phình.

**Giải pháp áp dụng**
`ProcessedEventsCleanupJob` chạy theo `@Scheduled(fixedDelay)`:
- Default retention: **7 ngày** (lớn hơn maximum retry window của Kafka mặc định)
- Default interval: **1 giờ**
- Default initial delay: **60 giây** (tránh chạy ngay lúc startup)
- Configurable qua `hcr.event-bus.processed-events.*` properties
- Auto-wired bởi `HcrAutoConfiguration` khi `ProcessedEventRepository` bean tồn tại

Yêu cầu: ứng dụng phải bật `@EnableScheduling` ở entry-point class (cùng requirement với reconciliation).

**Bằng chứng implementation**
- `hcr-inventory/src/main/java/io/hrc/inventory/persistence/ProcessedEventsCleanupJob.java`
- `hcr-inventory/src/main/java/io/hrc/inventory/persistence/ProcessedEventRepository.java` — `deleteByProcessedAtBefore(Instant)`
- `hcr-autoconfigure/src/main/java/io/hrc/autoconfigure/HcrAutoConfiguration.java` — `processedEventsCleanupJob()` bean
- `hcr-autoconfigure/src/main/java/io/hrc/autoconfigure/HcrProperties.java` — `ProcessedEventsProperties`

---

## Bảng tổng kết

| ID | Edge case | Trạng thái | Module |
|----|-----------|:-:|--------|
| S1 | Step thất bại giữa chừng | ✅ | saga |
| S2 | Compensating transaction fail | ✅ | saga + reconciliation |
| S3 | Saga orchestrator crash | ✅ | saga |
| S4 | Step timeout (UNKNOWN status) | ✅ | saga + payment |
| SA1 | Saga-level timeout | ✅ | saga |
| I1 | Redis crash giữa DECR và publish | ✅ (qua reconciliation) | inventory |
| I2 | Broker down | ✅ | inventory + eventbus |
| I3 | P2 retry loop vô tận | ✅ | inventory |
| I4 | P3 oversell do race | ✅ (Lua script) | inventory |
| E1 | At-least-once duplicate | ✅ | eventbus |
| E2 | Partial state consumer | ✅ (same-TX) | eventbus |
| E3 | Out-of-order delivery | ✅ (Kafka partition) | eventbus |
| EA2 | Poison message / DLQ | ✅ | eventbus |
| G1 | Idempotency key scope | ✅ | gateway |
| G2 | Circuit Breaker false positive | ✅ | gateway |
| GA2 | Idempotency TTL | ✅ | gateway |
| R2 | Reconciliation concurrent với traffic | ✅ | reconciliation |
| EA1 | Dedup table phình to | ✅ | eventbus |
| R1 | Phân biệt release vs lỗi | ⏳ Đang thiết kế | reconciliation |

**Legend:** ✅ đã implement | ⏳ đã thiết kế, chưa implement

---

## Edge case ngoài phạm vi (đưa vào "Hướng phát triển" — Chương 6)

Các edge case sau đã được nhận diện nhưng quyết định **không xử lý** trong scope đồ án (chấp nhận limitation hoặc cần đầu tư lớn):

| ID | Mô tả ngắn | Lý do bỏ qua |
|----|-----------|--------------|
| SA2 | Compensation storm (100 saga cùng compensate) | Hiếm gặp với workload concert ticket. Cần rate limiter ở compensation path. |
| SA3 | Không thể undo email đã gửi | Best-effort compensation, ngoài scope inventory framework. |
| IA1 | Redis evict key tồn kho | Cần memory monitoring + eviction policy `noeviction`. Vận hành issue, không phải framework. |
| IA2 | Redis Cluster split-brain | Framework giới hạn ở **single Redis** hoặc **Sentinel**. Không hỗ trợ Cluster. |
| IA3 | Cache "sold out" status | Optimization, không phải correctness issue. |
| RA2 | Reconciliation tự fix vs alert-only | Hiện đã có `mismatchThreshold` để cấu hình. Đủ cho thesis scope. |
| GA1 | Rate limit theo IP vs user ID | Cần auth layer. Đồ án không có auth. |

---

## Cách dùng tài liệu này trong đồ án

**Chương 3 — Thiết kế:**
- Mỗi nhóm (S, I, E, G, R) nên có 1 subsection mô tả edge case + giải pháp.
- Dùng bảng tổng kết làm summary cuối chương.

**Chương 4 — Implementation:**
- Khi mô tả module, reference các edge case đã handle qua ID.
- Vd. "Module saga implement [S1], [S2], [S3], [S4], [SA1] thông qua AbstractSagaOrchestrator..."

**Chương 6 — Hướng phát triển:**
- Section "Edge case ngoài phạm vi" làm cơ sở cho future work.
- R1 + EA1 có thể đưa vào "Đã thiết kế, chuẩn bị implement".

---

## Liên kết

- Note gốc: [`../edge_cases_notes.txt`](../edge_cases_notes.txt)
- R1 design: [`r1_event_log_design.md`](r1_event_log_design.md)
- Framework design: [`framework_design.md`](framework_design.md)
- Tiến độ chung: [`PROGRESS.md`](PROGRESS.md)
