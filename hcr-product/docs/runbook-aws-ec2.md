# Runbook — vận hành 3 microservice + infra trên AWS EC2

> Cheatsheet copy-paste cho việc khởi động, chạy load test, và reset state giữa các lần test.
> Cho phần setup hạ tầng lần đầu (Security Group, build JAR, scp lên EC2…), xem `deploy-aws-ec2.md`.

## Topology

| Role | EC2 | Private IP | Public IP | Port |
|------|-----|------------|-----------|------|
| **hcr-infra** | docker-compose: Postgres/Redis/Kafka/Zipkin/Prometheus/Grafana | `172.31.41.163` | (SSH only) | 5432/6379/9092/9411/9090/3000 |
| **ms-inventory** | Spring Boot | `172.31.42.95` | — | 8082 |
| **ms-payment** | Spring Boot | `172.31.40.105` | — | 8083 |
| **ms-order** | Spring Boot (HTTP entry point) | `172.31.39.31` | `13.212.116.159` | 8081 |

Thay private IP theo deployment thực tế của bạn.

---

## 1. Start tuần tự (đầy đủ)

### 1.1. hcr-infra — khởi động docker-compose

```bash
ssh -i <key.pem> ec2-user@<HCR_INFRA_PUBLIC_IP>

cd /home/ec2-user

# Start toàn bộ stack với KAFKA_HOST = private IP của infra
sudo KAFKA_HOST=172.31.41.163 docker-compose up -d

# Đợi healthy (~20s)
sleep 20 && sudo docker-compose ps
```

**Verify:**
```bash
# Tất cả container Up + postgres/kafka/redis có "(healthy)"
sudo docker-compose ps

# 3 database tồn tại
sudo docker exec hcr-postgres psql -U hcr -d postgres -c "\l" | grep _db

# Kafka advertise đúng IP (không phải localhost)
sudo docker exec hcr-kafka kafka-broker-api-versions \
  --bootstrap-server localhost:9092 2>&1 | head -3
# Phải thấy 172.31.41.163:9092
```

Nếu DB thiếu (lần đầu vào volume cũ):
```bash
sudo docker exec hcr-postgres psql -U hcr -d postgres -c "CREATE DATABASE order_db OWNER hcr;"
sudo docker exec hcr-postgres psql -U hcr -d postgres -c "CREATE DATABASE inventory_db OWNER hcr;"
sudo docker exec hcr-postgres psql -U hcr -d postgres -c "CREATE DATABASE payment_db OWNER hcr;"
```

### 1.2. ms-inventory — start TRƯỚC TIÊN

> ms-inventory phải start trước vì nó seed Redis từ DB. Nếu start ms-order trước, Redis trống → order request fail.

```bash
ssh -i <key.pem> ec2-user@<MS_INVENTORY_PUBLIC_IP>

DB_HOST=172.31.41.163 DB_NAME=inventory_db \
REDIS_HOST=172.31.41.163 \
KAFKA_BOOTSTRAP=172.31.41.163:9092 \
ZIPKIN_ENDPOINT=http://172.31.41.163:9411/api/v2/spans \
java -jar /home/ec2-user/ms-inventory-1.0.0-SNAPSHOT.jar
```

**Đợi log thấy:**
```
[RedisSeeder] Seeded N concert tickets into Redis
Tomcat started on port 8082
```

### 1.3. ms-payment

```bash
ssh -i <key.pem> ec2-user@<MS_PAYMENT_PUBLIC_IP>

DB_HOST=172.31.41.163 DB_NAME=payment_db \
KAFKA_BOOTSTRAP=172.31.41.163:9092 \
ZIPKIN_ENDPOINT=http://172.31.41.163:9411/api/v2/spans \
java -jar /home/ec2-user/ms-payment-1.0.0-SNAPSHOT.jar
```

**Đợi log:** `Tomcat started on port 8083`

### 1.4. ms-order

