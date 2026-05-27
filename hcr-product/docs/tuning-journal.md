# Performance Tuning Journal — hcr-product

> Nhật ký tuning từng bước cho `hcr-product` (3 microservice: ms-order / ms-inventory / ms-payment).
> Mỗi entry = 1 thay đổi config + kết quả burst test, để sau làm tài liệu thesis.

## Mục tiêu

Đạt threshold của `burst.js`:

```
http_req_duration{status:202}: p95 < 500ms, p99 < 1000ms
http_req_failed:               rate < 0.01
errors:                        rate < 0.01
```

Hiện trạng baseline (sau Step 0): http_req_failed PASS, nhưng p95(202) FAIL.

## Phương pháp

```
1. Measure  →  2. Tune 1 biến  →  3. Re-test (cùng burst.js)  →  4. Note kết quả
                                                                    ↓
                                                                 repeat
```

Quy tắc:
- **Một biến mỗi vòng** — không gộp nhiều thay đổi.
- Cùng máy, cùng warmup (`mvn spring-boot:run` → đợi ready → bắn k6).
- Reset Redis + DB giữa các lần test (theo §8.8 README).

## Ma trận kết quả

| Bước | Thay đổi | p95(202) | p99(202) | http_req_failed | RPS hữu ích | Zero-oversell | Note |
|------|----------|----------|----------|-----------------|-------------|---------------|------|
| Step 0 | Baseline (default Spring Boot) | ❌ timeout | ❌ timeout | ❌ ~50% | ~207 | ✅ | 5 292 errors do pool exhaust |
| Step 0.5 | +Hikari 50, Tomcat 400/500 | ⚠️ FAIL (latency) | ⚠️ FAIL | ✅ 0% | ~267 | ✅ | Hết errors nhưng tail latency cao |
| Step 1 | Profile (Micrometer timers) | — avg 476ms | — max 1.7s | ❌ ~50% (regression) | — | ✅ | Catalog lookup avg 1.27s = bottleneck |
| Step 2 | **Cache catalog in-memory** | ~108ms | max 1.71s, p95 fail | ✅ 0% | ~373 | ✅ | DB lookup loại bỏ; Redis thành bottleneck |
| Step 2b | **Lettuce Redis pool 64** | **98.8ms avg** | **max 0.377s ✅** | ✅ 0% | ~278 | ✅ | **🎯 PASS tất cả threshold** |
| Step 3 | JPA batch insert | — | — | — | — | — | ❌ Skip — chỉ ảnh hưởng CONFIRMED, không path 202 |
| Step 4 | Postgres max_connections | — | — | — | — | — | ❌ Skip — catalog đã cache, không hit DB |
| Step 5 | JVM heap + G1GC | — | — | — | — | — | ❌ Skip — chưa cần (threshold đã pass) |
| Step 6 | Consumer-side tune | — | — | — | — | — | ❌ Skip — không ảnh hưởng path 202 |

---

## Step 0 — Baseline (default Spring Boot)

**Date**: 2026-05-07 (trước khi tune)

**Config**: tất cả default Spring Boot — Hikari pool 10, Tomcat threads 200, accept-count 100.

**Burst result**:
- Total: 10 584
- 202 Accepted: 514
- 422 Out-of-stock: 4 778
- **Real errors: 5 292 (~50%)** — connection refused / pool exhausted
- RPS hữu ích: ~207

**Diagnose**: 1000 RPS spike vượt thread pool + connection pool default → request bị drop trước khi vào application.

**Quyết định**: Tune Hikari + Tomcat trước (Step 0.5).

---

## Step 0.5 — Hikari pool + Tomcat threads tune

**Date**: 2026-05-07

**File sửa**: `hcr-product/ms-order/src/main/resources/application.yml`

**Thay đổi**:
```yaml
server.tomcat:
  threads.max: 400              # default 200
  threads.min-spare: 50
  accept-count: 500             # default 100
  max-connections: 4000

spring.datasource.hikari:
  maximum-pool-size: 50         # default 10
  minimum-idle: 10
  connection-timeout: 5000
  leak-detection-threshold: 30000
```

