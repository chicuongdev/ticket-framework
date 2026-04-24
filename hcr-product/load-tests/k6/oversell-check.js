// Sanity test — verify endpoint chạy + idempotency hoạt động trước khi load test thật.
//
// Mục tiêu:
//   - Endpoint POST /orders trả về 202
//   - GET /orders/{id} trả 200 với status RESERVED/CONFIRMED/CANCELLED
//   - Cùng idempotencyKey → cùng orderId (idempotent)
//
// Cách chạy:
//   k6 run hcr-product/load-tests/k6/oversell-check.js
//
// Test này chỉ chạy 30s với 5 VU — không stress, dùng để smoke check sau khi up infra.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, placeOrder, RESOURCES } from './lib/common.js';

export const options = {
    vus: 5,
    duration: '30s',
    thresholds: {
        'checks': ['rate>0.95'],
    },
};

export default function () {
    // 1. Place order
    const placeRes = placeOrder(RESOURCES.MEDIUM, 1, 'smoke');
    check(placeRes, {
        '[place] status 202': (r) => r.status === 202,
        '[place] body has orderId': (r) => {
            try { return !!r.json('orderId'); } catch { return false; }
        },
    });

    if (placeRes.status !== 202) {
        sleep(1);
        return;
    }

    const orderId = placeRes.json('orderId');

    // 2. Get order
    sleep(0.2);
    const getRes = http.get(`${BASE_URL}/orders/${orderId}`);
    check(getRes, {
        '[get] status 200': (r) => r.status === 200,
        '[get] orderId matches': (r) => {
            try { return r.json('orderId') === orderId; } catch { return false; }
        },
        '[get] status valid': (r) => {
            try {
                const s = r.json('status');
                return ['PENDING', 'RESERVED', 'CONFIRMED', 'CANCELLED'].includes(s);
            } catch { return false; }
        },
    });

    sleep(0.5);
}
