#!/usr/bin/env bash
set -euo pipefail

# ===== Config =====
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

COMPOSE="docker compose"
NGINX_CONTAINER="${NGINX_CONTAINER:-nginx}"

BLUE_PORT=8081
GREEN_PORT=8082

HEALTH_PATH="${HEALTH_PATH:-/actuator/health}"
HEALTH_RETRY="${HEALTH_RETRY:-30}"
HEALTH_SLEEP="${HEALTH_SLEEP:-3}"

NGINX_ACTIVE_CONF="deploy/nginx.conf"
NGINX_BLUE_CONF="deploy/nginx.blue.conf"
NGINX_GREEN_CONF="deploy/nginx.green.conf"

log() { echo -e "[$(date '+%F %T')] $*"; }
die() { echo -e "❌ $*" >&2; exit 1; }

need() {
  command -v "$1" >/dev/null 2>&1 || die "缺少命令：$1"
}

need docker
need curl

# ===== Helpers =====
current_color_from_conf() {
  if [[ ! -f "$NGINX_ACTIVE_CONF" ]]; then
    echo "none"; return 0
  fi
  if grep -q "agent_blue:8080" "$NGINX_ACTIVE_CONF"; then
    echo "blue"; return 0
  fi
  if grep -q "agent_green:8080" "$NGINX_ACTIVE_CONF"; then
    echo "green"; return 0
  fi
  echo "unknown"
}

health_check_local_port() {
  local port="$1"
  local retry="$HEALTH_RETRY"

  until curl -fsS "http://127.0.0.1:${port}${HEALTH_PATH}" | grep -q '"UP"\|UP'; do
    retry=$((retry-1))
    log "⏳ waiting health on :${port} (left=${retry})"
    sleep "$HEALTH_SLEEP"
    if [[ "$retry" -le 0 ]]; then
      return 1
    fi
  done
  return 0
}

nginx_reload_or_rollback() {
  local backup="$1"

  # nginx -t（容器内检测配置）
  if ! docker exec "$NGINX_CONTAINER" nginx -t >/dev/null 2>&1; then
    log "⚠️ nginx -t failed, rollback..."
    cp -f "$backup" "$NGINX_ACTIVE_CONF"
    docker exec "$NGINX_CONTAINER" nginx -s reload || true
    return 1
  fi

  docker exec "$NGINX_CONTAINER" nginx -s reload
  return 0
}

# ===== Main =====
log "🧬 Tencent Cloud BlueGreen Deploy v4 (auto switch + auto rollback)"

[[ -f docker-compose.yml ]] || die "请在项目根目录执行（docker-compose.yml 同级）"
[[ -f .env ]] || log "⚠️ 未检测到 .env（如果你用到变量占位符，会解析为空）"

CURRENT="$(current_color_from_conf)"
case "$CURRENT" in
  blue) TARGET="green"; TARGET_PORT="$GREEN_PORT" ;;
  green) TARGET="blue"; TARGET_PORT="$BLUE_PORT" ;;
  none|unknown)
    log "ℹ️ 当前 nginx.conf 未指向蓝/绿（首次部署），默认先上 blue"
    CURRENT="none"
    TARGET="blue"; TARGET_PORT="$BLUE_PORT"
    ;;
  *) die "无法识别当前颜色：$CURRENT" ;;
esac

TARGET_SERVICE="agent_${TARGET}"
OLD_SERVICE="agent_${CURRENT}"

log "🟢 Current Traffic : ${CURRENT}"
log "🚀 Deploy Target   : ${TARGET} (service=${TARGET_SERVICE}, port=${TARGET_PORT})"

log "📥 git pull..."
git pull || true

log "🔨 Building & starting ${TARGET_SERVICE} ..."
$COMPOSE up -d --build "$TARGET_SERVICE"

log "🧪 Health checking ${TARGET} on :${TARGET_PORT}${HEALTH_PATH}"
if ! health_check_local_port "$TARGET_PORT"; then
  log "❌ Health FAIL => stop ${TARGET_SERVICE}"
  $COMPOSE stop "$TARGET_SERVICE" || true
  die "发布失败（新版本未健康）"
fi
log "✅ Health OK"

# Switch nginx upstream
BACKUP="$(mktemp)"
cp -f "$NGINX_ACTIVE_CONF" "$BACKUP" 2>/dev/null || true

log "🔁 Switching nginx upstream => ${TARGET}"
if [[ "$TARGET" == "blue" ]]; then
  cp -f "$NGINX_BLUE_CONF" "$NGINX_ACTIVE_CONF"
else
  cp -f "$NGINX_GREEN_CONF" "$NGINX_ACTIVE_CONF"
fi

log "♻️ Reloading nginx..."
if ! nginx_reload_or_rollback "$BACKUP"; then
  log "❌ Nginx reload failed => stop ${TARGET_SERVICE}"
  $COMPOSE stop "$TARGET_SERVICE" || true
  rm -f "$BACKUP" || true
  die "切流失败（已回滚 nginx.conf）"
fi
rm -f "$BACKUP" || true
log "🎉 Traffic switched to ${TARGET}"

# Stop old version (optional)
if [[ "$CURRENT" == "blue" || "$CURRENT" == "green" ]]; then
  log "🧹 Stopping old service ${OLD_SERVICE} ..."
  $COMPOSE stop "$OLD_SERVICE" || true
fi

log "✅ Deploy SUCCESS"