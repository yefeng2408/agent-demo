#!/usr/bin/env bash
set -euo pipefail

echo "🔥 Agent Deploy v24 — FULL AUTO RELEASE"

APP_NAME="agent"

PORT_BLUE=8081
PORT_GREEN=8082

CURRENT=""
TARGET=""
TARGET_PORT=""

ROOT_DIR="$HOME/agent-stack"

FRONTEND_DIR="$ROOT_DIR/frontend"

COMPOSE_FILE="$ROOT_DIR/docker-compose.yml"

ENV_FILE="$ROOT_DIR/.env"

if [ -f "$ENV_FILE" ]; then
  echo "🔐 Loading env from .env"
  set -a
  source "$ENV_FILE"
  set +a
else
  echo "⚠️  .env file not found at $ENV_FILE"
fi

if [ ! -f "$COMPOSE_FILE" ]; then
  echo "❌ docker compose file not found: $COMPOSE_FILE"
  echo "   (check file name: docker-compose.yml)"
  exit 1
fi

FRONT_NET="agent-stack_frontend_net"
BACK_NET="agent-stack_backend_net"

READY_HOST="127.0.0.1"
READY_TIMEOUT_SEC=60

#########################################
# STEP 0 — Pull latest
#########################################

cd "$ROOT_DIR"
echo "📥 git pull..."
git pull

#########################################
# STEP 1 — Detect changes (Smart Build)
#########################################

# First deploy or no reflog → full build
if git rev-parse HEAD@{1} >/dev/null 2>&1; then
  CHANGED_FILES=$(git diff HEAD@{1} --name-only || true)
else
  echo "🆕 First deploy detected — FULL BUILD"
  CHANGED_FILES="frontend/ src/ pom.xml"
fi

BUILD_FRONTEND=false
BUILD_BACKEND=false

echo "$CHANGED_FILES" | grep -q "^frontend/" && BUILD_FRONTEND=true || true
echo "$CHANGED_FILES" | grep -q "^src/" && BUILD_BACKEND=true || true
echo "$CHANGED_FILES" | grep -q "pom.xml" && BUILD_BACKEND=true || true


#########################################
# STEP 2 — Frontend Build (Docker Mode — NO NODE ON SERVER)
#########################################

if [ "$BUILD_FRONTEND" = true ]; then
  echo "🎨 Building frontend via Docker (NO NODE ON SERVER)..."

  docker build \
    -t agent-frontend:latest \
    -f "$ROOT_DIR/frontend/Dockerfile" \
    "$FRONTEND_DIR"

  # 清理旧 dist（防止残留旧 hash 文件）
  rm -rf "$FRONTEND_DIR/dist"

  # 将构建产物复制到 nginx 挂载目录
  TMP_CONTAINER=$(docker create agent-frontend:latest)
  docker cp "$TMP_CONTAINER":/app/dist "$FRONTEND_DIR/dist"
  docker rm "$TMP_CONTAINER"

  echo "✅ Frontend docker build complete."
else
  echo "⚡ Frontend unchanged — skip docker build"
fi

#########################################
# STEP 3 — Backend Build
#########################################

if [ "$BUILD_BACKEND" = true ]; then
  echo "☕ Building backend..."
  ./mvnw clean package -DskipTests
else
  echo "⚡ Backend unchanged — skip build"
fi

#LOCAL_JAR="$ROOT_DIR/target/agent-0.0.1-SNAPSHOT.jar"
LOCAL_JAR="$(ls -1 target/*.jar | grep -v '\.original$' | head -n 1)"

#########################################
# STEP 4 — Ensure infra & sanity check compose
#########################################

echo "🔍 Compose services:"
SERVICES="$(docker compose -f "$COMPOSE_FILE" config --services)"
echo "$SERVICES"

need_services=("nginx" "ollama" "neo4j" "etcd" "minio" "milvus")
for s in "${need_services[@]}"; do
  if ! echo "$SERVICES" | grep -qx "$s"; then
    echo "❌ compose missing service: $s"
    echo "   -> You are running with compose file: $COMPOSE_FILE"
    exit 1
  fi
done

echo "🧱 Starting infra..."
docker compose -f "$COMPOSE_FILE" up -d "${need_services[@]}"

