# Deploy hcr-product lên AWS EC2

> Hướng dẫn deploy 3 microservice + infra lên EC2 cho mục đích demo thesis.
> Region khuyến nghị: **ap-southeast-1 (Singapore)** — gần VN nhất, latency ~30-50ms.

## TL;DR

- **4 EC2** (3 service + 1 infra) — `t3.small × 3 + t3.medium × 1`
- ~**$70/tháng** nếu chạy 24/7 hoặc ~$5-10 cho 1 tuần demo
- Local build JAR → `scp` lên EC2 → `systemd` service auto-restart
- k6 chạy từ local máy bạn, đánh vào public IP của ms-order

---

## 1. Topology

### Option A — 4 instance (recommended)

```
┌────────────────────────────────────────────────────────────────┐
│  VPC (default)  ·  ap-southeast-1  ·  Single AZ (1a)           │
│                                                                │
│  ┌────────────────┐  ┌────────────────┐  ┌──────────────────┐  │
│  │ ec2-ms-order   │  │ ec2-ms-inv     │  │ ec2-ms-payment   │  │
│  │ t3.small       │  │ t3.small       │  │ t3.small         │  │
│  │ public IP ✓    │  │ public IP ✗    │  │ public IP ✗      │  │
│  │ port 8081      │  │ port 8082      │  │ port 8083        │  │
│  │ JAR + systemd  │  │ JAR + systemd  │  │ JAR + systemd    │  │
│  └────────┬───────┘  └────────┬───────┘  └────────┬─────────┘  │
│           │                   │                   │            │
│           └─────────┬─────────┴─────────┬─────────┘            │
│                     │                   │                      │
│           ┌─────────▼───────────────────▼─────────┐            │
│           │ ec2-infra · t3.medium · public IP ✗   │            │
│           │ Docker Compose:                       │            │
│           │  - Postgres 16  (5432)                │            │
│           │  - Redis 7      (6379)                │            │
│           │  - Kafka KRaft  (9092 internal)       │            │
│           │  - Prometheus   (9090)                │            │
│           │  - Grafana      (3000)                │            │
│           │  - Zipkin       (9411)                │            │
│           └───────────────────────────────────────┘            │
└────────────────────────────────────────────────────────────────┘
        ▲                                          ▲
        │ HTTP POST /orders (k6)                   │ SSH tunnel
        │                                          │ (port-forward
   Local máy bạn                              Grafana 3000,
                                              Prometheus 9090)
```

**Tại sao 4 instance:**
- Ms-order public, 2 service còn lại private (chỉ ms-order nhận HTTP từ client; ms-inv & ms-payment chỉ nói chuyện qua Kafka).
- Infra private — Postgres/Redis/Kafka KHÔNG bao giờ expose internet.
- Failure isolation: nếu Kafka chết, services vẫn chạy nhưng không xử lý event mới — diagnose dễ.

### Option B — 3 instance (cost-optimal)

```
ms-order box (t3.medium) cũng host infra qua docker-compose
├─ Spring Boot app (port 8081, public)
└─ docker-compose: postgres, redis, kafka, prom, grafana

ms-inv box  (t3.small, private)
ms-payment box (t3.small, private)
```

Tiết kiệm 1 instance ~$15/tháng nhưng:
- ms-order box phải chia CPU/RAM với Kafka (Kafka ăn ~1GB heap)
- Bench latency của ms-order sẽ bias vì JVM compete tài nguyên
- Khó tách biệt khi diagnose

**Quyết định**: Hướng dẫn dưới đây dùng Option A. Cuối file có note Option B nếu bạn muốn collapse.

---

## 2. Prerequisites

### Local máy bạn

- AWS account đã verify thẻ (Free Tier không đủ — t3.small không cover 24/7)
- AWS CLI cấu hình xong: `aws configure` → access key, region `ap-southeast-1`
- SSH key pair đã import lên AWS (`hcr-key.pem`)
- Java 17 + Maven 3.9+ (để build JAR)
- Docker (chỉ cần local build infra image nếu custom — không bắt buộc)
- k6 (load test)

### IAM permissions tối thiểu

User cần policy `AmazonEC2FullAccess` + `IAMReadOnlyAccess`. Production thì narrow hơn — không cần ở thesis.

### Region + AZ

