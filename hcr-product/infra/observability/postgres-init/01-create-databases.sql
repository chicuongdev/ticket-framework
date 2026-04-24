-- Tạo 3 database riêng cho 3 microservice (Database-per-Service pattern)
-- Cùng 1 Postgres instance để demo dễ chạy; production tách instance riêng.

CREATE DATABASE order_db OWNER hcr;
CREATE DATABASE inventory_db OWNER hcr;
CREATE DATABASE payment_db OWNER hcr;
