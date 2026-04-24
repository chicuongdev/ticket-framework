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

## Verify zero oversell sau burst test

```sql
-- Connect: psql -h localhost -U hcr -d order_db
SELECT status, COUNT(*)
FROM ticket_orders
WHERE resource_id = 'concert-003'
GROUP BY status;
```

Tổng `CONFIRMED + RESERVED <= 500`. Nếu có pending RESERVED quá 5 phút, reconciliation sẽ tự cancel + release Redis trong cycle tiếp theo.

## Quan sát trong khi chạy

- **Grafana** http://localhost:3000 (admin/admin) — JVM heap, HTTP latency histogram, Kafka consumer lag
- **Prometheus** http://localhost:9090 — query trực tiếp metrics:
  - `hcr_inventory_reserve_failures_total{reason="OUT_OF_STOCK"}`
  - `hcr_saga_confirmed_total`
  - `hcr_payment_attempts_total`
- **Zipkin** http://localhost:9411 — pick 1 trace để xem flow ms-order → Kafka → ms-payment → Kafka → ms-order

## Reset giữa các lần chạy

```bash
# Reset Redis (xoá hết inventory) + restart ms-inventory để seed lại
docker exec -it $(docker ps -qf name=redis) redis-cli FLUSHALL
docker restart ms-inventory  # nếu chạy bằng docker; chạy local thì restart lại

# Hoặc xoá DB orders
psql -h localhost -U hcr -d order_db -c "TRUNCATE ticket_orders;"
```
