# Deploy hcr-product lên GCP Compute Engine

> Hướng dẫn deploy 3 microservice + infra lên Google Cloud cho mục đích đo benchmark thesis.
> Region: **asia-southeast1 (Singapore)** — latency từ VN ~25-40ms.
> Đối chiếu phiên bản AWS: [`deploy-aws-ec2.md`](deploy-aws-ec2.md).
> Vận hành hằng ngày: tạo `runbook-gcp.md` riêng nếu cần (chưa có).

## TL;DR

- **4 VM** (recommended) — App / Data / Bus+Obs / Loadgen.
- **5 VM** option (phụ lục A) — tách Redis riêng để cực sạch số đo P3.
- Switch P1/P2/P3 qua env var `ACTIVE_PROTOTYPE=p1|p2|p3` (đã setup ở `application.yml`).
- SSH qua **IAP tunnel** (không expose port 22 ra internet) — trên **Windows phải dùng OpenSSH + ProxyCommand** vì `gcloud compute ssh --tunnel-through-iap` bị bug với bundled PuTTY (xem Step 5).
- VM **bắt buộc có outbound internet** (public IP hoặc Cloud NAT) — nếu không, `apt`/`docker pull` fail (xem Step 4.6).
- Cost: ~**$5-20/tuần** demo nếu STOP khi không dùng; ~$300/tháng nếu 24/7. Public IP thêm ~$15/tháng 24/7 (gần 0 khi STOP).

---

## 0. Pre-deploy adjustments (CẦN làm trước khi deploy)

Trước khi đụng vào GCP, làm 4 thay đổi để giảm bias số đo benchmark:

### 0.1. Tăng Prometheus scrape interval (5s → 30s)

⚠ **Bug đang tồn tại**: `infra/observability/prometheus.yml` set `scrape_interval: 5s` — aggressive gấp 3 lần default (15s). Mỗi 5s, Prometheus gọi `/actuator/prometheus` lên cả 3 service → CPU spike trên app dưới tải cao = méo số đo throughput.

**Fix**: đổi `5s` → `30s` (đủ để Grafana vẽ chart, không bị nhiễu).

```diff
 global:
-  scrape_interval: 5s
-  evaluation_interval: 5s
+  scrape_interval: 30s
+  evaluation_interval: 30s
```

Nếu đo pure throughput không cần dashboard live: tạm `60s` hoặc disable scrape job bằng comment.

### 0.2. Giảm tracing sampling probability

Cả 3 `application.yml` đang set `management.tracing.sampling.probability: 1.0` (sample 100% — debug-grade). Lúc đo benchmark, giảm xuống `0.01` (1%) hoặc `0.0` (off).

```diff
 management:
   tracing:
     sampling:
-      probability: 1.0
+      probability: 0.01
```

Trace span build + push tới Zipkin tiêu tốn CPU + latency mỗi request. 100% sample → +5-15% overhead trong path nóng.

### 0.3. Tách infra thành 2 docker-compose (data + bus-obs)

`infra/docker-compose.yml` hiện chạy tất cả trên 1 host. Khi deploy 4-VM, chia thành 2 file dùng **docker compose profile**:

- **Data services** (Postgres + Redis) → VM `hcr-data` với SSD
- **Bus + Observability** (Kafka + Prom + Grafana + Zipkin) → VM `hcr-busobs`

Tôi đề xuất tạo 2 file mới (chi tiết ở Step 7–8). Để tôi tạo sau khi bạn duyệt outline doc.

### 0.4. Verify env var pattern

`application.yml` cả 3 service đã được parameter hóa qua `${VAR:default}` và `spring.profiles.active: ${ACTIVE_PROTOTYPE:p1}` — KHÔNG cần đổi code. Chỉ set env var ở systemd unit file.

---

## 1. Topology — 4 VM (recommended)

```
┌─────────────────────────────────────────────────────────────────────┐
│   VPC `hcr-vpc` · subnet 10.20.0.0/24 · zone asia-southeast1-a      │
│                                                                     │
│  ┌──────────────────────┐         ┌──────────────────────┐          │
│  │  hcr-app             │         │  hcr-data            │          │
│  │  e2-standard-4       │  TCP    │  e2-standard-2 + SSD │          │
│  │  4vCPU / 16GB        │ ───────▶│  Postgres :5432      │          │
│  │  ─ ms-order 8081     │         │  Redis :6379         │          │
│  │  ─ ms-inventory 8082 │         │  10.20.0.3           │          │
│  │  ─ ms-payment 8083   │         └──────────────────────┘          │
│  │  10.20.0.2           │                                           │
│  │                      │         ┌──────────────────────┐          │
│  │                      │  TCP    │  hcr-busobs          │          │
│  │                      │ ───────▶│  e2-standard-2       │          │
│  │                      │         │  Kafka :9092         │          │
│  │                      │ ◀──────│  Prometheus :9090    │          │
│  │                      │ scrape  │  Grafana :3000       │          │
│  └──────────────────────┘         │  Zipkin :9411        │          │
│            ▲                      │  10.20.0.4           │          │
│            │ HTTP 8081            └──────────────────────┘          │
│            │                                                        │
│  ┌─────────┴────────────┐                                           │
│  │  hcr-loadgen         │                                           │
│  │  e2-standard-4       │                                           │
│  │  k6                  │                                           │
│  │  10.20.0.5           │                                           │
│  └──────────────────────┘                                           │
│                                                                     │
│  Tất cả VM: NO public IP. SSH qua IAP tunnel từ máy bạn.            │
└─────────────────────────────────────────────────────────────────────┘
```

**Tại sao 4 VM:**
- **`hcr-app`** dedicate 4 vCPU cho 3 JVM — không bị Postgres/Redis tranh CPU.
- **`hcr-data`** SSD persistent disk — Postgres async sync (P3) và P1/P2 row-lock đều IO-bound.
- **`hcr-busobs`** tách Prometheus + Kafka khỏi data/app — Prometheus scrape không can thiệp DB/Redis.
- **`hcr-loadgen`** k6 trên VM riêng — single-VM k6 có thể tiêu CPU cao ở RPS cao, nếu chung app box → false bottleneck.

