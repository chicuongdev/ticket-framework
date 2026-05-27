# R1 — Event Log Reconciliation Design

> Design document để giải quyết edge case **[R1]** trong `edge_cases_notes.txt`:
> phân biệt **release hợp lệ** vs **lỗi thật** trong reconciliation P3.
>
> Status: **DRAFT — chờ review trước khi implement**
> Tác giả: HCR team
> Ngày: 2026-05-01

---

## 1. Vấn đề hiện tại

### 1.1 Cách reconciliation hoạt động bây giờ

`InventoryReconciler.reconcile(resourceId)` (file `hcr-reconciliation/src/.../inventory/InventoryReconciler.java:95`):

```java
delta = redisAvailable - dbAvailable
if delta > 0  → Redis cao hơn DB → AUTO-FIX (set Redis = DB)
if delta < 0  → "DB lag bình thường" → IGNORE
if delta == 0 → consistent
```

### 1.2 Tại sao không đủ

**Case bị miss** (silent bug):

```
Thời điểm T₀:
  Redis = 100, DB = 100 (consistent)

T₀ + 1s: 20 reservation hợp lệ qua Redis Lua DECRBY
T₀ + 2s: bug X làm Redis bị DECR thêm 5 lần (không có event publish)

Tại T₀ + 3s khi reconcile chạy:
  Redis = 75 (100 - 20 hợp lệ - 5 bug)
  DB    = 100 (consumer chưa kịp xử lý 20 events)

  delta = 75 - 100 = -25
  → reconciler hiện tại: "DB lag bình thường, IGNORE"
  → BUG 5 ĐƠN VỊ KHÔNG ĐƯỢC PHÁT HIỆN
```

Lý do: chỉ so sánh **2 snapshot tĩnh** (Redis vs DB), không có thông tin về **luồng** (events đang trong-flight). Một mismatch có thể là:

| Diễn giải | Hành động đúng |
|-----------|----------------|
| Lag P3 bình thường | Bỏ qua |
| Bug thật trong Lua / publish | Phải fix + alert |

→ Reconciler hiện tại **gộp cả hai** thành "lag bình thường" → false negative.

### 1.3 Tại sao không thể fix bằng cách đơn giản hơn

Đã cân nhắc các giải pháp đơn giản, đều không đủ:

| Phương án | Vấn đề |
|-----------|--------|
| Đếm `in_flight` orders trong DB rồi so `Redis = DB - in_flight` | Race condition: `in_flight` thay đổi liên tục giữa 2 query. Cũng không bắt được trường hợp Redis bị DECR mà KHÔNG có order tương ứng (bug ngoài luồng) |
| Đợi đủ lâu để consumer catch up rồi mới so | Không xác định được "đủ lâu là bao nhiêu". Vẫn không phân biệt được lag vs bug |
| So timestamp của Redis modification vs DB modification | Redis không lưu modification time per-key. Đắt nếu lưu |

---

## 2. Giải pháp đề xuất — Event Log Replay

### 2.1 Ý tưởng

Lưu **mọi thay đổi inventory** (RESERVED / CONFIRMED / RELEASED) vào bảng `hcr_inventory_log`. Reconciler **replay** log từ snapshot gần nhất để tính giá trị Redis **kỳ vọng**, so với Redis **thực tế**:

```
expected_redis = last_snapshot.value + Σ(delta của events sau snapshot)
actual_redis   = GET hcr:inventory:{resourceId}

if expected ≠ actual → đó MỚI là bug thật
if expected == actual → consistent (kể cả release hợp lệ vẫn match vì có entry trong log)
```

### 2.2 Tại sao cách này work

- Mỗi delta hợp lệ trên Redis đều có entry tương ứng trong log → match.
- Bug "DECR Redis không log" → expected sẽ cao hơn actual → phát hiện.
- Release hợp lệ → có entry RELEASED → expected match actual → không bị nhầm là lỗi.

### 2.3 Bù lại — chi phí

- 1 INSERT vào `hcr_inventory_log` cho mỗi inventory operation. Đặt **trong cùng transaction** với UPDATE `inventory` ở `InventoryPersistenceConsumer` → không có write amplification ngoài critical path (P3 critical path vẫn chỉ Redis).
- Bảng phình to → cần snapshot job định kỳ để truncate.

---

## 3. Schema

### 3.1 Bảng `hcr_inventory_log`

