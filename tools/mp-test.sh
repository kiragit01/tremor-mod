#!/usr/bin/env bash
# Multiplayer test (SPEC 15, stage 5): a dedicated server on localhost and two scripted autotest clients, Alice and
# Bob, each running its own script from ./autotest (see tremor.client.dev.AutoTest; the client joins the server
# given by -Dtremor.autotest.server instead of opening a world). Everything lives under run-mp/: server/ (offline
# mode, both players op, a fixed seed) and alice/, bob/ (their game directories, with their reports in
# tremor-autotest/<time>/). The server is stopped when both clients have quit (or after the time limit).
# Usage: tools/mp-test.sh <script for Alice> <script for Bob> [time limit in seconds, default 900]
set -u
cd "$(dirname "$0")/.."
ROOT=$(pwd -W 2>/dev/null || pwd)
ALICE_SCRIPT=${1:?script for Alice}
BOB_SCRIPT=${2:?script for Bob}
LIMIT=${3:-900}

tools/gradle-locked.sh compileJava processResources prepareServerRun prepareClientAutotestRun -q || exit 1
rm -rf run-mp/server/world
MODS="-Dfml.modFolders=tremor%%$ROOT/build/classes/java/main;tremor%%$ROOT/build/resources/main"
MD="$ROOT/build/moddev"
# Gradle passes the game's classpath on the command line; it is kept in build/moddev/serverClasspathArg.txt
# ("-cp <entries>") and clientClasspathArg.txt, taken once from the command lines of ./gradlew runServer and
# runClientAutotest (the same entries, but the client needs its own order).
CP="$MD/serverClasspathArg.txt"
CCP="$MD/clientClasspathArg.txt"
[ -f "$CP" ] && [ -f "$CCP" ] || { echo "[mp-test] $CP or $CCP is missing: copy the -cp of a runServer and of a runClientAutotest java command line into them"; exit 1; }
MAIN=net.neoforged.devlaunch.Main

mkdir -p run-mp/server run-mp/alice run-mp/bob
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
motd=tremor mp test
PROPS
python - <<'PY'
import hashlib, json, uuid
def offline(name):
    h = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode()).digest())
    h[6] = h[6] & 0x0f | 0x30
    h[8] = h[8] & 0x3f | 0x80
    return str(uuid.UUID(bytes=bytes(h)))
ops = [{"uuid": offline(n), "name": n, "level": 4, "bypassesPlayerLimit": False} for n in ("Alice", "Bob")]
open("run-mp/server/ops.json", "w").write(json.dumps(ops, indent=2))
PY

echo "[mp-test] starting the server"
(cd run-mp/server && exec java "$MODS" @"$MD/serverRunVmArgs.txt" @"$CP" $MAIN @"$MD/serverRunProgramArgs.txt" > server.out 2>&1) &
SERVER=$!
for i in $(seq 1 180); do
  grep -q "Done (" run-mp/server/server.out 2>/dev/null && break
  sleep 2
done
grep -q "Done (" run-mp/server/server.out || { echo "[mp-test] the server did not start"; kill $SERVER; exit 1; }

client() {
  local name=$1 script=$2
  (cd "run-mp/$name" && exec java "$MODS" @"$MD/clientAutotestRunVmArgs.txt" \
      -Dtremor.autotest="$ROOT/autotest/$script" -Dtremor.autotest.server=localhost:25565 \
      @"$CCP" $MAIN @"$MD/clientAutotestRunProgramArgs.txt" --username "${name^}" > client.out 2>&1) &
}
echo "[mp-test] starting Alice ($ALICE_SCRIPT) and Bob ($BOB_SCRIPT)"
client alice "$ALICE_SCRIPT"; ALICE=$!
sleep 20
client bob "$BOB_SCRIPT"; BOB=$!

waited=0
while kill -0 $ALICE 2>/dev/null || kill -0 $BOB 2>/dev/null; do
  sleep 5; waited=$((waited + 5))
  if [ $waited -ge "$LIMIT" ]; then echo "[mp-test] time limit"; kill $ALICE $BOB 2>/dev/null; break; fi
done
sleep 5
kill $SERVER 2>/dev/null
echo "[mp-test] done; reports:"
ls -1d run-mp/alice/tremor-autotest/* run-mp/bob/tremor-autotest/* 2>/dev/null | tail -2