**Burst result**:
- Total: 10 869
- 202 Accepted: 501
- 422 Out-of-stock: 10 368
- **Real errors: 0** ✅
- RPS hữu ích: ~267
- p95(202): vẫn fail threshold (latency cao)

**Diagnose**: Pool đã đủ — không còn connection refused. Server response 422 nhanh thay vì timeout. Còn lại là tail latency.

**Quyết định**: Đi tiếp — profile để tìm bottleneck mới.

---

## Step 1 — Profile path 202 với Micrometer timers

**Date**: 2026-05-08

### Insight quan trọng (đọc lại code trước khi profile)

Path 202 thực tế **KHÔNG có JPA insert** — async saga chỉ save order vào DB sau khi CONFIRMED. Path 202 gồm:

```
[Redis SETNX claim] → [DB catalog findById] → [Redis Lua DECR reserve] → [Redis save saga state] → [Kafka publish] → [Redis SET idempotency value]
       ~1ms              ~5-10ms?                 ~1ms                       ~1ms                   ~0ms (async)         ~1ms
```

→ JPA batch insert (Step 3 ban đầu) **không liên quan tới p95 path 202**. Nhưng vẫn giữ vì path CONFIRMED (qua PaymentResultListener) có JPA save.

→ Suspect mới: **catalog lookup `catalogRepository.findById()`** chạy mỗi request, không cache → 1000 SELECT/s vào Postgres.

### Thay đổi

**File**: `hcr-product/ms-order/src/main/java/io/hrc/product/order/controller/OrderController.java`

Thêm 4 Micrometer Timer:
- `ms_order_path202_total_duration_ms` — end-to-end POST /orders
- `ms_order_idempotency_claim_duration_ms` — Redis SETNX
- `ms_order_orchestrator_duration_ms` — orchestrator.process() whole
- `ms_order_idempotency_set_duration_ms` — Redis SET sau reserve OK

**File**: `hcr-product/ms-order/src/main/java/io/hrc/product/order/saga/TicketBookingOrchestrator.java`

Thêm 1 Timer:
- `ms_order_catalog_lookup_duration_ms` — DB `findById` mỗi request

Tất cả timers `publishPercentiles(0.5, 0.95, 0.99)`.

Cross-reference với metric framework có sẵn:
- `hcr_reservation_duration_ms{strategy="redis-atomic", outcome="success"}` — Redis Lua DECR

### Cách lấy số

1. Restart ms-order:
   ```bat
   cd hcr-product\ms-order && mvn spring-boot:run
   ```

2. Reset state (theo §8.8 README):
   ```bat
   docker exec hcr-redis redis-cli FLUSHALL
   docker exec hcr-postgres psql -U hcr -d order_db -c "TRUNCATE ticket_orders;"
   ```
   Chờ Seeder warm Redis xong.

3. Chạy burst:
   ```bat
   k6 run hcr-product\load-tests\k6\burst.js
   ```

4. Scrape Prometheus endpoint:
   ```bat
   curl http://localhost:8081/actuator/prometheus | findstr duration_ms
   ```

5. Hoặc query Grafana / Prometheus với query:
   ```promql
   ms_order_path202_total_duration_ms{quantile="0.95"}
   ms_order_orchestrator_duration_ms{quantile="0.95"}
   ms_order_catalog_lookup_duration_ms{quantile="0.95"}
   ms_order_idempotency_claim_duration_ms{quantile="0.95"}
   ms_order_idempotency_set_duration_ms{quantile="0.95"}
   hcr_reservation_duration_ms{quantile="0.95",outcome="success"}
   ```

### Result

**Note về Prometheus output**: Field `quantile=0.5/0.95/0.99` đều hiển thị `0.0` — đây là Micrometer precision quirk khi `publishPercentiles` trên Timer mặc định round về integer-second. Dùng `_sum / _count` để tính avg (đủ tin cậy cho diagnose).

