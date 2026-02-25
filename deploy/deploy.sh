#!/usr/bin/env bash
set -euo pipefail

#############################################
# Agent Deploy — v16 (CN Mirror + Fallback)
# Rootless + ZeroBuild + Auto Release Pull
#############################################

APP_NAME="${APP_NAME:-agent}"
DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JAR_DIR="$DEPLOY_DIR/deploy/jars"

BLUE_PORT="${BLUE_PORT:-8081}"
GREEN_PORT="${GREEN_PORT:-8082}"

# GitHub repo info
GITHUB_OWNER="${GITHUB_OWNER:-yefeng2408}"
GITHUB_REPO="${GITHUB_REPO:-agent-demo}"

# Optional: GitHub token to avoid rate-limit (recommended)
# export GITHUB_TOKEN=xxxxx
GITHUB_TOKEN="${GITHUB_TOKEN:-}"

# Mirror strategy
# MIRROR_MODE:
#   cn   -> mirror first, then raw
#   raw  -> raw first, then mirror
MIRROR_MODE="${MIRROR_MODE:-cn}"

# If health endpoint returns 401/403, treat as PASS to avoid false rollback
HEALTH_ALLOW_PROTECTED="${HEALTH_ALLOW_PROTECTED:-1}"

# Keep failed container for debugging (1 keep, 0 remove)
KEEP_FAILED_CONTAINER="${KEEP_FAILED_CONTAINER:-1}"

# How long to wait for health (seconds)
HEALTH_TIMEOUT_SEC="${HEALTH_TIMEOUT_SEC:-180}"
HEALTH_INTERVAL_SEC="${HEALTH_INTERVAL_SEC:-3}"

LOCK_FILE="${LOCK_FILE:-/tmp/agent-stack-deploy.lock}"

#############################################
echo "🧠 Agent Deploy v16"
echo "📦 Zero Build Mode"
echo "🔐 Rootless Mode"
echo "--------------------------------"
echo "📌 Repo: ${GITHUB_OWNER}/${GITHUB_REPO}"
echo "🌐 MIRROR_MODE=${MIRROR_MODE}"
echo "🩺 HEALTH_TIMEOUT_SEC=${HEALTH_TIMEOUT_SEC}  INTERVAL=${HEALTH_INTERVAL_SEC}"
echo "--------------------------------"

#############################################
# 🔒 Lock
#############################################
if [ -f "$LOCK_FILE" ]; then
  echo "❌ Another deploy is running. lock=$LOCK_FILE"
  exit 1
fi
touch "$LOCK_FILE"
trap 'rm -f "$LOCK_FILE"' EXIT

#############################################
# Helpers
#############################################

log() { echo -e "$*"; }

curl_common_args=(
  -sS
  --connect-timeout 10
  --max-time 30
)

auth_header_args=()
if [ -n "$GITHUB_TOKEN" ]; then
  auth_header_args+=( -H "Authorization: Bearer $GITHUB_TOKEN" )
fi

http_code() {
  # usage: http_code URL
  curl -o /dev/null -w "%{http_code}" "${curl_common_args[@]}" "$1" || echo "000"
}

download_one() {
  # usage: download_one URL OUTFILE
  local url="$1"
  local outfile="$2"
  log "⬇️  Trying: $url"
  # -f: fail on non-2xx; -L: follow redirects; -C -: resume
  if curl -fL \
      --connect-timeout 10 \
      --max-time 1200 \
      --retry 8 \
      --retry-delay 3 \
      --retry-connrefused \
      -C - \
      -o "$outfile" \
      "$url"; then
    return 0
  fi
  return 1
}

# Mirrors that work by prefixing the full GitHub URL
MIRROR_PREFIXES=(
  "https://ghproxy.com/"
  "https://mirror.ghproxy.com/"
  "https://ghfast.top/"
)

build_candidates() {
  # usage: build_candidates RAW_URL
  local raw="$1"
  local out=()

  if [ "$MIRROR_MODE" = "raw" ]; then
    out+=( "$raw" )
    for p in "${MIRROR_PREFIXES[@]}"; do out+=( "${p}${raw}" ); done
  else
    # cn: mirror first
    for p in "${MIRROR_PREFIXES[@]}"; do out+=( "${p}${raw}" ); done
    out+=( "$raw" )
  fi

  printf "%s\n" "${out[@]}"
}

download_with_fallback() {
  # usage: download_with_fallback RAW_URL OUTFILE
  local raw="$1"
  local outfile="$2"

  mapfile -t candidates < <(build_candidates "$raw")

  for u in "${candidates[@]}"; do
    if download_one "$u" "$outfile"; then
      log "✅ Downloaded: $outfile"
      return 0
    else
      log "⚠️  Failed: $u"
    fi
  done

  log "❌ All download candidates failed."
  return 1
}

github_latest_release_json() {
  local api="https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/releases/latest"
  curl "${curl_common_args[@]}" "${auth_header_args[@]}" "$api"
}

extract_tag_name() {
  # usage: extract_tag_name JSON
  # naive parse without jq
  echo "$1" | grep -oE '"tag_name"\s*:\s*"[^"]+"' | head -n1 | sed -E 's/.*"([^"]+)".*/\1/'
}

extract_jar_url() {
  # usage: extract_jar_url JSON
  # Prefer app.jar, else first .jar asset
  local json="$1"

  local appjar
  appjar="$(echo "$json" | grep -oE '"browser_download_url"\s*:\s*"[^"]+app\.jar"' | head -n1 | sed -E 's/.*"([^"]+)".*/\1/')"
  if [ -n "$appjar" ]; then
    echo "$appjar"
    return 0
  fi

  local anyjar
  anyjar="$(echo "$json" | grep -oE '"browser_download_url"\s*:\s*"[^"]+\.jar"' | head -n1 | sed -E 's/.*"([^"]+)".*/\1/')"
  if [ -n "$anyjar" ]; then
    echo "$anyjar"
    return 0
  fi

  return 1
}