- Region: `ap-southeast-1`
- AZ: `ap-southeast-1a` (đặt cùng AZ tất cả 4 instance để latency nội bộ thấp + không tốn data transfer giữa AZ)

---

## 3. Network setup

### 3.1 VPC

Dùng VPC default của region. Default VPC đã có sẵn 1 public subnet ở mỗi AZ + Internet Gateway. Không cần tạo VPC riêng cho thesis.

### 3.2 Security Groups

Tạo 2 security group:

#### `sg-hcr-service` (cho 3 instance service)

| Type | Protocol | Port | Source | Mục đích |
|------|----------|------|--------|----------|
| SSH | TCP | 22 | YOUR_IP/32 | SSH từ máy bạn |
| Custom TCP | TCP | 8081 | YOUR_IP/32 | k6 đánh ms-order public |
| Custom TCP | TCP | 8081-8083 | sg-hcr-service | Service-to-service nội bộ |
| Custom TCP | TCP | 9000-9090 | sg-hcr-service | Actuator/metrics |

Lệnh CLI:

```bash
aws ec2 create-security-group --group-name sg-hcr-service \
  --description "HCR services" --vpc-id $VPC_ID

# Get YOUR_IP
MY_IP=$(curl -s ifconfig.me)/32

aws ec2 authorize-security-group-ingress --group-name sg-hcr-service \
  --protocol tcp --port 22 --cidr $MY_IP

aws ec2 authorize-security-group-ingress --group-name sg-hcr-service \
  --protocol tcp --port 8081 --cidr $MY_IP

# Service-to-service (intra-SG)
aws ec2 authorize-security-group-ingress --group-name sg-hcr-service \
  --protocol tcp --port 8081-8083 --source-group sg-hcr-service
```

#### `sg-hcr-infra` (cho instance hạ tầng)

| Type | Protocol | Port | Source | Mục đích |
|------|----------|------|--------|----------|
| SSH | TCP | 22 | YOUR_IP/32 | SSH từ máy bạn |
| Postgres | TCP | 5432 | sg-hcr-service | DB từ services |
| Redis | TCP | 6379 | sg-hcr-service | Redis từ services |
| Kafka | TCP | 9092 | sg-hcr-service | Kafka từ services |
| Prometheus | TCP | 9090 | sg-hcr-service | Prom scrape services |
| Grafana | TCP | 3000 | YOUR_IP/32 | Grafana web (qua SSH tunnel an toàn hơn) |
| Zipkin | TCP | 9411 | sg-hcr-service | Tracing |

Tất cả port DB/cache/Kafka **chỉ cho phép từ sg-hcr-service**, không bao giờ public.

---

## 4. Launch 4 EC2 instances

### 4.1 AMI base

Ubuntu 24.04 LTS — `ami-XXXXXX` (lấy AMI ID mới nhất từ console hoặc):

```bash
UBUNTU_AMI=$(aws ec2 describe-images --owners 099720109477 \
  --filters "Name=name,Values=ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*" \
  --query "sort_by(Images, &CreationDate)[-1].ImageId" --output text)
```

### 4.2 Launch lệnh

```bash
# Common params
KEY_NAME=hcr-key
SUBNET_ID=subnet-XXXXX        # public subnet trong AZ-1a

# Infra (no public IP — accessible only via bastion or session manager)
aws ec2 run-instances --image-id $UBUNTU_AMI --instance-type t3.medium \
  --key-name $KEY_NAME --security-group-ids sg-hcr-infra \
  --subnet-id $SUBNET_ID --no-associate-public-ip-address \
  --block-device-mappings '[{"DeviceName":"/dev/sda1","Ebs":{"VolumeSize":30,"VolumeType":"gp3"}}]' \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=hcr-infra}]'

# ms-order — public IP để k6 đánh
aws ec2 run-instances --image-id $UBUNTU_AMI --instance-type t3.small \
  --key-name $KEY_NAME --security-group-ids sg-hcr-service \
  --subnet-id $SUBNET_ID --associate-public-ip-address \
  --block-device-mappings '[{"DeviceName":"/dev/sda1","Ebs":{"VolumeSize":15,"VolumeType":"gp3"}}]' \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=hcr-ms-order}]'

# ms-inventory — private
aws ec2 run-instances --image-id $UBUNTU_AMI --instance-type t3.small \
  --key-name $KEY_NAME --security-group-ids sg-hcr-service \
  --subnet-id $SUBNET_ID --no-associate-public-ip-address \
  --block-device-mappings '[{"DeviceName":"/dev/sda1","Ebs":{"VolumeSize":15,"VolumeType":"gp3"}}]' \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=hcr-ms-inventory}]'

# ms-payment — private
aws ec2 run-instances --image-id $UBUNTU_AMI --instance-type t3.small \
  --key-name $KEY_NAME --security-group-ids sg-hcr-service \
  --subnet-id $SUBNET_ID --no-associate-public-ip-address \
  --block-device-mappings '[{"DeviceName":"/dev/sda1","Ebs":{"VolumeSize":15,"VolumeType":"gp3"}}]' \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=hcr-ms-payment}]'
```

