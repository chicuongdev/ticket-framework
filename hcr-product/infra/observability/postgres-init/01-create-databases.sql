-- Database per microservice + per prototype (P1/P2/P3 isolation).
--
-- Lý do tách per-prototype:
--   - P1 (pessimistic) và P2 (optimistic) share entity ConcertTicket có @Version, nhưng P1
--     không nên dùng @Version. Tách DB để mỗi prototype có schema riêng (Hibernate ddl-auto
--     tự tạo) — reset/test P1 không bẩn state P2.
--   - P3 (Redis source of truth) cần inventory_p3_db cho persistence consumer (DB sync async).
--   - payment_db dùng chung — payment_attempts log không phụ thuộc prototype.
--
-- Cùng 1 Postgres instance để demo dễ chạy; production tách instance riêng.

CREATE DATABASE order_p1_db OWNER hcr;
CREATE DATABASE order_p2_db OWNER hcr;
CREATE DATABASE order_p3_db OWNER hcr;
CREATE DATABASE inventory_p3_db OWNER hcr;
CREATE DATABASE payment_db OWNER hcr;
