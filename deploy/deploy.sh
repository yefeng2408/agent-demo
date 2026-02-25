#!/usr/bin/env bash
set -euo pipefail

#############################################
# Agent Deploy — v17 CN Mirror Turbo
# Blue/Green + ZeroBuild + Auto Release Pull
#############################################

APP_NAME="agent"
DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JAR_DIR="$DEPLOY_DIR/deploy/jars"

BLUE_PORT="${BLUE_PORT:-8081}"
GREEN_PORT="${GREEN_PORT:-8082}"

# ====== Repo config (GitHub) ======
GITHUB_OWNER="${GITHUB_OWNER:-yefeng2408}"
GITHUB_REPO="${GITHUB_REPO:-agent-demo}"
GITHUB_API="https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/releases/latest"

# ====== Download behavior ======
# 0 = 强制直连 GitHub
# 1 = 国内代理优先（默认）
CN_MIRROR_FIRST="${CN_MIRROR_FIRST:-1}"

# 国内代理列表（会自动探活，能用哪个用哪个）
# 说明：这些代理网站稳定性随时间波动，所以做成自动探活 + fallback
MIRROR_PREFIXES=(
  "https://ghproxy.com/"
  "https://mirror.ghproxy.com/"
  "https://github.moeyy.xyz/"
  "https://gh.api.99988866.xyz/"
)

# ====== Docker runtime image (JRE) ======
# 你可以自己 export RUNTIME_IMAGE=xxx 来覆盖
RUNTIME_IMAGE="${RUNTIME_IMAGE:-eclipse-temurin:21-jre}"

# 国内镜像（不保证每个都存在，所以会逐个尝试）
# Tencent: ccr.ccs.tencentyun.com
# Aliyun : registry.cn-hangzhou.aliyuncs.com
RUNTIME_IMAGE_CANDIDATES=(
  "ccr.ccs.tencentyun.com/library/eclipse-temurin:21-jre"
  "registry.cn-hangzhou.aliyuncs.com/library/eclipse-temurin:21-jre"
  "eclipse-temurin:21-jre"
)

LOCK_FILE="/tmp/agent-stack-deploy.lock"

# ====== Health check ======
# 0 = 正常检查
# 1 = 跳过（默认，避免你现在这种“启动成功但误判回滚”）
SKIP_HEALTH="${SKIP_HEALTH:-1}"
HEALTH_TIMEOUT_SEC="${HEALTH_TIMEOUT_SEC:-60}"
HEALTH_INTERVAL_SEC="${HEALTH_INTERVAL_SEC:-3}"
KEEP_FAILED_CONTAINER="${KEEP_FAILED_CONTAINER:-1}"

#############################################
echo "🧠 Agent Deploy v17 — CN Mirror Turbo"
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
# 🧠 Detect current slot
#############################################
CURRENT="green"
if docker ps | grep -q "agent_blue" ; then CURRENT="blue"; fi
if docker ps | grep -q "agent_green" ; then CURRENT="green"; fi
echo "🎯 Current Traffic : $CURRENT"

#############################################
# 🧠 Decide target
#############################################
if [ "$CURRENT" = "blue" ]; then
  TARGET="green"
  TARGET_PORT=$GREEN_PORT
else
  TARGET="blue"
  TARGET_PORT=$BLUE_PORT
fi
echo "🚀 Deploy Target : $TARGET ($TARGET_PORT)"

mkdir -p "$JAR_DIR/$TARGET"

#############################################
# Helpers
#############################################
have_cmd() { command -v "$1" >/dev/null 2>&1; }

curl_head_ok() {
  local url="$1"
  curl -fsSIL --connect-timeout 3 --max-time 6 "$url" >/dev/null 2>&1
}

download_with_curl() {
  local url="$1"
  local out="$2"
  # -C - 支持断点续传
  curl -fL --connect-timeout 8 --max-time 0 \
    --retry 6 --retry-delay 2 --retry-all-errors \
    -C - -o "$out" "$url"
}

json_get_jar_url() {
  # 优先 app.jar，其次第一个 .jar
  # 用 python3 解析，避免依赖 jq
  python3 - "$@" <<'PY'
import json,sys
data=json.load(sys.stdin)
assets=data.get("assets") or []
# 1) app.jar
for a in assets:
    name=a.get("name","")
    if name=="app.jar":
        print(a.get("browser_download_url",""))
        sys.exit(0)
# 2) first .jar
for a in assets:
    name=a.get("name","")
    if name.endswith(".jar"):
        print(a.get("browser_download_url",""))
        sys.exit(0)
print("")
PY
}

choose_runtime_image() {

  # ⚠️ stdout 只能输出最终镜像
  # 日志必须 >&2

  for img in "${RUNTIME_IMAGE_CANDIDATES[@]}"; do
    echo "🐳 Pull runtime image try: $img" >&2

    if docker pull "$img" >/dev/null 2>&1; then
      echo "$img"
      return 0
    fi

    echo "⚠️ Pull failed: $img" >&2
  done

  # fallback
  echo "$RUNTIME_IMAGE"
}

#############################################
# 🧠 Resolve latest jar from GitHub release
#############################################
echo "🔎 Resolve latest release via GitHub API..."
if ! have_cmd python3; then
  echo "❌ python3 not found. Please install: sudo apt-get update && sudo apt-get install -y python3"
  exit 1
fi

