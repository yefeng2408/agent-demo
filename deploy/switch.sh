#!/usr/bin/env bash
set -euo pipefail

COLOR="${1:-}"
if [[ "$COLOR" != "blue" && "$COLOR" != "green" ]]; then
  echo "Usage: ./deploy/switch.sh blue|green"
  exit 1
fi

NGINX_CONTAINER="nginx"
ACTIVE_CONF="deploy/nginx.conf"
BLUE_CONF="deploy/nginx.blue.conf"
GREEN_CONF="deploy/nginx.green.conf"

# ===============================
# 1️⃣ 健康检查
# ===============================
if [[ "$COLOR" == "blue" ]]; then
  TARGET_URL="http://127.0.0.1:8081/actuator/health"
  SRC="$BLUE_CONF"
else
  TARGET_URL="http://127.0.0.1:8082/actuator/health"
  SRC="$GREEN_CONF"
fi

echo "[1/4] Checking target health: $TARGET_URL"

curl -fsS "$TARGET_URL" | grep -q '"status":"UP"' \
  || { echo "❌ target not healthy"; exit 1; }

echo "✅ target is UP"

# ===============================
# 2️⃣ 备份当前 nginx.conf
# ===============================
echo "[2/4] Backup nginx.conf"
BACKUP=$(mktemp)
cp "$ACTIVE_CONF" "$BACKUP" 2>/dev/null || true

# ===============================
# 3️⃣ 切换配置
# ===============================
echo "[3/4] Switching nginx.conf -> $COLOR"
cp "$SRC" "$ACTIVE_CONF"

# 先测试 nginx 配置合法性
if ! docker exec "$NGINX_CONTAINER" nginx -t ; then
  echo "❌ nginx config invalid -> rollback"
  cp "$BACKUP" "$ACTIVE_CONF"
  docker exec "$NGINX_CONTAINER" nginx -s reload || true
  exit 1
fi

# ===============================
# 4️⃣ Reload nginx（0停机）
# ===============================
echo "[4/4] Reloading nginx"
docker exec "$NGINX_CONTAINER" nginx -s reload

rm -f "$BACKUP"

echo "🎉 Switched traffic to: $COLOR"