"""Usage: round.py <round-number> <seed> [count]
Creates $OMR_WORK/rounds/r<N>/<piece>/{lilypond.pdf, verovio.pdf, truth.txt} for random pieces."""
import os, sys, random, subprocess, copy, traceback
W = os.environ.get('OMR_WORK', '/tmp/omr-work')
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from music21 import corpus, stream, clef, layout, converter
import importlib.util
# reuse helpers without running the main loop
src = open(os.path.join(HERE, 'make_corpus.py')).read().split('for name, path, nmeas, kind in CASES:')[0]
mc = {'__file__': os.path.join(HERE, 'make_corpus.py')}
exec(src, mc)

N, SEED = int(sys.argv[1]), int(sys.argv[2])
COUNT = int(sys.argv[3]) if len(sys.argv) > 3 else 5
rnd = random.Random(SEED)
OUT = os.path.join(W, 'rounds', f'r{N}')
os.makedirs(OUT, exist_ok=True)

paths = [str(p) for p in corpus.getPaths()]
chorales = [p for p in paths if '/bach/bwv' in p and p.endswith(('.mxl', '.xml'))]
folk_opus = [p for p in paths if 'essenFolksong' in p or 'oneills1850' in p or 'ryansMammoth' in p]
used = set(open(os.path.join(W, 'rounds/used.txt')).read().split()) if os.path.exists(os.path.join(W, 'rounds/used.txt')) else set()

def truth_for(s, d):
    parts = list(s.parts)
    sel = parts[-2:] if len(parts) > 2 else parts
    lines = []
    for i, p in enumerate(sel):
        hand = 'L' if (len(sel) == 2 and i == 1) else 'R'
        for nn in p.stripTies().flatten().notes:
            if nn.duration.isGrace: continue
            for pitch in nn.pitches:
                lines.append(f"{pitch.midi} {float(nn.offset):.4f} {float(nn.quarterLength):.4f} {hand}")
    open(os.path.join(d, 'truth.txt'), 'w').write('\n'.join(lines))

made = 0
attempts = 0
while made < COUNT and attempts < 60:
    attempts += 1
    kind = rnd.choice(['chorale', 'chorale', 'folk', 'folk'])
    try:
        if kind == 'chorale':
            p = rnd.choice(chorales)
            key = 'ch:' + os.path.basename(p)
            if key in used: continue
            s = corpus.parse(p)
            if len(s.parts) != 4: continue
            s = mc['piano_reduction_chorale'](p)
            name = 'chorale_' + os.path.basename(p).split('.')[0]
        else:
            p = rnd.choice(folk_opus)
            op = corpus.parse(p)
            scores = list(op.scores) if hasattr(op, 'scores') else [op]
            idx = rnd.randrange(len(scores))
            key = f'folk:{os.path.basename(p)}:{idx}'
            if key in used: continue
            s = scores[idx]
            if len(s.flatten().notes) < 12: continue
            name = 'folk_' + os.path.basename(p).split('.')[0] + f'_{idx}'
        d = os.path.join(OUT, name)
        os.makedirs(d, exist_ok=True)
        mc['write_pdfs'](name, s, d)
        # truth from the exported MusicXML (what was engraved)
        truth_for(converter.parse(os.path.join(d, 'score.musicxml')), d)
        for f in os.listdir(d):
            if f.endswith('.png'): os.remove(os.path.join(d, f))
        used.add(key); made += 1
        print('made', name)
    except Exception as e:
        print('skip', kind, e)
open(os.path.join(W, 'rounds/used.txt'), 'w').write('\n'.join(sorted(used)))
