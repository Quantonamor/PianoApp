#!/bin/bash
# One-time setup of the OMR evaluation tools in a fresh session/container.
# Creates $OMR_WORK (default /tmp/omr-work) with: Python venv (LilyPond, music21, Verovio),
# rounds/ (all earlier rounds + used.txt), regress/ (fixed corpus + all rounds, as symlinks),
# omr/ (debug overlays) and kcp.txt (Kotlin compiler classpath for kcheck.sh).
set -e
REPO=$(cd "$(dirname "$0")/../.." && pwd)
W=${OMR_WORK:-/tmp/omr-work}
mkdir -p "$W/rounds" "$W/omr"

if [ ! -x "$W/lyenv/bin/python" ]; then
  python3 -m venv "$W/lyenv"
  "$W/lyenv/bin/pip" install -q lilypond music21 verovio cairosvg
fi

# Earlier rounds (PDFs + solutions are versioned in the repo) and the list of pieces already used.
for r in "$REPO"/tools/omr-eval/rounds/r*; do [ -d "$W/rounds/$(basename "$r")" ] || cp -r "$r" "$W/rounds/"; done
[ -f "$W/rounds/used.txt" ] || cp "$REPO/tools/omr-eval/used_pieces.txt" "$W/rounds/used.txt"

# Regression set = fixed corpus + every round.
rm -rf "$W/regress"; mkdir -p "$W/regress"
for d in "$REPO"/core/src/test/resources/corpus/*/ "$W"/rounds/r*/*/; do
  [ -f "$d/truth.txt" ] && ln -sfn "${d%/}" "$W/regress/$(basename "$d")"
done

# Kotlin compiler classpath for kcheck.sh (jars come into the Gradle cache with the first core build).
(cd "$REPO" && ./gradlew -q :core:compileKotlin >/dev/null 2>&1 || true)
C=~/.gradle/caches/modules-2/files-2.1
CP=""
for a in kotlin-compiler-embeddable kotlin-stdlib kotlin-script-runtime kotlin-reflect kotlin-daemon-embeddable trove4j annotations kotlinx-coroutines-core-jvm; do
  j=$(find "$C" -name "$a-[0-9]*.jar" ! -name "*sources*" 2>/dev/null | sort -V | tail -1)
  [ -n "$j" ] && CP="$CP:$j"
done
echo "${CP#:}" > "$W/kcp.txt"

echo "OMR_WORK=$W ready: $(ls "$W/regress" | wc -l) regression pieces, rounds: $(ls -d "$W"/rounds/r* | xargs -n1 basename | tr '\n' ' ')"