### 4.3 Note IP

Sau khi launch, ghi lại 4 IP:

```bash
aws ec2 describe-instances --filters "Name=tag:Name,Values=hcr-*" \
  --query "Reservations[].Instances[].[Tags[?Key=='Name']|[0].Value,PublicIpAddress,PrivateIpAddress]" \
  --output table
```

Ví dụ (default VPC `172.31.0.0/16`):
```
| hcr-infra        | -            | 172.31.41.163 |
| hcr-ms-order     | 13.x.x.x     | 172.31.41.164 |
| hcr-ms-inventory | -            | 172.31.41.165 |
| hcr-ms-payment   | -            | 172.31.41.166 |
```

Để các service connect Postgres/Redis/Kafka, sẽ dùng **private IP của infra** (`172.31.41.163`).

### 4.4 SSH vào private instance

3 instance không có public IP → SSH qua ms-order (bastion) hoặc dùng SSM Session Manager.

Cách bastion (đơn giản nhất):

```bash
# Local ~/.ssh/config
Host hcr-bastion
  HostName 13.x.x.x          # public IP ms-order
  User ubuntu
  IdentityFile ~/.ssh/hcr-key.pem

Host hcr-infra hcr-ms-inv hcr-ms-payment
  User ubuntu
  IdentityFile ~/.ssh/hcr-key.pem
  ProxyJump hcr-bastion

Host hcr-infra
  HostName 172.31.41.163

Host hcr-ms-inv
  HostName 172.31.41.165

Host hcr-ms-payment
  HostName 172.31.41.166
```

Test: `ssh hcr-infra` → phải vào được instance infra qua bastion.

---

## 5. Bootstrap mỗi instance

### 5.1 Trên `hcr-infra`

Cài Docker + docker-compose:

```bash
ssh hcr-infra

sudo apt update && sudo apt install -y docker.io docker-compose-v2 git
sudo usermod -aG docker ubuntu
exit                          # logout để apply group
ssh hcr-infra
docker --version              # verify
```

### 5.2 Trên 3 instance service

Cài Java 17:

```bash
# Repeat cho hcr-ms-order, hcr-ms-inv, hcr-ms-payment
ssh hcr-ms-order

sudo apt update && sudo apt install -y openjdk-17-jdk-headless
java -version                 # verify openjdk 17

# Tạo dir cho app
sudo mkdir -p /opt/hcr && sudo chown ubuntu:ubuntu /opt/hcr
```

---

## 6. Deploy infra (instance `hcr-infra`)

### 6.1 Modify docker-compose cho cloud

File `infra/docker-compose.yml` đang chạy trên local. Cần điều chỉnh **Kafka advertised listener** để các service từ EC2 khác connect vào (Kafka rất kén listener config).

Trên máy local, copy ra file mới:

```bash
cp hcr-product/infra/docker-compose.yml hcr-product/infra/docker-compose.aws.yml
```

Sửa trong `docker-compose.aws.yml` (phần Kafka):

```yaml
  kafka:
    image: confluentinc/cp-kafka:7.6.1
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: 'broker,controller'
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: 'CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT'
      # ↓ Thay đổi quan trọng — dùng private IP của hcr-infra
      KAFKA_ADVERTISED_LISTENERS: 'PLAINTEXT://172.31.41.163:9092'
      KAFKA_LISTENERS: 'PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093'
      KAFKA_CONTROLLER_LISTENER_NAMES: 'CONTROLLER'
      KAFKA_CONTROLLER_QUORUM_VOTERS: '1@kafka:9093'
      KAFKA_INTER_BROKER_LISTENER_NAME: 'PLAINTEXT'
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      CLUSTER_ID: '4L6g3nShT-eMCtK--X86sw'
    ports:
      - "9092:9092"
```

