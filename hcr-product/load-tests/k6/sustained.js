// Sustained / Soak test — 2000 VU constant trong 5 phút, đánh vào concert-001 (10000 vé).
//
// Mục tiêu đo:
//   - Throughput ổn định (req/s)
//   - p50 / p95 / p99 latency theo thời gian
//   - Memory leak / connection pool exhaustion
//   - Reconciliation chạy ngầm vẫn không ảnh hưởng latency
//
// Cách chạy:
//   k6 run hcr-product/load-tests/k6/sustained.js
//
// Quan sát Grafana (http://localhost:3000) trong khi chạy:
//   - Prometheus dashboard: HTTP latency histogram, JVM heap, Kafka consumer lag
//   - Zipkin (http://localhost:9411): trace 1 order qua ms-order → ms-payment → back

import { Counter } from 'k6/metrics';
import { placeOrder, tagResponse, isAccepted, RESOURCES } from './lib/common.js';

export const options = {
    scenarios: {
        soak: {
            executor: 'constant-vus',
            vus: 2000,
            duration: '5m',
        },
    },
    thresholds: {
        'http_req_duration': ['p(50)<200', 'p(95)<800', 'p(99)<2000'],
        'http_req_failed': ['rate<0.005'],
    },
    // k6 mặc định không tính p(50)/p(99) — phải khai báo tường minh.
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(95)', 'p(99)'],
};

const accepted = new Counter('orders_accepted');
const rejected = new Counter('orders_rejected');
const failed = new Counter('orders_failed');

export default function () {
    const res = placeOrder(RESOURCES.LARGE, 1, 'soak');
    tagResponse(res);
    if (isAccepted(res)) accepted.add(1);
    else if (res.status === 422) rejected.add(1);
    else failed.add(1);
}

export function handleSummary(data) {
    const a = data.metrics.orders_accepted?.values.count ?? 0;
    const r = data.metrics.orders_rejected?.values.count ?? 0;
    const f = data.metrics.orders_failed?.values.count ?? 0;
    const dur = data.metrics.http_req_duration?.values ?? {};
    return {
        stdout: `\n=== SUSTAINED TEST SUMMARY ===
Accepted (201/202):  ${a}
Rejected (422):      ${r}
Failed   (5xx):      ${f}
p50:  ${(dur['p(50)'] ?? 0).toFixed(1)}ms
p95:  ${(dur['p(95)'] ?? 0).toFixed(1)}ms
p99:  ${(dur['p(99)'] ?? 0).toFixed(1)}ms
==============================
`,
    };
}