**Tradeoff so với 5 VM (phụ lục A):** Postgres + Redis cùng VM. Khi P3 chạy peak, Redis (single-thread) + Postgres async sync có thể compete CPU. Với e2-standard-2 (2 vCPU), Redis chiếm 1 core, Postgres còn 1 → vẫn ổn. Nếu cần con số chính xác đến 3 chữ số cho thesis defense, dùng 5 VM.

---

## 2. GCP account setup (lần đầu)

Bạn nói chưa từng dùng GCP — mục này hướng dẫn từ 0.

### 2.1. Tạo project

1. Vào https://console.cloud.google.com
2. Top bar: dropdown bên cạnh "Google Cloud" → **NEW PROJECT**
3. Đặt tên: `hcr-thesis-demo` → CREATE
4. Đợi 1–2 phút, chọn project vừa tạo từ dropdown.
5. Note lại **PROJECT_ID** (vd `hcr-thesis-demo-462813`) — dùng cho gcloud sau.

### 2.2. Enable billing

Compute Engine yêu cầu billing account.

1. Menu trái (☰) → **Billing** → **LINK A BILLING ACCOUNT**
2. **Free trial**: GCP tặng **$300 credit / 90 ngày** cho account mới + e2-micro free forever.
3. Nhập thẻ credit (verify thôi — không bị charge trong free trial nếu < $300).
4. Link account vào project.

### 2.3. Cài gcloud CLI (Windows)

1. Download: https://cloud.google.com/sdk/docs/install-sdk#windows
2. Chạy installer (`GoogleCloudSDKInstaller.exe`)
3. Tick "Run gcloud init" cuối installer.
4. Mở PowerShell mới (PATH cần refresh).

Verify:
```powershell
gcloud --version
```

### 2.4. Login + set default

```powershell
gcloud auth login
# Browser sẽ mở, login Google account đã link billing.

gcloud config set project hcr-thesis-demo-462813   # thay PROJECT_ID
gcloud config set compute/region asia-southeast1
gcloud config set compute/zone asia-southeast1-a

# Enable 2 API cần
gcloud services enable compute.googleapis.com
gcloud services enable iap.googleapis.com
```

> 💡 **Pitfall lần đầu**: Nếu chạy `gcloud config set compute/region` TRƯỚC khi enable API, gcloud auto-prompt:
> ```
> API [compute.googleapis.com] not enabled on project [...]. Would you like to enable and retry (y/N)?
> ```
> → Bấm `y`, mất ~1-2 phút enable. Tương tự cho `iap.googleapis.com` khi cần.

---

## 3. Network setup

### 3.1. VPC + subnet

```powershell
gcloud compute networks create hcr-vpc --subnet-mode=custom

gcloud compute networks subnets create hcr-subnet `
  --network=hcr-vpc `
  --range=10.20.0.0/24 `
  --region=asia-southeast1
```

### 3.2. Firewall rules

```powershell
# (1) SSH qua IAP — chỉ cho phép Google's IAP range 35.235.240.0/20
gcloud compute firewall-rules create hcr-allow-iap-ssh `
  --network=hcr-vpc `
  --direction=INGRESS --action=ALLOW `
  --rules=tcp:22 `
  --source-ranges=35.235.240.0/20

# (2) Service-to-service nội bộ (cùng subnet)
gcloud compute firewall-rules create hcr-allow-internal `
  --network=hcr-vpc `
  --direction=INGRESS --action=ALLOW `
  --rules=tcp:8081-8083,tcp:5432,tcp:6379,tcp:9092,tcp:9090,tcp:9411,tcp:3000 `
  --source-ranges=10.20.0.0/24
```

> 💡 **GCP IAP** thay thế bastion — không cần expose port 22 ra internet, không cần `.pem` key thủ công. SSH key tự generate khi `gcloud compute ssh` lần đầu.

---

## 4. Tạo 4 VM

### 4.1. VM `hcr-app` (3 JVM)

```powershell
gcloud compute instances create hcr-app `
  --machine-type=e2-standard-4 `
  --image-family=ubuntu-2404-lts-amd64 `
  --image-project=ubuntu-os-cloud `
  --boot-disk-size=20GB --boot-disk-type=pd-balanced `
  --network=hcr-vpc --subnet=hcr-subnet `
  --no-address `
  --tags=hcr-app
```

`--no-address`: không gán public IP. SSH qua IAP.

### 4.2. VM `hcr-data` (Postgres + Redis)

```powershell
gcloud compute instances create hcr-data `
  --machine-type=e2-standard-2 `
  --image-family=ubuntu-2404-lts-amd64 `
  --image-project=ubuntu-os-cloud `
  --boot-disk-size=30GB --boot-disk-type=pd-ssd `
  --network=hcr-vpc --subnet=hcr-subnet `
  --no-address `
  --tags=hcr-data
```

`pd-ssd`: SSD persistent disk (cao IOPS — Postgres cần). +$5/tháng nhưng đáng cho benchmark.

### 4.3. VM `hcr-busobs` (Kafka + Prom + Grafana + Zipkin)

```powershell
gcloud compute instances create hcr-busobs `
  --machine-type=e2-standard-2 `
  --image-family=ubuntu-2404-lts-amd64 `
  --image-project=ubuntu-os-cloud `
  --boot-disk-size=20GB --boot-disk-type=pd-balanced `
  --network=hcr-vpc --subnet=hcr-subnet `
  --no-address `
  --tags=hcr-busobs
```

> Kafka cần tối thiểu 4GB RAM. e2-standard-2 có 8GB — đủ Kafka + Prom + Grafana + Zipkin.

### 4.4. VM `hcr-loadgen` (k6)

```powershell
gcloud compute instances create hcr-loadgen `
  --machine-type=e2-standard-4 `
  --image-family=ubuntu-2404-lts-amd64 `
  --image-project=ubuntu-os-cloud `
  --boot-disk-size=20GB --boot-disk-type=pd-balanced `
  --network=hcr-vpc --subnet=hcr-subnet `
  --no-address `
  --tags=hcr-loadgen
