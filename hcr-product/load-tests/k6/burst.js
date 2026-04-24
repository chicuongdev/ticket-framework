// Burst / Spike test — đánh vào concert-003 (500 vé) với hàng nghìn VU đồng thời.
//
// === LƯU Ý QUAN TRỌNG VỀ INVARIANT ZERO-OVERSELL ===
// HTTP 202 count CÓ THỂ > 500 mà vẫn KHÔNG oversell. Lý do:
//   - Mock payment có ~10% fail rate
//   - Khi payment fail → orchestrator compensate → release Redis (INCRBY)
//   - Slot vừa release được request kế tiếp DECRBY → thêm 1 HTTP 202
//   - Tổng 202 = 500 + N (N = số reserves đã rotate qua compensate cycle)
//
// Invariant ĐÚNG để verify zero-oversell (chạy SAU test):
//   1. CONFIRMED + RESERVED <= 500 trong DB
//      SELECT status, COUNT(*) FROM ticket_orders WHERE resource_id='concert-003' GROUP BY status;
//   2. CONFIRMED + Redis_available = total
//      docker exec hcr-redis redis-cli GET hcr:inventory:concert-003
//
// Mục tiêu của script này:
//   - p95 latency < 500ms khi 1000 VU concurrent
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
import { placeOrder, tagResponse, RESOURCES } from './lib/common.js';

// Coi 202 và 422 là "expected status" — http_req_failed không tính 422 là failed.
// Real errors (5xx, connection failed) vẫn được flag.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 422, 409));

export const options = {
    scenarios: {
        burst: {
            executor: 'ramping-arrival-rate',
            startRate: 50,
            timeUnit: '1s',
            preAllocatedVUs: 500,
            maxVUs: 1500,
            stages: [
                { duration: '10s', target: 200 },   // warm-up
                { duration: '20s', target: 1000 },  // peak — vé sẽ hết trong window này
                { duration: '10s', target: 50 },    // cooldown
            ],
        },
    },
    thresholds: {
        'http_req_duration{status:202}': ['p(95)<500', 'p(99)<1000'],
        'http_req_failed': ['rate<0.01'],   // 5xx + connection errors only
        'errors':          ['rate<0.01'],   // custom: non-202/422/409 responses
    },
};

const acceptedCounter = new Counter('orders_accepted');
const rejectedCounter = new Counter('orders_rejected');
const errorRate = new Rate('errors');

export default function () {
    const res = placeOrder(RESOURCES.SMALL, 1, 'burst');
    tagResponse(res);

    if (res.status === 202) acceptedCounter.add(1);
    else if (res.status === 422 || res.status === 409) rejectedCounter.add(1);
    else errorRate.add(1);

    sleep(0.1);
}

export function handleSummary(data) {
    const accepted = data.metrics.orders_accepted ? data.metrics.orders_accepted.values.count : 0;
    const rejected = data.metrics.orders_rejected ? data.metrics.orders_rejected.values.count : 0;
    const errors = data.metrics.errors ? Math.round(data.metrics.errors.values.rate * (accepted + rejected)) : 0;
    const total = accepted + rejected + errors;
    return {
        stdout: `\n=== BURST TEST SUMMARY ===
Total requests:    ${total}
Accepted (202):    ${accepted}    (có thể > 500 do compensate-retry cycle, không phải oversell)
Rejected (422):    ${rejected}
Real errors:       ${errors}    (5xx / connection failed)

→ Verify zero-oversell SAU test:
  docker exec hcr-postgres psql -U hcr -d order_db -c \\
    "SELECT status, COUNT(*) FROM ticket_orders WHERE resource_id='concert-003' GROUP BY status;"
  → CONFIRMED + RESERVED phải <= 500
==========================
`,
    };
}
