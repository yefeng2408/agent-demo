#!/usr/bin/env bash
set -euo pipefail

echo "🔥 Deploy v13 — Zero-Build Ultra Fast Mode (no docker build, no mvn)"

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

COMPOSE="docker compose"
NGINX_CONTAINER="${NGINX_CONTAINER:-nginx}"

BLUE_PORT=8081
GREEN_PORT=8082

LOCK_FILE="/tmp/agent-stack-deploy.lock"

# jar 来源（你可以改成从 /target 或下载）
JAR_SRC="${JAR_SRC:-$PROJECT_ROOT/target/agent-0.0.1-SNAPSHOT.jar}"

BLUE_JAR="${PROJECT_ROOT}/deploy/jars/blue/app.jar"
GREEN_JAR="${PROJECT_ROOT}/deploy/jars/green/app.jar"

log(){ echo -e "[$(date '+%F %T')] $*"; }

############################################
# 🔒 Lock
############################################
exec 9>"$LOCK_FILE"
flock -n 9 || { echo "🚫 deploy already running"; exit 1; }

############################################
# 🧼 Safe Pull（可按需关闭）
############################################
log "📦 Safe Pull..."
git reset --hard HEAD
git clean -fd
git pull

############################################
# ✅ jar 检查
############################################
if [[ ! -f "$JAR_SRC" ]]; then
  log "❌ JAR_SRC not found: $JAR_SRC"
  log "👉 你需要先把 jar 放到这里，或把 JAR_SRC 指到正确路径"
  exit 1
fi

############################################
# 🎯 Current Traffic
############################################
ACTIVE=$(grep -q "$BLUE_PORT" deploy/nginx.conf && echo "blue" || echo "green")

if [[ "$ACTIVE" == "blue" ]]; then
  TARGET="green"
  TARGET_PORT=$GREEN_PORT
  TARGET_JAR="$GREEN_JAR"
  SRC_CONF="deploy/nginx.green.conf"
else
  TARGET="blue"
  TARGET_PORT=$BLUE_PORT
  TARGET_JAR="$BLUE_JAR"
  SRC_CONF="deploy/nginx.blue.conf"
fi

SERVICE="agent_${TARGET}"
OLD_SERVICE="agent_${ACTIVE}"

log "🎯 Current Traffic : $ACTIVE"
log "🚀 Deploy Target   : $TARGET ($TARGET_PORT)"
log "📦 Jar Source      : $JAR_SRC"
log "📌 Jar Target      : $TARGET_JAR"

############################################
# 🏎 Ultra Fast Replace Jar（秒级）
############################################
mkdir -p "$(dirname "$TARGET_JAR")"
cp -f "$JAR_SRC" "$TARGET_JAR"

# 可选：显示版本戳（帮助确认不是 old jar）
log "🔎 Jar timestamp:"
ls -lah "$TARGET_JAR"

############################################
# ▶ 重启目标服务（不 build）
############################################
log "♻️ Restart $SERVICE ..."
$COMPOSE up -d --no-deps "$SERVICE"
$COMPOSE restart "$SERVICE"

############################################
# 🧠 Auto Health Detect
############################################
detect_health() {
  local PORT=$1
  local BASE="http://127.0.0.1:${PORT}"
  local PATHS=("/actuator/health" "/health" "/ready" "/")
  for p in "${PATHS[@]}"; do
    if curl -fsS "${BASE}${p}" >/dev/null 2>&1; then
      echo "$p"; return
    fi
  done
  echo ""
}

log "🧪 Detecting health endpoint ..."
HEALTH_PATH=$(detect_health "$TARGET_PORT")

if [[ -z "$HEALTH_PATH" ]]; then
  log "❌ No health endpoint detected"
  log "🧯 rollback: keep $ACTIVE"
  $COMPOSE logs --tail=200 "$SERVICE" || true
  $COMPOSE stop "$SERVICE" || true
  exit 1
fi

log "✅ Health Path Detected : $HEALTH_PATH"

############################################
# ⏱ Health Check Loop
############################################
RETRY=20
until curl -fsS "http://127.0.0.1:${TARGET_PORT}${HEALTH_PATH}" >/dev/null 2>&1
do
  log "⏳ waiting health on :${TARGET_PORT}${HEALTH_PATH} (left=$RETRY)"
  sleep 2
  ((RETRY--))
  if [[ $RETRY -le 0 ]]; then
    log "❌ Health FAIL => rollback"
    $COMPOSE logs --tail=200 "$SERVICE" || true
    $COMPOSE stop "$SERVICE" || true
    exit 1
  fi
done

log "🎉 Health PASS"

############################################
# 🔀 Switch traffic
############################################
log "🔀 Switching traffic -> $TARGET"
cp "$SRC_CONF" deploy/nginx.conf
docker exec "$NGINX_CONTAINER" nginx -s reload

############################################
# 🧹 Stop old
############################################
log "🧹 Stop old service $OLD_SERVICE"
$COMPOSE stop "$OLD_SERVICE" || true

log "🚀 Deploy SUCCESS (v13 Ultra Fast)"