"""HCR Test Console — FastAPI backend.

Chạy trên VM hcr-loadgen (nơi k6 đã cài sẵn). Backend exec:
  - k6 binary local (cùng VM)
  - SSH sang hcr-app gọi /usr/local/bin/hcr-switch-prototype.sh (sed env + unit + restart 3 service)
  - SSH sang hcr-data cho docker exec psql/redis-cli

Switch / verify pattern theo hcr-product/docs/runbook-gcp.md (mục 3.2, 9.6):
  - Source-of-truth: /etc/hcr/env (ACTIVE_PROTOTYPE=p1|p2|p3)
  - DB_NAME của ms-order: trong unit file /etc/systemd/system/hcr-order.service
  - Verify prototype: /actuator/hcr → inventory.configuredStrategy

Cấu hình qua env vars — xem README.md.
"""

import asyncio
import json
import os
import re
import shlex
import subprocess
import time
import uuid
from pathlib import Path
from typing import Optional

import httpx
from fastapi import FastAPI, HTTPException
from fastapi.responses import HTMLResponse, PlainTextResponse
from pydantic import BaseModel

# ============================================================================
# Config
# ============================================================================
HCR_APP_HOST  = os.environ.get("HCR_APP_HOST",  "10.20.0.2")
HCR_DATA_HOST = os.environ.get("HCR_DATA_HOST", "10.20.0.3")
SSH_USER      = os.environ.get("SSH_USER",      "Admin")
SSH_OPTS      = ["-o", "StrictHostKeyChecking=no", "-o", "ConnectTimeout=5",
                 "-o", "BatchMode=yes"]

MS_ORDER_URL     = f"http://{HCR_APP_HOST}:8081"
MS_INVENTORY_URL = f"http://{HCR_APP_HOST}:8082"
MS_PAYMENT_URL   = f"http://{HCR_APP_HOST}:8083"

# Resolve paths relative to this file
CONSOLE_DIR    = Path(__file__).parent.resolve()
K6_SCRIPTS_DIR = Path(os.environ.get("K6_SCRIPTS_DIR", CONSOLE_DIR.parent / "k6"))
K6_OUTPUT_DIR  = Path(os.environ.get("K6_OUTPUT_DIR",  CONSOLE_DIR / "outputs"))
K6_OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

POSTGRES_USER  = os.environ.get("POSTGRES_USER", "hcr")

# ============================================================================
# App
# ============================================================================
app = FastAPI(title="HCR Test Console", docs_url="/api/docs")

# In-memory state — UI là single-user
RUNS: dict[str, dict] = {}

# Concurrency guard cho switch (tránh 2 click liên tiếp)
_switch_lock = asyncio.Lock()


# ============================================================================
# Helpers
# ============================================================================
def ssh(host: str, cmd: str, timeout: int = 30) -> str:
    """Run a command via ssh. Returns stdout. Raises on non-zero exit."""
    full = ["ssh", *SSH_OPTS, f"{SSH_USER}@{host}", cmd]
    r = subprocess.run(full, capture_output=True, text=True, timeout=timeout)
    if r.returncode != 0:
        raise RuntimeError(
            f"ssh {host} failed (rc={r.returncode}): {r.stderr.strip() or r.stdout.strip()}"
        )
    return r.stdout.strip()


async def fetch_health(client: httpx.AsyncClient, name: str, url: str) -> dict:
    try:
        r = await client.get(f"{url}/actuator/health", timeout=3.0)
        body = r.json()
        return {
            "name": name,
            "url": url,
            "status": body.get("status", "UNKNOWN"),
            "components": list((body.get("components") or {}).keys()),
            "http": r.status_code,
        }
    except Exception as e:
        return {"name": name, "url": url, "status": "DOWN", "error": str(e)}


_STRATEGY_TO_PROTO = {
    "pessimistic-lock": "P1",
    "optimistic-lock":  "P2",
    "redis-atomic":     "P3",
}


async def fetch_active_prototype(client: httpx.AsyncClient) -> Optional[str]:
    """/actuator/env không được expose. Dùng /actuator/hcr (đã expose) → map
    inventory.configuredStrategy → P1/P2/P3."""
    try:
        r = await client.get(f"{MS_ORDER_URL}/actuator/hcr", timeout=3.0)
        if r.status_code != 200:
            return None
        body = r.json()
        strategy = (body.get("inventory") or {}).get("configuredStrategy")
        return _STRATEGY_TO_PROTO.get(strategy)
    except Exception:
        return None


