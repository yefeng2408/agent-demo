#!/usr/bin/env bash
set -euo pipefail

#############################################
# Agent Deploy v18 — CN Mirror + Auto Network
# ✅ 自动读取最新 release
# ✅ 自动识别 jar
# ✅ 自动镜像优先
# ✅ 自动 fallback
# ✅ 自动加入 nginx networks（修复 host not found）
#############################################

APP_NAME="agent"
DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JAR_DIR="$DEPLOY_DIR/deploy/jars"

BLUE_PORT=8081
GREEN_PORT=8082

# GitHub Repo
GH_OWNER="yefeng2408"
GH_REPO="agent-demo"

LOCK_FILE="/tmp/agent-stack-deploy.lock"
SKIP_HEALTH="${SKIP_HEALTH:-0}"     # SKIP_HEALTH=1 跳过健康检查
HEALTH_TIMEOUT_SEC="${HEALTH_TIMEOUT_SEC:-60}"

echo "🧠 Agent Deploy v18 — CN Mirror + Auto Network"
echo "📦 Zero Build Mode"
echo "🔐 Rootless Mode"
echo "--------------------------------"

#############################################
# 🔒 Lock
#############################################
if [ -f "$LOCK_FILE" ]; then
  echo "❌ Another deploy running."
  exit 1
fi
touch "$LOCK_FILE"
trap "rm -f $LOCK_FILE" EXIT

#############################################
# 0) nginx 必须存在
#############################################
if ! docker ps --format '{{.Names}}' | grep -qx nginx; then
  echo "❌ nginx container not running."
  exit 1
fi

#############################################
# 1) Detect current slot
#############################################
CURRENT="green"
if docker ps --format '{{.Names}}' | grep -qx agent_blue; then CURRENT="blue"; fi
if docker ps --format '{{.Names}}' | grep -qx agent_green; then CURRENT="green"; fi
echo "🎯 Current Traffic : $CURRENT"

if [ "$CURRENT" = "blue" ]; then
  TARGET="green"; TARGET_PORT=$GREEN_PORT
else
  TARGET="blue";  TARGET_PORT=$BLUE_PORT
fi
echo "🚀 Deploy Target : $TARGET ($TARGET_PORT)"

mkdir -p "$JAR_DIR/$TARGET"

#############################################
# 2) Resolve latest release asset (GitHub API)
#############################################
echo "🔎 Resolve latest release via GitHub API..."
API_JSON="$(curl -fsSL --retry 3 --retry-delay 2 "https://api.github.com/repos/$GH_OWNER/$GH_REPO/releases/latest")" || {
  echo "❌ GitHub API failed."
  exit 1
}

