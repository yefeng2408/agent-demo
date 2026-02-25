#!/usr/bin/env bash
set -e

#############################################
# Agent Deploy — Stable LTS FINAL
# Rootless + ZeroBuild + Auto Artifact Pull
#############################################

APP_NAME="agent"
DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JAR_DIR="$DEPLOY_DIR/deploy/jars"

BLUE_PORT=8081
GREEN_PORT=8082

RELEASE_URL="https://gitee.com/yf123456/agent-demo/releases/download/latest/app.jar"

LOCK_FILE="/tmp/agent-stack-deploy.lock"

#############################################
echo "🧠 Agent Stable LTS FINAL Deploy"
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

if docker ps | grep agent_blue >/dev/null ; then
  CURRENT="blue"
fi

if docker ps | grep agent_green >/dev/null ; then
  CURRENT="green"
fi

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
# 🧠 Pull Artifact
#############################################

echo "📦 Pull latest app.jar from release..."

curl -L -o "$JAR_DIR/$TARGET/app.jar" "$RELEASE_URL"

#############################################
# 🧠 Stop old target container (if exists)
#############################################

docker rm -f agent_$TARGET >/dev/null 2>&1 || true

#############################################
# 🧠 Run container (Zero Build Runtime)
#############################################

echo "🐳 Starting agent_$TARGET..."

docker run -d \
  --name agent_$TARGET \
  -p $TARGET_PORT:8080 \
  -v "$JAR_DIR/$TARGET/app.jar:/app/app.jar" \
  eclipse-temurin:21-jre \
  java -Xms256m -Xmx768m -jar /app/app.jar

#############################################
# 🧠 Smart Health Detect
#############################################

echo "🧪 Waiting Health..."

HEALTH_OK=0

for i in {1..20}; do
  sleep 3

  if curl -fs http://localhost:$TARGET_PORT/actuator/health >/dev/null ; then
      HEALTH_OK=1
      break
  fi

  if curl -fs http://localhost:$TARGET_PORT/health >/dev/null ; then
      HEALTH_OK=1
      break
  fi

done

#############################################
# 🧠 Health Result
#############################################

if [ "$HEALTH_OK" -ne 1 ]; then
   echo "❌ Health FAIL — rollback"
   docker rm -f agent_$TARGET || true
   exit 1
fi

echo "✅ Health PASS"

#############################################
# 🧠 Switch Traffic (Nginx Weight Switch)
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

docker rm -f agent_$CURRENT >/dev/null 2>&1 || true

echo "🎉 Deploy SUCCESS — Stable LTS FINAL"