# Runbook — Vận hành test cycle trên GCP

> Quy trình **end-to-end** chạy 1 cycle test cho mỗi prototype trên 4 VM GCP.
> Đối chiếu deploy lần đầu: [`deploy-gcp.md`](deploy-gcp.md).
> Layout cố định: SSH config `~/.ssh/config` đã setup match `hcr-*`; JAR ở `/opt/hcr/`; env ở `/etc/hcr/env`; systemd unit ở `/etc/systemd/system/hcr-*.service`.

---

## 0. Tổng quan & checkpoint

Mỗi cycle test có **3 flow**:

- **Flow A — Test only**: code không đổi, script không đổi, chỉ chạy lại test với data sạch (~5 phút)
- **Flow B — Sau khi sửa code Java**: build lại JAR ở local, upload, restart, test (~15 phút)
- **Flow C — Sau khi sửa script k6**: upload `.js` lên loadgen, chạy test luôn — không cần restart service (~1 phút)

**4 terminal SSH song song** giúp thao tác nhanh:
```bash
ssh hcr-app        # restart service + check log + smoke test
ssh hcr-data       # reset DB + verify invariants
ssh hcr-busobs     # restart kafka/zipkin (rare — chỉ khi VM restart)
ssh hcr-loadgen    # chạy k6
```

**Checkpoint xuyên suốt:**

| Phase | Verify lệnh | Kết quả mong đợi |
|-------|-------------|------------------|
| VMs running | `gcloud compute instances list --zone=...` | 4 VM `RUNNING` |
| Postgres OK | `docker exec hcr-postgres psql -U hcr -d postgres -l` | 5+ DB hiện ra |
| Kafka OK | `docker exec hcr-kafka kafka-topics --bootstrap-server localhost:9092 --list` | Liệt kê topic, không lỗi |
| Service UP | `sudo systemctl status hcr-{order,inventory,payment}` | Tất cả `Active: active (running)`, timestamp ổn định 30s |
| Schema migrated | `\d ticket_orders` | Có cột `inventory_released_at` |
| Smoke OK | `curl POST /orders` | 201 với `"status":"CONFIRMED"` (P1/P2) hoặc 202 (P3) |

---

## 1. Flow A — Test only (code không đổi)

### 1.1. Khởi động VM (local PowerShell)

```powershell
gcloud compute instances start hcr-app hcr-data hcr-busobs hcr-loadgen --zone=asia-southeast1-a
Start-Sleep -Seconds 45
ssh hcr-app "echo OK; date"
```

→ Nếu SSH timeout: đợi thêm 30s, retry. VM mới start cần ~60s để SSH ready.

### 1.2. Verify infra UP (terminal `hcr-data`)

```bash
docker ps --format 'table {{.Names}}\t{{.Status}}'
docker exec hcr-postgres psql -U hcr -d postgres -l | grep -E 'order_p|inventory_p|payment_db'
```

→ Phải có ít nhất postgres + redis chạy + 5 DB hiện ra. Nếu chưa, chạy:
```bash
cd ~/infra
docker compose up -d postgres redis
sleep 10
```

### 1.3. Verify Kafka + Zipkin UP (terminal `hcr-busobs`)

```bash
docker ps --format 'table {{.Names}}\t{{.Status}}'
docker exec hcr-kafka kafka-topics --bootstrap-server localhost:9092 --list 2>&1 | head -5
```

→ Nếu không chạy:
```bash
cd ~/infra
# .env phải có KAFKA_HOST=10.20.0.4 (lý do: KAFKA_ADVERTISED_LISTENERS dùng biến này)
cat .env || echo 'KAFKA_HOST=10.20.0.4' > .env
docker compose up -d kafka zipkin prometheus grafana
sleep 20
docker logs hcr-kafka 2>&1 | grep advertised.listeners | head -3
```
→ Log phải show `PLAINTEXT://10.20.0.4:9092` (KHÔNG `localhost`).

### 1.4. Verify services UP (terminal `hcr-app`)

```bash
sudo systemctl status hcr-inventory hcr-payment hcr-order --no-pager | grep -E 'Active|Loaded'
```

→ Nếu service không up hoặc crash-loop: xem log:
```bash
sudo journalctl -u hcr-order --since '5 minutes ago' --no-pager | tail -50
```

