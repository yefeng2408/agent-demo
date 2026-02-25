#!/usr/bin/env bash
set -e

echo "🧬 DeepMind BlueGreen Deploy v3"

# ===============================
# 1️⃣ 自动检测当前 running agent
# ===============================
RUNNING=$(docker ps --format '{{.Names}}' | grep agent_ || true)

if echo "$RUNNING" | grep -q agent_blue; then
  CURRENT="blue"
  TARGET="green"
  PORT=8082
else
  CURRENT="green"
  TARGET="blue"
  PORT=8081
fi

TARGET_SERVICE="agent_${TARGET}"

echo "🟢 Current Running : $CURRENT"
echo "🚀 Deploy Target   : $TARGET"

# ===============================
# 2️⃣ 拉代码（如果存在git）
# ===============================
echo "📥 git pull..."
git pull || true

# ===============================
# 3️⃣ Build 新版本
# ===============================
echo "🔨 Building $TARGET_SERVICE ..."
docker compose up -d --build $TARGET_SERVICE

echo "⏳ wait boot..."
sleep 8

# ===============================
# 4️⃣ HealthCheck
# ===============================
echo "🧪 Health checking :$PORT"

RETRY=20

until curl -fsS http://127.0.0.1:$PORT/actuator/health | grep -q '"UP"'; do
  echo "⏳ waiting health..."
  sleep 3
  RETRY=$((RETRY-1))

  if [[ $RETRY -le 0 ]]; then
    echo "❌ Health FAIL -> rollback"
    docker compose stop $TARGET_SERVICE
    exit 1
  fi
done

echo "✅ Health OK"

# ===============================
# 5️⃣ 优雅下线旧版本
# ===============================
OLD_SERVICE="agent_${CURRENT}"

echo "🧹 Stop old version $OLD_SERVICE"
docker compose stop $OLD_SERVICE || true

echo "🎉 Deploy SUCCESS"