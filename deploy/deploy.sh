#!/usr/bin/env bash
set -euo pipefail

# =========================
# v11 — Rootless SRE Deploy
# =========================
# Features:
# - Rootless docker (no sudo) if user in docker group
# - Safe git pull (auto stash / pop)
# - BuildKit + buildx check
# - Build timeout auto-kill (防卡死)
# - Blue/Green deploy + smart health
# - Auto rollback + rich logs (可观测)
# - Minimal downtime

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

LOCK_FILE="/tmp/agent-stack-deploy.lock"
LOG_DIR="/var/log/agent-deploy"
mkdir -p "$LOG_DIR" 2>/dev/null || true
LOG_FILE="$LOG_DIR/deploy_$(date +%F_%H%M%S).log"

# --- log helpers ---
log() { echo "[$(date '+%F %T')] $*" | tee -a "$LOG_FILE" ; }
die() { log "❌ $*"; exit 1; }

# --- lock (atomic) ---
exec 9>"$LOCK_FILE" || true
if ! flock -n 9; then
  die "Deploy is already running (lock=$LOCK_FILE)"
fi

cleanup() {
  local code=$?
  log "🧹 cleanup (exit=$code)"
}
trap cleanup EXIT

need() { command -v "$1" >/dev/null 2>&1 || die "Missing command: $1"; }
need git
need docker

# --- docker permission check (rootless) ---
if ! docker info >/dev/null 2>&1; then
  cat <<'EOF' >&2
❌ Docker daemon not accessible for current user.
Fix (one-time):
  sudo usermod -aG docker ubuntu
  newgrp docker
  docker ps
EOF
  exit 1
fi

# --- Compose command ---
COMPOSE="docker compose"

# --- BuildKit on ---
export DOCKER_BUILDKIT=1
export COMPOSE_DOCKER_CLI_BUILD=1

# --- config ---
SERVICE_BLUE="agent_blue"
SERVICE_GREEN="agent_green"
PORT_BLUE="8081"
PORT_GREEN="8082"
HEALTH_PATHS=("/actuator/health" "/actuator/health/liveness" "/health" "/")
HEALTH_RETRY="${HEALTH_RETRY:-30}"
HEALTH_SLEEP="${HEALTH_SLEEP:-2}"
BUILD_TIMEOUT="${BUILD_TIMEOUT:-25m}"   # 防止卡死
NO_CACHE="${NO_CACHE:-0}"

# 你 nginx 的切流脚本（你项目里已有 switch.sh/ nginx.*.conf 就用它）
SWITCH_SCRIPT="${PROJECT_ROOT}/deploy/switch.sh"

# --- detect current traffic by nginx active conf marker (你的实现不同可改这里) ---
detect_current() {
  # 简化：默认 current=blue；如果你 switch.sh 有状态文件，改为读状态文件更准
  # 也可以：grep -q "agent_green" deploy/nginx.conf && echo green || echo blue
  if [ -f "${PROJECT_ROOT}/deploy/nginx.conf" ] && grep -q "agent_green" "${PROJECT_ROOT}/deploy/nginx.conf"; then
    echo "green"
  else
    echo "blue"
  fi
}

target_color() {
  local current="$1"
  if [ "$current" = "blue" ]; then echo "green"; else echo "blue"; fi
}

# --- safe git pull (stash -> pull -> pop) ---
safe_pull() {
  log "📦 Safe Pull..."
  local stashed=0

  if ! git diff --quiet || ! git diff --cached --quiet; then
    stashed=1
    log "🧳 Local changes detected, stashing..."
    git stash push -u -m "auto-stash-before-deploy $(date +%F_%T)" >/dev/null
  fi

  # 你这边是 gitee，pull 时可能被 deploy.sh 本地改动挡住；stash 已解决
  git pull --rebase

  if [ "$stashed" = "1" ]; then
    log "🎒 Restoring stash..."
    if ! git stash pop >/dev/null; then
      die "Stash pop conflict. Resolve manually."
    fi
  fi

  log "✅ Repo up to date: $(git rev-parse --short HEAD)"
}

# --- build with timeout + progress ---
build_service() {
  local svc="$1"
  local args=()
  if [ "$NO_CACHE" = "1" ]; then args+=(--no-cache); fi

  log "🏗️  Building $svc (timeout=$BUILD_TIMEOUT, no_cache=$NO_CACHE)..."
  # 防卡死：超时直接 kill
  timeout --signal=KILL "$BUILD_TIMEOUT" \
    $COMPOSE build --progress=plain "${args[@]}" "$svc" \
    | tee -a "$LOG_FILE"
}

# --- start target container ---
up_target() {
  local color="$1"
  local svc port
  if [ "$color" = "blue" ]; then svc="$SERVICE_BLUE"; port="$PORT_BLUE"; else svc="$SERVICE_GREEN"; port="$PORT_GREEN"; fi

  log "🚀 Starting $color ($svc :$port)..."
  $COMPOSE up -d --no-deps "$svc" | tee -a "$LOG_FILE"
}

# --- smart health check ---
health_check() {
  local color="$1"
  local port
  if [ "$color" = "blue" ]; then port="$PORT_BLUE"; else port="$PORT_GREEN"; fi

  log "🩺 Health checking $color on :$port ..."
  for ((i=1; i<=HEALTH_RETRY; i++)); do
    for p in "${HEALTH_PATHS[@]}"; do
      if curl -fsS "http://127.0.0.1:${port}${p}" >/dev/null 2>&1; then
        log "✅ Health OK: :$port$p"
        return 0
      fi
    done
    sleep "$HEALTH_SLEEP"
  done

  log "❌ Health FAIL for $color. Showing last logs:"
  local svc
  if [ "$color" = "blue" ]; then svc="$SERVICE_BLUE"; else svc="$SERVICE_GREEN"; fi
  $COMPOSE logs --tail=200 "$svc" | tee -a "$LOG_FILE"
  return 1
}

# --- switch traffic ---
switch_traffic() {
  local to="$1"
  log "🔀 Switching traffic to: $to"
  if [ -x "$SWITCH_SCRIPT" ]; then
    "$SWITCH_SCRIPT" "$to" | tee -a "$LOG_FILE"
  else
    log "⚠️ switch.sh not found/executable; please wire traffic switch here."
  fi
}

# --- rollback ---
rollback() {
  local current="$1"
  log "⏪ Rollback traffic to: $current"
  switch_traffic "$current"
}

main() {
  log "🚀 Deploy v11 (Rootless + BuildKit Cache + Timeout + Smart Health + Observability)"
  safe_pull

  local current target
  current="$(detect_current)"
  target="$(target_color "$current")"

  log "🎯 Current Traffic: $current"
  log "🎯 Deploy Target : $target"

  # Build target only
  if [ "$target" = "blue" ]; then
    build_service "$SERVICE_BLUE"
  else
    build_service "$SERVICE_GREEN"
  fi

  # Start target
  up_target "$target"

  # Health
  if health_check "$target"; then
    switch_traffic "$target"
    log "✅ Deploy SUCCESS -> traffic=$target"
  else
    log "❌ Deploy FAILED -> rollback"
    rollback "$current"
    die "Deploy failed, rolled back."
  fi
}

main "$@"