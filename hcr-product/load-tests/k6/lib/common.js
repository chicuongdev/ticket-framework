// Shared helpers cho 3 script k6.
import http from 'k6/http';
import { check } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';

// 3 concert tương ứng data.sql:
//   concert-001: 10000 vé (large pool — soak test)
//   concert-002:  5000 vé
//   concert-003:   500 vé (small pool — oversell test)
export const RESOURCES = {
    LARGE: 'concert-001',
    MEDIUM: 'concert-002',
    SMALL: 'concert-003',
};

// Mỗi VU + iter unique → idempotency-key duy nhất
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

// Phân loại response: 202 = nhận, 422 = fail nghiệp vụ (hết vé/...), 4xx/5xx = lỗi hạ tầng
export function tagResponse(res) {
    const ok = check(res, {
        'status accepted (202)': (r) => r.status === 202,
        'status rejected (422)': (r) => r.status === 422,
        'no 5xx': (r) => r.status < 500,
    });
    return ok;
}
