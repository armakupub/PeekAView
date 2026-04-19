#!/usr/bin/env bash
set -euo pipefail

# --- Paths ---
PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$PROJECT_ROOT/src"
BUILD_DIR="$PROJECT_ROOT/build"
CLASSES_DIR="$BUILD_DIR/classes"
JAR_OUT="$BUILD_DIR/peekaview.jar"

# Per-machine overrides — create build.local (gitignored) to set PZ_DIR etc.
# See build.local.example for the template.
if [ -f "$PROJECT_ROOT/build.local" ]; then
    # shellcheck disable=SC1091
    source "$PROJECT_ROOT/build.local"
fi

if [ -z "${PZ_DIR:-}" ] || [ ! -f "$PZ_DIR/projectzomboid.jar" ]; then
    echo "[build] ERROR: Project Zomboid install not found." >&2
    echo "        Set PZ_DIR in build.local, e.g.:" >&2
    echo "          cp build.local.example build.local" >&2
    echo "          # then edit build.local to match your Steam install" >&2
    echo "        or via env:" >&2
    echo "          PZ_DIR=/d/Steam/steamapps/common/ProjectZomboid ./build.sh" >&2
    exit 1
fi
: "${MOD_INSTALL_ROOT:=$USERPROFILE/Zomboid/mods/PeekAView}"

PZ_JAR="$PZ_DIR/projectzomboid.jar"
ZB_JAR="$PZ_DIR/ZombieBuddy.jar"

# Pick the Zulu JDK under tools/ (glob tolerates version bumps).
JDK_DIR="$(ls -d "$PROJECT_ROOT"/tools/zulu*-win_x64 2>/dev/null | head -n 1)"
if [ -z "$JDK_DIR" ]; then
    echo "[build] ERROR: no Zulu JDK found under $PROJECT_ROOT/tools/zulu*-win_x64" >&2
    echo "        Download a Zulu JDK 25 Windows x64 build from https://www.azul.com/downloads/" >&2
    echo "        and extract it into tools/ so that tools/zulu25.../bin/javac.exe exists." >&2
    exit 1
fi
JAVAC="$JDK_DIR/bin/javac.exe"
JAR="$JDK_DIR/bin/jar.exe"

# --- Clean ---
rm -rf "$BUILD_DIR"
mkdir -p "$CLASSES_DIR"

# --- Compile ---
echo "[build] Compiling..."
mapfile -t SOURCES < <(find "$SRC_DIR" -name '*.java')

"$JAVAC" \
    --release 17 \
    -classpath "$PZ_JAR;$ZB_JAR" \
    -d "$CLASSES_DIR" \
    "${SOURCES[@]}"

# --- Package jar ---
echo "[build] Packaging jar..."
"$JAR" --create --file "$JAR_OUT" -C "$CLASSES_DIR" .

# --- Stage mod directory ---
echo "[build] Staging mod directory..."
STAGE="$BUILD_DIR/stage/PeekAView"
rm -rf "$STAGE"
mkdir -p "$STAGE/42.13/media/java/client"
cp "$PROJECT_ROOT/mod_files/mod.info" "$STAGE/mod.info"
cp "$PROJECT_ROOT/mod_files/42.13/mod.info" "$STAGE/42.13/mod.info"
cp "$PROJECT_ROOT/poster.png" "$STAGE/poster.png"
cp "$PROJECT_ROOT/poster.png" "$STAGE/42.13/poster.png"
cp "$JAR_OUT" "$STAGE/42.13/media/java/client/peekaview.jar"

if [ -d "$PROJECT_ROOT/mod_files/42.13/media/lua" ]; then
    cp -r "$PROJECT_ROOT/mod_files/42.13/media/lua" "$STAGE/42.13/media/lua"
fi

# --- Install to Zomboid mods dir ---
echo "[build] Installing to $MOD_INSTALL_ROOT"
rm -rf "$MOD_INSTALL_ROOT"
mkdir -p "$(dirname "$MOD_INSTALL_ROOT")"
cp -r "$STAGE" "$MOD_INSTALL_ROOT"

echo "[build] Done."
echo "       Jar:     $JAR_OUT"
echo "       Install: $MOD_INSTALL_ROOT"
