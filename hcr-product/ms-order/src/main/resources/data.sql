-- ms-order catalog + inventory seed.
--   P3 (redis-atomic): Redis la source of truth; cot available_quantity o day khong dung.
--   P1/P2 (pessimistic/optimistic-lock): bang nay LA inventory store cua saga sync —
--     available_quantity phai = total_quantity (reserve chay truc tiep tren order_db).
-- ON CONFLICT DO NOTHING: restart KHONG ghi de (giu ton kho dang chay). Reset that su
--   = chay reset-p1p2.sh (TRUNCATE bang nay) roi restart -> insert lai fresh.

INSERT INTO concert_tickets (
    resource_id, total_quantity, available_quantity, version, low_stock_threshold,
    updated_at, concert_name, price_per_ticket, currency
)
VALUES
    ('concert-001', 10000, 10000, 0, 0, NOW(), 'Anh Trai Vu Ngan Cong Gai',  500000.00, 'VND'),
    ('concert-002',  5000,  5000, 0, 0, NOW(), 'Born Pink Tour HCMC',       2500000.00, 'VND'),
    ('concert-003',   500,   500, 0, 0, NOW(), 'Acoustic Night',             800000.00, 'VND')
ON CONFLICT (resource_id) DO NOTHING;
