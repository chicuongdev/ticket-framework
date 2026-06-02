// Burst / Spike test — đánh vào concert-003 (500 vé) với hàng chục nghìn VU đồng thời (peak 10000 RPS).
//
// === LƯU Ý QUAN TRỌNG VỀ INVARIANT ZERO-OVERSELL ===
// HTTP 201/202 count CÓ THỂ > 500 mà vẫn KHÔNG oversell. Lý do:
//   - Mock payment có ~10% fail rate
//   - Khi payment fail → orchestrator compensate → release inventory
//   - Slot vừa release được request kế tiếp reserve lại → thêm 1 HTTP nhận đơn
//   - Tổng nhận đơn = 500 + N (N = số reserves đã rotate qua compensate cycle)
//
// Invariant ĐÚNG để verify zero-oversell (chạy SAU test):
//   1. CONFIRMED + RESERVED <= 500 trong DB
//      SELECT status, COUNT(*) FROM ticket_orders WHERE resource_id='concert-003' GROUP BY status;
//   2. P3:    CONFIRMED + Redis_available = total
//             docker exec hcr-redis redis-cli GET hcr:inventory:concert-003
//      P1/P2: CONFIRMED + concert_tickets.available_quantity = total
//
// Mục tiêu của script này:
//   - p95 latency < 500ms khi peak 10000 req/s
//   - Tỉ lệ lỗi hạ tầng (5xx, connection failed) < 1%
//   - Throughput đo được dưới burst
//
// 422 (out-of-stock) KHÔNG phải lỗi → không tính vào http_req_failed.
//
// Cách chạy:
//   k6 run hcr-product/load-tests/k6/burst.js
//   k6 run --env BASE_URL=http://localhost:8081 hcr-product/load-tests/k6/burst.js

import http from 'k6/http';
import { Counter, Rate } from 'k6/metrics';
import { sleep } from 'k6';
import { placeOrder, tagResponse, isAccepted, RESOURCES } from './lib/common.js';

// Coi 2xx và 422/409 là "expected status" — http_req_failed không tính 422 là failed.
// Real errors (5xx, connection failed) vẫn được flag.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 422, 409));

// === Cấu hình qua env (defaults = giá trị đã chạy trong burst_10x_comparison_20260528.md) ===
const START_RATE   = parseInt(__ENV.START_RATE   || '500');
const WARM_RATE    = parseInt(__ENV.WARM_RATE    || '2000');
const PEAK_RATE    = parseInt(__ENV.PEAK_RATE    || '10000');
const COOL_RATE    = parseInt(__ENV.COOL_RATE    || '500');
const WARMUP_SEC   = parseInt(__ENV.WARMUP_SEC   || '10');
const PEAK_SEC     = parseInt(__ENV.PEAK_SEC     || '20');
const COOLDOWN_SEC = parseInt(__ENV.COOLDOWN_SEC || '10');
const PRE_VUS      = parseInt(__ENV.PRE_VUS      || '5000');
const MAX_VUS      = parseInt(__ENV.MAX_VUS      || '15000');
const RESOURCE     = __ENV.RESOURCE              || RESOURCES.SMALL;

export const options = {
    scenarios: {
        burst: {
            executor: 'ramping-arrival-rate',
            startRate: START_RATE,
            timeUnit: '1s',
            preAllocatedVUs: PRE_VUS,
            maxVUs: MAX_VUS,
            stages: [
                { duration: `${WARMUP_SEC}s`,   target: WARM_RATE },  // warm-up
                { duration: `${PEAK_SEC}s`,     target: PEAK_RATE },  // peak
                { duration: `${COOLDOWN_SEC}s`, target: COOL_RATE },  // cooldown
            ],
        },
    },
    thresholds: {
        // P3 async nhận đơn = 202, P1/P2 sync = 201 — khai báo cả 2; mode nào không
        // dùng tới thì sub-metric rỗng, threshold pass mặc nhiên (không có sample = không vi phạm).
        'http_req_duration{status:202}': ['p(95)<500', 'p(99)<1000'],
        'http_req_duration{status:201}': ['p(95)<500', 'p(99)<1000'],
        'http_req_failed': ['rate<0.01'],   // 5xx + connection errors only
        'errors':          ['rate<0.01'],   // custom: non-accepted/422/409 responses
    },
    // k6 mặc định không tính p(50)/p(99) — phải khai báo tường minh để handleSummary đọc được.
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(95)', 'p(99)'],
};

const acceptedCounter = new Counter('orders_accepted');
const rejectedCounter = new Counter('orders_rejected');
const errorRate = new Rate('errors');

export default function () {
    const res = placeOrder(RESOURCE, 1, 'burst');
    tagResponse(res);

    if (isAccepted(res)) acceptedCounter.add(1);
    else if (res.status === 422 || res.status === 409) rejectedCounter.add(1);
    else errorRate.add(1);

    sleep(0.1);
}

export function handleSummary(data) {
    const accepted = data.metrics.orders_accepted ? data.metrics.orders_accepted.values.count : 0;
    const rejected = data.metrics.orders_rejected ? data.metrics.orders_rejected.values.count : 0;
    // Tổng iter = mọi VU đã chạy default function bao nhiêu lần (chính xác hơn rate × N)
    const total = data.metrics.iterations ? data.metrics.iterations.values.count : 0;
    const errors = total - accepted - rejected;
    const errPct = total > 0 ? ((errors / total) * 100).toFixed(1) : '0.0';

    // Latency tổng (tất cả request, không phân biệt status)
    const dur = data.metrics.http_req_duration?.values ?? {};
    // Latency riêng theo status (P1/P2 sync = 201, P3 async = 202)
    const dur201 = data.metrics['http_req_duration{status:201}']?.values ?? {};
    const dur202 = data.metrics['http_req_duration{status:202}']?.values ?? {};
    const fmt = (v) => v === undefined ? 'N/A' : `${v.toFixed(1)}ms`;

    return {
        stdout: `\n=== BURST TEST SUMMARY ===
Total requests:      ${total}    (= iterations)
Accepted (201/202):  ${accepted}    (có thể > 500 do compensate-retry cycle, không phải oversell)
Rejected (422):      ${rejected}    (out-of-stock, expected)
Real errors:         ${errors}    (${errPct}% — 5xx / connection failed / timeout)

Latency (toàn bộ requests):
  avg: ${fmt(dur.avg)}    med: ${fmt(dur.med)}    p95: ${fmt(dur['p(95)'])}    p99: ${fmt(dur['p(99)'])}    max: ${fmt(dur.max)}

Latency 201 (sync P1/P2): p95: ${fmt(dur201['p(95)'])}    p99: ${fmt(dur201['p(99)'])}
Latency 202 (async P3):   p95: ${fmt(dur202['p(95)'])}    p99: ${fmt(dur202['p(99)'])}

→ Verify zero-oversell SAU test:
  docker exec hcr-postgres psql -U hcr -d order_p1_db -c \\
    "SELECT status, COUNT(*) FROM ticket_orders WHERE resource_id='concert-003' GROUP BY status;"
  → CONFIRMED + RESERVED phải <= 500
==========================
`,
    };
}