Nếu cần restart (vd sau khi VM start lần đầu):
```bash
sudo systemctl restart hcr-inventory; sleep 20
sudo systemctl restart hcr-payment; sleep 5
sudo systemctl restart hcr-order; sleep 15
```

### 1.5. Reset DB state trước test (terminal `hcr-data`)

> ⚠ Lệnh `TRUNCATE` xoá data — chỉ chạy khi sẵn sàng cho test mới.

**Cho P1:**
```bash
docker exec hcr-postgres psql -U hcr -d order_p1_db -c "TRUNCATE ticket_orders, hcr_processed_events; UPDATE concert_tickets SET available_quantity=total_quantity, version=0;"
docker exec hcr-postgres psql -U hcr -d order_p1_db -c "SELECT resource_id, available_quantity FROM concert_tickets;"
```

**Cho P2:** đổi `order_p1_db` → `order_p2_db`. Không có Redis state cần reset.

**Cho P3:** ngoài reset DB còn phải reset Redis (xem mục 2.3).

→ Phải show 3 concert với `available_quantity = total_quantity`.

### 1.6. Smoke test (terminal `hcr-app`)

```bash
curl -s http://localhost:8081/actuator/health; echo
curl -s -X POST http://localhost:8081/orders \
  -H "Content-Type: application/json" \
  -d "{\"resourceId\":\"concert-003\",\"requesterId\":\"smoke\",\"quantity\":1,\"idempotencyKey\":\"smoke-$(date +%s)\"}"; echo
```

→ Phải thấy `{"status":"UP"}` và JSON order có `"status":"CONFIRMED"` (P1/P2) hoặc `"status":"RESERVED"` (P3).

→ Nếu trả `SYSTEM_ERROR`: xem mục 5 Troubleshooting.

### 1.7. Reset state lần 2 (sau smoke) — bước 1.5 lặp lại

Smoke order vừa tạo cần xoá để không nhiễu kết quả test.

### 1.8. Chạy k6 test (terminal `hcr-loadgen`)

> Nếu vừa sửa file `.js` ở local → đi qua **Flow C** (mục 2b) để upload trước khi chạy.

3 test script có sẵn:

```bash
# Burst — 1000 VU peak × 20s, đánh concert-003 (500 vé) — test high concurrency
k6 run --env BASE_URL=http://10.20.0.2:8081 ~/load-tests/k6/burst.js

# Sustained — 200 VU × 5 phút, đánh concert-002 (5000 vé) — test stability
k6 run --env BASE_URL=http://10.20.0.2:8081 ~/load-tests/k6/sustained.js

# Oversell-check — 5 VU × 30s (smoke level) — verify endpoint chạy
k6 run --env BASE_URL=http://10.20.0.2:8081 ~/load-tests/k6/oversell-check.js
```

→ Lưu output (đặc biệt accepted/rejected count, p95 latency) để paste vào kết quả test.

### 1.9. Verify ngay sau test (terminal `hcr-data`)

**Lưu ý: đổi `concert-003` và `order_p1_db` theo test bạn chạy.**

```bash
# Status distribution
docker exec hcr-postgres psql -U hcr -d order_p1_db -c "SELECT status, COUNT(*) FROM ticket_orders WHERE resource_id='concert-003' GROUP BY status;"

# Breakdown lý do cancel (real vs phantom orphan)
docker exec hcr-postgres psql -U hcr -d order_p1_db -c "SELECT failure_reason, COUNT(*) FROM ticket_orders WHERE resource_id='concert-003' AND status='CANCELLED' GROUP BY failure_reason;"

# Zero oversell check
docker exec hcr-postgres psql -U hcr -d order_p1_db -c "SELECT (SELECT COUNT(*) FROM ticket_orders WHERE resource_id='concert-003' AND status='CONFIRMED') AS confirmed, (SELECT available_quantity FROM concert_tickets WHERE resource_id='concert-003') AS available;"

# Orphan count
docker exec hcr-postgres psql -U hcr -d order_p1_db -c "SELECT COUNT(*) AS orphans FROM ticket_orders WHERE status IN ('CANCELLED','EXPIRED') AND inventory_released_at IS NULL;"
```