```

### 4.5. Ghi lại private IP

```powershell
gcloud compute instances list
```

Output dạng:
```
NAME          ZONE                INTERNAL_IP   EXTERNAL_IP  STATUS
hcr-app       asia-southeast1-a   10.20.0.2     -            RUNNING
hcr-data      asia-southeast1-a   10.20.0.3     -            RUNNING
hcr-busobs    asia-southeast1-a   10.20.0.4     -            RUNNING
hcr-loadgen   asia-southeast1-a   10.20.0.5     -            RUNNING
```

**Ghi lại 4 IP này** — dùng cho config app + Prometheus targets.

> Trong doc này tôi giả định IP mặc định `10.20.0.2-5`. Thay đổi cho phù hợp với deployment thực tế.

### 4.6. ⚠ REQUIRED — Cấp outbound internet cho VM

VM tạo với `--no-address` **không có public IP** → **không reach internet** → `apt update`, `docker pull`, `k6` install... đều fail với `Network is unreachable` / `Connection timed out`.

Triệu chứng khi quên bước này (Step 6 bootstrap sẽ fail):
```
Err:1 http://security.ubuntu.com/ubuntu noble-security InRelease
  Could not connect to security.ubuntu.com:80 ... connection timed out
E: Package 'docker.io' has no installation candidate
```

**3 option, chọn 1:**

**Option A — Add ephemeral public IP (recommended cho thesis demo)**

```powershell
gcloud compute instances add-access-config hcr-app --zone=asia-southeast1-a
gcloud compute instances add-access-config hcr-data --zone=asia-southeast1-a
gcloud compute instances add-access-config hcr-busobs --zone=asia-southeast1-a
gcloud compute instances add-access-config hcr-loadgen --zone=asia-southeast1-a
```

Verify mỗi VM có `EXTERNAL_IP`:
```powershell
gcloud compute instances list
```

✅ SSH vẫn an toàn qua IAP — firewall rule `hcr-allow-iap-ssh` (Step 3.2) chỉ cho source `35.235.240.0/20`. Public IP chỉ phục vụ outbound, port 22 vẫn KHÔNG expose ra internet.

Cost: ~$0.005/hour/VM × 4 VM = **~$15/tháng 24/7** (gần như 0 khi STOP).

**Option B — Cloud NAT (production-grade, không exposed surface)**

```powershell
gcloud compute routers create hcr-nat-router --network=hcr-vpc --region=asia-southeast1

gcloud compute routers nats create hcr-nat-gateway `
  --router=hcr-nat-router `
  --region=asia-southeast1 `
  --nat-all-subnet-ip-ranges `
  --auto-allocate-nat-external-ips
```

Chờ ~1-2 phút cho NAT propagate. Test outbound từ VM (sau khi setup SSH ở Step 5):
```cmd
ssh hcr-data "curl -s -o /dev/null -w '%{http_code}\n' https://www.google.com"
```
Phải trả `200`.

**Option C — Bỏ `--no-address` khi tạo VM**

Nếu CHƯA tạo VM, xóa flag `--no-address` ở các lệnh Step 4.1-4.4. VM sẽ có public IP ngay từ đầu (giống Option A nhưng skip bước add-access-config).

→ Cho thesis demo: **Option A** đơn giản nhất, **không ảnh hưởng benchmark** vì traffic load test vẫn chạy private IP `10.20.0.x` (k6 → app → DB/Redis/Kafka đều internal).

---

## 5. SSH qua IAP

> ### ⚠ Windows pitfall — `gcloud compute ssh --tunnel-through-iap` DỄ FAIL
>
> Path mặc định Google docs khuyến nghị (1 lệnh):
> ```cmd
> gcloud compute ssh hcr-app --tunnel-through-iap --zone=asia-southeast1-a
> ```
> Trên Windows **rất hay bị bug**:
> ```
> ERROR: (gcloud.compute.ssh) [...\putty.exe] exited with return code [1].
> option "-legacy-stdio-prompts" not available in this tool
> ```
>
> **Root cause**: gcloud truyền flag `-legacy-stdio-prompts` cho `putty.exe` (GUI), nhưng PuTTY GUI chưa bao giờ support flag này (chỉ `plink.exe` có). PuTTY bundled cũ silently ignore → ok. Update gcloud / replace putty mới → strict parsing → error.
>
> **Không fix được bằng cách update putty hay gcloud.** Bug có từ lâu, Google biết nhưng chưa fix vì Linux/Mac không dính.
>
> → **Workaround chính thức**: dùng OpenSSH client thuần + `ProxyCommand` (theo [doc IAP TCP forwarding](https://cloud.google.com/iap/docs/using-tcp-forwarding#tunneling_with_ssh)). Section 5.1-5.4 dưới đây.

### 5.1. Cài OpenSSH Client (Windows 10/11)

Windows 10/11 có OpenSSH Client làm optional feature. Verify:
```cmd
ssh -V
```
Nếu thấy `OpenSSH_for_Windows_X.X` → OK, skip xuống 5.2.

Nếu lỗi `'ssh' is not recognized`:
```powershell
# Mở PowerShell as Administrator
Add-WindowsCapability -Online -Name OpenSSH.Client~~~~0.0.1.0
```
Mở lại CMD/PowerShell mới (refresh PATH), verify `ssh -V` lại.

### 5.2. Tạo `~/.ssh/config` cho IAP tunnel

Tạo file `C:\Users\<USERNAME>\.ssh\config` (không extension):

```
Host hcr-*
  User <LINUX_USER>
  IdentityFile C:\Users\<USERNAME>\.ssh\google_compute_engine
  ProxyCommand <GCLOUD_PATH>\google-cloud-sdk\bin\gcloud.cmd compute start-iap-tunnel %h 22 --listen-on-stdin --zone=asia-southeast1-a
  StrictHostKeyChecking accept-new
  ServerAliveInterval 60
  ServerAliveCountMax 3
