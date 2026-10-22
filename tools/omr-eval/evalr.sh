# usage: evalr.sh <dir> [extra gradle args]
D=$1; shift
REPO=$(cd "$(dirname "$0")/../.." && pwd)
W=${OMR_WORK:-/tmp/omr-work}
export REPO
rm -f $REPO/core/build/test-results/test/TEST-de.pianotiles.core.OmrCorpusEval.xml
cd "$REPO" && timeout 1500 ./gradlew :core:test --tests '*OmrCorpusEval*' -Pomr.corpus=$D -Pomr.debugDir=$W/omr "$@" -q 2>&1 | grep -E "^e: " | head
python3 - <<'PY'
import re, os
s=open(os.environ['REPO']+'/core/build/test-results/test/TEST-de.pianotiles.core.OmrCorpusEval.xml').read()
for l in [x for x in re.findall(r'^\w+\s+(?:lilypond|verovio).*',s,re.M) if not x.startswith('DIFF')]:
    l=re.sub(r'\s+',' ',l); m=re.search(r'noten=\s*([\d.]+)%.*?präzision=\s*([\d.]+)%.*?rhythmus=\s*([\d.]+)%.*?(key=\S+ ts=\S+)',l)
    ok = m and float(m.group(1))==100 and float(m.group(2))==100 and float(m.group(3))==100
    print(('OK   ' if ok else 'FAIL ')+l.split(' pages')[0].ljust(40), m.group(1),m.group(2),m.group(3),m.group(4), l[l.find('ERR'):] if 'ERR' in l else '')
print(re.findall(r'DURCHSCHNITT.*',s)); print(re.findall(r'FEHLERARTEN.*',s))
import os
if os.environ.get('DIFF'):
    for l in re.findall(r'^DIFF.*',s,re.M): print(l[:400])
PY
