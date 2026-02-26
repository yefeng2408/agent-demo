#!/usr/bin/env bash
set -euo pipefail

echo "🧠 Agent Deploy v20 — Zero-Downtime (TCP Ready + Prewarm, NO Healthcheck)"

APP_NAME="agent"
PORT_BLUE=8081
PORT_GREEN=8082

FRONT_NET="agent-stack_frontend_net"
BACK_NET="agent-stack_backend_net"

ROOT_DIR="$HOME/agent-stack"
DEPLOY_DIR="$ROOT_DIR/deploy"
TMP_DIR="$DEPLOY_DIR/tmp"

mkdir -p "$TMP_DIR"

# ✅ Release Jar
JAR_URL="https://github.com/yefeng2408/agent-demo/releases/download/latest/app.jar"

# ✅ TCP ready settings
READY_HOST="127.0.0.1"
READY_TIMEOUT_SEC=60
READY_INTERVAL_SEC=1

#########################################
# Detect current traffic
#########################################
if docker exec nginx sh -c "grep -q 'server agent_blue:8080;' /etc/nginx/nginx.conf"; then
  CURRENT="blue"
  TARGET="green"
  TARGET_PORT=$PORT_GREEN
else
  CURRENT="green"
  TARGET="blue"
  TARGET_PORT=$PORT_BLUE
fi

echo "🎯 Current Traffic : $CURRENT"
echo "🚀 Deploy Target  : $TARGET ($TARGET_PORT)"

#########################################
# Download jar
#########################################
TARGET_JAR="$TMP_DIR/app-${TARGET}.jar"
echo "⬇️ Download latest app.jar..."
curl -L -o "$TARGET_JAR" "$JAR_URL"

#########################################
# Build immutable image
#########################################
TS="$(date +%Y%m%d-%H%M%S)"
TARGET_IMAGE="${APP_NAME}:${TARGET}-${TS}"

echo "🐳 Building image $TARGET_IMAGE"
docker build \
  -f "$ROOT_DIR/Dockerfile.runtime" \
  --build-arg JAR_FILE="$(realpath --relative-to="$ROOT_DIR" "$TARGET_JAR")" \
  -t "$TARGET_IMAGE" \
  "$ROOT_DIR"

#########################################
# Remove old target container
#########################################
docker rm -f "${APP_NAME}_${TARGET}" 2>/dev/null || true

#########################################
# Run new container (target)
#########################################
echo "🚀 Starting ${APP_NAME}_${TARGET} ..."
docker run -d \
  --name "${APP_NAME}_${TARGET}" \
  --network "$BACK_NET" \
  --network-alias "${APP_NAME}_${TARGET}" \
  -p "${TARGET_PORT}:8080" \
  -e SPRING_PROFILES_ACTIVE=prd \
  -e TZ=Asia/Shanghai \
  -e MILVUS_HOST=milvus \
  -e MILVUS_PORT=19530 \
  -e NEO4J_URI=bolt://neo4j:7687 \
  -e NEO4J_USERNAME="${NEO4J_USERNAME:-}" \
  -e NEO4J_PASSWORD="${NEO4J_PASSWORD:-}" \
  -e SPRING_AI_OLLAMA_BASE_URL="http://ollama:11434" \
  -e SPRING_AI_OPENAI_API_KEY="${OPENAI_API_KEY:-}" \
  -e JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-}" \
  "$TARGET_IMAGE"

docker network connect "$FRONT_NET" "${APP_NAME}_${TARGET}" 2>/dev/null || true

#########################################
# v20: TCP Ready Wait (NO healthcheck)
#########################################
echo "⏳ Waiting TCP ready on ${READY_HOST}:${TARGET_PORT} (timeout ${READY_TIMEOUT_SEC}s)..."

start_ts="$(date +%s)"
while true; do
  if nc -z "$READY_HOST" "$TARGET_PORT" >/dev/null 2>&1; then
    echo "✅ TCP READY: ${READY_HOST}:${TARGET_PORT}"
    break
  fi
  now_ts="$(date +%s)"
  if (( now_ts - start_ts >= READY_TIMEOUT_SEC )); then
    echo "❌ TCP NOT READY in ${READY_TIMEOUT_SEC}s. Dump last logs:"
    docker logs --tail 200 "${APP_NAME}_${TARGET}" || true
    exit 1
  fi
  sleep "$READY_INTERVAL_SEC"
done

#########################################
# v20: Prewarm 1 request before switching
#########################################
echo "🔥 Prewarm request to new target..."
curl -sS "http://${READY_HOST}:${TARGET_PORT}/" >/dev/null 2>&1 || true

#########################################
# Switch nginx traffic (atomic edit)
#########################################
echo "🔄 Switching traffic to $TARGET"
docker exec nginx sh -c "
set -e
sed -i 's/server agent_${CURRENT}:8080;/server agent_${TARGET}:8080;/g' /etc/nginx/nginx.conf
nginx -t
nginx -s reload
"

#########################################
# Remove old container AFTER switch
#########################################
echo "🧹 Removing old container ${APP_NAME}_${CURRENT}"
docker rm -f "${APP_NAME}_${CURRENT}" 2>/dev/null || true

echo "✅ v20 Deploy SUCCESS — Zero downtime achieved"