```

Thay placeholders:
- `<USERNAME>` — Windows user (vd `Admin`)
- `<LINUX_USER>` — username Linux trên VM (thường = Windows user, gcloud tự generate ở lần SSH đầu)
- `<GCLOUD_PATH>` — đường dẫn cài gcloud SDK (vd `D:\GoogleCloud`)

⚠ **Tạo bằng notepad**: "Save as type" chọn **All Files (\*.\*)**, file name `config` (KHÔNG `.txt`). Verify:
```cmd
dir "C:\Users\<USERNAME>\.ssh\config"
```
Phải thấy `config` không có extension.

> 💡 Lần đầu SSH chưa có file `google_compute_engine` (private key) — gcloud sẽ tự generate khi `start-iap-tunnel` lần đầu, push public key lên project metadata. Đợi ~30s.

### 5.3. Test SSH

```cmd
ssh hcr-app
```

Lần đầu hỏi:
```
Are you sure you want to continue connecting (yes/no/[fingerprint])?
```
→ Gõ **`yes`** (full word, không phải `y`) + Enter. Vào shell `<LINUX_USER>@hcr-app:~$`. Gõ `exit` để thoát.

Test tiếp 3 VM:
```cmd
ssh hcr-data
ssh hcr-busobs
ssh hcr-loadgen
```

Cả 4 đều dùng cùng pattern `hcr-*` → không cần config riêng.

> 💡 **Cosmetic error sau exit**: thường thấy traceback `[Errno 5] stdin ReadFile failed` ở cuối. Đây là gcloud IAP tunnel tear-down bug trên Windows — **bỏ qua**, không ảnh hưởng functionality. Trên Linux/Mac không có.

### 5.4. SCP (upload file)

```cmd
scp local-file.txt hcr-app:~/file.txt
scp -r C:\path\to\folder hcr-app:~/folder
```

Vì `.ssh/config` match `hcr-*` cho cả ssh + scp → SCP cũng route qua IAP tunnel tự động, không cần thêm flag.

### 5.5. Fallback (Linux/Mac, hoặc Windows nếu PuTTY may mắn work)

Trên Linux/Mac, `gcloud compute ssh` dùng OpenSSH bundled, work ngay:
```bash
gcloud compute ssh hcr-app --tunnel-through-iap --zone=asia-southeast1-a
gcloud compute scp local.txt hcr-app:~/ --tunnel-through-iap --zone=asia-southeast1-a
```

Trên Windows: thử lệnh trên trước. Nếu lỗi `putty.exe exited with return code [1]` hoặc `option "-legacy-stdio-prompts" not available` → quay lại Section 5.1-5.4.

> 📌 **Từ Step 6 trở đi**, mọi lệnh `gcloud compute ssh hcr-X --tunnel-through-iap --command="..."` ở doc gốc đã được rewrite thành `ssh hcr-X "..."`. Mọi `gcloud compute scp ... --tunnel-through-iap` thành `scp ...`. Nếu bạn dùng Linux/Mac và muốn dùng path `gcloud compute ssh`, cứ thay ngược lại — semantics tương đương.

---

## 6. Bootstrap mỗi VM

> Prerequisite: Step 4.6 đã cấp outbound internet (public IP hoặc Cloud NAT). Nếu chưa, mọi lệnh `apt` ở dưới sẽ fail.
> Prerequisite: Step 5 đã setup `.ssh/config` để dùng alias `ssh hcr-X`.

### 6.1. `hcr-data` + `hcr-busobs` — cài Docker

```cmd
ssh hcr-data "sudo apt update && sudo apt install -y docker.io docker-compose-v2 && sudo usermod -aG docker $USER"
ssh hcr-busobs "sudo apt update && sudo apt install -y docker.io docker-compose-v2 && sudo usermod -aG docker $USER"
```

> 💡 `$USER` không bị Windows CMD expand (đó là biến shell Linux) — gửi nguyên qua SSH, Linux shell remote tự expand thành username.

Verify (group `docker` áp dụng từ SSH session tiếp theo, không cần logout thủ công):
```cmd
ssh hcr-data "docker --version"
ssh hcr-busobs "docker --version"
```
Phải thấy `Docker version 2x.x.x` (vd `26.1.3`).

### 6.2. `hcr-app` — cài Java 17

```cmd
ssh hcr-app "sudo apt update && sudo apt install -y openjdk-17-jdk-headless && sudo mkdir -p /opt/hcr && sudo chown $USER:$USER /opt/hcr && java -version"
```

Output cuối phải thấy `openjdk version "17.x.x"`.

### 6.3. `hcr-loadgen` — cài k6

Lệnh dài, vào shell rồi paste cho dễ debug:
```cmd
ssh hcr-loadgen
```

Trong shell loadgen:
```bash
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo 'deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main' | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt update && sudo apt install -y k6
k6 version
exit
```

Verify in `k6 v0.5x.x`.

---

## 7. Deploy infra-data trên `hcr-data`

### 7.1. Tạo `infra/docker-compose.gcp-data.yml` (local)

```yaml
name: hcr-data

services:
  postgres:
    image: postgres:15-alpine
    container_name: hcr-postgres
    ports:
      - "0.0.0.0:5432:5432"
    environment:
      POSTGRES_USER: hcr
      POSTGRES_PASSWORD: hcr
      POSTGRES_DB: postgres
    volumes:
      - ./observability/postgres-init:/docker-entrypoint-initdb.d
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U hcr"]
      interval: 5s
      timeout: 3s
      retries: 10

  redis:
    image: redis:7-alpine
    container_name: hcr-redis
    ports:
      - "0.0.0.0:6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 10

volumes:
  pgdata:
```

Khác `docker-compose.yml` gốc:
- Chỉ Postgres + Redis (đã tách Kafka/Prom/Grafana/Zipkin)
- Bind `0.0.0.0` cho port để app từ VM khác connect được

### 7.2. Upload + start

```powershell
gcloud compute scp --recurse hcr-product/infra hcr-data:~/infra --tunnel-through-iap

gcloud compute ssh hcr-data --tunnel-through-iap --command="
  cd ~/infra && docker compose -f docker-compose.gcp-data.yml up -d
  docker compose -f docker-compose.gcp-data.yml ps
"
```

Verify 2 container UP + `(healthy)`:
```powershell
gcloud compute ssh hcr-data --tunnel-through-iap --command="
  docker exec hcr-postgres psql -U hcr -d postgres -c '\l' | grep _db