**Pass criteria ngay sau test:**
- `confirmed + available = total_quantity` (vd 500 cho concert-003) → **zero oversell**
- `orphans` có thể > 0 (chưa qua reconciliation cycle)

### 1.10. Verify Case 6 reconciliation (đợi 90s)

```bash
sleep 90

docker exec hcr-postgres psql -U hcr -d order_p1_db -c "SELECT COUNT(*) AS orphans_remaining FROM ticket_orders WHERE status IN ('CANCELLED','EXPIRED') AND inventory_released_at IS NULL;"

docker exec hcr-postgres psql -U hcr -d order_p1_db -c "SELECT (SELECT COUNT(*) FROM ticket_orders WHERE resource_id='concert-003' AND status='CONFIRMED') AS confirmed, (SELECT available_quantity FROM concert_tickets WHERE resource_id='concert-003') AS available;"
```

**Pass criteria sau reconciliation:**
- `orphans_remaining = 0` → **zero leak**
- `confirmed + available = total_quantity` vẫn đúng

### 1.11. (Optional) Check log Case 6 (terminal `hcr-app`)

```bash
sudo journalctl -u hcr-order --since '5 minutes ago' --no-pager | grep -iE 'case 6|orphan|reconcil' | tail -20
```

→ Thấy log `[Reconciliation] Case 6: found N orphan...` chứng tỏ reconciliation đã chạy.

### 1.12. Stop VMs khi xong (local PowerShell)

```powershell
gcloud compute instances stop hcr-app hcr-data hcr-busobs hcr-loadgen --zone=asia-southeast1-a
```

→ Bắt buộc làm khi không test, tiết kiệm ~$15/ngày.

---

## 2. Flow B — Sau khi sửa code

Áp dụng khi đổi code Java (vd fix bug, thêm feature). Build lại JAR, đẩy lên, restart, test.

### 2.1. Build local (PowerShell)

```powershell
cd C:\Users\Admin\Documents\HUST\2025.2\io.hrc

# Nếu chỉ đổi code trong 1 module + downstream:
mvn install -DskipTests -pl hcr-saga -am
cd hcr-product
mvn install -DskipTests -pl ms-order -am
cd ..

# Hoặc build full (chậm hơn nhưng chắc):
mvn clean install -DskipTests
cd hcr-product
mvn clean install -DskipTests
cd ..
```

→ Build success → JAR mới ở `hcr-product/ms-{order,inventory,payment}/target/*.jar`.

### 2.2. Upload JAR lên hcr-app (PowerShell)

> Chỉ upload service đã build lại. Thường chỉ ms-order. Nếu sửa hcr-core/hcr-inventory/hcr-saga thì cả 3 service đều bị ảnh hưởng → upload cả 3.

```powershell
cd C:\Users\Admin\Documents\HUST\2025.2\io.hrc

# Chỉ ms-order (case phổ biến):
scp hcr-product/ms-order/target/ms-order-1.0.0-SNAPSHOT.jar hcr-app:~/ms-order.jar

# Hoặc cả 3 (case sửa framework module):
scp hcr-product/ms-order/target/ms-order-1.0.0-SNAPSHOT.jar         hcr-app:~/ms-order.jar
scp hcr-product/ms-inventory/target/ms-inventory-1.0.0-SNAPSHOT.jar  hcr-app:~/ms-inventory.jar
scp hcr-product/ms-payment/target/ms-payment-1.0.0-SNAPSHOT.jar      hcr-app:~/ms-payment.jar
```

### 2.3. Move + chown + restart (terminal `hcr-app`)

```bash
# Move JAR (chỉ những file mới upload)
sudo mv ~/ms-*.jar /opt/hcr/
sudo chown Admin:Admin /opt/hcr/*.jar
ls -la /opt/hcr/

# Restart theo thứ tự: inventory → payment → order
sudo systemctl restart hcr-inventory; sleep 20
sudo systemctl restart hcr-payment; sleep 5
sudo systemctl restart hcr-order; sleep 15
sudo systemctl status hcr-inventory hcr-payment hcr-order --no-pager | grep -E 'Active|Loaded'
```

→ Cả 3 phải `Active: active (running)`.

### 2.4. Verify schema migration (nếu sửa entity)