```bash
ssh -i <key.pem> ec2-user@<MS_ORDER_PUBLIC_IP>

DB_HOST=172.31.41.163 DB_NAME=order_db \
REDIS_HOST=172.31.41.163 \
KAFKA_BOOTSTRAP=172.31.41.163:9092 \
MS_PAYMENT_URL=http://172.31.40.105:8083 \
ZIPKIN_ENDPOINT=http://172.31.41.163:9411/api/v2/spans \
java -jar /home/ec2-user/ms-order-1.0.0-SNAPSHOT.jar
```

> `MS_PAYMENT_URL` trỏ tới private IP của ms-payment — `ms-order` gọi sang đó để charge (P1/P2) và để reconciliation hỏi trạng thái thanh toán (P3).

**Đợi log:** `Tomcat started on port 8081`

### 1.5. Verify cluster healthy

Từ laptop:
```cmd
curl http://13.212.116.159:8081/actuator/health
:: {"status":"UP"}
```

Trên hcr-infra — xem cả 3 consumer group đều có `ACTIVE MEMBERS`:
```bash
for grp in ms-inventory ms-payment ms-order; do
  echo "=== $grp ==="
  sudo docker exec hcr-kafka kafka-consumer-groups \
    --bootstrap-server localhost:9092 --group $grp --describe
done
```
Cột `CONSUMER-ID` và `HOST` phải có giá trị (không phải `-`).

---

## 2. Chạy load test

### 2.1. Smoke test 1 order (CMD)

```cmd
cd C:\Users\Admin
echo {"resourceId":"concert-001","requesterId":"smoke-1","quantity":1,"idempotencyKey":"smoke-001"} > smoke.json

curl -X POST http://13.212.116.159:8081/orders ^
  -H "Content-Type: application/json" -d @smoke.json
:: Expect: HTTP 202 (P3) hoặc 201 (P1/P2) + JSON với orderId
```

### 2.2. Sustained test (200 VU × 5 min)

```cmd
k6 run --env BASE_URL=http://13.212.116.159:8081 ^
  hcr-product\load-tests\k6\sustained.js
```

### 2.3. Oversell verification (concert-003, 500 vé pool nhỏ)

```cmd
k6 run --env BASE_URL=http://13.212.116.159:8081 ^
  hcr-product\load-tests\k6\oversell-check.js
```

### 2.4. Burst test (spike)

```cmd
k6 run --env BASE_URL=http://13.212.116.159:8081 ^
  hcr-product\load-tests\k6\burst.js
```

### 2.5. Quan sát realtime — SSH tunnel cho Grafana/Zipkin

```cmd
:: Terminal riêng, giữ chạy suốt test
ssh -i <key.pem> ^
  -L 3000:172.31.41.163:3000 ^
  -L 9411:172.31.41.163:9411 ^
  -L 9090:172.31.41.163:9090 ^
  ec2-user@<HCR_INFRA_PUBLIC_IP>
```

Browser:
- `http://localhost:3000` — Grafana (admin/admin)
- `http://localhost:9411` — Zipkin trace
- `http://localhost:9090` — Prometheus raw query

---

## 3. Verify zero-oversell sau test

Trên hcr-infra:

```bash
# 1. Redis — source of truth của P3
sudo docker exec hcr-redis redis-cli GET hcr:inventory:concert-001

# 2. Inventory DB — sync từ persistence consumer
sudo docker exec hcr-postgres psql -U hcr -d inventory_db -c \
  "SELECT resource_id, total_quantity, available_quantity FROM concert_tickets;"

# 3. Idempotency dedup count
sudo docker exec hcr-postgres psql -U hcr -d inventory_db -c \
  "SELECT COUNT(*) FROM hcr_processed_events;"

# 4. Order DB — số CONFIRMED/CANCELLED
sudo docker exec hcr-postgres psql -U hcr -d order_db -c \
  "SELECT status, COUNT(*) FROM ticket_orders GROUP BY status;"
```

**Invariant cần check:**

```
CONFIRMED + CANCELLED + IN_FLIGHT == số HTTP 202 từ k6
CONFIRMED ≤ total_quantity              (zero oversell)
Redis = total - (đã reserve - đã release)
```

---

## 4. Reset state giữa các lần test

