#!/bin/bash
# rollback-hcr-app.sh — undo setup-hcr-app.sh trên VM hcr-app.
# Chạy: sudo bash rollback-hcr-app.sh
#
# Sau khi rollback xong: /etc/hcr/env (runbook gốc) là source-of-truth duy nhất.

set -euo pipefail

if [[ $EUID -ne 0 ]]; then
    echo "ERROR: cần chạy bằng sudo." >&2
    exit 1
fi

ORDER_UNIT="/etc/systemd/system/hcr-order.service"
INV_UNIT="/etc/systemd/system/hcr-inventory.service"

echo "==> Remove drop-in EnvironmentFile=/etc/hcr/active.env"
rm -f /etc/systemd/system/hcr-order.service.d/console.conf
rm -f /etc/systemd/system/hcr-inventory.service.d/console.conf
rmdir /etc/systemd/system/hcr-order.service.d 2>/dev/null || true
rmdir /etc/systemd/system/hcr-inventory.service.d 2>/dev/null || true

echo "==> Restore unit files from .bak (uncomment Environment= lines)"
for unit in "$ORDER_UNIT" "$INV_UNIT"; do
    if [[ -f "$unit.bak" ]]; then
        cp "$unit.bak" "$unit"
        rm "$unit.bak"
        echo "    restored: $unit"
    fi
done

echo "==> Remove /etc/hcr/p?.env + active.env (giữ /etc/hcr/env gốc)"
rm -f /etc/hcr/p1.env /etc/hcr/p2.env /etc/hcr/p3.env /etc/hcr/active.env

echo "==> Remove old sudoers"
rm -f /etc/sudoers.d/hcr-console

echo "==> daemon-reload"
systemctl daemon-reload

cat <<EOF

═══════════════════════════════════════════════════════════════════════
ROLLBACK DONE.

Verify:
  systemctl show hcr-order -p EnvironmentFiles
    → CHỈ thấy /etc/hcr/env (không còn active.env)
  cat /etc/hcr/env | grep ACTIVE_PROTOTYPE
  grep DB_NAME /etc/systemd/system/hcr-order.service
    → Environment="DB_NAME=order_pX_db" (đã uncomment lại)

Bước tiếp:
  sudo bash setup-hcr-app.sh   # cài helper + sudoers theo convention runbook
═══════════════════════════════════════════════════════════════════════
EOF