# ============================================================================
# /api/status — UI poll mỗi 30s
# ============================================================================
@app.get("/api/status")
async def status():
    async with httpx.AsyncClient() as client:
        services = await asyncio.gather(
            fetch_health(client, "ms-order",     MS_ORDER_URL),
            fetch_health(client, "ms-inventory", MS_INVENTORY_URL),
            fetch_health(client, "ms-payment",   MS_PAYMENT_URL),
        )
        prototype = await fetch_active_prototype(client)

    all_up = all(s.get("status") == "UP" for s in services)
    return {
        "ts": int(time.time()),
        "active_prototype": prototype,
        "all_up": all_up,
        "services": list(services),
        "hosts": {"app": HCR_APP_HOST, "data": HCR_DATA_HOST},
    }


# ============================================================================
# /api/switch — gọi helper hcr-switch-prototype.sh trên hcr-app
# ============================================================================
class SwitchReq(BaseModel):
    target: str  # P1 / P2 / P3


SWITCH_HELPER = "/usr/local/bin/hcr-switch-prototype.sh"


@app.post("/api/switch")
async def switch_prototype(req: SwitchReq):
    t = req.target.strip().lower()
    if t not in ("p1", "p2", "p3"):
        raise HTTPException(400, "target must be P1, P2, or P3")

    if _switch_lock.locked():
        raise HTTPException(409, "another switch is in progress")

    async with _switch_lock:
        t0 = time.time()
        target_upper = t.upper()
        log: list[str] = []

        # 1. Gọi helper — sed env + sed unit + daemon-reload + restart 3 service.
        #    Timeout cao (~60s) vì helper sleep 15+3+5 = ~23s + thời gian boot JVM.
        try:
            out = ssh(HCR_APP_HOST, f"sudo {SWITCH_HELPER} {t}", timeout=120)
            log.append(f"helper output: {out.strip()[-300:]}")
        except Exception as e:
            raise HTTPException(500, f"switch helper failed: {e}")

        # 2. Poll /actuator/hcr cho đến khi strategy khớp — tối đa thêm 90s
        deadline = time.time() + 90
        async with httpx.AsyncClient() as client:
            while time.time() < deadline:
                actual = await fetch_active_prototype(client)
                if actual == target_upper:
                    log.append(f"verified inventory.configuredStrategy → {actual}")
                    return {
                        "ok": True,
                        "prototype": target_upper,
                        "took_s": round(time.time() - t0, 1),
                        "log": log,
                    }
                await asyncio.sleep(2)

        raise HTTPException(
            504,
            f"timeout 90s waiting for /actuator/hcr to report {target_upper}. log={log}",
        )


# ============================================================================
# /api/reset — reset DỮ LIỆU của CẢ 3 prototype + payment + Redis
# ============================================================================
RESET_COMMANDS = [
    # Truncate ticket_orders trong cả 3 order DBs
    ("truncate order_p1_db.ticket_orders",
     "docker exec hcr-postgres psql -U hcr -d order_p1_db -c 'TRUNCATE ticket_orders;'"),
    ("truncate order_p2_db.ticket_orders",
     "docker exec hcr-postgres psql -U hcr -d order_p2_db -c 'TRUNCATE ticket_orders;'"),
    ("truncate order_p3_db.ticket_orders",
     "docker exec hcr-postgres psql -U hcr -d order_p3_db -c 'TRUNCATE ticket_orders;'"),
    # Reset concert_tickets cho P1/P2 (DB là source of truth)
    ("reset order_p1_db.concert_tickets",
     "docker exec hcr-postgres psql -U hcr -d order_p1_db -c "
     "\"UPDATE concert_tickets SET available_quantity = total_quantity, version = version + 1;\""),
    ("reset order_p2_db.concert_tickets",
     "docker exec hcr-postgres psql -U hcr -d order_p2_db -c "
     "\"UPDATE concert_tickets SET available_quantity = total_quantity, version = version + 1;\""),
    # Reset inventory_p3_db (sync target)
    ("reset inventory_p3_db.concert_tickets",
     "docker exec hcr-postgres psql -U hcr -d inventory_p3_db -c "
     "\"UPDATE concert_tickets SET available_quantity = total_quantity, version = version + 1;\""),
    ("clear inventory_p3_db.hcr_processed_events",
     "docker exec hcr-postgres psql -U hcr -d inventory_p3_db -c 'DELETE FROM hcr_processed_events;'"),
    # Payment
    ("truncate payment_db.payment_attempts",
     "docker exec hcr-postgres psql -U hcr -d payment_db -c 'TRUNCATE payment_attempts;'"),
    # Redis (idempotency + P3 inventory)
    ("redis FLUSHALL",
     "docker exec hcr-redis redis-cli FLUSHALL"),
]


