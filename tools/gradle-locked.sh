#!/usr/bin/env bash
# Runs ./gradlew with a project-wide lock, so that several automated agents working in this checkout at the same time
# never run Gradle concurrently (they would fight over build/ outputs). Usage: tools/gradle-locked.sh <gradle args>
set -u
cd "$(dirname "$0")/.."
LOCK=build/.gradle-agent-lock
mkdir -p build
waited=0
until mkdir "$LOCK" 2>/dev/null; do
  # Break locks older than 20 minutes (a crashed holder).
  if [ -d "$LOCK" ] && [ $(( $(date +%s) - $(stat -c %Y "$LOCK" 2>/dev/null || echo 0) )) -gt 1200 ]; then
    rmdir "$LOCK" 2>/dev/null
    continue
  fi
  sleep 3
  waited=$((waited + 3))
  if [ $((waited % 60)) -eq 0 ]; then echo "[gradle-locked] waiting for another Gradle run (${waited}s)..." >&2; fi
done
trap 'rmdir "$LOCK" 2>/dev/null' EXIT
./gradlew "$@" --console=plain
