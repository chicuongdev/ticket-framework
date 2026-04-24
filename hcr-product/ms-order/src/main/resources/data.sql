-- ms-order local catalog: chỉ giữ giá vé để build PaymentRequestedEvent.
-- resource_id phải khớp ms-inventory's data.sql. available_quantity/version không dùng (Redis là source of truth).

INSERT INTO concert_tickets (
    resource_id, total_quantity, available_quantity, version, low_stock_threshold,
    updated_at, concert_name, price_per_ticket, currency
)
VALUES
    ('concert-001', 10000, 0, 0, 0, NOW(), 'Anh Trai Vu Ngan Cong Gai',  500000.00, 'VND'),
    ('concert-002',  5000, 0, 0, 0, NOW(), 'Born Pink Tour HCMC',       2500000.00, 'VND'),
    ('concert-003',   500, 0, 0, 0, NOW(), 'Acoustic Night',             800000.00, 'VND')
ON CONFLICT (resource_id) DO NOTHING;