"
# Phải thấy: inventory_db, order_db, payment_db
```

---

## 8. Deploy bus-obs trên `hcr-busobs`

### 8.1. Tạo `infra/docker-compose.gcp-busobs.yml` (local)

```yaml
name: hcr-busobs

services:
  kafka:
    image: confluentinc/cp-kafka:7.6.0
    container_name: hcr-kafka
    ports:
      - "0.0.0.0:9092:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
      # ⚠ QUAN TRỌNG: advertise private IP của hcr-busobs (10.20.0.4)
      # — nếu để localhost, app từ hcr-app sẽ kết nối thành công bootstrap
      # rồi fail khi Kafka redirect về `localhost:9092` (resolve về app VM!).
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://${KAFKA_HOST:-10.20.0.4}:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@localhost:9093
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
      CLUSTER_ID: MkU3OEVGRkNBRjEwNDAxNA
    healthcheck:
      test: ["CMD-SHELL", "kafka-broker-api-versions --bootstrap-server localhost:9092"]
      interval: 10s
      timeout: 5s
      retries: 10

  zipkin:
    image: openzipkin/zipkin:latest
    container_name: hcr-zipkin
    ports:
      - "0.0.0.0:9411:9411"

  prometheus:
    image: prom/prometheus:latest
    container_name: hcr-prometheus
    ports:
      - "0.0.0.0:9090:9090"
    volumes:
      - ./observability/prometheus.yml:/etc/prometheus/prometheus.yml

  grafana:
    image: grafana/grafana:latest
    container_name: hcr-grafana
    ports:
      - "0.0.0.0:3000:3000"
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
    volumes:
      - ./observability/grafana/provisioning:/etc/grafana/provisioning
    depends_on:
      - prometheus
```

### 8.2. Sửa `prometheus.yml` để scrape app VM

```yaml
global:
  scrape_interval: 30s              # đã đổi ở Step 0.1 — KHÔNG để 5s
  evaluation_interval: 30s

scrape_configs:
  - job_name: ms-order
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["10.20.0.2:8081"]      # IP private của hcr-app

  - job_name: ms-inventory
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["10.20.0.2:8082"]

  - job_name: ms-payment
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["10.20.0.2:8083"]
```

### 8.3. Upload + start

```powershell
gcloud compute scp --recurse hcr-product/infra hcr-busobs:~/infra --tunnel-through-iap

gcloud compute ssh hcr-busobs --tunnel-through-iap --command="
  cd ~/infra && KAFKA_HOST=10.20.0.4 docker compose -f docker-compose.gcp-busobs.yml up -d
  docker compose -f docker-compose.gcp-busobs.yml ps
"
```

Verify Kafka advertise đúng IP:
```powershell
gcloud compute ssh hcr-busobs --tunnel-through-iap --command="
  docker exec hcr-kafka kafka-broker-api-versions --bootstrap-server localhost:9092 2>&1 | head -3