Ngoài ra, đảm bảo Postgres/Redis bind 0.0.0.0:

```yaml
  postgres:
    ports:
      - "0.0.0.0:5432:5432"
  redis:
    ports:
      - "0.0.0.0:6379:6379"
```

### 6.2 Copy lên `hcr-infra`

```bash
scp -r hcr-product/infra hcr-infra:/home/ubuntu/
ssh hcr-infra "cd ~/infra && docker compose -f docker-compose.aws.yml up -d"
```

Verify:

```bash
ssh hcr-infra "docker compose -f ~/infra/docker-compose.aws.yml ps"
# Tất cả service phải UP
```

---

## 7. Deploy 3 service

### 7.1 Build JAR (local)

```bash
cd hcr-product
mvn -DskipTests clean package
ls ms-*/target/*.jar
# ms-order/target/ms-order-1.0.0-SNAPSHOT.jar
# ms-inventory/target/ms-inventory-1.0.0-SNAPSHOT.jar
# ms-payment/target/ms-payment-1.0.0-SNAPSHOT.jar
```

### 7.2 Config qua environment variables (không cần sửa code)

`application.yml` của cả 3 service đã được **parameter hóa** bằng `${VAR:default}` — local dev không cần env var, vẫn fallback `localhost`. Trên EC2 chỉ cần set env var qua systemd.

Env var dùng cho 3 service:

| Env var | Giá trị trên EC2 | Default (local) |
|---|---|---|
| `DB_HOST` | `172.31.41.163` | `localhost` |
| `DB_PORT` | `5432` | `5432` |
| `DB_NAME` | tuỳ service: `order_db` / `inventory_db` / `payment_db` | (đã có trong YAML) |
| `DB_USER` | `hcr` | `hcr` |
| `DB_PASS` | `hcr` | `hcr` |
| `REDIS_HOST` | `172.31.41.163` | `localhost` |
| `REDIS_PORT` | `6379` | `6379` |
| `KAFKA_BOOTSTRAP` | `172.31.41.163:9092` | `localhost:9092` |
| `ZIPKIN_ENDPOINT` | `http://172.31.41.163:9411/api/v2/spans` | `http://localhost:9411/api/v2/spans` |

> **Lưu ý**: `172.31.41.163` là **private IP của hcr-infra trong default VPC**. Service-to-service traffic không đi qua internet → free, an toàn. Public IP của infra box chỉ để bạn SSH vào, không dùng cho service config.

### 7.3 Copy JAR lên 3 instance

```bash
scp ms-order/target/ms-order-1.0.0-SNAPSHOT.jar hcr-ms-order:/opt/hcr/app.jar
scp ms-inventory/target/ms-inventory-1.0.0-SNAPSHOT.jar hcr-ms-inv:/opt/hcr/app.jar
scp ms-payment/target/ms-payment-1.0.0-SNAPSHOT.jar hcr-ms-payment:/opt/hcr/app.jar
```

### 7.4 systemd service file (lặp cho 3 instance)

Trên mỗi instance tạo `/etc/systemd/system/hcr-app.service`:

**ms-order** (`/etc/systemd/system/hcr-app.service`):

```ini
[Unit]
Description=HCR ms-order
After=network.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/opt/hcr
Environment="DB_HOST=172.31.41.163"
Environment="DB_NAME=order_db"
Environment="REDIS_HOST=172.31.41.163"
Environment="KAFKA_BOOTSTRAP=172.31.41.163:9092"
Environment="ZIPKIN_ENDPOINT=http://172.31.41.163:9411/api/v2/spans"
ExecStart=/usr/bin/java -Xms512m -Xmx1g -jar /opt/hcr/app.jar
Restart=on-failure
RestartSec=5
StandardOutput=journal
StandardError=journal
SyslogIdentifier=hcr-app
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
```

**ms-inventory**: copy file trên, đổi `Description`, đổi `DB_NAME=inventory_db`.
**ms-payment**: copy file trên, đổi `Description`, đổi `DB_NAME=payment_db`, xoá dòng `REDIS_HOST` (ms-payment không dùng Redis).