health_probe_once() {
  # usage: health_probe_once PORT
  # return codes:
  #   0 -> PASS
  #   2 -> PROTECTED (401/403)
  #   1 -> FAIL
  local port="$1"

  local urls=(
    "http://localhost:${port}/actuator/health"
    "http://localhost:${port}/actuator/health/liveness"
    "http://localhost:${port}/actuator/health/readiness"
    "http://localhost:${port}/health"
    "http://localhost:${port}/"
  )

  local protected_hit=0
  for u in "${urls[@]}"; do
    local code
    code="$(http_code "$u")"

    if [ "$code" = "200" ]; then
      return 0
    fi

    if [ "$code" = "401" ] || [ "$code" = "403" ]; then
      protected_hit=1
      continue
    fi

    # 3xx / 404 / 5xx / 000 -> try next
  done

  if [ "$protected_hit" = "1" ]; then
    return 2
  fi

  return 1
}

#############################################
# 🧠 Detect current slot
#############################################

CURRENT="green"
if docker ps | grep -q "agent_blue" >/dev/null 2>&1; then CURRENT="blue"; fi
if docker ps | grep -q "agent_green" >/dev/null 2>&1; then CURRENT="green"; fi
log "🎯 Current Traffic : $CURRENT"

#############################################
# 🧠 Decide target
#############################################

if [ "$CURRENT" = "blue" ]; then
  TARGET="green"
  TARGET_PORT="$GREEN_PORT"
else
  TARGET="blue"
  TARGET_PORT="$BLUE_PORT"
fi
log "🚀 Deploy Target : $TARGET ($TARGET_PORT)"

mkdir -p "$JAR_DIR/$TARGET"

#############################################
# ✅ Auto read latest release + auto detect jar
#############################################

log "🔎 Fetching latest release info..."
release_json="$(github_latest_release_json || true)"

if [ -z "$release_json" ]; then
  log "❌ Failed to fetch GitHub release JSON (empty)."
  log "   Tip: export GITHUB_TOKEN=... to avoid rate-limit."
  exit 1
fi

tag="$(extract_tag_name "$release_json" || true)"
jar_raw_url="$(extract_jar_url "$release_json" || true)"

if [ -z "$jar_raw_url" ]; then
  log "❌ No .jar asset found in latest release!"
  log "   Please ensure release assets include app.jar (or any .jar)."
  exit 1
fi

log "🏷️  Latest tag: ${tag:-<unknown>}"
log "🧩 Jar asset:  $jar_raw_url"

#############################################
# ✅ Auto mirror first + fallback download
#############################################

log "📦 Pull latest jar from release..."
outfile="$JAR_DIR/$TARGET/app.jar"

# Download to temp first, then move (avoid partial corrupt)
tmpfile="${outfile}.downloading"
rm -f "$tmpfile" || true

download_with_fallback "$jar_raw_url" "$tmpfile"

mv -f "$tmpfile" "$outfile"
log "📦 Jar ready: $outfile ($(du -h "$outfile" | awk '{print $1}'))"

#############################################
# 🧠 Stop old target container (if exists)
#############################################
docker rm -f "agent_${TARGET}" >/dev/null 2>&1 || true

#############################################
# 🧠 Run container (Zero Build Runtime)
#############################################

log "🐳 Starting agent_${TARGET}..."

docker run -d \
  --name "agent_${TARGET}" \
  -p "${TARGET_PORT}:8080" \
  -v "$outfile:/app/app.jar" \
  eclipse-temurin:21-jre \
  java -Xms256m -Xmx768m -jar /app/app.jar >/dev/null

#############################################
# 🧠 Health Detect (robust)
#############################################

log "🧪 Waiting Health..."

deadline=$(( $(date +%s) + HEALTH_TIMEOUT_SEC ))
health_status=1

while [ "$(date +%s)" -lt "$deadline" ]; do
  if health_probe_once "$TARGET_PORT"; then
    health_status=0
    break
  else
    rc=$?
    if [ "$rc" = "2" ] && [ "$HEALTH_ALLOW_PROTECTED" = "1" ]; then
      log "🟡 Health endpoint protected (401/403), treat as PASS (HEALTH_ALLOW_PROTECTED=1)"
      health_status=0
      break
    fi
  fi

  sleep "$HEALTH_INTERVAL_SEC"
done

if [ "$health_status" -ne 0 ]; then
  log "❌ Health FAIL — show logs (last 200 lines)"
  docker logs --tail 200 "agent_${TARGET}" || true

  if [ "$KEEP_FAILED_CONTAINER" = "1" ]; then
    log "🧷 KEEP_FAILED_CONTAINER=1, container kept: agent_${TARGET}"
    exit 1
  fi

  log "↩️ rollback: remove failed container"
  docker rm -f "agent_${TARGET}" || true
  exit 1
fi

log "✅ Health PASS"

#############################################
# 🧠 Switch Traffic (Nginx upstream switch)
#############################################

log "🔀 Switch traffic to $TARGET"

if [ "$TARGET" = "blue" ]; then
  docker exec nginx sh -c "echo 'set \$upstream http://agent_blue:8080;' > /etc/nginx/conf.d/upstream.conf"
else
  docker exec nginx sh -c "echo 'set \$upstream http://agent_green:8080;' > /etc/nginx/conf.d/upstream.conf"
fi

docker exec nginx nginx -s reload

#############################################
# 🧠 Stop old slot
#############################################
docker rm -f "agent_${CURRENT}" >/dev/null 2>&1 || true

log "🎉 Deploy SUCCESS — v16"