```sql
CREATE TABLE hcr_inventory_log (
    id              BIGSERIAL PRIMARY KEY,
    resource_id     VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(16)  NOT NULL,    -- RESERVED | CONFIRMED | RELEASED
    delta           INT          NOT NULL,    -- ±N (RESERVED âm, RELEASED dương, CONFIRMED = 0)
    balance_after   BIGINT       NOT NULL,    -- giá trị Redis sau khi apply (đọc trong Lua hoặc consumer)
    event_id        VARCHAR(64)  NOT NULL,    -- = eventId của EventBus event (idempotency anchor)
    occurred_at     TIMESTAMP    NOT NULL,
    UNIQUE (event_id)
);

CREATE INDEX idx_log_resource_time ON hcr_inventory_log (resource_id, occurred_at);
```

**Ghi chú thiết kế:**
- `event_type = CONFIRMED` có `delta = 0` vì confirm chỉ chuyển trạng thái (RESERVED → CONFIRMED), không thay đổi `available`. Vẫn ghi vào log để có audit trail đầy đủ.
- `UNIQUE (event_id)` — nếu consumer xử lý event lần 2 (retry), INSERT thất bại → giữ nguyên log không trùng entry. Tận dụng [E1] same-transaction guarantee.
- `balance_after` lấy từ giá trị Redis trả về (Lua script đã trả về `balance_after`). Có thể bỏ nếu muốn — nhưng để debug/audit thì tiện.

### 3.2 Bảng `hcr_inventory_snapshot`

```sql
CREATE TABLE hcr_inventory_snapshot (
    id              BIGSERIAL PRIMARY KEY,
    resource_id     VARCHAR(64)  NOT NULL,
    snapshot_value  BIGINT       NOT NULL,    -- giá trị Redis tại thời điểm snapshot
    cutoff_event_id VARCHAR(64)  NOT NULL,    -- event_id cuối cùng đã được tính vào snapshot
    cutoff_time     TIMESTAMP    NOT NULL,
    UNIQUE (resource_id, cutoff_time)
);

CREATE INDEX idx_snapshot_resource ON hcr_inventory_snapshot (resource_id, cutoff_time DESC);
```

**Ghi chú:**
- `cutoff_event_id` quan trọng — replay chỉ apply events có `id > snapshot_event_id` (sắp xếp theo `id` BIGSERIAL).
- Giữ N snapshot gần nhất (vd. 7 ngày), không phải chỉ 1 — để debug ngược thời gian.

---

## 4. Component changes

### 4.1 Module `hcr-inventory`

#### 4.1.1 New entity + repo

```
hcr-inventory/src/main/java/io/hrc/inventory/log/
  InventoryLogEntry.java       — JPA entity
  InventoryLogEntryRepository.java
  InventoryLogEventType.java   — enum RESERVED/CONFIRMED/RELEASED
```

#### 4.1.2 Sửa `InventoryPersistenceConsumer.java`

**Trước (hiện tại):**
```java
transactionTemplate.execute(status -> {
    insertProcessedEvent(eventId);  // dedup
    updateInventory(resourceId, delta);  // UPDATE hcr_inventory
    return null;
});
```

**Sau:**
```java
transactionTemplate.execute(status -> {
    insertProcessedEvent(eventId);
    updateInventory(resourceId, delta);
    insertInventoryLog(eventId, resourceId, eventType, delta, balanceAfter);  // NEW
    return null;
});
```

Cùng cách cho `BatchInventoryPersistenceConsumer` — gom log entries trong batch, INSERT hàng loạt.

#### 4.1.3 Lua script — không thay đổi

Lua script `inventory_reserve.lua` và `inventory_release.lua` không thay đổi. Vẫn trả `balance_after` như cũ. Log được ghi ở consumer (sau khi event được publish và xử lý), không phải tại Redis.

**Tại sao không log ngay tại Lua?** Vì log nằm trong Postgres, không thuộc cùng transaction với Redis. Nếu log tại Lua, vẫn có gap. Log tại consumer = log đảm bảo same-TX với UPDATE DB.

### 4.2 Module `hcr-reconciliation`

#### 4.2.1 Thay đổi `InventoryReconciler`

**Strategy hiện tại** (snapshot-diff): GIỮ LẠI làm fallback, đổi tên `SnapshotDiffReconciler`.

**Strategy mới**: `EventLogReplayReconciler` — implement cùng interface.

```java
public interface InventoryReconcilerStrategy {
    ReconciliationOutcome reconcile(String resourceId);
}
```

Switch qua config:

```yaml
hcr.reconciliation.inventory.strategy: event-log   # event-log | snapshot-diff
```

Default = `event-log` khi `hcr.inventory.strategy = redis-atomic`. Khi P1/P2 (DB là source of truth), không cần reconciler inventory.

