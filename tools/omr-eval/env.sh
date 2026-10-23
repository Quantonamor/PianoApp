# Shared settings for the OMR tools; `source` it. Works on Linux and in Git Bash on Windows.
#   REPO  repository root          W    working dir ($OMR_WORK, default /tmp/omr-work)
#   PY    system Python 3 (small helper scripts)
#   VPY   Python of the venv with LilyPond/music21/Verovio (after setup.sh); "$W/py" wraps it
#   winpath P   path as the JVM/Gradle understands it (C:/... on Windows, unchanged elsewhere)
REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
W=${OMR_WORK:-/tmp/omr-work}
if command -v python3 >/dev/null 2>&1 && python3 -c "" >/dev/null 2>&1; then PY=python3
elif command -v py >/dev/null 2>&1; then PY="py -3"
else PY=python; fi
if [ -x "$W/lyenv/Scripts/python.exe" ]; then VPY="$W/lyenv/Scripts/python.exe"; else VPY="$W/lyenv/bin/python"; fi
winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }
export REPO W
# Java classpath separator
if command -v cygpath >/dev/null 2>&1; then SEP=';'; else SEP=':'; fi
export PYTHONIOENCODING=utf-8