@app.post("/api/reset")
async def reset_all():
    """Reset toàn bộ data của cả 3 prototype, sau đó restart ms-inventory để re-seed Redis."""
    t0 = time.time()
    results = []

    # 1. Run all reset commands on hcr-data
    for label, cmd in RESET_COMMANDS:
        try:
            out = ssh(HCR_DATA_HOST, cmd, timeout=15)
            results.append({"step": label, "ok": True, "out": out[:200]})
        except Exception as e:
            results.append({"step": label, "ok": False, "error": str(e)})

    # 2. Restart ms-inventory để Seeder warm lại Redis cho prototype hiện tại
    try:
        ssh(HCR_APP_HOST, "sudo systemctl restart hcr-inventory", timeout=20)
        # Wait for ready
        deadline = time.time() + 60
        ready = False
        async with httpx.AsyncClient() as client:
            while time.time() < deadline:
                try:
                    h = await client.get(f"{MS_INVENTORY_URL}/actuator/health", timeout=2.0)
                    if h.status_code == 200 and h.json().get("status") == "UP":
                        ready = True
                        break
                except Exception:
                    pass
                await asyncio.sleep(2)
        results.append({
            "step": "restart hcr-inventory + wait ready",
            "ok": ready,
            "ready_within_60s": ready,
        })
    except Exception as e:
        results.append({"step": "restart hcr-inventory", "ok": False, "error": str(e)})

    all_ok = all(r.get("ok") for r in results)
    return {"ok": all_ok, "took_s": round(time.time() - t0, 1), "results": results}


# ============================================================================
# /api/test/run — exec k6 với env overrides
# ============================================================================
class TestReq(BaseModel):
    scenario: str  # burst | sustained | oversell-check
    resource: Optional[str] = None
    # burst params
    start_rate:   Optional[int] = None
    warm_rate:    Optional[int] = None
    peak_rate:    Optional[int] = None
    cool_rate:    Optional[int] = None
    warmup_sec:   Optional[int] = None
    peak_sec:     Optional[int] = None
    cooldown_sec: Optional[int] = None
    pre_vus:      Optional[int] = None
    max_vus:      Optional[int] = None
    # sustained / smoke
    vus:      Optional[int] = None
    duration: Optional[str] = None  # "5m" hoặc "30s"


_ALLOWED_SCENARIOS = {"burst", "sustained", "oversell-check"}


@app.post("/api/test/run")
async def run_test(req: TestReq):
    scenario = req.scenario
    if scenario not in _ALLOWED_SCENARIOS:
        raise HTTPException(400, f"scenario must be one of {sorted(_ALLOWED_SCENARIOS)}")

    script = K6_SCRIPTS_DIR / f"{scenario}.js"
    if not script.exists():
        raise HTTPException(500, f"k6 script not found: {script}")

    # Build env vars cho k6
    payload = req.model_dump(exclude_none=True)
    payload.pop("scenario", None)
    env_overrides = {k.upper(): str(v) for k, v in payload.items()}
    env_overrides["BASE_URL"] = MS_ORDER_URL

    run_id = uuid.uuid4().hex[:8]
    ts = time.strftime("%Y%m%d-%H%M%S")
    out_path = K6_OUTPUT_DIR / f"{ts}-{scenario}-{run_id}.txt"

    cmd = ["k6", "run", str(script)]
    proc_env = {**os.environ, **env_overrides}
    out_fh = open(out_path, "w", encoding="utf-8")
    out_fh.write(f"# k6 run {scenario} — env: {json.dumps(env_overrides)}\n")
    out_fh.flush()
    proc = subprocess.Popen(cmd, stdout=out_fh, stderr=subprocess.STDOUT, env=proc_env)

    RUNS[run_id] = {
        "proc": proc,
        "out_path": out_path,
        "out_fh": out_fh,
        "started_at": time.time(),
        "scenario": scenario,
        "env": env_overrides,
    }
    return {
        "run_id": run_id,
        "scenario": scenario,
        "out_file": str(out_path.name),
        "env": env_overrides,
    }


