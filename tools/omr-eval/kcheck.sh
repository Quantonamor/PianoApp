#!/bin/bash
# Syntax check of the app sources (types can't be resolved without Compose; only syntax errors are reported)
CP=$(cat /tmp/claude-0/kcp.txt)
STD=$(echo $CP | tr ':' '\n' | grep kotlin-stdlib)
rm -rf /tmp/claude-0/kout
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-reflect -no-stdlib -cp "$STD" -d /tmp/claude-0/kout $(find /home/user/PianoApp/app/src -name "*.kt") 2>&1 | grep -iE "syntax|expecting|unexpected" | grep -v "^warning" | head -40
echo "syntax check done"