"
# Phải thấy '10.20.0.4:9092', KHÔNG phải 'localhost:9092'
```

---

## 9. Deploy 3 app trên `hcr-app`

> Prerequisite: Step 5 `.ssh/config` đã setup → dùng được alias `ssh hcr-app` + `scp ... hcr-app:`.
>
> ### Artifact local cần chuẩn bị (KHÔNG inline trong VM nữa)
>
> Các file dưới đã có trong repo — version-controlled, sửa local rồi upload, không gõ tay heredoc trên VM:
>
> ```
> hcr-product/infra/
> ├── env.gcp.example                  (template /etc/hcr/env)
> └── systemd/
>     ├── hcr-order.service            (Xmx 2g, DB_NAME=order_db)
>     ├── hcr-inventory.service        (Xmx 2g, DB_NAME=inventory_db)
>     └── hcr-payment.service          (Xmx 1g, DB_NAME=payment_db)
> ```
>
> ⚠ **Kiểm tra `User=` trong unit file** trước khi upload. Mặc định set `User=ubuntu` (Ubuntu 24.04 GCP image). Nếu VM của bạn không có user `ubuntu` (ví dụ `.ssh/config` set `User=Admin` → VM dùng user `Admin`), `sed -i` thay cả 3 unit file:
> ```cmd
> ssh hcr-app "id ubuntu 2>/dev/null && echo OK || echo MISSING"
> ```
> Nếu `MISSING` → sửa local: `sed -i 's/User=ubuntu/User=Admin/' hcr-product/infra/systemd/*.service` (hoặc bằng tay).

### 9.1. Build 3 JAR (local)

```powershell
cd hcr-product
mvn -DskipTests clean package
```

Output: `ms-{order,inventory,payment}/target/ms-*-1.0.0-SNAPSHOT.jar`

### 9.2. Edit `env.gcp.example` (nếu cần)

Mặc định file có:
```
ACTIVE_PROTOTYPE=p1
DB_HOST=10.20.0.3        # hcr-data private IP
REDIS_HOST=10.20.0.3     # hcr-data private IP
KAFKA_BOOTSTRAP=10.20.0.4:9092           # hcr-busobs
ZIPKIN_ENDPOINT=http://10.20.0.4:9411/api/v2/spans
MS_PAYMENT_URL=http://localhost:8083     # cùng VM hcr-app
```

Nếu private IP của VM bạn khác `10.20.0.3` / `10.20.0.4` → edit file local trước khi upload.

`DB_NAME` không ở đây — đã set per-service trong unit file (`Environment="DB_NAME=order_db"` etc.).

### 9.3. Upload artifact (JAR + env + 3 unit file)

```cmd
scp ms-order/target/ms-order-1.0.0-SNAPSHOT.jar hcr-app:~/ms-order.jar
scp ms-inventory/target/ms-inventory-1.0.0-SNAPSHOT.jar hcr-app:~/ms-inventory.jar
scp ms-payment/target/ms-payment-1.0.0-SNAPSHOT.jar hcr-app:~/ms-payment.jar

scp infra/env.gcp.example hcr-app:~/env
scp infra/systemd/hcr-order.service hcr-app:~/
scp infra/systemd/hcr-inventory.service hcr-app:~/
scp infra/systemd/hcr-payment.service hcr-app:~/
```

> 💡 Tất cả land vào `~` (home dir) trước — bước 9.4 sẽ `sudo mv` vào đúng vị trí cuối (vì user ssh thường không có quyền write vào `/opt/hcr` và `/etc/`).

### 9.4. Install (1 lệnh SSH)

```cmd
ssh hcr-app "sudo mkdir -p /opt/hcr /etc/hcr && sudo mv ~/ms-*.jar /opt/hcr/ && sudo chown ubuntu:ubuntu /opt/hcr/*.jar && sudo mv ~/env /etc/hcr/env && sudo mv ~/hcr-*.service /etc/systemd/system/ && sudo systemctl daemon-reload && sudo systemctl enable hcr-inventory hcr-payment hcr-order"
```

Lệnh này làm 6 việc:
1. Tạo `/opt/hcr` + `/etc/hcr`
2. Move 3 JAR → `/opt/hcr/`
3. `chown` JAR cho user mà systemd unit chạy (sửa `ubuntu` nếu unit dùng user khác — xem note đầu Step 9)
4. Move env file → `/etc/hcr/env`
5. Move 3 unit file → `/etc/systemd/system/`
6. `daemon-reload` + `enable` 3 service (chưa start)

### 9.5. Start đúng thứ tự

```cmd
ssh hcr-app "sudo systemctl start hcr-inventory && sleep 15 && sudo journalctl -u hcr-inventory -n 30 | grep -i seeder"
ssh hcr-app "sudo systemctl start hcr-payment && sleep 5"
ssh hcr-app "sudo systemctl start hcr-order && sleep 5 && sudo systemctl status hcr-inventory hcr-payment hcr-order --no-pager"
```

Tại sao thứ tự inventory → payment → order:
- **Inventory trước**: `RedisSeeder` (P3) hoặc `data.sql` (P1/P2) phải seed xong trước khi order đầu tiên đến.
- **Payment giữa**: ms-order gọi HTTP sang ms-payment lúc xử lý order — payment phải UP trước khi order accept request.
- **Order cuối**: entry point, lên cuối tránh request fail trong lúc dependency chưa ready.

`sleep 15` cho inventory: đợi RedisSeeder log `Seeded N tickets...` (Spring Boot startup ~8-12s + seed ~2-3s).

> JVM heap: e2-standard-4 có 16GB. Để dành 4GB cho OS, 12GB chia 3 JVM → tổng `-Xmx` = 2+2+1 = 5GB, dư xa.

### 9.6. Verify

```cmd
ssh hcr-app "curl -s http://localhost:8081/actuator/health && echo"
ssh hcr-app "curl -s http://localhost:8082/actuator/health && echo"
ssh hcr-app "curl -s http://localhost:8083/actuator/health && echo"
```

Phải thấy 3 lần `{"status":"UP"}`.

Verify prototype đúng:
```cmd
ssh hcr-app "curl -s http://localhost:8082/actuator/hcr 2>/dev/null | head; grep ACTIVE_PROTOTYPE /etc/hcr/env"
```

### 9.7. Redeploy (sau khi đổi code)

Khi đã có infra (đã chạy Step 9 1 lần), redeploy chỉ cần upload JAR + restart:

```cmd
# Build + upload
cd hcr-product && mvn -DskipTests clean package
scp ms-order/target/ms-order-1.0.0-SNAPSHOT.jar hcr-app:~/ms-order.jar
scp ms-inventory/target/ms-inventory-1.0.0-SNAPSHOT.jar hcr-app:~/ms-inventory.jar
scp ms-payment/target/ms-payment-1.0.0-SNAPSHOT.jar hcr-app:~/ms-payment.jar

# Replace + restart (giữ env + unit file)
ssh hcr-app "sudo mv ~/ms-*.jar /opt/hcr/ && sudo chown ubuntu:ubuntu /opt/hcr/*.jar && sudo systemctl restart hcr-inventory && sleep 10 && sudo systemctl restart hcr-payment && sleep 3 && sudo systemctl restart hcr-order"
```

Đổi unit file (heap, JVM args)? Upload lại từng cái + `sudo systemctl daemon-reload` + restart service tương ứng.

---

## 10. Smoke test

Trên `hcr-app` (hoặc loadgen):

```bash
curl http://localhost:8081/actuator/health
# {"status":"UP"}

curl -X POST http://localhost:8081/orders \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: smoke-$(date +%s)" \
  -d "{\"resourceId\":\"concert-001\",\"requesterId\":\"u1\",\"quantity\":1,\"idempotencyKey\":\"smoke-$(date +%s)\"}"
```

Kỳ vọng:
- `ACTIVE_PROTOTYPE=p1` hoặc `p2` → **HTTP 201**
- `ACTIVE_PROTOTYPE=p3` → **HTTP 202**

Verify DB:
```powershell
gcloud compute ssh hcr-data --tunnel-through-iap --command="
  docker exec hcr-postgres psql -U hcr -d order_db -c \
  'SELECT order_id, status FROM ticket_orders ORDER BY created_at DESC LIMIT 5;'
"
```

---

## 11. Run k6 từ `hcr-loadgen`

### 11.1. Upload test scripts

```powershell
gcloud compute scp --recurse hcr-product/load-tests hcr-loadgen:~/load-tests --tunnel-through-iap
```

### 11.2. Chạy

```powershell
gcloud compute ssh hcr-loadgen --tunnel-through-iap
```

Trong shell loadgen:
```bash
# Sustained 200VU × 5 phút
k6 run --env BASE_URL=http://10.20.0.2:8081 ~/load-tests/k6/sustained.js

# Oversell check (chiến lược test 0-overpost)
k6 run --env BASE_URL=http://10.20.0.2:8081 ~/load-tests/k6/oversell-check.js

# Burst (spike)
k6 run --env BASE_URL=http://10.20.0.2:8081 ~/load-tests/k6/burst.js
```

> ⚠ Verify zero-oversell qua DB/Redis state, KHÔNG qua HTTP 202 count
> (HTTP 202 count CÓ THỂ > capacity do compensate-retry cycle, không phải oversell).
> Async P3: query `concert_tickets.available_quantity` trên `inventory_db` để kiểm tra.

### 11.3. Verify zero-oversell

```bash
gcloud compute ssh hcr-data --tunnel-through-iap --command="
  # Redis (P3 source-of-truth)
  docker exec hcr-redis redis-cli GET hcr:inventory:concert-003

  # Postgres (P1/P2 source-of-truth, hoặc DB sync của P3)
  docker exec hcr-postgres psql -U hcr -d inventory_db -c \
    'SELECT resource_id, total_quantity, available_quantity FROM concert_tickets;'

  docker exec hcr-postgres psql -U hcr -d order_db -c \
    'SELECT status, COUNT(*) FROM ticket_orders WHERE resource_id=\$\$concert-003\$\$ GROUP BY status;'
"
```

---

## 12. Switch prototype P1 ↔ P2 ↔ P3

### 12.1. Edit env file → restart 3 service

```powershell
gcloud compute ssh hcr-app --tunnel-through-iap
```

Trong VM:
```bash
# Đổi ACTIVE_PROTOTYPE
sudo sed -i 's/^ACTIVE_PROTOTYPE=.*/ACTIVE_PROTOTYPE=p2/' /etc/hcr/env