> ⚠ **KHÔNG SET trực tiếp** `hcr:inventory:*` qua `redis-cli SET` — sẽ phá guard của `release.lua` (script check version key trước khi INCR). Cách an toàn duy nhất: FLUSHALL rồi để Seeder tự seed lại.

### 4.1. Reset Redis

Trên hcr-infra:
```bash
sudo docker exec hcr-redis redis-cli FLUSHALL
sudo docker exec hcr-redis redis-cli DBSIZE
# Phải = 0
```

### 4.2. Reset Postgres (tuỳ chọn — phụ thuộc muốn xoá gì)

**Cách A — Truncate tables (nhanh, giữ schema):**
```bash
sudo docker exec hcr-postgres psql -U hcr -d order_db -c "TRUNCATE ticket_orders;"
sudo docker exec hcr-postgres psql -U hcr -d inventory_db -c "TRUNCATE hcr_processed_events;"
sudo docker exec hcr-postgres psql -U hcr -d inventory_db -c \
  "UPDATE concert_tickets SET available_quantity = total_quantity, version = 0;"
```

**Cách B — Drop database (clean slate, mất schema → Hibernate tạo lại):**
```bash
# Dừng 3 service Java trước (Ctrl+C ở mỗi terminal)

sudo docker exec hcr-postgres psql -U hcr -d postgres -c "DROP DATABASE order_db;"
sudo docker exec hcr-postgres psql -U hcr -d postgres -c "DROP DATABASE inventory_db;"
sudo docker exec hcr-postgres psql -U hcr -d postgres -c "DROP DATABASE payment_db;"
sudo docker exec hcr-postgres psql -U hcr -d postgres -c "CREATE DATABASE order_db OWNER hcr;"
sudo docker exec hcr-postgres psql -U hcr -d postgres -c "CREATE DATABASE inventory_db OWNER hcr;"
sudo docker exec hcr-postgres psql -U hcr -d postgres -c "CREATE DATABASE payment_db OWNER hcr;"
```

### 4.3. Restart 3 service Java

Sau reset Redis hoặc Postgres, **PHẢI restart cả 3 service** (Ctrl+C rồi `java -jar` lại) theo đúng thứ tự ms-inventory → ms-payment → ms-order.

Lý do:
- ms-inventory seed lại Redis từ DB (nếu Redis trống)
- ms-order/payment cache Redis connection — sau FLUSHALL không tự refresh

### 4.4. Verify reset xong

```bash
sudo docker exec hcr-redis redis-cli GET hcr:inventory:concert-001
# = 10000 (đã seed lại)

sudo docker exec hcr-postgres psql -U hcr -d inventory_db -c \
  "SELECT available_quantity FROM concert_tickets WHERE resource_id='concert-001';"
# = 10000

sudo docker exec hcr-postgres psql -U hcr -d order_db -c \
  "SELECT COUNT(*) FROM ticket_orders;"
# = 0
```

---

## 5. Stop services (graceful shutdown)

### 5.1. Java services

Ở mỗi terminal `java -jar`, gửi `Ctrl+C`. Spring Boot sẽ:
- Drain Tomcat (hoàn thành request đang xử lý)
- Close Kafka consumer (commit offset)
- Close Hikari pool

Đợi log `Application shut down completed` rồi đóng SSH.

### 5.2. Infra

```bash
# Stop nhưng giữ data
sudo docker-compose stop

# Stop + xoá container (giữ pgdata volume)
sudo docker-compose down

# Stop + xoá tất cả (data Postgres mất → init script chạy lại lần sau)
sudo docker-compose down -v
```

---

## 6. Troubleshooting nhanh