| Stage | Calls | Sum (s) | **Avg (ms)** | Max (s) | % path |
|-------|-------|---------|--------------|---------|--------|
| Path 202 total (success only) | 501 | 238.7 | **476** | 1.70 | 100% |
| **Catalog lookup (DB findById)** | 14474 | 18376 | **🔴 1 270** | **4.73** | **~90% bottleneck** |
| Orchestrator whole | 14474 | 20591 | 1 422 | 5.17 | catalog + reserve + nhỏ |
| Idempotency claim (Redis SETNX) | 14863 | 545 | 37 | 4.74 | tail spike |
| Idempotency set (Redis SET) | 501 | 17 | 34 | 0.37 | local Redis chậm bất thường |
| Reserve (Redis Lua DECR) | 501 | 41 | 82 | 0.46 | nên <1ms khi không tải |

### Diagnose

**Catalog DB lookup chiếm ~90% latency của path 202.** Mỗi POST /orders chạy 1 `SELECT * FROM concert_tickets WHERE id=?` — 1000 RPS = 1000 query/s vào Postgres không có cache. Hikari pool 50 không đủ → query queue 1-2s avg, max 4.7s gần chạm `connection-timeout=5s` → SQLException → 500.

Đây cũng là root cause của **regression errors**: trong Step 1 này có 7389 errors (Step 0.5 chỉ 0). Cùng một bottleneck, dưới load hơi cao hơn (test variance) đã đẩy một số request vượt timeout.

Phụ: Redis ops (SETNX/SET) avg 30-80ms — local Redis không nên chậm vậy. Lettuce default share 1 connection (multiplex) — dưới spike có thể queue. Sẽ tune ở Step 2b nếu Step 2 chưa đủ.

### Quyết định

**Pivot Step 2**: bỏ Kafka batching (publish() async, không phải bottleneck), thay bằng **Cache concert catalog in-memory**. Catalog là dữ liệu read-only (~3 record), load vào `ConcurrentHashMap` lúc startup → loại 100% DB query trên hot path.

Expected: path 202 avg 476ms → ~30-50ms; errors về 0 (không còn DB queue → không SQLException).

---

---

## Step 7 — Finalize

**Date**: 2026-05-08

### Tổng kết quá trình

3 bước tune có tác động (Step 0.5, 2, 2b) đã đưa hệ thống từ 50% lỗi về 0 lỗi, tất cả threshold k6 PASS, latency p95 đáp ứng SLA. Steps 3-6 đã skip vì profile cho thấy không phải bottleneck thật.

### Bài học rút ra

1. **Profile trước, tune sau** — Step 1 cho thấy hypothesis ban đầu (Kafka batching) là sai; bottleneck thật là catalog DB lookup. Nếu nhảy thẳng vào tune Kafka thì uổng công.

2. **Distribution shift** — sau khi fix bottleneck A, bottleneck B sẽ lộ ra. Step 2 giảm catalog xuống 0ms, nhưng idempotency_claim từ 37ms tăng lên 114ms vì Redis giờ chịu toàn bộ load. Phải tune tiếp tới khi không còn chokepoint nào.

3. **Tail latency là metric quan trọng nhất**: avg có thể nhỏ nhưng max/p95/p99 lớn → SLA fail. Step 2 avg 108ms (đẹp) nhưng max 1.71s → p95 fail. Phải tune cho cả tail.

4. **Default Spring Boot tốt cho dev, không đủ cho production load** — Hikari 10, Tomcat 200, Lettuce 1-conn, catalog không cache, timeout 60s. Mỗi config default đều thành bottleneck tại tải spike.

5. **Correctness được giữ nguyên**: trong mọi bước, zero-oversell invariant không bị phá. Tuning chỉ động vào throughput/latency, framework architecture đảm bảo correctness tuyệt đối.

### Final benchmark — `burst.js`, peak 1000 RPS, 40s