# Verify
grep ACTIVE_PROTOTYPE /etc/hcr/env

# Restart đúng thứ tự
sudo systemctl restart hcr-inventory
sleep 10
sudo systemctl restart hcr-payment
sleep 3
sudo systemctl restart hcr-order
```

### 12.2. Reset state TRƯỚC khi đo prototype mới

⚠ **KHÔNG SET trực tiếp `hcr:inventory:*` qua `redis-cli SET`** — sẽ phá guard của `release.lua`. Cách an toàn duy nhất: FLUSHALL rồi để Seeder tự seed lại.

```bash
gcloud compute ssh hcr-data --tunnel-through-iap --command="
  # Reset Redis
  docker exec hcr-redis redis-cli FLUSHALL

  # Reset order_db.ticket_orders
  docker exec hcr-postgres psql -U hcr -d order_db -c 'TRUNCATE ticket_orders;'

  # Reset inventory back to total
  docker exec hcr-postgres psql -U hcr -d inventory_db -c \
    'UPDATE concert_tickets SET available_quantity = total_quantity, version = 0; TRUNCATE hcr_processed_events;'
"

# Sau đó restart hcr-inventory để re-seed Redis
gcloud compute ssh hcr-app --tunnel-through-iap --command="
  sudo systemctl restart hcr-inventory
  sleep 10
  sudo journalctl -u hcr-inventory -n 30 | grep -i seeder
"
```

---

## 13. Truy cập Monitoring (Grafana / Prom / Zipkin)

Vì các VM không có public IP, dùng **IAP TCP tunnel** để forward port về máy local.

### 13.1. Grafana

```powershell
gcloud compute start-iap-tunnel hcr-busobs 3000 --local-host-port=localhost:3000
```
Mở browser: http://localhost:3000 — login `admin/admin`.

### 13.2. Prometheus

```powershell
gcloud compute start-iap-tunnel hcr-busobs 9090 --local-host-port=localhost:9090
```
http://localhost:9090

### 13.3. Zipkin

```powershell
gcloud compute start-iap-tunnel hcr-busobs 9411 --local-host-port=localhost:9411
```
http://localhost:9411

> Mỗi tunnel chiếm 1 terminal. Mở 3 terminal song song nếu muốn cả 3 cùng lúc.

---

## 14. Cost estimate (asia-southeast1)

| VM | Spec | $/hour | $/tháng (24/7) |
|---|---|---|---|
| hcr-app | e2-standard-4 | $0.134 | ~$97 |
| hcr-data | e2-standard-2 + 30GB pd-ssd | $0.067 + $0.007 | ~$54 |
| hcr-busobs | e2-standard-2 + 20GB pd-balanced | $0.067 + $0.002 | ~$50 |
| hcr-loadgen | e2-standard-4 | $0.134 | ~$97 |
| Network egress | Within zone | FREE | $0 |
| **Tổng 24/7** | | | **~$298** |

### Cách giảm cost

**(1) STOP VM khi không demo** — chỉ tính disk (~$10/tháng tổng):
```powershell
gcloud compute instances stop hcr-app hcr-data hcr-busobs hcr-loadgen
# Khi cần dùng lại:
gcloud compute instances start hcr-app hcr-data hcr-busobs hcr-loadgen
```

**(2) STOP loadgen + app khi không test** — giữ data + busobs để query lại số đo cũ.

**(3) Test 1 tuần** (vài giờ/ngày): **~$15-25 tổng**.

**(4) Spot/Preemptible VM** (giảm 60-91%): thêm `--provisioning-model=SPOT` khi tạo. Nhưng có thể bị reclaim → KHÔNG dùng cho data VM.

### Auto-stop schedule

```powershell
gcloud compute resource-policies create instance-schedule hcr-night-stop `
  --region=asia-southeast1 `
  --vm-stop-schedule="0 23 * * *" `
  --vm-start-schedule="0 8 * * *" `
  --timezone=Asia/Ho_Chi_Minh

# Apply lên app + loadgen (giữ data + busobs nếu cần query log)
gcloud compute instances add-resource-policies hcr-app `
  --resource-policies=hcr-night-stop --zone=asia-southeast1-a
gcloud compute instances add-resource-policies hcr-loadgen `
  --resource-policies=hcr-night-stop --zone=asia-southeast1-a
```

---

## 15. Cleanup (xoá toàn bộ)

```powershell
gcloud compute instances delete hcr-app hcr-data hcr-busobs hcr-loadgen --quiet
gcloud compute firewall-rules delete hcr-allow-iap-ssh hcr-allow-internal --quiet
gcloud compute networks subnets delete hcr-subnet --region=asia-southeast1 --quiet
gcloud compute networks delete hcr-vpc --quiet

