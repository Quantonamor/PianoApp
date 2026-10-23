import os, random, re, sys
from music21 import corpus, converter
import verovio
OUT=sys.argv[1]; os.makedirs(OUT, exist_ok=True)
random.seed(int(sys.argv[2]) if len(sys.argv)>2 else 1)
paths=[str(p) for p in corpus.getCorePaths()]
abc=[p for p in paths if p.endswith('.abc')]
mx=[p for p in paths if p.endswith('.mxl') and '/bach/' in p]

def truth(s, expand=True):
    if expand:
        try: s=s.expandRepeats()
        except Exception as e: pass
    lines=[]
    for nn in s.stripTies().flatten().notes:
        if nn.duration.isGrace: continue
        for p in nn.pitches:
            lines.append(f"{p.midi} {float(nn.offset):.4f} {float(nn.quarterLength):.4f}")
    return lines

n_abc=0
for path in random.sample(abc, 40):
    txt=open(path,encoding='latin-1').read()
    tunes=re.split(r'\n(?=X:)', txt)
    tunes=[t for t in tunes if t.strip().startswith('X:')]
    if not tunes: continue
    t=random.choice(tunes).strip()+'\n'
    try:
        s=converter.parse(t, format='abc')
        tr=truth(s)
    except Exception as e:
        continue
    if len(tr)<8: continue
    name=f"abc_{os.path.basename(path)[:-4]}_{n_abc}"
    d=os.path.join(OUT,name); os.makedirs(d,exist_ok=True)
    open(os.path.join(d,'score.abc'),'w').write(t)
    open(os.path.join(d,'truth.txt'),'w').write('\n'.join(tr))
    n_abc+=1
    if n_abc>=15: break
n_mei=0
tk=verovio.toolkit()
for path in random.sample(mx, 6):
    s=corpus.parse(path)
    fn=s.write('musicxml')
    tk.loadFile(str(fn))
    mei=tk.getMEI()
    name=f"mei_{os.path.basename(path)[:-4]}"
    d=os.path.join(OUT,name); os.makedirs(d,exist_ok=True)
    open(os.path.join(d,'score.mei'),'w').write(mei)
    open(os.path.join(d,'truth.txt'),'w').write('\n'.join(truth(s)))
    n_mei+=1
print(n_abc, n_mei)