_SUMMARY_TITLE_RE = re.compile(r"=== ([A-Z][A-Z \-]+SUMMARY) ===")
_SUMMARY_INT_FIELDS = [
    ("total",    r"Total requests:\s+(\d+)"),
    ("accepted", r"Accepted \(201/202\):\s+(\d+)"),
    ("rejected", r"Rejected \(422\):\s+(\d+)"),
]


def parse_k6_summary(text: str) -> Optional[dict]:
    """Extract the `=== XXX SUMMARY ===` block from k6 stdout.

    All 3 scripts (burst/sustained/oversell-check) emit this fixed format
    via handleSummary in lib/common.js."""
    title_m = _SUMMARY_TITLE_RE.search(text)
    if not title_m:
        return None

    out: dict = {"title": title_m.group(1).strip()}

    for key, pat in _SUMMARY_INT_FIELDS:
        m = re.search(pat, text)
        if m:
            out[key] = int(m.group(1))

    m = re.search(r"Real errors:\s+(\d+)\s+\(([\d.]+)%", text)
    if m:
        out["errors"] = int(m.group(1))
        out["errors_pct"] = float(m.group(2))

    m = re.search(
        r"Latency \(toàn bộ requests\):\s*\n"
        r"\s+avg:\s*(\S+)\s+med:\s*(\S+)\s+p95:\s*(\S+)\s+p99:\s*(\S+)\s+max:\s*(\S+)",
        text,
    )
    if m:
        out["latency"] = {
            "avg": m.group(1), "med": m.group(2),
            "p95": m.group(3), "p99": m.group(4), "max": m.group(5),
        }

    m = re.search(r"Latency 201 \(sync P1/P2\):\s+p95:\s*(\S+)\s+p99:\s*(\S+)", text)
    if m:
        out["latency_201"] = {"p95": m.group(1), "p99": m.group(2)}

    m = re.search(r"Latency 202 \(async P3\):\s+p95:\s*(\S+)\s+p99:\s*(\S+)", text)
    if m:
        out["latency_202"] = {"p95": m.group(1), "p99": m.group(2)}

    return out


@app.get("/api/test/status/{run_id}")
def test_status(run_id: str):
    run = RUNS.get(run_id)
    if not run:
        raise HTTPException(404, "run not found")
    poll = run["proc"].poll()
    try:
        output = run["out_path"].read_text(encoding="utf-8", errors="replace")
    except FileNotFoundError:
        output = ""
    # Summary chỉ xuất hiện ở cuối output sau khi k6 chạy xong handleSummary.
    # Parse mỗi lần (output có thể đang ở giữa nhận stream — sẽ không match cho đến khi block đầy đủ).
    summary = parse_k6_summary(output)
    return {
        "run_id": run_id,
        "scenario": run["scenario"],
        "running": poll is None,
        "exit_code": poll,
        "elapsed_s": round(time.time() - run["started_at"], 1),
        "output": output,
        "summary": summary,
        "out_file": run["out_path"].name,
    }


@app.post("/api/test/stop/{run_id}")
def test_stop(run_id: str):
    run = RUNS.get(run_id)
    if not run:
        raise HTTPException(404, "run not found")
    proc = run["proc"]
    if proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
    return {"ok": True, "exit_code": proc.poll()}


