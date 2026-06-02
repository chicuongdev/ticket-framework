# HCR Test Console

UI demo cho việc test 3 prototype (P1/P2/P3) trên GCP. Wrap các thao tác hiện
đang gõ tay qua SSH thành một trang web duy nhất.

```
┌─ Header status bar (poll mỗi 30s) ───────────────────────────────────┐
│ Prototype: P1 · ms-order: UP · ms-inventory: UP · ms-payment: UP      │
└──────────────────────────────────────────────────────────────────────┘
┌─ Test config ──────────────────┐ ┌─ DB Verify (invariant) ──────────┐
│ Scenario  [burst ▼]            │ │ ZERO-OVERSELL OK  FULLY RECONCILED│
│ Resource  [concert-003 ▼]      │ │ Prototype: P3 · Resource: 003     │
│ Start RPS [500]  Warm [2000]   │ │ CONFIRMED  500                    │
│ Peak [10000]    Cool [500]     │ │ RESERVED   0                      │
│ Warmup[10] Peak[20] Cool[10]   │ │ redis_available  0                │
│ Pre VUs[5000] Max VUs[15000]   │ │ = total  500 / expected 500       │
│ Target [P1 ▼]                  │ │                                   │
│ [Switch] [Reset] [▶ Run] [■]   │ │ <raw query output>                │
│         [Verify DB]            │ │                                   │
└────────────────────────────────┘ └───────────────────────────────────┘
┌─ k6 output (live stream, refresh 1s khi đang chạy) ─────────────────┐
│ scenarios: (100.00%) 1 scenario, 15000 max VUs, 50s max duration... │
│ running (0m20.0s), 06734/15000 VUs, 84223 complete and 0 interrup…  │
│ ...                                                                  │
└──────────────────────────────────────────────────────────────────────┘
```

## Kiến trúc

- **Backend** (`app.py`) — FastAPI, chạy trên VM `hcr-loadgen`:
  - `GET  /api/status` — health 3 service + active prototype (UI poll mỗi 30s)
  - `POST /api/switch` — `target: P1|P2|P3` → symlink `/etc/hcr/active.env` + restart
  - `POST /api/reset` — truncate cả 3 `order_pX_db.ticket_orders`, reset `concert_tickets`, FLUSHALL Redis, restart `ms-inventory` để re-seed
  - `POST /api/test/run` — exec `k6 run` với env override; trả `run_id`
  - `GET  /api/test/status/{run_id}` — output + exit code
  - `POST /api/test/stop/{run_id}` — terminate k6 process
  - `GET  /api/verify?resource=concert-003` — query DB + tính invariant `CONFIRMED + available = total`
- **Frontend** (`index.html`) — single-file vanilla JS + Fetch API.
- **k6 scripts** (`../k6/*.js`) — đã được sửa để đọc params từ `__ENV.X` với defaults giữ nguyên giá trị production-grade.

## Bootstrap (chạy 1 lần)

### Bước 1 — trên VM `hcr-app` (setup EnvFile + sudoers)

```bash
# Copy script lên hcr-app
gcloud compute scp hcr-product/load-tests/console/setup-hcr-app.sh \
    hcr-app:~/setup-hcr-app.sh --zone=asia-southeast1-a --tunnel-through-iap

# SSH vào và chạy
gcloud compute ssh hcr-app --zone=asia-southeast1-a --tunnel-through-iap
sudo bash ~/setup-hcr-app.sh

# Script này sẽ:
#   - Tạo /etc/hcr/{p1,p2,p3}.env
#   - Symlink active.env → p1.env (default)
#   - Sửa hcr-order.service + hcr-inventory.service để dùng EnvironmentFile
#   - Thêm sudoers entry cho user 'console' để được restart + ln -sfn không cần password
#   - daemon-reload
```

Verify:

```bash
sudo systemctl show hcr-order -p EnvironmentFiles
# → EnvironmentFiles=/etc/hcr/active.env (ignore_errors=no)
readlink /etc/hcr/active.env
# → /etc/hcr/p1.env
```

### Bước 2 — trên VM `hcr-loadgen` (cài + chạy console)

```bash
# Cài Python deps
gcloud compute ssh hcr-loadgen --zone=asia-southeast1-a --tunnel-through-iap

cd ~/io.hrc/hcr-product/load-tests/console
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

# Đảm bảo loadgen có SSH key tới hcr-app + hcr-data
# (gcloud thường tự setup; nếu không có, ssh-keygen + ssh-copy-id)

# Chạy console
HCR_APP_HOST=10.20.0.3 HCR_DATA_HOST=10.20.0.2 \
SSH_USER=Admin \
python app.py
# → server lắng nghe 0.0.0.0:8090
```

### Bước 3 — IAP tunnel từ máy local Windows về loadgen

```powershell
gcloud compute start-iap-tunnel hcr-loadgen 8090 `
    --local-host-port=localhost:8090 `
    --zone=asia-southeast1-a
```

Mở trình duyệt → `http://localhost:8090/`.

## Env vars (override khi cần)

| Var | Default | Mục đích |
|--|--|--|
| `HCR_APP_HOST` | `10.20.0.3` | IP nội bộ VM hcr-app (chạy 3 microservice) |
| `HCR_DATA_HOST` | `10.20.0.2` | IP nội bộ VM hcr-data (chạy Docker: PG, Redis, Kafka) |
| `SSH_USER` | `Admin` | User SSH (đã verify: VM dùng `Admin` không phải `ubuntu`) |
| `K6_SCRIPTS_DIR` | `../k6` | Path tới folder chứa burst.js / sustained.js / oversell-check.js |
| `K6_OUTPUT_DIR` | `./outputs` | Nơi lưu file output mỗi lần chạy (timestamped) |
| `POSTGRES_USER` | `hcr` | User psql trong docker exec |
| `PORT` | `8090` | Port FastAPI bind |

## Luồng demo điển hình

```
1. Mở UI → check status bar: 3 service UP, "stable (3 checks)" sau ~90s
2. Chọn Target = P1 → [Switch Prototype]  (~30-60s)
3. [Reset All Data]  (~15-20s — truncate cả 3 DB + FLUSHALL + restart ms-inventory)
4. Chọn Scenario=burst, Resource=concert-003 → tinh chỉnh params nếu cần
5. [▶ Run Test]  → output stream realtime, kết thúc sau ~40s
6. [Verify DB]   → invariant box hiển thị xanh nếu ZERO-OVERSELL OK
7. Switch sang P2, lặp lại bước 3-6
8. Switch sang P3, lặp lại bước 3-6
```

## Lưu ý

- **Single-user UI** — state lưu in-memory (`RUNS` dict). Restart `app.py` mất state.
- **Output files** lưu ở `outputs/{timestamp}-{scenario}-{run_id}.txt` để review sau.
- **Concurrency lock** trên `/api/switch` — 2 click liên tiếp → request 2 trả 409.
- **Verify auto-detect prototype** qua actuator `/env/hcr.product.active-prototype`, sau đó query đúng DB tương ứng (`order_p1_db`/`order_p2_db`/`order_p3_db`).
- **Reset reset CẢ 3 prototype** (truncate `order_p1_db`, `order_p2_db`, `order_p3_db`, `inventory_p3_db`, `payment_db`) — đảm bảo switch sau đó không gặp data cũ.
