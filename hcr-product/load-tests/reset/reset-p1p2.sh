#!/usr/bin/env bash
set -euo pipefail
# Reset state truoc moi lan test P1/P2 (saga sync).
# Chay tren EC2 infra (noi co container hcr-postgres, hcr-redis).
#
#   P1/P2: inventory = bang order_db.concert_tickets. Redis chi giu idempotency keys.
#   Sau khi chay script nay -> restart ms-order voi -Dspring.profiles.active=p1|p2;
#   data.sql se seed lai concert_tickets voi available_quantity = total_quantity.

echo "[reset-p1p2] TRUNCATE ticket_orders + concert_tickets (order_db)"
docker exec -i hcr-postgres psql -U hcr -d order_db \
  -c "TRUNCATE TABLE ticket_orders, concert_tickets;"

echo "[reset-p1p2] Redis FLUSHALL (xoa idempotency keys)"
docker exec -i hcr-redis redis-cli FLUSHALL

echo "[reset-p1p2] DONE. Buoc tiep theo:"
echo "  1) Restart ms-order: java -Dspring.profiles.active=p1 ... -jar ms-order.jar"
echo "  2) Smoke: POST /orders  ->  ky vong HTTP 201"