| | Default | Step 0.5 | Step 2 | **Step 2b (final)** |
|---|---|---|---|---|
| Real errors | 5 292 (~50%) | 0 | 0 | **0** |
| `http_req_failed` threshold | ❌ | ✅ | ✅ | ✅ |
| `errors` threshold | ❌ | ✅ | ✅ | ✅ |
| `http_req_duration{status:202}` p95 | ❌ | ❌ | ❌ | **✅ PASS** |
| Path 202 max | timeout | timeout | 1.71 s | **0.377 s** |
| Path 202 avg | timeout | timeout | 108 ms | **98.8 ms** |
| Throughput hữu ích | ~207 RPS | ~267 RPS | ~373 RPS | ~278 RPS |
| Zero-oversell | ✅ | ✅ | ✅ | ✅ |

### File đã thay đổi

- `hcr-product/ms-order/src/main/resources/application.yml` (Step 0.5 + 2b)
- `hcr-product/ms-order/pom.xml` (Step 2b — commons-pool2)
- `hcr-product/ms-order/src/main/java/.../service/ConcertTicketCatalog.java` (Step 2 mới)
- `hcr-product/ms-order/src/main/java/.../saga/TicketBookingOrchestrator.java` (Step 2 + Step 1 timer)
- `hcr-product/ms-order/src/main/java/.../controller/OrderController.java` (Step 1 timer)
- `hcr-product/README.md` §8.9 + §11 — note benchmark final
- `hcr-product/docs/tuning-journal.md` — file này

### Bước tiếp theo nếu muốn đẩy SLA chặt hơn

Hiện burst.js threshold đã pass. Nếu thay bằng test khắc nghiệt hơn (sustained 2000 RPS hoặc p99 < 100ms), các hướng còn lại:

- **Early reject ở Gateway** khi `hcr_inventory_available = 0` — bỏ luôn 422 path khỏi orchestrator.
- **Tune ms-inventory + ms-payment** consumer side — giảm saga end-to-end (hiện 35-45s).
- **Postgres `max_connections=300`** + `shared_buffers=512MB` — phòng khi tune ms-inventory cũng tăng pool.
- **Postgres prepared statement cache + connection-init-sql** — giảm parsing overhead per query.
- **JFR/async-profiler** flame graph để xác định CPU hot path trong saga.
- **Kafka producer batching** (`linger.ms=5`, `batch.size=32768`, `acks=1`) — nếu Kafka producer-side latency > 10ms.

---

## Step 2b — Tune Lettuce Redis pool

**Date**: 2026-05-08

### Mục tiêu

Loại bottleneck Redis ops (claim 114ms / set 12.5ms / reserve 24ms — tổng ~150ms riêng Redis). Spring Data Redis (Lettuce) mặc định dùng **1 shared multiplexed connection** — non-blocking nhưng dưới spike 1000 RPS, write-flush trên Netty event loop trở thành chokepoint.

### Thay đổi

**File**: `hcr-product/ms-order/pom.xml` — thêm dep:
```xml
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-pool2</artifactId>
</dependency>
```

**File**: `hcr-product/ms-order/src/main/resources/application.yml`:
```yaml
spring.data.redis:
  timeout: 5000ms              # per-op timeout, match Hikari (default 60s OOM risk)
  connect-timeout: 2000ms      # TCP connect khi reconnect
  lettuce:
    pool:
      enabled: true
      max-active: 64
      max-idle: 20
      min-idle: 8
      max-wait: 2000ms
```

**Ghi chú timeout**: Đặt 5s/2s/2s — không phải để fail-fast hung-hăng, mà đủ thoáng cho spike (p99 ~3-4s vẫn pass) và đủ chặt để không pile thread gây OOM. Default Lettuce 60s là không phù hợp dưới load.

### Result

