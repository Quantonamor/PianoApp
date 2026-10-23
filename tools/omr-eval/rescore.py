"""Rebuilds truth.txt for existing pieces (fixed corpus or round folders) from the music21 corpus.
Usage: rescore.py [--pdf] DIR [DIR ...]  (each DIR holds one folder per piece);
--pdf also engraves lilypond.pdf and verovio.pdf again (after a fix of the generator in make_corpus.py)."""
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from music21 import corpus, converter
from truthlib import write_truth
src = open(os.path.join(HERE, 'make_corpus.py')).read().split('for name, path, nmeas, kind in CASES:')[0]
mc = {'__file__': os.path.join(HERE, 'make_corpus.py')}
exec(src, mc)
W = os.environ.get('OMR_WORK', '/tmp/omr-work')
paths = [str(p).replace(os.sep, '/') for p in corpus.getPaths()]
cases = {c[0]: c for c in mc['CASES']}
used = set()
for f in (os.path.join(HERE, 'used_pieces.txt'), os.path.join(W, 'rounds', 'used.txt')):
    if os.path.exists(f): used |= set(open(f).read().split())


def score_for(name):
    if name in cases:
        _, path, nmeas, kind = cases[name]
        if kind == 'chorale': return mc['piano_reduction_chorale'](path)
        if kind == 'folk':
            s = corpus.parse(path)
            return s.scores[0] if hasattr(s, 'scores') else s
        if kind == 'folk2': return corpus.parse('oneills1850').scores[7]
        s = corpus.parse(path)
        return s.measures(1, nmeas) if nmeas else s
    if name.startswith('chorale_'):
        bwv = name[len('chorale_'):]
        # the folder name drops the chorale number (bwv244.15 -> chorale_bwv244); the list of used pieces has it
        files = [u[3:] for u in used if u.startswith('ch:') and u[3:].split('.')[0] == bwv]
        p = next(p for p in paths if '/bach/' in p and (os.path.basename(p) in files if files else
                 os.path.basename(p).split('.')[0] == bwv) and p.endswith(('.mxl', '.xml')))
        return mc['piano_reduction_chorale'](p)
    if name.startswith('folk_'):
        base, idx = name[len('folk_'):].rsplit('_', 1)
        p = next(p for p in paths if os.path.basename(p).split('.')[0] == base)
        op = corpus.parse(p)
        return (list(op.scores) if hasattr(op, 'scores') else [op])[int(idx)]
    raise KeyError(name)


os.makedirs(os.path.join(W, 'tmp'), exist_ok=True)
engrave = '--pdf' in sys.argv
for root in [a for a in sys.argv[1:] if a != '--pdf']:
    for name in sorted(os.listdir(root)):
        d = os.path.join(root, name)
        if not os.path.isfile(os.path.join(d, 'truth.txt')): continue
        try:
            xml = os.path.join(W, 'tmp', name + '.musicxml')
            if engrave:
                mc['write_pdfs'](name, score_for(name), d)
                for f in os.listdir(d):
                    if f.endswith(('.png', '.ly', '.musicxml')): os.remove(os.path.join(d, f))
            score_for(name).write('musicxml', fp=xml)
            old = open(os.path.join(d, 'truth.txt')).read().split('\n')
            write_truth(converter.parse(xml), d)
            new = open(os.path.join(d, 'truth.txt')).read().split('\n')
            print('ok  ', name, len(old), '->', len(new), 'notes', '(changed)' if old != new else '')
        except Exception as e:
            print('FAIL', name, e)