#### 4.2.2 New class `EventLogReplayReconciler`

Pseudocode:

```java
ReconciliationOutcome reconcile(String resourceId) {
    perResourceLock.lock(resourceId);
    try {
        Snapshot snap = snapshotRepo.findLatest(resourceId);
        if (snap == null) {
            // Chưa có snapshot — coi DB available là baseline
            snap = bootstrapSnapshotFromDb(resourceId);
        }

        long sumDeltas = logRepo.sumDeltasSince(resourceId, snap.cutoffEventId);
        long expected = snap.snapshotValue + sumDeltas;
        long actual = redisTemplate.get("hcr:inventory:" + resourceId);

        if (expected == actual) {
            return CONSISTENT;
        }

        if (expected > actual) {
            // Redis bị "mất" delta — bug DECR không log, hoặc Redis crash
            return classifyAndFix(REDIS_UNDERFLOW, expected, actual);
        } else {
            // Redis "thừa" delta — phantom increment
            return classifyAndFix(REDIS_OVERFLOW, expected, actual);
        }
    } finally {
        perResourceLock.unlock(resourceId);
    }
}
```

#### 4.2.3 New `InventorySnapshotJob`

```java
@Scheduled(fixedDelayString = "${hcr.reconciliation.snapshot.interval:6h}")
void snapshotAll() {
    for (String resourceId : listAllResourceIds()) {
        perResourceLock.lock(resourceId);
        try {
            long actualRedis = readRedis(resourceId);
            String latestEventId = logRepo.findLatestEventId(resourceId);

            snapshotRepo.insert(new Snapshot(resourceId, actualRedis, latestEventId, now()));

            // Truncate log cũ hơn retention window (giữ 1 snapshot gần nhất + retention buffer)
            logRepo.deleteOlderThan(resourceId, now() minus retentionDuration);
        } finally {
            perResourceLock.unlock(resourceId);
        }
    }
}
```

**Quan trọng — atomicity:**
- `readRedis` và `findLatestEventId` phải đọc dưới cùng lock. Nếu không, có thể có event được publish giữa 2 read → snapshot không match.
- Trong khoảng giữa `lock` và `unlock` ở đây, các consumer có thể vẫn ghi log entry mới — nhưng những entries đó có `id > latestEventId` → không bị tính vào snapshot, sẽ được replay lần reconcile sau. ✓

#### 4.2.4 Thay đổi `ReconciliationCase` enum

```java
public enum ReconciliationCase {
    STALE_PENDING,
    LATE_PAYMENT_SUCCESS,
    INVENTORY_MISMATCH,             // GIỮ — tổng quát cho cả 2 hướng dưới
    UNPERSISTED_RESERVATION,
    DUPLICATE_ORDER,

    // NEW — chi tiết hơn cho event-log strategy
    REDIS_UNDERFLOW,                // expected > actual: Redis mất delta
    REDIS_OVERFLOW                  // expected < actual: Redis có thêm phantom
}
```

Snapshot-diff reconciler vẫn dùng `INVENTORY_MISMATCH`. Event-log reconciler dùng 2 case mới chính xác hơn.

### 4.3 Module `hcr-autoconfigure`

Thêm property:

```yaml
hcr:
  reconciliation:
    inventory:
      strategy: event-log              # event-log | snapshot-diff
    snapshot:
      enabled: true
      interval: 6h
      retention: 7d                    # giữ log + snapshot trong 7 ngày
```

Auto-config wire bean theo strategy.

---

## 5. Algorithm chi tiết — example trace

### 5.1 Setup

```
T₀ = 09:00:00, Redis[concert-001] = 100, DB[concert-001] = 100

Snapshot tại T₀ (do bootstrap):
  hcr_inventory_snapshot row: { resource=concert-001, value=100, cutoff_event_id=null, cutoff_time=09:00 }
```

### 5.2 Diễn biến

| Time | Action | Redis | Log entry | Consumer applied? |
|------|--------|-------|-----------|------------------|
| 09:01:01 | Reserve 5 (event=e1) | 95 | RESERVED, delta=-5, eventId=e1 | Yes (đã ghi log) |
| 09:01:02 | Reserve 3 (event=e2) | 92 | RESERVED, delta=-3, eventId=e2 | Yes |
| 09:01:03 | Confirm e1 (event=e3) | 92 | CONFIRMED, delta=0, eventId=e3 | Yes |
| 09:01:04 | Release 3 (event=e4) | 95 | RELEASED, delta=+3, eventId=e4 | **Chưa** (consumer lag) |

### 5.3 Reconciler chạy lúc 09:01:04.500

