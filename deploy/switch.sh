#!/usr/bin/env bash
set -euo pipefail

COLOR="${1:-}"
if [[ "$COLOR" != "blue" && "$COLOR" != "green" ]]; then
  echo "Usage: ./deploy/switch.sh blue|green"
  exit 1
fi

# 1) 先检查目标环境健康（actuator）
if [[ "$COLOR" == "blue" ]]; then
  TARGET_URL="http://127.0.0.1:8081/actuator/health"
  SRC="deploy/nginx.blue.conf"
else
  TARGET_URL="http://127.0.0.1:8082/actuator/health"
  SRC="deploy/nginx.green.conf"
fi

echo "[1/3] Checking target health: $TARGET_URL"
curl -fsS "$TARGET_URL" | grep -q "UP"
echo "✅ target is UP"

# 2) 切 nginx.conf（软链接或复制都行，这里用复制，最稳）
echo "[2/3] Switching nginx.conf -> $COLOR"
cp "$SRC" "deploy/nginx.conf"

# 3) reload nginx（0 downtime）
echo "[3/3] Reloading nginx"
docker exec nginx nginx -s reload

echo "🎉 Switched traffic to: $COLOR"