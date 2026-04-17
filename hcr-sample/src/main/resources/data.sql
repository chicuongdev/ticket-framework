-- Seed dữ liệu concert ticket cho demo
INSERT INTO concert_tickets (resource_id, total_quantity, available_quantity, low_stock_threshold, version, updated_at,
                              concert_name, venue, event_date, price_per_ticket)
VALUES ('concert-2026-06-15', 100, 100, 10, 0, CURRENT_TIMESTAMP,
        'Anh Trai Say Hi Tour', 'SVĐ Mỹ Đình, Hà Nội', '2026-06-15', 500000);