> **Note JVM heap**: t3.small có 2GB RAM. `-Xmx1g` an toàn. Nếu OOM xảy ra, hạ xuống `-Xmx768m` hoặc nâng instance lên t3.medium.

Enable + start:

```bash
sudo systemctl daemon-reload
sudo systemctl enable hcr-app
sudo systemctl start hcr-app
sudo systemctl status hcr-app          # verify running
sudo journalctl -u hcr-app -f          # tail logs
```

Lặp 3 lần cho 3 instance.

### 7.5 Thứ tự start

Quan trọng — phải đúng thứ tự để Redis Seeder của ms-inventory chạy trước khi ms-order nhận request:

1. `hcr-infra` (đã up ở Step 6)
2. `hcr-ms-inventory` — chạy `RedisSeeder` warm Redis
3. `hcr-ms-payment` — subscribe Kafka topics
4. `hcr-ms-order` — sẵn sàng nhận HTTP

Đợi log `[RedisSeeder] Seeded N concert tickets` xuất hiện ở ms-inventory trước khi start ms-order.

---

## 8. Smoke test

Từ máy local:

```bash
# Health check
curl http://<ms-order-public-ip>:8081/actuator/health

# Đặt 1 vé
curl -X POST http://<ms-order-public-ip>:8081/orders \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: smoke-$(date +%s)" \
  -d "{\"resourceId\":\"concert-001\",\"requesterId\":\"u1\",\"quantity\":1,\"idempotencyKey\":\"smoke-$(date +%s)\"}"

# Phải nhận HTTP 202 + orderId
```

Kiểm tra DB:

```bash
ssh hcr-infra "docker exec hcr-postgres psql -U hcr -d order_db \
  -c 'SELECT order_id, status FROM ticket_orders ORDER BY created_at DESC LIMIT 5;'"
```

---

## 9. Load test từ local

```bash
# Local máy
k6 run --env BASE_URL=http://<ms-order-public-ip>:8081 \
  hcr-product/load-tests/k6/burst.js
```

Lưu ý:
- Latency sẽ cao hơn local ~30-50ms (network RTT VN→Singapore).
- Threshold của burst.js (`p95<500ms`) có thể fail vì RTT — có thể nới lên `p95<700ms` cho test cloud.
- Tách metric: dùng `tags: { env: 'cloud' }` để phân biệt local vs cloud trong Grafana.

Verify zero-oversell sau test:

```bash
ssh hcr-infra
docker exec hcr-postgres psql -U hcr -d order_db -c "SELECT status, COUNT(*) FROM ticket_orders WHERE resource_id='concert-003' GROUP BY status;"
docker exec hcr-redis redis-cli GET hcr:inventory:concert-003
```

---

## 10. Monitoring

### 10.1 Grafana qua SSH tunnel (an toàn — không expose 3000 ra internet)

```bash
# Trên local
ssh -L 3000:172.31.41.163:3000 hcr-bastion
# Mở browser: http://localhost:3000   (admin/admin)
```

### 10.2 Prometheus dashboard

Cũng qua SSH tunnel:

```bash
ssh -L 9090:172.31.41.163:9090 hcr-bastion
# http://localhost:9090
```

Prometheus của hcr-infra cần được cấu hình scrape 3 service. Sửa `infra/prometheus.yml`:

```yaml
scrape_configs:
  - job_name: 'ms-order'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['10.0.1.11:8081']
  - job_name: 'ms-inventory'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['10.0.1.12:8082']
  - job_name: 'ms-payment'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['10.0.1.13:8083']
```

Restart prometheus container:

```bash
ssh hcr-infra "docker compose -f ~/infra/docker-compose.aws.yml restart prometheus"
```

---

## 11. Cost estimate (24/7, ap-southeast-1)

| Resource | Spec | $/hour | $/tháng |
|----------|------|--------|---------|
| 3× t3.small | 2 vCPU, 2GB | $0.0264 | $19 × 3 = $57 |
| 1× t3.medium | 2 vCPU, 4GB | $0.0528 | ~$38 |
| EBS gp3 (4×) | 75GB tổng | — | ~$8 |
| Data transfer | ~5GB out (test runs) | — | ~$0.5 |
| **Tổng** | | | **~$104/tháng** |

