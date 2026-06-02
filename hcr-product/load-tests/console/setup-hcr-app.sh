#!/bin/bash
# setup-hcr-app.sh — chạy MỘT LẦN trên VM hcr-app (sudo).
#
# Theo convention runbook-gcp.md:
#   - /etc/hcr/env là source-of-truth (đã có từ deploy gốc)
#   - DB_NAME của ms-order nằm trong unit file /etc/systemd/system/hcr-order.service
#   - Switch prototype = sed cả 2 file + daemon-reload + restart 3 service
#
# Script này KHÔNG tạo file env mới, KHÔNG sửa unit file. Chỉ:
#   1. Cài helper /usr/local/bin/hcr-switch-prototype.sh để console gọi qua sudo
#   2. Sudoers whitelist 3 lệnh: helper p1, helper p2, helper p3
#
# Cách chạy: sudo bash setup-hcr-app.sh

set -euo pipefail

if [[ $EUID -ne 0 ]]; then
    echo "ERROR: cần chạy bằng sudo." >&2
    exit 1
fi

SSH_USER="${SSH_USER:-Admin}"
HELPER="/usr/local/bin/hcr-switch-prototype.sh"

echo "==> SSH_USER = $SSH_USER"
echo "==> Verify /etc/hcr/env tồn tại (deploy gốc)…"
[[ -f /etc/hcr/env ]] || { echo "ERROR: /etc/hcr/env not found. Run deploy-gcp.md Step 9 first."; exit 1; }
[[ -f /etc/systemd/system/hcr-order.service ]] || { echo "ERROR: unit file not found"; exit 1; }

# ──────────────────────────────────────────────────────────────────────────
# 1. Helper script — chạy bằng root qua sudo, làm toàn bộ switch
# ──────────────────────────────────────────────────────────────────────────
echo "==> Cài $HELPER"
cat > "$HELPER" <<'HELPER_EOF'
#!/bin/bash
# /usr/local/bin/hcr-switch-prototype.sh — switch P1/P2/P3 theo runbook 3.2/3.3.
# Args: p1 | p2 | p3
# Sửa /etc/hcr/env + unit file ms-order, daemon-reload, restart 3 service.

set -euo pipefail

TARGET="${1:-}"
case "$TARGET" in
    p1|p2|p3) ;;
    *) echo "Usage: $0 p1|p2|p3" >&2; exit 1 ;;
esac

ENV_FILE="/etc/hcr/env"
ORDER_UNIT="/etc/systemd/system/hcr-order.service"

# 1. Update ACTIVE_PROTOTYPE in env file
sed -i "s/^ACTIVE_PROTOTYPE=.*/ACTIVE_PROTOTYPE=$TARGET/" "$ENV_FILE"
grep ^ACTIVE_PROTOTYPE "$ENV_FILE"

# 2. Update DB_NAME of ms-order in unit file
sed -i -E "s|(Environment=\"?DB_NAME=order_)p[123](_db\"?)|\1${TARGET}\2|" "$ORDER_UNIT"
grep -E 'DB_NAME=order_' "$ORDER_UNIT" || { echo "ERROR: DB_NAME line not found in unit"; exit 1; }

# 3. daemon-reload
systemctl daemon-reload

# 4. Restart in order: inventory → payment → order (theo runbook 9.5)
echo "==> restart hcr-inventory"
systemctl restart hcr-inventory
sleep 15

echo "==> restart hcr-payment"
systemctl restart hcr-payment
sleep 3

echo "==> restart hcr-order"
systemctl restart hcr-order
sleep 5

echo "Switched to $TARGET"
HELPER_EOF

chmod 0755 "$HELPER"

# ──────────────────────────────────────────────────────────────────────────
# 2. Sudoers — whitelist 3 lệnh (1 cho mỗi target)
# ──────────────────────────────────────────────────────────────────────────
SUDOERS_FILE="/etc/sudoers.d/hcr-console"
echo "==> Tạo $SUDOERS_FILE (user=$SSH_USER)"

cat > "$SUDOERS_FILE" <<EOF
# HCR test console — passwordless whitelist cho user $SSH_USER.
# Chỉ cho phép gọi helper với 3 target cố định, không cho ALL.
$SSH_USER ALL=(root) NOPASSWD: $HELPER p1
$SSH_USER ALL=(root) NOPASSWD: $HELPER p2
$SSH_USER ALL=(root) NOPASSWD: $HELPER p3
EOF

chmod 0440 "$SUDOERS_FILE"
visudo -cf "$SUDOERS_FILE"

cat <<EOF

═══════════════════════════════════════════════════════════════════════
SETUP DONE.

Verify:
  cat /etc/hcr/env | grep ACTIVE_PROTOTYPE
  ls -l $HELPER
  cat $SUDOERS_FILE

Test helper (chạy ngay, không qua console):
  sudo $HELPER p1
  curl -s http://localhost:8081/actuator/hcr | jq '.inventory.configuredStrategy, .saga.mode'
    → "pessimistic-lock"  "sync"

Test passwordless sudo từ loadgen:
  ssh Admin@10.20.0.3 'sudo -n $HELPER p1'
  → KHÔNG hiện prompt password = OK.
═══════════════════════════════════════════════════════════════════════
EOF
