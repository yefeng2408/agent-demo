#!/usr/bin/env bash
set -e

JAR_PATH="${JAR_PATH:-/app/app.jar}"

if [ ! -f "$JAR_PATH" ]; then
  echo "❌ jar not found: $JAR_PATH"
  ls -lah /app || true
  exit 1
fi

exec java -jar "$JAR_PATH"