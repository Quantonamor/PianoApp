#!/bin/bash
# Syntax check of the app sources (types can't be resolved without Compose; only syntax errors are reported)
REPO=$(cd "$(dirname "$0")/../.." && pwd)
W=${OMR_WORK:-/tmp/omr-work}
CP=$(cat $W/kcp.txt)
STD=$(echo $CP | tr ':' '\n' | grep kotlin-stdlib)
rm -rf $W/kout
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-reflect -no-stdlib -cp "$STD" -d $W/kout $(find "$REPO"/app/src -name "*.kt") 2>&1 | grep -iE "syntax|expecting|unexpected" | grep -v "^warning" | head -40
echo "syntax check done"
