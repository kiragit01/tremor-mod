#!/usr/bin/env bash
# Lag test: a dedicated server on localhost (as tools/mp-test.sh: run-mp/server, offline mode, the player op) and one
# scripted autotest client, Alice, that joins it through tools/lag-proxy.py, every packet delayed both ways, with the
# client's watch on (-Dtremor.autotest.watch: the camera turned and the player moved by the server are reported).
# The report is in run-mp/alice/tremor-autotest. Only main.
# Usage: tools/lag-test.sh <script> [delay ms each way, default 120] [time limit in seconds, default 600]
set -u
cd "$(dirname "$0")/.."
ROOT=$(pwd -W 2>/dev/null || pwd)
SCRIPT=${1:?script}
DELAY=${2:-120}
LIMIT=${3:-600}

tools/gradle-locked.sh compileJava processResources prepareServerRun prepareClientAutotestRun -q || exit 1
rm -rf run-mp/server/world
MODS="-Dfml.modFolders=tremor%%$ROOT/build/classes/java/main;tremor%%$ROOT/build/resources/main"
MD="$ROOT/build/moddev"
CP="$MD/serverClasspathArg.txt"
CCP="$MD/clientClasspathArg.txt"
[ -f "$CP" ] && [ -f "$CCP" ] || { echo "[lag-test] $CP or $CCP is missing (see tools/mp-test.sh)"; exit 1; }
MAIN=net.neoforged.devlaunch.Main

mkdir -p run-mp/server run-mp/alice
echo "eula=true" > run-mp/server/eula.txt
cat > run-mp/server/server.properties <<'PROPS'
online-mode=false
level-seed=20261004
gamemode=survival
difficulty=peaceful
spawn-protection=0
allow-flight=true
view-distance=8
simulation-distance=8
motd=tremor lag test
PROPS
python - <<'PY'
import hashlib, json, uuid
h = bytearray(hashlib.md5(b"OfflinePlayer:Alice").digest())
h[6] = h[6] & 0x0f | 0x30
h[8] = h[8] & 0x3f | 0x80
ops = [{"uuid": str(uuid.UUID(bytes=bytes(h))), "name": "Alice", "level": 4, "bypassesPlayerLimit": False}]
open("run-mp/server/ops.json", "w").write(json.dumps(ops, indent=2))
PY

echo "[lag-test] starting the server"
(cd run-mp/server && exec java "$MODS" @"$MD/serverRunVmArgs.txt" @"$CP" $MAIN @"$MD/serverRunProgramArgs.txt" > server.out 2>&1) &
SERVER=$!
for i in $(seq 1 180); do
  grep -q "Done (" run-mp/server/server.out 2>/dev/null && break
  sleep 2
done
grep -q "Done (" run-mp/server/server.out || { echo "[lag-test] the server did not start"; kill $SERVER; exit 1; }
python -I tools/lag-proxy.py 25566 127.0.0.1 25565 "$DELAY" > run-mp/proxy.out 2>&1 &
PROXY=$!
sleep 1

echo "[lag-test] starting Alice ($SCRIPT), $DELAY ms each way"
(cd run-mp/alice && exec java "$MODS" @"$MD/clientAutotestRunVmArgs.txt" -Dtremor.autotest.watch=true \
    -Dtremor.autotest="$ROOT/autotest/$SCRIPT" -Dtremor.autotest.server=127.0.0.1:25566 \
    @"$CCP" $MAIN @"$MD/clientAutotestRunProgramArgs.txt" --username Alice > client.out 2>&1) &
ALICE=$!

waited=0
while kill -0 $ALICE 2>/dev/null; do
  sleep 5; waited=$((waited + 5))
  if [ $waited -ge "$LIMIT" ]; then echo "[lag-test] time limit"; kill $ALICE 2>/dev/null; break; fi
done
sleep 5
kill $SERVER $PROXY 2>/dev/null
echo "[lag-test] done; report:"
ls -1d run-mp/alice/tremor-autotest/* 2>/dev/null | tail -1