# Nếu xoá hoàn toàn:
gcloud projects delete hcr-thesis-demo-462813
```

---

## 16. Common pitfalls

### Network / SSH (Step 4-5)

| Triệu chứng | Nguyên nhân | Fix |
|---|---|---|
| `apt update` trên VM báo `Network is unreachable`, `Connection timed out` | VM không có public IP và không có Cloud NAT (`--no-address` ở Step 4) | Step 4.6 — Option A (add-access-config) hoặc Option B (Cloud NAT) |
| `gcloud compute ssh ... --tunnel-through-iap` báo `option "-legacy-stdio-prompts" not available` | Bundled `putty.exe` trên Windows version mới strict, không nhận flag plink-only | Dùng OpenSSH + ProxyCommand thay vì gcloud SSH wrapper (Section 5.1-5.4) |
| `gcloud compute ssh` báo `Your platform does not support SSH` | Đã rename/xóa putty.exe nhưng gcloud không fallback OpenSSH trên Windows | Restore putty.exe (rename `.bak` về), rồi đi đường ProxyCommand |
| PuTTY hỏi `Store key in cache?` → bấm Enter → connection cancelled | Plink/PuTTY: Return = cancel (không phải accept) | Gõ `y` (plink) hoặc `yes` (OpenSSH), tuyệt đối KHÔNG Enter trống |
| Sau SSH exit, traceback `[Errno 5] stdin ReadFile failed` ở cuối | Cosmetic, gcloud IAP tunnel tear-down trên Windows | Bỏ qua hoàn toàn — SSH session đã thoát sạch trước đó |
| `gcloud compute ssh` hang ~60s rồi fail | Firewall rule IAP chưa apply | Verify rule `hcr-allow-iap-ssh` exist + source `35.235.240.0/20` |
| `ssh hcr-app` báo `Bad owner or permissions on .ssh/config` | Windows OpenSSH strict về permission file config | `icacls "C:\Users\<USER>\.ssh\config" /inheritance:r /grant:r "%USERNAME%:F"` |
| `CreateProcessW failed error:2` khi ssh với ProxyCommand | OpenSSH posix_spawnp không resolve `gcloud` (vì là `.cmd`) | Dùng FULL PATH `D:\GoogleCloud\google-cloud-sdk\bin\gcloud.cmd` trong ProxyCommand |

### Deploy / runtime (Step 7+)

| Triệu chứng | Nguyên nhân | Fix |
|---|---|---|
| App start nhưng `Connection refused 10.20.0.3:5432` | Postgres chưa bind 0.0.0.0 | Sửa compose: `0.0.0.0:5432:5432` |
| Kafka producer fail `localhost:9092` từ app | `KAFKA_ADVERTISED_LISTENERS` còn `localhost` | Set `KAFKA_HOST=10.20.0.4` khi `docker compose up`, recreate kafka container |
| `OptimisticLockingFailureException` storm ở P2 | Contention cực cao | Bình thường — backoff retry sẽ xử lý. Nếu muốn smooth: tăng `baseDelayMs` |
| HTTP 202 count > capacity | Compensate-retry cycle, KHÔNG phải oversell | Verify oversell qua DB state (mục 11.3) |
| Số đo throughput chập chờn | Prometheus scrape 5s | Confirm đã đổi `scrape_interval: 30s` (mục 0.1) |
| Grafana không có data | Prometheus target down | Vào http://localhost:9090/targets — phải xanh cả 3 |
| ms-inventory không seed Redis | DB chưa tạo inventory_db | Verify `\l` trong psql; nếu thiếu — chạy lại `01-create-databases.sql` |
| Cost cao bất ngờ | Quên stop VM cuối tuần | Setup auto-stop schedule (mục 14) |

---

## 17. Mapping với report Chương 4 (L-A)

Theo memory thesis: **Chapter 4 scope L-A** = mở rộng hcr-product chạy được cả P1/P2/P3 + đo thực.

Doc này cover:
- **Setup** (mục 0–6) → §4.x.1 "Môi trường thí nghiệm"
- **Deploy + switch prototype** (mục 7–12) → §4.x.2 "Quy trình triển khai"
- **k6 + verify** (mục 11) → §4.x.3 "Phương pháp đo"
- **Cost/cleanup** (mục 14–15) → phụ lục

Số đo collect từ Grafana / k6 output → bảng kết quả chương 4.

---

## Phụ lục A — 5 VM topology (max accuracy)

Khi nào dùng: thesis defense cần con số P3 chính xác đến 3 chữ số sig, hoặc phát hiện Redis bottleneck.

```
hcr-app    (e2-standard-4)   10.20.0.2
hcr-db     (e2-standard-2)   10.20.0.3   ← chỉ Postgres
hcr-redis  (e2-standard-2)   10.20.0.6   ← chỉ Redis ⭐ tách ra
hcr-busobs (e2-standard-2)   10.20.0.4
hcr-loadgen(e2-standard-4)   10.20.0.5
```

Thay đổi so với 4-VM:
1. Tạo VM mới `hcr-redis`:
   ```powershell
   gcloud compute instances create hcr-redis `
     --machine-type=e2-standard-2 `
     --image-family=ubuntu-2404-lts-amd64 `
     --image-project=ubuntu-os-cloud `
     --boot-disk-size=20GB --boot-disk-type=pd-balanced `
     --network=hcr-vpc --subnet=hcr-subnet `
     --no-address --tags=hcr-redis
   ```
2. Compose data VM chỉ còn Postgres (bỏ redis).
3. Compose riêng cho Redis VM (1 service).
4. Update `/etc/hcr/env` trên hcr-app:
   ```
   REDIS_HOST=10.20.0.6      # IP mới của hcr-redis
   DB_HOST=10.20.0.3         # giữ nguyên
   ```

Cost: +~$50/tháng (24/7) hoặc +~$2-5/tuần demo.

Lợi ích: Khi P3 chạy peak Redis 10k req/s, Redis VM không bị Postgres async sync xen vào → throughput Redis tight với ground truth.

---

## Liên kết

- [`deploy-aws-ec2.md`](deploy-aws-ec2.md) — phiên bản AWS (cấu trúc tương đương)
- [`prototype-flows.md`](prototype-flows.md) — flow chi tiết của 3 prototype
- [`tuning-journal.md`](tuning-journal.md) — nhật ký tuning performance
- [`../README.md`](../README.md) — overview product
- GCP pricing calculator: https://cloud.google.com/products/calculator
- gcloud CLI reference: https://cloud.google.com/sdk/gcloud/reference