| Lỗi log | Nguyên nhân | Fix |
|---------|-------------|-----|
| `Connection to localhost:5432 refused` | Env var `DB_HOST` không set | Prefix `DB_HOST=172.31.41.163` trước `java -jar` |
| `FATAL: database "X_db" does not exist` | DB chưa tạo (volume Postgres cũ) | `CREATE DATABASE X_db OWNER hcr;` |
| `Connection to localhost:9092 ... Broker may not be available` | Kafka advertise sai (chưa có `KAFKA_HOST`) | Restart infra: `sudo KAFKA_HOST=172.31.41.163 docker-compose up -d --force-recreate kafka` |
| `column "X" does not exist` khi findAll | Schema mismatch — table cũ từ run trước | `DROP TABLE X CASCADE;` rồi restart service |
| Consumer group `has no active members` | Service Java đã chết | `ps aux \| grep ms-` để check, restart |
| `Out of memory: Killed process` (dmesg) | EC2 quá nhỏ, kernel OOM-kill | Tăng instance type hoặc set `-Xmx512m` |

---

## 7. Order chuẩn cho mỗi phiên test

```
1. Start infra (1.1)              ✓ KAFKA_HOST set, 3 DB tồn tại
2. Start ms-inventory (1.2)       ✓ Seed Redis xong
3. Start ms-payment (1.3)         ✓ Consumer subscribe payment-requested
4. Start ms-order (1.4)           ✓ HTTP 8081 up
5. Verify cluster (1.5)           ✓ 3 consumer group có active member
6. Smoke test 1 order (2.1)       ✓ HTTP 202
7. Run k6 sustained (2.2)         → quan sát Grafana song song
8. Verify invariant (3)           ✓ CONFIRMED + CANCELLED + Redis available = total
9. Reset (4) → lặp lại từ bước 6 cho test khác
```

---

## 8. Chạy từng prototype — P1 / P2 / P3

3 prototype switch qua **Spring profile của `ms-order`**. `ms-inventory`/`ms-payment` không đổi.

| | **P1** | **P2** | **P3** |
|---|---|---|---|
| Profile ms-order | `p1` | `p2` | *(không set — mặc định)* |
| Strategy / Saga | pessimistic-lock / sync | optimistic-lock / sync | redis-atomic / async |
| HTTP khi đặt OK | **201** | **201** | **202** |
| Service cần chạy | infra + **ms-order + ms-payment** | infra + **ms-order + ms-payment** | infra + **cả 3 service** |
| Inventory store | `order_db.concert_tickets` | `order_db.concert_tickets` | Redis |
| Reset | `reset-p1p2.sh` (8.5) | `reset-p1p2.sh` (8.5) | mục 4 |

> P1/P2 dùng saga **đồng bộ** — `ms-order` tự reserve trên `order_db`, nhưng bước charge gọi **HTTP đồng bộ sang `ms-payment`** (`POST /payments`). Vậy P1/P2 cần `ms-order` + `ms-payment`; **không cần** `ms-inventory` (P1/P2 reserve thẳng trên `order_db.concert_tickets`). P3 **async** cần đủ 3 service.

> `ms-order` **không còn payment gateway local** — cả 3 prototype đều thanh toán qua `ms-payment` (service duy nhất tích hợp cổng thanh toán). Trên EC2 phải set `MS_PAYMENT_URL` cho `ms-order`; local mặc định `http://localhost:8083`.

> ⚠️ DB từ các run cũ có thể còn rows `concert_tickets` với `available_quantity=0`. Lần đầu chuyển sang P1/P2 **phải reset (8.5) một lần** để seed lại `available = total`.

### 8.1. P1 / P2 trên LOCAL (Windows)

```cmd
:: 1. Infra (localhost — KAFKA_HOST mặc định = localhost)
cd hcr-product\infra
docker-compose up -d

:: 2. Build jar (ms-order + ms-payment)
mvn -f hcr-product\pom.xml -pl ms-order,ms-payment -am -q clean package -DskipTests

:: 3. Start ms-payment (terminal riêng) — P1/P2 charge gọi HTTP sang đây
java -jar hcr-product\ms-payment\target\ms-payment-1.0.0-SNAPSHOT.jar

:: 4. Start ms-order với profile p1 (env mặc định = localhost; MS_PAYMENT_URL mặc định 8083)
java -Dspring.profiles.active=p1 -jar hcr-product\ms-order\target\ms-order-1.0.0-SNAPSHOT.jar
::    → P2: đổi thành -Dspring.profiles.active=p2

:: 5. Smoke (terminal khác) — kỳ vọng HTTP 201
k6 run hcr-product\load-tests\k6\oversell-check.js
```