Hibernate `ddl-auto: update` tự ALTER TABLE khi field mới được thêm. Verify:
```bash
# Terminal hcr-data
docker exec hcr-postgres psql -U hcr -d order_p1_db -c "\d ticket_orders" | grep <field_name>
```

→ Nếu cột mới không hiện: app chưa kịp boot, đợi 30s rồi retry. Nếu sau 1 phút vẫn chưa có → schema migration fail (check log).

### 2.5. Tiếp Flow A từ bước 1.5

Đến đây giống Flow A. Reset DB → smoke → k6 → verify.

---

## 2b. Flow C — Sửa test script k6 (không build code)

Áp dụng khi chỉ chỉnh file `.js` trong `hcr-product/load-tests/k6/` (vd thay đổi VU count,
target RPS, thresholds, summary format). KHÔNG cần build JAR, KHÔNG cần restart service.

### 2b.1. Upload file đã sửa (local PowerShell)

```powershell
cd C:\Users\Admin\Documents\HUST\2025.2\io.hrc
```

**Option 1 — Upload 1 file** (phổ biến nhất, chỉ sửa 1 script):
```powershell
scp hcr-product/load-tests/k6/burst.js       hcr-loadgen:~/load-tests/k6/burst.js
# Hoặc:
scp hcr-product/load-tests/k6/sustained.js   hcr-loadgen:~/load-tests/k6/sustained.js
scp hcr-product/load-tests/k6/oversell-check.js hcr-loadgen:~/load-tests/k6/oversell-check.js
```

**Option 2 — Upload cả thư mục** (khi sửa nhiều file hoặc `lib/common.js`):
```powershell
scp -r hcr-product/load-tests/k6 hcr-loadgen:~/load-tests/
```

> ⚠ `scp -r ... hcr-loadgen:~/load-tests/` (có slash cuối) sẽ ghi đè nội dung dir `~/load-tests/k6`.
> Không có `--delete` như rsync — file cũ không bị xoá, file mới đè lên. OK cho use case này.

**Option 3 — Sửa trực tiếp trên VM** (không khuyến nghị — mất sync với git):
```bash
ssh hcr-loadgen
nano ~/load-tests/k6/burst.js
```

### 2b.2. Verify file đã update (terminal `hcr-loadgen`)

```bash
ls -la ~/load-tests/k6/
head -5 ~/load-tests/k6/burst.js
# Hoặc check field cụ thể đã đổi:
grep -E 'vus:|target:|startRate' ~/load-tests/k6/burst.js
```

### 2b.3. Chạy test (terminal `hcr-loadgen`)

Tiếp như bước 1.8 — không cần restart hay reset gì khác (trừ khi muốn data sạch):
```bash
k6 run --env BASE_URL=http://10.20.0.2:8081 ~/load-tests/k6/burst.js
```

→ Nếu test mới có VU/RPS cao hơn nhiều: theo dõi `free -h` và `top` trên loadgen để
phát hiện OOM sớm. k6 ~1-2 MB/VU; 15000 VU ≈ 15-30 GB.

---

## 3. Switching prototype: P1 ↔ P2 ↔ P3

### 3.1. Khái niệm trước khi switch

| Khía cạnh | P1 (pessimistic) | P2 (optimistic) | P3 (Redis atomic) |
|----------|:----------------:|:---------------:|:-----------------:|
| Saga mode | sync | sync | async |
| Source of truth | DB | DB | Redis |
| ms-order DB | `order_p1_db` | `order_p2_db` | `order_p3_db` |
| ms-inventory active? | Không (idle) | Không (idle) | **Có** (RedisSeeder + Consumer) |
| ms-inventory DB | `inventory_p3_db`* | `inventory_p3_db`* | `inventory_p3_db` |
| HTTP response | 201 Created | 201 Created | 202 Accepted |
| Reserve = nhanh? | DB lock blocking | DB version retry | Redis Lua atomic |
| Cần reset Redis? | Không | Không | **Có** |

\* P1/P2 không cần inventory DB riêng vì ms-inventory không xử lý. Dùng chung `inventory_p3_db` (idle data) là OK. Nếu muốn perfect isolation, tạo thêm `inventory_p1_db` / `inventory_p2_db` rồi update unit file (xem mục 3.4).

### 3.2. Steps switch từ P1 sang P2 (terminal `hcr-app`)