# Wait milvus port 19530 ready from inside backend_net (no need nc in milvus image)
echo "⏳ Waiting Milvus 19530..."
for i in $(seq 1 120); do
  if docker run --rm --network "$BACK_NET" alpine:3.19 \
      sh -c "apk add --no-cache netcat-openbsd >/dev/null 2>&1 && nc -z milvus 19530"; then
    echo "✅ Milvus ready"
    break
  fi
  if [ "$i" -eq 120 ]; then
    echo "❌ Milvus not ready after 240s"
    docker logs --tail=200 milvus || true
    exit 1
  fi
  sleep 2
done

#########################################
# STEP 4.5 — Detect current traffic (blue/green)
#########################################

# Decide which color is currently serving traffic by reading nginx upstream.
# If nginx points to agent_blue:8080, we deploy green; otherwise deploy blue.
if docker exec nginx sh -c "grep -q 'server agent_blue:8080;' /etc/nginx/nginx.conf"; then
  CURRENT="blue"
  TARGET="green"
  TARGET_PORT=$PORT_GREEN
else
  CURRENT="green"
  TARGET="blue"
  TARGET_PORT=$PORT_BLUE
fi

echo "🎯 Current=$CURRENT → Deploy=$TARGET (port=$TARGET_PORT)"

#########################################
# STEP 5 — Build Immutable Image
#########################################
TS="$(date +%Y%m%d-%H%M%S)"
TARGET_IMAGE="${APP_NAME}:${TARGET}-${TS}"

echo "📦 Detecting jar..."
LOCAL_JAR="$(ls -1 target/*.jar | grep -v '\.original$' | head -n 1)"


if [ -z "${LOCAL_JAR:-}" ]; then
  echo "❌ No jar found in target/"
  exit 1
fi

echo "✅ Using jar: $LOCAL_JAR"

echo "🐳 Building image: $TARGET_IMAGE"

docker build \
  -f "$ROOT_DIR/Dockerfile.runtime" \
  --build-arg JAR_FILE="$LOCAL_JAR" \
  -t "$TARGET_IMAGE" \
  "$ROOT_DIR"
#########################################
# STEP 6 — Start new container
#########################################

docker rm -f "${APP_NAME}_${TARGET}" 2>/dev/null || true

docker run -d \
  --name "${APP_NAME}_${TARGET}" \
  --network "$BACK_NET" \
  --network-alias "${APP_NAME}_${TARGET}" \
  -p "${TARGET_PORT}:8080" \
  -e SPRING_PROFILES_ACTIVE=prd \
  -e MILVUS_HOST=milvus \
  -e NEO4J_URI=bolt://neo4j:7687 \
  -e SPRING_NEO4J_URI=bolt://neo4j:7687 \
  -e SPRING_NEO4J_AUTHENTICATION_USERNAME="${NEO4J_USERNAME}" \
  -e SPRING_NEO4J_AUTHENTICATION_PASSWORD="${NEO4J_PASSWORD}" \
  -e SPRING_AI_OLLAMA_BASE_URL="http://ollama:11434" \
  -e OPENAI_API_KEY="${OPENAI_API_KEY:-}" \
  "$TARGET_IMAGE"

docker network connect "$FRONT_NET" "${APP_NAME}_${TARGET}" || true

#########################################
# STEP 7 — TCP READY
#########################################

echo "⏳ Waiting TCP ready..."

start_ts=$(date +%s)

while true; do
  if nc -z "$READY_HOST" "$TARGET_PORT"; then
    echo "✅ TCP READY"
    break
  fi

  now_ts=$(date +%s)
  if (( now_ts - start_ts > READY_TIMEOUT_SEC )); then
    docker logs "${APP_NAME}_${TARGET}"
    exit 1
  fi

  sleep 1
done

#########################################
# STEP 8 — PREWARM
#########################################

curl -s "http://${READY_HOST}:${TARGET_PORT}/" >/dev/null || true

#########################################
# STEP 9 — SWITCH NGINX
#########################################

docker exec nginx sh -c "
sed -i 's/server agent_${CURRENT}:8080;/server agent_${TARGET}:8080;/g' /etc/nginx/nginx.conf
nginx -t
nginx -s reload
"

#########################################
# STEP 10 — Remove old
#########################################

docker rm -f "${APP_NAME}_${CURRENT}" || true

echo "🎉 DEPLOY SUCCESS"