### 8.2. P1 / P2 trên EC2

Cần **hcr-infra** + **ms-payment** + **ms-order** — bỏ qua bước start ms-inventory ở mục 1.

```bash
# Trên hcr-infra: start infra như mục 1.1

# Trên ms-payment EC2 (start TRƯỚC ms-order):
DB_HOST=172.31.41.163 DB_NAME=payment_db \
KAFKA_BOOTSTRAP=172.31.41.163:9092 \
ZIPKIN_ENDPOINT=http://172.31.41.163:9411/api/v2/spans \
java -jar /home/ec2-user/ms-payment-1.0.0-SNAPSHOT.jar

# Trên ms-order EC2 — MS_PAYMENT_URL trỏ tới private IP ms-payment:
DB_HOST=172.31.41.163 DB_NAME=order_db \
REDIS_HOST=172.31.41.163 \
KAFKA_BOOTSTRAP=172.31.41.163:9092 \
MS_PAYMENT_URL=http://172.31.40.105:8083 \
ZIPKIN_ENDPOINT=http://172.31.41.163:9411/api/v2/spans \
java -Dspring.profiles.active=p1 -jar /home/ec2-user/ms-order-1.0.0-SNAPSHOT.jar
#    → P2: -Dspring.profiles.active=p2
```

Smoke từ laptop — kỳ vọng **HTTP 201**:
```cmd
curl -X POST http://13.212.116.159:8081/orders -H "Content-Type: application/json" -d @smoke.json
```

### 8.3. P3 trên LOCAL (Windows)

```cmd
cd hcr-product\infra && docker-compose up -d

:: 3 terminal riêng — KHÔNG set profile (mặc định = P3)
java -jar hcr-product\ms-inventory\target\ms-inventory-1.0.0-SNAPSHOT.jar
java -jar hcr-product\ms-payment\target\ms-payment-1.0.0-SNAPSHOT.jar
java -jar hcr-product\ms-order\target\ms-order-1.0.0-SNAPSHOT.jar

:: Smoke — kỳ vọng HTTP 202
k6 run hcr-product\load-tests\k6\oversell-check.js
```

### 8.4. P3 trên EC2

Theo đúng quy trình mục 1 (start đủ 3 service, **không set profile**). HTTP đặt OK = **202**.

### 8.5. Reset state cho P1 / P2

`load-tests/reset/reset-p1p2.sh` chạy trên hcr-infra (EC2) — hoặc local thì bỏ `sudo`:

```bash
# TRUNCATE ticket_orders + concert_tickets (order_db); restart sẽ seed lại available = total
sudo docker exec -i hcr-postgres psql -U hcr -d order_db -c "TRUNCATE TABLE ticket_orders, concert_tickets;"
sudo docker exec -i hcr-redis redis-cli FLUSHALL
```

Sau đó **restart ms-order** (profile p1/p2) — `data.sql` seed lại `concert_tickets` với `available_quantity = total_quantity`.

### 8.6. Verify zero-oversell cho P1 / P2

P1/P2 source-of-truth là Postgres `order_db` — query trực tiếp:

```bash
sudo docker exec -i hcr-postgres psql -U hcr -d order_db -c "
SELECT ct.resource_id, ct.total_quantity, ct.available_quantity,
       COALESCE(SUM(o.quantity) FILTER (WHERE o.status='CONFIRMED'), 0) AS confirmed_qty,
       COALESCE(SUM(o.quantity) FILTER (WHERE o.status='CANCELLED'), 0) AS cancelled_qty
FROM concert_tickets ct
LEFT JOIN ticket_orders o ON o.resource_id = ct.resource_id
GROUP BY ct.resource_id, ct.total_quantity, ct.available_quantity
ORDER BY ct.resource_id;"
```

**Invariant:** `confirmed_qty ≤ total_quantity` và `available_quantity = total_quantity − confirmed_qty` cho mọi resource.
```
