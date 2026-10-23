#!/bin/bash
# Syntax check of the app sources (types can't be resolved without Compose; only syntax errors are reported)
source "$(dirname "$0")/env.sh"
CP=$(cat "$W/kcp.txt")
STD=$(echo "$CP" | tr "$SEP" '\n' | grep kotlin-stdlib)
rm -rf "$W/kout"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-reflect -no-stdlib -cp "$STD" -d "$(winpath "$W/kout")" $(find "$REPO"/app/src -name "*.kt") 2>&1 | grep -iE "syntax|expecting|unexpected" | grep -v "^warning" | head -40
echo "syntax check done"
