# Load Tests — k6

3 kịch bản đánh vào ms-order (`POST /orders`) để đo zero-oversell, throughput, latency.

## Yêu cầu

- [k6](https://k6.io) (Windows: `winget install k6`)
- Toàn bộ stack đang chạy (xem `infra/docker-compose.yml`)
- 3 microservices đã start: ms-order (8081), ms-inventory (8082), ms-payment (8083)
- Redis đã được seed catalog (ms-inventory tự seed lúc startup)

## 3 script

| Script | Mục tiêu | Concert | VU | Duration |
|--------|----------|---------|----|----------|
| `oversell-check.js` | Smoke test endpoint + idempotency | concert-002 | 5 | 30s |
| `burst.js` | Spike — verify zero oversell | concert-003 (500 vé) | 0→1000 | 40s |
| `sustained.js` | Soak — đo throughput + latency | concert-001 (10000 vé) | 200 | 5m |

## Chạy

```bash
# Smoke test trước (verify infra OK)
k6 run hcr-product/load-tests/k6/oversell-check.js

# Burst — kiểm tra zero oversell
k6 run hcr-product/load-tests/k6/burst.js

# Sustained — đo p95 latency
k6 run hcr-product/load-tests/k6/sustained.js

# Override URL nếu cần (ví dụ chạy từ máy khác)
k6 run --env BASE_URL=http://192.168.1.10:8081 hcr-product/load-tests/k6/burst.js
```

## ⚠️ HIỂU ĐÚNG INVARIANT ZERO-OVERSELL

**HTTP 202 count CÓ THỂ > 500 mà vẫn KHÔNG oversell.** Lý do:
1. 500 reserves đầu DECRBY thành công → Redis `concert-003` = 0
2. Mock payment có ~10% fail rate → orchestrator `compensate()` → `release()` → Redis `INCRBY 1`
3. Slot vừa release được request kế tiếp DECRBY → thêm 1 HTTP 202

→ Tổng 202 = 500 + N (N = số reserves rotate qua compensate cycle, thường 50-150).

**Verify zero-oversell ĐÚNG (chạy SAU burst test):**

```bash
# 1. DB invariant: CONFIRMED + RESERVED <= 500
docker exec hcr-postgres psql -U hcr -d order_db -c \
  "SELECT status, COUNT(*) FROM ticket_orders WHERE resource_id='concert-003' GROUP BY status;"

# 2. Redis invariant: CONFIRMED_count + Redis_available = 500
docker exec hcr-redis redis-cli GET "hcr:inventory:concert-003"
```

Cộng 2 con số phải = `total_quantity = 500`. Đây là invariant cứng — vi phạm = bug Lua hoặc bug atomicity.

Nếu có pending RESERVED quá 5 phút (do payment timeout/unknown), reconciliation sẽ tự cancel + release Redis trong cycle tiếp theo.

## Quan sát trong khi chạy

- **Grafana** http://localhost:3000 (admin/admin) — JVM heap, HTTP latency histogram, Kafka consumer lag
- **Prometheus** http://localhost:9090 — query trực tiếp metrics:
  - `hcr_inventory_reserve_failures_total{reason="OUT_OF_STOCK"}`
  - `hcr_saga_confirmed_total`
  - `hcr_payment_attempts_total`
- **Zipkin** http://localhost:9411 — pick 1 trace để xem flow ms-order → Kafka → ms-payment → Kafka → ms-order

## Reset giữa các lần chạy

**LUÔN làm đủ 5 bước này trước khi chạy lại load test.** KHÔNG bao giờ `redis-cli SET hcr:inventory:*` thủ công (sẽ phá guard của `release.lua` vì thiếu key `hcr:inventory:total:*` → có thể oversell thật sự).

```bash
# 1. Wipe Redis (inventory keys, saga state, idempotency claims)
docker exec hcr-redis redis-cli FLUSHALL

# 2. Wipe orders + payment audit + processed events
docker exec hcr-postgres psql -U hcr -d order_db     -c "DELETE FROM ticket_orders;"
docker exec hcr-postgres psql -U hcr -d payment_db   -c "DELETE FROM payment_attempts;"
docker exec hcr-postgres psql -U hcr -d inventory_db -c "DELETE FROM hcr_processed_events;"

# 3. Reset available_quantity về full trong inventory_db
#    (persistence consumer đã decrement available qua test trước)
docker exec hcr-postgres psql -U hcr -d inventory_db -c \
  "UPDATE concert_tickets SET available_quantity = total_quantity, version = version + 1;"

# 4. Restart ms-inventory để RedisSeeder warm Redis từ Postgres
#    (Ctrl+C trong terminal đang chạy ms-inventory, rồi chạy lại)
java -jar ms-inventory/target/ms-inventory-1.0.0-SNAPSHOT.jar

# 5. Verify TRƯỚC khi chạy k6
docker exec hcr-redis redis-cli GET "hcr:inventory:concert-003"        # → 500
docker exec hcr-redis redis-cli GET "hcr:inventory:total:concert-003"  # → 500
```

`RedisSeeder` là **idempotent** (check `redis.hasKey()` trước, skip nếu có). Nếu chỉ FLUSHALL Redis mà không TRUNCATE order tables, restart ms-inventory vẫn seed lại Redis bình thường — nhưng order cũ trong DB sẽ làm sai số liệu test sau.
