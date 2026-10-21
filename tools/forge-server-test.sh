#!/usr/bin/env bash
# Release check of the Forge jar on a real Forge 1.20.1 dedicated server, as players run it (SRG names, no client
# classes, the jar as built, not the classes of a dev run): the server starts with the mod, the hollow dimension is
# there, two tremors are spawned 800 blocks apart, one lies in wait, a block of quicksand is set and read back (its
# block entity), the tremors are despawned and the server stops cleanly. Then the checks, and the errors of the log.
# The server lives under run-prod/forge-1.20.1/: installed there on the first run (the Forge installer is downloaded
# from maven.minecraftforge.net into run-prod/installer/, it fetches the vanilla server and the libraries), its world
# new each run, its log in run-prod/forge-1.20.1/logs/latest.log. Java 17 from the Gradle toolchains (or $JAVA17).
# Build the jar first on the forge-1.20.1 branch (./gradlew build): it stays in build/libs on either branch.
# Usage: tools/forge-server-test.sh [mod version, default mod_version of gradle.properties]
# TREMOR_EXTRA_MODS="a.jar b.jar" puts more mods in with it (Simple Voice Chat: its plugin must then load).
set -u
cd "$(dirname "$0")/.."
ROOT=$(pwd)
FORGE=1.20.1-47.4.26
VERSION=${1:-$(sed -n 's/^mod_version=//p' gradle.properties | tr -d '\r')}
JAR=$ROOT/build/libs/tremor-forge-1.20.1-$VERSION.jar
SERVER=run-prod/forge-1.20.1
LIMIT=300
[ -f "$JAR" ] || { echo "[forge-server-test] $JAR is missing: build it on the forge-1.20.1 branch"; exit 1; }
JAVA=${JAVA17:-}
for j in ~/.gradle/jdks/*17*/bin/java ~/.gradle/jdks/*17*/bin/java.exe; do
    [ -z "$JAVA" ] && [ -x "$j" ] && JAVA=$j
done
[ -n "$JAVA" ] || { echo "[forge-server-test] no Java 17: set JAVA17 to its java"; exit 1; }
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) ARGS=win_args.txt ;;
    *) ARGS=unix_args.txt ;;
esac

if [ ! -f "$SERVER/libraries/net/minecraftforge/forge/$FORGE/$ARGS" ]; then
    INSTALLER=run-prod/installer/forge-$FORGE-installer.jar
    mkdir -p run-prod/installer "$SERVER"
    if [ ! -f "$INSTALLER" ]; then
        echo "[forge-server-test] downloading the Forge $FORGE installer"
        curl -fsSL -o "$INSTALLER" \
            "https://maven.minecraftforge.net/net/minecraftforge/forge/$FORGE/forge-$FORGE-installer.jar" || exit 1
    fi
    echo "[forge-server-test] installing the server into $SERVER"
    "$JAVA" -jar "$INSTALLER" --installServer "$SERVER" > run-prod/installer/install.log 2>&1 \
        || { echo "[forge-server-test] the install failed: see run-prod/installer/install.log"; exit 1; }
    mv -f "forge-$FORGE-installer.jar.log" run-prod/installer/ 2>/dev/null
fi

cd "$SERVER"
rm -rf mods world logs crash-reports config/tremor-common.toml
mkdir -p mods
cp "$JAR" mods/
for extra in ${TREMOR_EXTRA_MODS:-}; do
    cp "$extra" mods/
done
echo "eula=true" > eula.txt
printf 'online-mode=false\nlevel-seed=20261008\nview-distance=4\nsimulation-distance=4\nspawn-protection=0\n' \
    > server.properties
echo "[forge-server-test] tremor $VERSION on Forge $FORGE"
{
    waited=0
    until grep -q 'Done (' logs/latest.log 2>/dev/null || [ $waited -ge $LIMIT ]; do
        sleep 2
        waited=$((waited + 2))
    done
    sleep 2
    echo "forceload add -32 -32 32 32"
    echo "forceload add 768 -32 832 32"
    sleep 4
    echo "execute in tremor:hollow run say hollow dimension is there"
    echo "execute positioned over world_surface run tremor spawn ~ ~ ~"
    echo "execute positioned 800 0 0 positioned over world_surface run tremor spawn ~ ~ ~"
    sleep 2
    echo "tremor stage hunting"
    echo "tremor ambush"
    echo "execute positioned over world_surface run setblock ~5 ~-1 ~5 tremor:quicksand"
    echo "execute positioned over world_surface run data get block ~5 ~-1 ~5"
    sleep 10
    echo "tremor info"
    echo "tremor despawn all"
    sleep 2
    echo "stop"
} | "$JAVA" @user_jvm_args.txt "@libraries/net/minecraftforge/forge/$FORGE/$ARGS" nogui > console.log 2>&1
echo "[forge-server-test] server exit $?"

LOG=logs/latest.log
FAILED=0
check() {
    if grep -q "$2" "$LOG" 2>/dev/null; then
        echo "  ok    $1"
    else
        echo "  FAIL  $1"
        FAILED=1
    fi
}
check "started" 'Done ('
check "the hollow dimension" 'hollow dimension is there'
check "two tremors spawned" 'spawned tremor #2'
check "lies in wait" 'lies in wait where it'
check "the quicksand's block entity" 'has the following block data'
check "stopped cleanly" 'All dimensions are saved'
case "${TREMOR_EXTRA_MODS:-}" in
    *voice*) check "the voice chat's plugin" 'Simple Voice Chat found' ;;
esac
ERRORS=$(grep -E '/(ERROR|FATAL)\]|Exception' "$LOG" 2>/dev/null)
if [ -n "$ERRORS" ]; then
    echo "  errors in the log:"
    echo "$ERRORS" | head -40 | sed 's/^/    /'
    echo "$ERRORS" | grep -qi tremor && { echo "  FAIL  errors about the mod"; FAILED=1; }
fi
[ -d crash-reports ] && { echo "  FAIL  a crash report: $SERVER/crash-reports"; FAILED=1; }
[ $FAILED -eq 0 ] && echo "[forge-server-test] PASSED" || echo "[forge-server-test] FAILED: see $SERVER/$LOG"
exit $FAILED