```
snap = { value=100, cutoff_event_id=null }
events_in_log = [e1(-5), e2(-3), e3(0)]  // e4 chưa được consumer xử lý → chưa có trong log
sum_deltas = -8
expected = 100 + (-8) = 92
actual = GET Redis = 95  // do release vừa rồi

expected (92) ≠ actual (95) → REDIS_OVERFLOW?
```

**Wait — false positive!** Release đã xảy ra trên Redis (Lua đã +3) nhưng consumer chưa kịp ghi vào log.

### 5.4 Vấn đề và cách giải quyết

**Đây là vấn đề căn bản:** log có lag so với Redis. Reconciler thấy mismatch nhưng không biết là lag hay bug.

**Giải pháp:** dùng **grace period**. Reconciler chỉ flag mismatch nếu nó **bền vững** qua nhiều round:

```
First detect: 09:01:04.500, mismatch = +3, store as "pending_mismatch"
Second check: 09:01:05.500
  → consumer đã xử lý e4
  → events_in_log = [e1, e2, e3, e4(+3)]
  → sum_deltas = -5
  → expected = 100 - 5 = 95
  → actual = 95
  → MATCH → clear pending_mismatch

Nếu sau N rounds (vd 3 rounds, ~3 phút) vẫn mismatch → mới flag là lỗi thật
```

→ Cần thêm bảng tạm `hcr_pending_mismatch (resource_id, first_detected_at, mismatch_value)` để track grace period.

**Alternative đơn giản hơn:** không cần bảng tạm — reconciler chỉ alert + retry sau, persist nothing. Lần thứ 3 confirm cùng mismatch mới fix. State giữ trong memory (acceptable vì reconciler chạy single-instance, restart sẽ reset đếm).

→ **Khuyến nghị**: in-memory counter, đơn giản hơn.

---

## 6. Snapshot strategy

### 6.1 Khi nào snapshot

**Time-based** (default): mỗi 6 giờ. Đơn giản, predictable.

Không dùng count-based vì:
- Cần đếm event per-resource → thêm 1 query
- Resource ít hoạt động sẽ không bao giờ snapshot → log không truncate

### 6.2 Atomic vs non-atomic

Snapshot phải atomic w.r.t. consumer ghi log:

```
LOCK(resource_id)
  redis_value   = GET redis
  latest_event  = SELECT MAX(id) FROM hcr_inventory_log WHERE resource_id = ?
  INSERT snapshot (redis_value, latest_event, now())
UNLOCK
```

Lock bằng Redisson `RLock` per resource. Lock scope nhỏ (~few ms).

### 6.3 Truncate strategy

Sau khi insert snapshot mới:

```
DELETE FROM hcr_inventory_log
WHERE resource_id = ?
  AND occurred_at < (now() - retention_duration)
  AND id < (SELECT cutoff_event_id FROM hcr_inventory_snapshot 
            WHERE resource_id = ? 
            ORDER BY cutoff_time DESC 
            LIMIT 1 OFFSET 1)  -- giữ snapshot trước đó để rollback nếu cần
```

Giữ 2 snapshot gần nhất, xóa log cũ hơn retention. Đảm bảo replay luôn có anchor.

### 6.4 Bootstrap khi chưa có snapshot

Khi reconciler chạy lần đầu với resource mới:

```java
if (snapshot == null) {
    // Đọc DB available làm baseline (DB và Redis đáng lẽ phải = ban đầu)
    long dbAvailable = readDb(resourceId);
    insertSnapshot(resourceId, dbAvailable, null /* không có cutoff event */, now());
}
```

Lần reconcile đầu tiên có thể lỗi (vì replay từ null = replay tất cả log cho resource đó). Acceptable vì chỉ chạy 1 lần.

---

## 7. Migration plan (an toàn cho production hiện tại)

### Phase 1 — Add log infrastructure (không động vào reconciler) — **0.5 ngày**
- [ ] Tạo entity `InventoryLogEntry` + migration script DDL
- [ ] Sửa `InventoryPersistenceConsumer` + `BatchInventoryPersistenceConsumer` để INSERT log entry trong cùng TX
- [ ] Test: chạy load test → verify log có đúng entries

### Phase 2 — Snapshot job — **0.5 ngày**
- [ ] Tạo `InventorySnapshotJob` + migration cho `hcr_inventory_snapshot`
- [ ] Cấu hình `@Scheduled` 6h
- [ ] Test: chạy 1 ngày → verify snapshot có, log được truncate