release_json="$(curl -fsSL --connect-timeout 8 --max-time 15 --retry 5 --retry-delay 2 --retry-all-errors "$GITHUB_API")"
JAR_URL="$(printf "%s" "$release_json" | python3 -c "import sys,json; data=json.load(sys.stdin); assets=data.get('assets') or [];
print(next((a.get('browser_download_url','') for a in assets if a.get('name')=='app.jar'), next((a.get('browser_download_url','') for a in assets if (a.get('name','').endswith('.jar'))), '')) )")"

if [ -z "${JAR_URL:-}" ]; then
  echo "❌ No jar asset found in latest release."
  echo "   Tip: upload app.jar to GitHub Release assets."
  exit 1
fi

echo "🏷️ Latest tag: latest"
echo "🧩 Jar asset: $JAR_URL"

#############################################
# 🧠 Pull Artifact (CN mirror first + fallback)
#############################################
echo "📦 Pull latest jar from release..."

OUT_JAR="$JAR_DIR/$TARGET/app.jar"
rm -f "$OUT_JAR.tmp" || true

# 1) CN mirror candidates
if [ "$CN_MIRROR_FIRST" = "1" ]; then
  for prefix in "${MIRROR_PREFIXES[@]}"; do
    mirror_url="${prefix}${JAR_URL}"
    echo "⬇️ Trying: $mirror_url"

    # 先探活，避免你这种 443 timeout 卡太久
    if ! curl_head_ok "$mirror_url"; then
      echo "⚠️ Mirror not reachable, skip."
      continue
    fi

    if download_with_curl "$mirror_url" "$OUT_JAR.tmp"; then
      mv "$OUT_JAR.tmp" "$OUT_JAR"
      echo "✅ Downloaded via mirror."
      break
    else
      echo "⚠️ Mirror download failed, try next."
      rm -f "$OUT_JAR.tmp" || true
    fi
  done
fi

# 2) direct GitHub fallback
if [ ! -f "$OUT_JAR" ]; then
  echo "⬇️ Fallback direct: $JAR_URL"
  download_with_curl "$JAR_URL" "$OUT_JAR.tmp"
  mv "$OUT_JAR.tmp" "$OUT_JAR"
  echo "✅ Downloaded via direct GitHub."
fi

# basic verify
if [ ! -s "$OUT_JAR" ]; then
  echo "❌ Downloaded jar is empty."
  exit 1
fi

echo "📦 Jar ready: $OUT_JAR ($(du -h "$OUT_JAR" | awk '{print $1}'))"

#############################################
# 🧠 Stop old target container (if exists)
#############################################
docker rm -f "agent_$TARGET" >/dev/null 2>&1 || true

#############################################
# 🧠 Choose runtime image (CN mirror first)
#############################################
RUNTIME_FINAL="$(choose_runtime_image)"
echo "🐳 Runtime image selected: $RUNTIME_FINAL"

#############################################
# 🧠 Run container (Zero Build Runtime)
#############################################
echo "🐳 Starting agent_$TARGET..."

docker run -d \
  --name "agent_$TARGET" \
  -p "$TARGET_PORT:8080" \
  -v "$OUT_JAR:/app/app.jar" \
  "$RUNTIME_FINAL" \
  java -Xms256m -Xmx768m -jar /app/app.jar

#############################################
# 🧠 Health Detect (v17 safe)
#############################################
if [ "$SKIP_HEALTH" = "1" ]; then
  echo "🟨 SKIP_HEALTH=1 — skip health check (v17 safe mode)"
  sleep 5
else
  echo "🧪 Waiting Health..."
  HEALTH_OK=0
  deadline=$(( $(date +%s) + HEALTH_TIMEOUT_SEC ))

  while [ "$(date +%s)" -lt "$deadline" ]; do
    if curl -fs "http://localhost:$TARGET_PORT/actuator/health" >/dev/null 2>&1; then
      HEALTH_OK=1; break
    fi
    if curl -fs "http://localhost:$TARGET_PORT/health" >/dev/null 2>&1; then
      HEALTH_OK=1; break
    fi
    if curl -sS --connect-timeout 1 "http://localhost:$TARGET_PORT/" >/dev/null 2>&1; then
      echo "🟡 Minimal port check PASS"
      HEALTH_OK=1; break
    fi
    sleep "$HEALTH_INTERVAL_SEC"
  done

  if [ "$HEALTH_OK" -ne 1 ]; then
    echo "❌ Health FAIL — show logs"
    docker logs --tail 200 "agent_$TARGET" || true
    if [ "$KEEP_FAILED_CONTAINER" = "1" ]; then
      echo "🧷 KEEP_FAILED_CONTAINER=1 — container kept"
      exit 1
    fi
    docker rm -f "agent_$TARGET" || true
    exit 1
  fi
  echo "✅ Health PASS"
fi

#############################################
# 🧠 Switch Traffic (Nginx upstream switch)
#############################################
echo "🔀 Switch traffic to $TARGET"

if [ "$TARGET" = "blue" ]; then
  docker exec nginx sh -c "echo 'set \$upstream http://agent_blue:8080;' > /etc/nginx/conf.d/upstream.conf"
else
  docker exec nginx sh -c "echo 'set \$upstream http://agent_green:8080;' > /etc/nginx/conf.d/upstream.conf"
fi

docker exec nginx nginx -s reload

#############################################
# 🧠 Stop old slot
#############################################
docker rm -f "agent_$CURRENT" >/dev/null 2>&1 || true

echo "🎉 Deploy SUCCESS — v17 CN Mirror Turbo"