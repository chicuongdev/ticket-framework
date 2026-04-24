-- Seed concert tickets (3 events for load testing). Idempotent: chỉ insert nếu chưa có.

INSERT INTO concert_tickets (
    resource_id, total_quantity, available_quantity, version, low_stock_threshold,
    updated_at, concert_name, venue, event_date
)
VALUES
    ('concert-001', 10000, 10000, 0, 1000, NOW(), 'Anh Trai Vu Ngan Cong Gai', 'My Dinh Stadium',   '2026-06-15'),
    ('concert-002',  5000,  5000, 0,  500, NOW(), 'Born Pink Tour HCMC',       'Phu Tho Stadium',   '2026-07-20'),
    ('concert-003',   500,   500, 0,   50, NOW(), 'Acoustic Night',            'Hanoi Opera House', '2026-05-10')
ON CONFLICT (resource_id) DO NOTHING;