```bash
# 1. Update env file: ACTIVE_PROTOTYPE
sudo sed -i 's/^ACTIVE_PROTOTYPE=.*/ACTIVE_PROTOTYPE=p2/' /etc/hcr/env
grep ACTIVE_PROTOTYPE /etc/hcr/env

# 2. Update unit file: DB_NAME của ms-order
sudo sed -i 's/DB_NAME=order_p1_db/DB_NAME=order_p2_db/' /etc/systemd/system/hcr-order.service
sudo grep DB_NAME /etc/systemd/system/hcr-order.service

# 3. Reload + restart
sudo systemctl daemon-reload
sudo systemctl restart hcr-inventory; sleep 20
sudo systemctl restart hcr-payment; sleep 5
sudo systemctl restart hcr-order; sleep 15
sudo systemctl status hcr-order --no-pager | grep Active
```

→ Verify smoke test (mục 1.6) trước khi chạy k6.

### 3.3. Steps switch từ P1/P2 sang P3 (terminal `hcr-app` + `hcr-data`)

**Trên `hcr-app`:**
```bash
# 1. Update env
sudo sed -i 's/^ACTIVE_PROTOTYPE=.*/ACTIVE_PROTOTYPE=p3/' /etc/hcr/env

# 2. Update unit file ms-order
sudo sed -i 's/DB_NAME=order_p[12]_db/DB_NAME=order_p3_db/' /etc/systemd/system/hcr-order.service
sudo grep DB_NAME /etc/systemd/system/hcr-order.service

# 3. Reload (chưa restart)
sudo systemctl daemon-reload
```

**Trên `hcr-data` — reset Redis trước khi ms-inventory boot:**
```bash
# CRITICAL: KHÔNG dùng SET trực tiếp hcr:inventory:* — phá guard release.lua.
# Cách an toàn duy nhất: FLUSHALL + để Seeder tự seed lại.
docker exec hcr-redis redis-cli FLUSHALL

# Reset inventory_p3_db data (để Seeder load fresh)
docker exec hcr-postgres psql -U hcr -d inventory_p3_db -c "UPDATE concert_tickets SET available_quantity=total_quantity, version=0; TRUNCATE hcr_processed_events;"

# Reset order_p3_db
docker exec hcr-postgres psql -U hcr -d order_p3_db -c "TRUNCATE ticket_orders, hcr_processed_events;"
```

**Quay lại `hcr-app` — restart:**
```bash
sudo systemctl restart hcr-inventory  # ms-inventory ACTIVE trong P3 — log phải show Seeder
sleep 20
sudo journalctl -u hcr-inventory -n 30 | grep -iE 'seeder|seeded'
```

→ Phải thấy log `[RedisSeeder] Seeded N tickets into Redis: ...`. Nếu không có → ms-inventory chưa kịp seed, đợi thêm.

```bash
sudo systemctl restart hcr-payment; sleep 5
sudo systemctl restart hcr-order; sleep 15

# Verify Redis có inventory
docker exec hcr-redis redis-cli GET hcr:inventory:concert-003
```

→ Phải show `"500"` (số nguyên).

### 3.4. (Optional) Tạo riêng `inventory_p1_db` / `inventory_p2_db`

Chỉ cần làm nếu bạn muốn ms-inventory data **idle thật sự** isolated giữa P1/P2/P3 runs. Hiện tại không cần — ms-inventory data không dùng cho P1/P2.

```bash
# Trên hcr-data
docker exec hcr-postgres psql -U hcr -d postgres -c "CREATE DATABASE inventory_p1_db OWNER hcr;"
docker exec hcr-postgres psql -U hcr -d postgres -c "CREATE DATABASE inventory_p2_db OWNER hcr;"

# Trên hcr-app, update unit file ms-inventory theo prototype hiện tại
sudo sed -i 's/DB_NAME=inventory_p3_db/DB_NAME=inventory_p1_db/' /etc/systemd/system/hcr-inventory.service
sudo systemctl daemon-reload
sudo systemctl restart hcr-inventory
```

---

## 4. Lưu ý quan trọng cho P2 và P3

### 4.1. P2 (Optimistic Lock) — khác biệt cần lưu tâm