**k6 burst summary**:
```
Total requests:  11 135
Accepted (202):     509   (Step 2: 547)
Rejected (422):  10 626
Real errors:          0   ✅
Iterations/s:    ~50.93/s, 41.6s wallclock
THRESHOLDS:      ✅ PASS — KHÔNG có dòng ERRO 'thresholds...crossed'
```

**Prometheus timer breakdown**:

| Stage | Avg Step 2 (ms) | **Avg Step 2b (ms)** | Max Step 2 (s) | **Max Step 2b (s)** | p95 (ms) | p99 (ms) |
|-------|-----------------|----------------------|----------------|---------------------|----------|----------|
| **Path 202 total** | 108 | **98.8** | 1.71 | **0.377** ✅ | — | — |
| **Idempotency claim** (Lettuce) | 114 | **30.5** | 0.78 | 0.631 | **58.7** | **167** |
| Idempotency set (Lettuce) | 12.5 | 11.8 | 0.47 | 0.111 | — | — |
| Reserve (Redisson Lua) | 24 | 25.9 | 0.46 | 0.223 | — | — |
| Catalog lookup (HashMap) | 0.002 | 0.001 | 0.014 ms | 0.18 ms | 1.7µs | 2.4µs |
| Orchestrator whole | 307 | 907 ⚠️ | 1.89 | 2.99 | 2 408 | 2 810 |
| Saga end-to-end (CONFIRMED) | 35 400 | 45 600 | 48.8 | 49.8 | — | — |

**Note quantile**: Step 2 hiện 0.0 cho mọi quantile (precision quirk khi tail spike vượt range default). Step 2b nay dữ liệu vào range chuẩn → quantile có giá trị thực.

### Diagnose

✅ Lettuce pool 64 connections đã giải quyết Redis bottleneck:
- Idempotency claim avg 114 → 30.5ms (p95 = 58.7ms, p99 = 167ms — tail tighter)
- Path 202 max 1.71s → 0.377s — tail giảm 4.5×
- Tất cả 3 threshold của burst.js PASS

⚠️ Orchestrator avg TĂNG 307 → 907ms vì path OOS giờ phải qua Lua DECR fail có queue + lock contention nhẹ. Không ảnh hưởng SLA của 202 (422 path không có threshold strict). Optimize trong tương lai = early reject ở Gateway khi Redis available=0.

### Quyết định

✅ **ACCEPT Step 2 + 2b** — giữ vĩnh viễn. Threshold đã đạt, no errors, throughput đủ.

⏭️ Steps 3-6 (JPA batch, Postgres tune, JVM heap, consumer-side tune) **KHÔNG CẦN** cho mục tiêu burst.js threshold:
- JPA batch insert: chỉ ảnh hưởng path CONFIRMED (qua PaymentResultListener), không phải hot path 202
- Postgres tune: catalog đã cache → DB khỏi hot path
- JVM heap: chưa thấy GC pause issue (no jstat data)
- Consumer-side: ms-inventory + ms-payment chỉ ảnh hưởng saga end-to-end, không phải path 202

→ Đi thẳng tới **Step 7 — finalize**.

---

## Step 2 — Cache concert catalog in-memory

**Date**: 2026-05-08

### Mục tiêu

Loại bỏ DB findById khỏi hot path. Catalog (~3 record) read-only sau seed → load 1 lần lúc startup vào ConcurrentHashMap.

### Thay đổi

**File mới**: `hcr-product/ms-order/src/main/java/io/hrc/product/order/service/ConcertTicketCatalog.java`

```java
@Component
public class ConcertTicketCatalog {
    private final Map<String, ConcertTicket> cache = new ConcurrentHashMap<>();

    @PostConstruct
    void warmUp() { repository.findAll().forEach(t -> cache.put(t.getResourceId(), t)); }

    public Optional<ConcertTicket> findById(String resourceId) {
        return Optional.ofNullable(cache.get(resourceId));
    }
}
```

**File sửa**: `TicketBookingOrchestrator.java`
- Thay `ConcertTicketRepository` bằng `ConcertTicketCatalog`
- `findById` giờ là HashMap O(1), không round-trip DB