Cách giảm:
- **Stop instance khi không demo** — ~$0 instance, vẫn tính EBS (~$8/tháng).
- **Spot instance** cho 3 service (không phải infra) — ~70% off → ~$30/tháng.
- **Một tuần demo** → khoảng $25-30 nếu auto-stop ngoài giờ.

---

## 12. Cleanup

Sau khi xong demo:

```bash
# Get instance IDs
INSTANCE_IDS=$(aws ec2 describe-instances \
  --filters "Name=tag:Name,Values=hcr-*" "Name=instance-state-name,Values=running,stopped" \
  --query "Reservations[].Instances[].InstanceId" --output text)

# Terminate
aws ec2 terminate-instances --instance-ids $INSTANCE_IDS

# Delete security groups (sau khi instance đã terminate xong)
aws ec2 delete-security-group --group-name sg-hcr-service
aws ec2 delete-security-group --group-name sg-hcr-infra
```

---

## 13. Production hardening (ngoài scope thesis)

Nếu muốn nâng lên thật:

| Khía cạnh | Hiện tại | Production |
|-----------|----------|------------|
| HA | 1 AZ, 1 instance/role | Multi-AZ, ASG min 2 |
| Load balancer | Direct IP | ALB + Target Group |
| TLS | Không | ACM cert + ALB :443 |
| DB | EC2 self-host | RDS Multi-AZ + read replica |
| Cache | EC2 self-host | ElastiCache Redis cluster |
| Kafka | Single broker | MSK 3-broker cluster |
| Secrets | Hardcode trong yml | Secrets Manager / Parameter Store |
| Deploy | scp + systemd | CI/CD (GitHub Actions → CodeDeploy) |
| Monitoring | Self-host Prom/Grafana | CloudWatch + AMP/AMG |
| Backup | Không | RDS automated backup + S3 snapshot |

Mỗi item đều có chi phí + complexity. Cho thesis demo thì topology hiện tại là đủ — story rõ "deploy thật trên cloud, 3 service tách biệt".

---

## Phụ lục — Option B (3 instance, infra co-locate)

Nếu muốn tiết kiệm $15/tháng, bỏ instance `hcr-infra`, chuyển docker-compose lên `hcr-ms-order`:

1. Nâng `hcr-ms-order` lên `t3.medium` (cần RAM cho Kafka).
2. Cài Docker thêm (cùng cài Java 17).
3. `docker compose up -d` infra trên ms-order box.
4. 3 service connect tới `localhost` cho Postgres/Redis/Kafka (vì cùng box, không cần đổi yml).
5. ms-inv & ms-payment connect tới **private IP của ms-order** thay vì `localhost`.

Tradeoff:
- ✅ Giảm 1 instance, ~$15/tháng
- ❌ Bench latency của ms-order bị bias vì JVM compete tài nguyên với Kafka
- ❌ Khó tách diagnose: ms-order chậm = vì code hay vì Kafka cùng box?

**Khuyến nghị**: chỉ dùng nếu budget cực chặt. Ngược lại stick với Option A.

---

## Phụ lục — Common pitfalls

| Triệu chứng | Nguyên nhân | Fix |
|-------------|-------------|-----|
| Service start nhưng không connect được Kafka | Kafka `advertised.listeners` còn là `localhost` | Sửa thành private IP của hcr-infra |
| Postgres "connection refused" | Listen address chưa bind 0.0.0.0 | Set `0.0.0.0:5432:5432` trong compose |
| `Connection reset` từ k6 | Security group chưa cho phép port 8081 từ IP của bạn | Update sg-hcr-service inbound rule |
| ms-order OOM (heap) | t3.small RAM 2GB, JVM 1GB + OS = sát | Hạ `-Xmx768m` hoặc nâng t3.medium |
| Latency cao bất thường | Cross-AZ traffic | Đảm bảo 4 instance cùng AZ |
| systemd start fail "address already in use" | Port 8081 đã bind | `sudo lsof -i :8081` rồi kill / reboot |

---

## Liên kết

- [`README.md`](../README.md) — overview product
- [`README.md` §8.9](../README.md#89-performance-tuning-đã-áp-dụng) — performance tuning đã apply
- [`docs/tuning-journal.md`](tuning-journal.md) — nhật ký tuning chi tiết
- AWS pricing: https://calculator.aws/