- **Reserve dùng retry**: nếu version mismatch (2 thread cùng reserve), strategy retry tối đa 3 lần với backoff. **Throughput < P1** dưới high contention.
- **Release vẫn dùng JPA merge với @Version** — `OptimisticLockStrategy.release()` không bypass version check như P1. Lý do: P2 dùng optimistic là chính sách (consistency với version). **Native SQL fix CHỈ apply cho P1.**
- **Hệ quả**: P2 có thể có release fail nhiều hơn P1 dưới high contention. Saga compensate retry x3 (đã fix) + Case 6 reconciliation sẽ catch các orphan này.
- **Kỳ vọng metric**: P2 latency p99 cao hơn P1 (do retry); throughput thấp hơn ~20-30%; zero oversell + zero leak (nhờ retry + Case 6).
- **Schema giống P1**: cùng entity `ConcertTicket` của ms-order. Khác P3 ở chỗ data persistence là DB chứ không phải Redis.

### 4.2. P3 (Redis Atomic) — khác biệt lớn nhất

- **Async saga**: client nhận HTTP 202 ngay sau khi reserve Redis success. Payment chạy nền qua `AutoChargeInitiation` (executor riêng), sau đó orchestrator `handlePaymentResult` confirm/cancel.
- **Verify zero-oversell PHẢI qua DB/Redis state, KHÔNG qua HTTP 202 count**. HTTP 202 có thể > 500 do compensate rotation, nhưng số CONFIRMED + Redis available phải = 500.
- **Cần verify thêm**: Redis ↔ DB sync. Sau test 1-2 phút, đợi `InventoryPersistenceConsumer` flush events từ Kafka về DB. Kiểm tra:
  ```bash
  # Redis = source of truth
  docker exec hcr-redis redis-cli GET hcr:inventory:concert-003

  # DB lag <= 5 phút theo Case 3 reconciliation
  docker exec hcr-postgres psql -U hcr -d inventory_p3_db -c "SELECT resource_id, available_quantity FROM concert_tickets WHERE resource_id='concert-003';"
  ```
  → Cuối cùng 2 con số phải match (eventual consistency).
- **Bonus query — kiểm reconciliation Case 3 (INVENTORY_MISMATCH)**:
  ```bash
  sudo journalctl -u hcr-order --since '10 minutes ago' --no-pager | grep -iE 'inventory_mismatch|case 3'
  ```
- **Reconciliation P3 cần override `getResourceIdsToReconcile()`**: trong `TicketReconciliationService` hiện tại return `List.of()` → Case 3 không chạy. Nếu cần test Case 3 phải sửa code.
- **Async timing**: order chuyển từ `RESERVED → CONFIRMED` sau ~1-5s do payment async. Verify ngay sau test sẽ thấy nhiều `RESERVED`, sau 90s mới chuyển hết.

### 4.3. Khi chuyển giữa các prototype — quy tắc vàng

1. **Luôn reset DB của prototype mới TRƯỚC khi start service** (tránh ms-inventory ghi đè schema lẫn nhau).
2. **P3 phải FLUSHALL Redis** trước khi restart hcr-inventory (Seeder mới có data fresh).
3. **Verify smoke test sau mỗi switch** — đừng skip. Tốn 5 giây, tránh chạy k6 trên config sai.
4. **Không switch giữa lúc test đang chạy** — k6 mid-flight có thể trả data hỗn loạn.
5. **Compare apples to apples**: cùng concert_id, cùng test script, cùng thời gian, cùng VM size khi so sánh P1/P2/P3 cho thesis.

---

## 5. Troubleshooting common issues

### 5.1. Service crash-loop với "database X does not exist"

Unit file `Environment="DB_NAME=..."` trỏ DB không tồn tại.
```bash
sudo grep DB_NAME /etc/systemd/system/hcr-*.service
docker exec hcr-postgres psql -U hcr -d postgres -l
# Match → sửa unit file → daemon-reload → restart
```

### 5.2. Service crash-loop với "null value in column X violates not-null constraint"