TAG_NAME="$(echo "$API_JSON" | grep -oE '"tag_name"\s*:\s*"[^"]+"' | head -n1 | cut -d'"' -f4)"
[ -n "${TAG_NAME:-}" ] || TAG_NAME="latest"
echo "🏷️ Latest tag: $TAG_NAME"

# 找第一个 .jar asset 的浏览器下载地址
ASSET_URL="$(echo "$API_JSON" | grep -oE '"browser_download_url"\s*:\s*"[^"]+\.jar"' | head -n1 | cut -d'"' -f4)"
if [ -z "${ASSET_URL:-}" ]; then
  # fallback：你固定的 latest/app.jar
  ASSET_URL="https://github.com/$GH_OWNER/$GH_REPO/releases/download/latest/app.jar"
fi
echo "🧩 Jar asset: $ASSET_URL"

# 自动识别 jar 文件名
JAR_NAME="$(basename "$ASSET_URL")"
[ -n "$JAR_NAME" ] || JAR_NAME="app.jar"
DEST_JAR="$JAR_DIR/$TARGET/$JAR_NAME"

#############################################
# 3) Download jar (CN mirror first + fallback)
#############################################
echo "📦 Pull latest jar from release..."

MIRRORS=(
  "https://ghproxy.com/"
  "https://mirror.ghproxy.com/"
  "https://github.moeyy.xyz/"
  "https://gh.api.99988866.xyz/"
  ""  # direct
)

download_ok=0
for prefix in "${MIRRORS[@]}"; do
  URL="${prefix}${ASSET_URL}"
  echo "⬇️ Trying: $URL"
  if curl -fL --connect-timeout 10 --max-time 600 \
      --retry 3 --retry-delay 2 \
      -o "$DEST_JAR" "$URL"; then
    download_ok=1
    break
  else
    echo "⚠️ Mirror not reachable, skip."
  fi
done

if [ "$download_ok" -ne 1 ]; then
  echo "❌ Download jar failed."
  exit 1
fi

echo "✅ Jar ready: $DEST_JAR ($(du -h "$DEST_JAR" | awk '{print $1}'))"
ln -sf "$DEST_JAR" "$JAR_DIR/$TARGET/app.jar"  # 统一对外路径

#############################################
# 4) Select runtime image (CN registry first + fallback)
#############################################
RUNTIME_CANDIDATES=(
  "ccr.ccs.tencentyun.com/library/eclipse-temurin:21-jre"
  "registry.cn-hangzhou.aliyuncs.com/library/eclipse-temurin:21-jre"
  "eclipse-temurin:21-jre"
)

RUNTIME_IMAGE=""
for img in "${RUNTIME_CANDIDATES[@]}"; do
  echo "🐳 Pull runtime image try: $img"
  if docker pull "$img" >/dev/null 2>&1; then
    RUNTIME_IMAGE="$img"
    break
  else
    echo "⚠️ Pull failed: $img"
  fi
done

if [ -z "$RUNTIME_IMAGE" ]; then
  echo "❌ No runtime image available."
  exit 1
fi
echo "🐳 Runtime image selected: $RUNTIME_IMAGE"

#############################################
# 5) Determine nginx networks (关键修复点)
#############################################
NGINX_NETS="$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{print $k " "}}{{end}}' nginx)"
if [ -z "${NGINX_NETS:-}" ]; then
  echo "❌ Cannot detect nginx networks."
  exit 1
fi
echo "🌐 Nginx networks: $NGINX_NETS"

PRIMARY_NET="$(echo "$NGINX_NETS" | awk '{print $1}')"
echo "🌐 Primary network for new agent: $PRIMARY_NET"

#############################################
# 6) Run target container & attach to all nginx networks
#############################################
echo "🧹 Stop old target container (if exists)"
docker rm -f "agent_$TARGET" >/dev/null 2>&1 || true

echo "🚀 Starting agent_$TARGET..."
docker run -d \
  --name "agent_$TARGET" \
  --network "$PRIMARY_NET" \
  --network-alias "agent_$TARGET" \
  -p "$TARGET_PORT:8080" \
  -v "$JAR_DIR/$TARGET/app.jar:/app/app.jar" \
  --restart unless-stopped \
  "$RUNTIME_IMAGE" \
  java -Xms256m -Xmx768m -jar /app/app.jar

# 补连其它 nginx network（让 nginx 一定能解析到 agent_xxx）
for net in $NGINX_NETS; do
  docker network connect "$net" "agent_$TARGET" 2>/dev/null || true
done

echo "🔎 Verify nginx can resolve agent_$TARGET ..."
docker exec nginx getent hosts "agent_$TARGET" || true

#############################################
# 7) Health check (可关闭)
#############################################
if [ "$SKIP_HEALTH" = "1" ]; then
  echo "🟨 SKIP_HEALTH=1 — skip health check (v18 safe mode)"
else
  echo "🧪 Waiting Health..."
  end=$((SECONDS + HEALTH_TIMEOUT_SEC))
  HEALTH_OK=0
  while [ $SECONDS -lt $end ]; do
    if curl -fs "http://localhost:$TARGET_PORT/actuator/health" >/dev/null 2>&1; then
      HEALTH_OK=1; break
    fi
    if curl -fs "http://localhost:$TARGET_PORT/health" >/dev/null 2>&1; then
      HEALTH_OK=1; break
    fi
    sleep 3
  done

  if [ "$HEALTH_OK" -ne 1 ]; then
    echo "❌ Health FAIL — rollback"
    docker rm -f "agent_$TARGET" >/dev/null 2>&1 || true
    exit 1
  fi
  echo "✅ Health PASS"
fi

#############################################
# 8) Switch traffic (你现有逻辑：写 upstream.conf + reload)
#############################################
echo "🔀 Switch traffic to $TARGET"

if [ "$TARGET" = "blue" ]; then
  docker exec nginx sh -c "echo 'set \$upstream http://agent_blue:8080;' > /etc/nginx/conf.d/upstream.conf"
else
  docker exec nginx sh -c "echo 'set \$upstream http://agent_green:8080;' > /etc/nginx/conf.d/upstream.conf"
fi

docker exec nginx nginx -t
docker exec nginx nginx -s reload

#############################################
# 9) Stop old slot (如果你想保留回滚能力，可注释掉)
#############################################
docker rm -f "agent_$CURRENT" >/dev/null 2>&1 || true

echo "🎉 Deploy SUCCESS — v18"