### Phase 3 — Replay reconciler — **1 ngày**
- [ ] Tạo `EventLogReplayReconciler` (mới)
- [ ] Giữ `SnapshotDiffReconciler` (rename từ class hiện tại) làm fallback
- [ ] Property `hcr.reconciliation.inventory.strategy` chọn strategy
- [ ] Default vẫn là `snapshot-diff` để backward compat

### Phase 4 — Switch + verify — **0.5 ngày**
- [ ] Đổi default sang `event-log`
- [ ] Inject bug giả (DECR Redis không publish event) → verify reconciler phát hiện
- [ ] Update README + GUIDE.md của `hcr-reconciliation`

**Total: ~2.5 ngày** (vs estimate ban đầu 2-3 ngày — match).

---

## 8. Test strategy

### 8.1 Unit test
- `InventoryLogEntry` JPA mapping
- `EventLogReplayReconciler.reconcile()` với scenarios:
  - Empty log + no snapshot → bootstrap from DB
  - Log entries, no mismatch → CONSISTENT
  - Log entries, Redis underflow → REDIS_UNDERFLOW
  - Log entries, Redis overflow → REDIS_OVERFLOW
  - Mismatch but within grace period → no fix yet
  - Mismatch persistent qua 3 round → fix

### 8.2 Integration test (`hcr-testing`)
- Full P3 flow: reserve → confirm → release → snapshot → reconcile → assert clean

### 8.3 Chaos test (cho thesis demo)
- Inject bug: skip publish event ở 1% requests → verify reconciler phát hiện trong N phút
- So sánh với `snapshot-diff` strategy: bug cùng mức không phát hiện → chứng minh contribution của event-log

---

## 9. Tradeoffs đã chấp nhận

| Tradeoff | Lý do chấp nhận |
|----------|-----------------|
| Log ghi đồng bộ trong consumer transaction → consumer chậm hơn | Consumer là async, không nằm trong critical path P3 |
| Bảng `hcr_inventory_log` tăng kích thước tuyến tính theo throughput | Snapshot job + truncate giải quyết. Retention 7 ngày → cap kích thước |
| Reconciler phức tạp hơn 3-4× code cũ | Đổi lại false negative rate ~0 (so với cao trong cách cũ). Justify được trong thesis |
| Vẫn có grace period false positive ngắn | Acceptable — chỉ delay alert vài phút, không sai về kết quả |

---

## 10. Open questions cần user duyệt

Trước khi implement, cần bạn confirm các quyết định sau:

1. **Snapshot interval**: 6h ổn không? Hay muốn ngắn hơn (2h) để log không phình quá nhanh?

2. **Retention**: 7 ngày đủ không? Thesis demo có thể cần xem log 1-2 ngày trước. Production thường 30-90 ngày.

3. **Grace period count**: 3 round (~3 phút với reconciler chạy 1 phút/lần) là OK hay muốn lâu hơn? Có ảnh hưởng tới MTTR.

4. **Default strategy sau migration**: 
   - **Option A**: default = `event-log`, người dùng phải opt-out nếu muốn cũ.
   - **Option B**: default = `snapshot-diff` (cũ), người dùng phải opt-in.
   - Khuyến nghị: A — vì event-log strict superior, không có lý do dùng cũ.

5. **`balance_after` field trong log**: giữ hay bỏ? Tăng row size ~8 bytes/entry. Có ích để debug nhưng không cần cho replay (delta đã đủ).

6. **Có cần `RECONCILED` event type không?** (note R1 gốc đề xuất). Mục đích: ghi lại khi reconciler tự fix Redis → lần sau replay không bị nhầm. Tôi nghĩ **CÓ** — khi reconciler set Redis = expected, nó phải ghi 1 entry RECONCILED với delta tương ứng để các snapshot/replay sau hiểu đúng.

7. **Snapshot scope**: snapshot **per-resource** (mỗi resource có snapshot riêng) hay **global** (1 snapshot cho tất cả)? Tôi đã design per-resource. Global sẽ đơn giản hơn nhưng không phù hợp khi có nghìn resource.

8. **Lock trong reconcile**: hiện đang là per-resource (`RLock` Redisson). Có cần escalate lên global khi snapshot không? Tôi nghĩ KHÔNG — chỉ cần per-resource.

---

## 11. Liên kết

- Edge case gốc: `edge_cases_notes.txt` §R1
- Implementation hiện tại: `hcr-reconciliation/src/.../InventoryReconciler.java:95`
- Module overview: `hcr-reconciliation/README.md`
- CLAUDE.md ref: §"Known limitations" — sau khi implement xong sẽ remove dòng "Reconciliation fix ≤ 5 phút" và thay bằng "Reconciliation phát hiện exact via event log replay"
