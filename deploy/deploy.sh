#!/usr/bin/env bash
set -euo pipefail

#######################################
# 🔥 Rootless SRE Deploy v9
#######################################

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

LOCK_FILE="/tmp/agent-stack-deploy.lock"
COMPOSE="docker compose"

BLUE_PORT=8081
GREEN_PORT=8082

NGINX_ACTIVE_CONF="deploy/nginx.conf"
NGINX_BLUE_CONF="deploy/nginx.blue.conf"
NGINX_GREEN_CONF="deploy/nginx.green.conf"

#######################################
# log
#######################################
log() { echo -e "[\033[32m$(date '+%F %T')\033[0m] $*"; }
die() { echo -e "[\033[31mERROR\033[0m] $*" >&2; exit 1; }

#######################################
# 🔒 lock
#######################################
if [ -f "$LOCK_FILE" ]; then
  die "Deploy locked: $LOCK_FILE"
fi
touch "$LOCK_FILE"
trap 'rm -f "$LOCK_FILE"' EXIT

#######################################
# 🔥 Rootless 检测
#######################################
if ! docker ps >/dev/null 2>&1; then
  die "Docker permission denied.
请执行：
sudo usermod -aG docker $USER
然后重新登录SSH"
fi

#######################################
# 🔥 自动清理 Mac 污染
#######################################
rm -f .DS_Store || true

#######################################
# 🔥 buildx 检测（非强制）
#######################################
if ! docker buildx version >/dev/null 2>&1; then
  log "⚠ buildx not found (可忽略)"
fi

#######################################
# 🔥 Safe Git Pull
#######################################
log "📦 Safe Pull..."
git stash push -u -m auto-deploy >/dev/null 2>&1 || true
git pull --rebase
git stash pop >/dev/null 2>&1 || true

#######################################
# 当前流量颜色
#######################################
if grep -q "$BLUE_PORT" "$NGINX_ACTIVE_CONF"; then
  CURRENT="blue"
  TARGET="green"
  TARGET_PORT=$GREEN_PORT
else
  CURRENT="green"
  TARGET="blue"
  TARGET_PORT=$BLUE_PORT
fi

log "🎯 Current Traffic : $CURRENT"
log "🚀 Deploy Target   : $TARGET ($TARGET_PORT)"

#######################################
# 🔥 build image（Rootless）
#######################################
log "🔨 Building agent_$TARGET ..."
$COMPOSE build --no-cache agent_$TARGET

#######################################
# 🔥 start target
#######################################
log "▶ Starting agent_$TARGET ..."
$COMPOSE up -d agent_$TARGET

#######################################
# 🔥 Health Check
#######################################
log "🩺 Health checking :$TARGET_PORT"

for i in {1..20}; do
  if curl -fs "http://127.0.0.1:$TARGET_PORT/actuator/health" >/dev/null 2>&1; then
    log "✅ Health OK"
    break
  fi
  sleep 3
done

if ! curl -fs "http://127.0.0.1:$TARGET_PORT/actuator/health" >/dev/null 2>&1; then
  log "❌ Health FAIL => rollback"
  $COMPOSE stop agent_$TARGET
  exit 1
fi

#######################################
# 🔥 切流量（Nginx）
#######################################
log "🌐 Switch Nginx Traffic -> $TARGET"

if [ "$TARGET" = "green" ]; then
  cp "$NGINX_GREEN_CONF" "$NGINX_ACTIVE_CONF"
else
  cp "$NGINX_BLUE_CONF" "$NGINX_ACTIVE_CONF"
fi

docker exec nginx nginx -s reload

#######################################
# 🔥 stop old
#######################################
log "🧹 Stop old agent_$CURRENT"
$COMPOSE stop agent_$CURRENT || true

log "🎉 Deploy SUCCESS (Rootless SRE v9)"