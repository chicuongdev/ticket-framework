// Shared helpers cho 3 script k6.
import http from 'k6/http';
import { check } from 'k6';

// Loai 422 (het ve) + 409 (idempotency conflict) khoi http_req_failed metric.
// k6 mac dinh coi >=400 la failure -> false alarm voi business reject.
http.setResponseCallback(
    http.expectedStatuses({ min: 200, max: 299 }, 409, 422)
);

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';

// 3 concert tuong ung data.sql:
//   concert-001: 10000 ve (large pool — soak test)
//   concert-002:  5000 ve
//   concert-003:   500 ve (small pool — oversell test)
export const RESOURCES = {
    LARGE: 'concert-001',
    MEDIUM: 'concert-002',
    SMALL: 'concert-003',
};

// "Nhan don" = 201 (sync P1/P2 — order da CONFIRMED) hoac 202 (async P3 — RESERVED).
export function isAccepted(res) {
    return res.status === 201 || res.status === 202;
}

// Moi VU + iter unique -> idempotency-key duy nhat
export function makeIdempotencyKey(prefix) {
    return `${prefix}-${__VU}-${__ITER}-${Date.now()}`;
}

export function placeOrder(resourceId, quantity, prefix) {
    const payload = JSON.stringify({
        resourceId,
        requesterId: `user-${__VU}`,
        quantity,
        idempotencyKey: makeIdempotencyKey(prefix),
    });
    const params = {
        headers: { 'Content-Type': 'application/json' },
        timeout: '10s',
    };
    return http.post(`${BASE_URL}/orders`, payload, params);
}

// Phan loai response: 201/202 = nhan, 422 = fail nghiep vu (het ve/...), 4xx/5xx = loi ha tang
export function tagResponse(res) {
    const ok = check(res, {
        'status accepted (201/202)': (r) => isAccepted(r),
        'status rejected (422)': (r) => r.status === 422,
        'no 5xx': (r) => r.status < 500,
    });
    return ok;
}