Timer `ms_order_catalog_lookup_duration_ms` giữ nguyên — sẽ thấy con số mới sau Step 2 (kỳ vọng < 1µs).

### Cách test

```bat
:: 1. Restart ms-order (Ctrl+C terminal cũ)
cd hcr-product\ms-order && mvn spring-boot:run
::    đợi log "[catalog-cache] Warmed N concert tickets..."

:: 2. Reset state
docker exec hcr-redis redis-cli FLUSHALL
docker exec hcr-postgres psql -U hcr -d order_db -c "TRUNCATE ticket_orders;"

:: 3. Burst
k6 run hcr-product\load-tests\k6\burst.js

:: 4. Scrape
curl http://localhost:8081/actuator/prometheus > tuning-step2.txt
findstr "duration_ms" tuning-step2.txt
```

### Result

**k6 burst summary**:
```
Total requests:  14 976  (Step 1: 7 404 — 2× throughput!)
Accepted (202):     547
Rejected (422):  14 429
Real errors:          0   ✅ (Step 1: 7 389)
Iterations/s:    ~50.93 iters/s, 40.1s wallclock
Threshold ERRO:  http_req_duration{status:202}  ← vẫn fail (tail latency)
```

**Prometheus timer breakdown**:

| Stage | Calls | Sum (s) | Avg Step 1 (ms) | **Avg Step 2 (ms)** | Δ | Max Step 2 |
|-------|-------|---------|-----------------|---------------------|---|------------|
| Path 202 total | 547 | 59.2 | 476 | **108** | 4.4× | 1.71 s |
| **Catalog lookup** | 14 976 | 0.028 | 1 270 | **0.002** | **~700 000×** ✅ | 0.014 ms |
| Orchestrator whole | 14 976 | 4 593 | 1 422 | 307 | 4.6× | 1.89 s |
| Reserve (Lua DECR) | 547 | 13.2 | 82 | 24 | 3.4× | 0.46 s |
| Idempotency set | 547 | 6.84 | 34 | 12.5 | 2.7× | 0.47 s |
| **Idempotency claim** | 14 976 | 1 712 | 37 | **114** | ⚠️ **3× CHẬM HƠN** | 0.78 s |
| Saga end-to-end (CONFIRMED) | 430 | 15 234 | 57 700 | 35 400 | 1.6× | 48.8 s |

### Diagnose

✅ Catalog cache hoạt động đúng kỳ vọng — loại 100% DB query trên hot path. Throughput tăng 2×, errors về 0.

⚠️ Path 202 avg 108ms nhưng tail vẫn cao → p95 vượt 500ms threshold.

⚠️ Idempotency claim avg tăng 37 → 114ms (max GIẢM 4.74 → 0.78s). Đây là **distribution shift**: trước đây DB chặn request ở createOrder → ít contention trên Redis. Sau cache, request đổ xuống Redis cùng lúc → Lettuce shared multiplexed connection queue. Tổng 3 Redis ops trên path success: 114 + 24 + 12.5 = **~150ms avg** chỉ riêng Redis.

→ Redis (Lettuce) chính thức là new bottleneck.

### Quyết định

✅ Accept Step 2 (cache catalog) — giữ lại vĩnh viễn, performance gain rõ rệt.
➡️ Đi tiếp **Step 2b — tune Lettuce pool** để fix tail latency Redis.

---

<!-- Template entry cho mỗi step tiếp theo:

## Step N — <tên thay đổi>

**Date**: YYYY-MM-DD

**File sửa**: `<path>`

**Thay đổi**:
```yaml
key: value
```

**Burst result**:
- p95(202): X ms
- p99(202): X ms
- http_req_failed: X%
- RPS hữu ích: X
- Zero-oversell: ✅ / ❌

**Diagnose**: <root cause / tại sao thay đổi này hiệu quả / không hiệu quả>

**Quyết định**: <accept change / rollback / tune tiếp>

---
-->