Schema xung đột — ms-order và ms-inventory cùng tạo `concert_tickets` trong 1 DB.
**Cause**: 2 service cùng `DB_NAME` (do env file shared) hoặc unit file lỗi.
**Fix**:
```bash
# Stop hết
sudo systemctl stop hcr-order hcr-inventory hcr-payment

# Trên hcr-data: drop polluted DB
docker exec hcr-postgres psql -U hcr -d postgres -c "DROP DATABASE order_p1_db;"
docker exec hcr-postgres psql -U hcr -d postgres -c "CREATE DATABASE order_p1_db OWNER hcr;"

# Đảm bảo mỗi service đúng DB
sudo grep DB_NAME /etc/systemd/system/hcr-*.service

# Restart
sudo systemctl start hcr-inventory; sleep 20
sudo systemctl start hcr-payment; sleep 5
sudo systemctl start hcr-order
```

### 5.3. k6 100% timeout / SYSTEM_ERROR — Kafka unreachable

Kafka container `KAFKA_ADVERTISED_LISTENERS` trỏ `localhost` thay vì `10.20.0.4`.
**Fix trên hcr-busobs:**
```bash
cd ~/infra
cat .env || echo 'KAFKA_HOST=10.20.0.4' > .env
docker rm -f hcr-kafka
docker compose up -d kafka
sleep 15
docker logs hcr-kafka 2>&1 | grep advertised.listeners
```
→ Phải show `PLAINTEXT://10.20.0.4:9092`.

Sau đó restart ms-order trên hcr-app: `sudo systemctl restart hcr-order`.

### 5.4. Docker container "network not found"

State stale sau VM restart.
```bash
docker compose down --remove-orphans
docker network prune -f
docker container prune -f
docker compose up -d <services>
```

### 5.5. Schema chưa có cột mới sau khi đổi code

Hibernate `ddl-auto: update` chỉ ADD column, không DROP / không RENAME. Nếu cột mới không hiện sau redeploy:
```bash
# Check app đã boot xong chưa
sudo journalctl -u hcr-order -n 50 | grep -iE 'started|listening'

# Force migrate bằng cách DROP DATABASE + recreate (mất data)
sudo systemctl stop hcr-order
docker exec hcr-postgres psql -U hcr -d postgres -c "DROP DATABASE order_p1_db;"
docker exec hcr-postgres psql -U hcr -d postgres -c "CREATE DATABASE order_p1_db OWNER hcr;"
sudo systemctl start hcr-order
```

### 5.6. Postgres `psql -U hcr` báo `database "hcr" does not exist`

Default DB tên `hcr` không có trong init script mới. Luôn dùng `-d postgres` cho DDL:
```bash
docker exec hcr-postgres psql -U hcr -d postgres -c "..."
```

---

## 6. Quick reference — check 1 cycle nhanh nhất

Sau khi VMs đã up, services đã up, từ trạng thái sạch:

```bash
# 1 (data) — Reset
docker exec hcr-postgres psql -U hcr -d order_p1_db -c "TRUNCATE ticket_orders, hcr_processed_events; UPDATE concert_tickets SET available_quantity=total_quantity, version=0;"

# 2 (loadgen) — Test
k6 run --env BASE_URL=http://10.20.0.2:8081 ~/load-tests/k6/burst.js

# 3 (data) — Verify ngay
docker exec hcr-postgres psql -U hcr -d order_p1_db -c "SELECT status, failure_reason, COUNT(*) FROM ticket_orders WHERE resource_id='concert-003' GROUP BY status, failure_reason;"

# 4 (data) — Đợi reconcile + verify final
sleep 90
docker exec hcr-postgres psql -U hcr -d order_p1_db -c "SELECT (SELECT COUNT(*) FROM ticket_orders WHERE resource_id='concert-003' AND status='CONFIRMED') AS confirmed, (SELECT available_quantity FROM concert_tickets WHERE resource_id='concert-003') AS available, (SELECT COUNT(*) FROM ticket_orders WHERE status IN ('CANCELLED','EXPIRED') AND inventory_released_at IS NULL) AS orphans;"
```

**Pass:** `confirmed + available = total`, `orphans = 0`.

---

## 7. Cost guard

| Trạng thái | Cost/ngày |
|-----------|-----------|
| 4 VM `RUNNING` 24/7 | ~$10 |
| 4 VM `STOPPED` | ~$0.50 (chỉ disk) |
| Quên stop 1 tuần | ~$70 |

**Sau mỗi session test, BẮT BUỘC**:
```powershell
gcloud compute instances stop hcr-app hcr-data hcr-busobs hcr-loadgen --zone=asia-southeast1-a
```