# ============================================================================
# /api/verify — query DB sau test, compute invariant
# ============================================================================
@app.get("/api/verify")
async def verify(resource: str = "concert-003"):
    if not re.fullmatch(r"[A-Za-z0-9_\-]+", resource):
        raise HTTPException(400, "invalid resource name")

    # Detect current prototype
    async with httpx.AsyncClient() as client:
        proto = await fetch_active_prototype(client)
    if proto not in ("P1", "P2", "P3"):
        raise HTTPException(
            503, f"cannot determine active prototype (got: {proto})"
        )

    db_name = f"order_{proto.lower()}_db"
    PSQL = f"docker exec hcr-postgres psql -U {POSTGRES_USER}"

    queries: dict[str, str] = {
        "orders_by_status":
            f"{PSQL} -d {db_name} -t -A -F'|' -c "
            f"\"SELECT status, COUNT(*) FROM ticket_orders "
            f"WHERE resource_id='{resource}' GROUP BY status ORDER BY status;\"",
        "orders_failure_reasons":
            f"{PSQL} -d {db_name} -t -A -F'|' -c "
            f"\"SELECT COALESCE(failure_reason, '(none)') AS reason, COUNT(*) "
            f"FROM ticket_orders WHERE resource_id='{resource}' "
            f"GROUP BY failure_reason ORDER BY 2 DESC;\"",
        "payments_by_status":
            f"{PSQL} -d payment_db -t -A -F'|' -c "
            f"\"SELECT status, COUNT(*) FROM payment_attempts "
            f"GROUP BY status ORDER BY status;\"",
    }
    if proto == "P3":
        queries["redis_available"] = (
            f"docker exec hcr-redis redis-cli GET hcr:inventory:{resource}"
        )
        queries["inventory_p3_db_available"] = (
            f"{PSQL} -d inventory_p3_db -t -A -c "
            f"\"SELECT available_quantity FROM concert_tickets "
            f"WHERE resource_id='{resource}';\""
        )
    else:
        queries["concert_tickets_available"] = (
            f"{PSQL} -d {db_name} -t -A -c "
            f"\"SELECT available_quantity FROM concert_tickets "
            f"WHERE resource_id='{resource}';\""
        )

    # Run all queries
    raw: dict[str, str] = {}
    for k, cmd in queries.items():
        try:
            raw[k] = ssh(HCR_DATA_HOST, cmd, timeout=10)
        except Exception as e:
            raw[k] = f"ERROR: {e}"

    # Compute invariant: CONFIRMED + available = total_capacity
    confirmed = 0
    by_status: dict[str, int] = {}
    for line in raw.get("orders_by_status", "").splitlines():
        if "|" in line:
            s, n = line.split("|", 1)
            try:
                by_status[s.strip()] = int(n.strip())
            except ValueError:
                pass
    confirmed = by_status.get("CONFIRMED", 0)
    reserved  = by_status.get("RESERVED", 0)

    inv: dict = {"confirmed": confirmed, "reserved": reserved, "by_status": by_status}
    if proto == "P3":
        try:
            inv["redis_available"] = int(raw.get("redis_available", "").strip())
        except (ValueError, TypeError):
            inv["redis_available"] = None
        if inv["redis_available"] is not None:
            inv["confirmed_plus_available"] = confirmed + inv["redis_available"]
    else:
        try:
            inv["db_available"] = int(raw.get("concert_tickets_available", "").strip())
        except (ValueError, TypeError):
            inv["db_available"] = None
        if inv["db_available"] is not None:
            inv["confirmed_plus_available"] = confirmed + inv["db_available"]

    # Expected total cho concert-003 / -002 / -001
    expected = {"concert-001": 10000, "concert-002": 5000, "concert-003": 500}.get(resource)
    inv["expected_total"] = expected
    if expected and inv.get("confirmed_plus_available") is not None:
        inv["zero_oversell_ok"] = inv["confirmed_plus_available"] <= expected
        inv["fully_reconciled"]  = inv["confirmed_plus_available"] == expected

    return {
        "prototype": proto,
        "resource": resource,
        "ts": int(time.time()),
        "invariant": inv,
        "queries": raw,
    }


# ============================================================================
# / — serve UI
# ============================================================================
@app.get("/", response_class=HTMLResponse)
def index():
    html = (CONSOLE_DIR / "index.html").read_text(encoding="utf-8")
    return html


@app.get("/api/config", response_class=PlainTextResponse)
def show_config():
    return (
        f"HCR_APP_HOST  = {HCR_APP_HOST}\n"
        f"HCR_DATA_HOST = {HCR_DATA_HOST}\n"
        f"SSH_USER      = {SSH_USER}\n"
        f"K6_SCRIPTS    = {K6_SCRIPTS_DIR}\n"
        f"K6_OUTPUTS    = {K6_OUTPUT_DIR}\n"
    )


if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("PORT", "8090"))
    uvicorn.run(app, host="0.0.0.0", port=port)
