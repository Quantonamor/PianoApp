"""Extracts the screenshots that the UI test prints into the CI log (lines "==== SHOT name ====" + base64 JPEG).

Usage: python3 tools/ci/extract_shots.py <saved job log> [out_dir]   (log saved by mcp__github__get_job_logs)
"""
import json,re,base64,sys
import os
out=sys.argv[2] if len(sys.argv) > 2 else "."
os.makedirs(out, exist_ok=True)
raw=open(sys.argv[1]).read()
try: txt=json.loads(raw)['logs_content']
except Exception: txt=raw
name=None
for l in txt.split('\n'):
    m=re.search(r'==== SHOT (\S+) ====',l)
    if m: name=m.group(1); continue
    if name:
        b=l.split(' ',1)[1] if l[:4]=='2026' else l
        try: open(os.path.join(out, name + ".jpg"), "wb").write(base64.b64decode(b.strip()))
        except Exception: pass
        name=None; continue
    if re.search(r'FAILED|failure>|AssertionError|at de\.pianotiles|OVERLAP',l): print(l[28:300])
