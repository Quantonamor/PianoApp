import os, sys, subprocess, copy, traceback
from music21 import corpus, stream, converter, layout, clef, meter
import verovio, cairosvg

OUT = '/tmp/claude-0/corpus2'
LY = '/tmp/claude-0/lyenv/lib/python3.13/site-packages/lilypond-binaries/bin'

def first_measures(s, n):
    return s.measures(1, n) if n else s

def piano_reduction_chorale(name):
    s = corpus.parse(name)
    parts = s.parts
    up = stream.Part(); lo = stream.Part()
    # soprano+alto -> treble, tenor+bass -> bass, as two voices
    ns = stream.Score()
    sa = stream.Part(); tb = stream.Part()
    for src, dst, c in [((parts[0], parts[1]), sa, clef.TrebleClef()), ((parts[2], parts[3]), tb, clef.BassClef())]:
        m1 = src[0].getElementsByClass(stream.Measure)
        m2 = src[1].getElementsByClass(stream.Measure)
        for a, b in zip(m1, m2):
            m = stream.Measure(number=a.number)
            if a.timeSignature: m.timeSignature = copy.deepcopy(a.timeSignature)
            if a.keySignature: m.keySignature = copy.deepcopy(a.keySignature)
            v1 = stream.Voice(); v2 = stream.Voice()
            for e in a.notesAndRests: v1.insert(e.offset, copy.deepcopy(e))
            for e in b.notesAndRests: v2.insert(e.offset, copy.deepcopy(e))
            m.insert(0, v1); m.insert(0, v2)
            dst.append(m)
        dst.insert(0, c)
    ns.insert(0, sa); ns.insert(0, tb)
    ns.insert(0, layout.StaffGroup([sa, tb], symbol='brace'))
    return ns

CASES = [
    ('mozart_k545', 'mozart/k545/movement1_exposition.mxl', 28, None),
    ('joplin_maple_leaf', 'joplin/maple_leaf_rag.mxl', 20, None),
    ('clara_polonaise1', 'schumann_clara/polonaise_op1n1.mxl', 24, None),
    ('cpe_bach_h186', 'cpebach/h186.mxl', 20, None),
    ('beach_prayer', 'beach/prayer_of_a_tired_child.musicxml', 20, None),
    ('schubert_lindenbaum', 'schubert/Lindenbaum.xml', 24, None),
    ('schumann_dichterliebe2', 'schumann_robert/dichterliebe_no2.xml', 20, None),
    ('aloha_oe', 'liliuokalani/aloha_oe.mxl', 20, None),
    ('lift_every_voice', 'johnson_j_r/lift_every_voice.mxl', 20, None),
    ('bach_bwv66_6', 'bach/bwv66.6', 0, 'chorale'),
    ('bach_bwv269', 'bach/bwv269', 0, 'chorale'),
    ('bach_bwv347', 'bach/bwv347', 0, 'chorale'),
    ('folk_essen', 'essenFolksong/altdeu10', 0, 'folk'),
    ('folk_oneills', 'oneills1850', 0, 'folk2'),
]

def write_pdfs(name, score, d):
    xml = os.path.join(d, 'score.musicxml')
    score.write('musicxml', fp=xml)
    # Verovio -> SVG pages -> PDF
    tk = verovio.toolkit()
    tk.setOptions({'pageWidth': 2100, 'pageHeight': 2970, 'scale': 45, 'adjustPageHeight': False, 'breaks': 'auto', 'footer': 'none', 'header': 'auto'})
    tk.loadFile(xml)
    for i in range(1, tk.getPageCount() + 1):
        svg = tk.renderToSVG(i)
        cairosvg.svg2pdf(bytestring=svg.encode(), write_to=os.path.join(d, f'vrv_p{i}.pdf'), background_color='white')
    pages = sorted(f for f in os.listdir(d) if f.startswith('vrv_p'))
    if len(pages) == 1: os.rename(os.path.join(d, pages[0]), os.path.join(d, 'verovio.pdf')); pages = []
    else: subprocess.run(['pdfunite'] + [os.path.join(d, p) for p in pages] + [os.path.join(d, 'verovio.pdf')], check=True)
    for p in pages: os.remove(os.path.join(d, p))
    # LilyPond via musicxml2ly
    subprocess.run([os.path.join(LY, 'musicxml2ly'), '--no-beaming', '-o', os.path.join(d, 'lily.ly'), xml], check=True, capture_output=True)
    lyfile = os.path.join(d, 'lily.ly')
    txt = open(lyfile).read()
    txt = txt.replace('\\paper {', '\\paper {\n  #(set-paper-size "a4")', 1) if '\\paper {' in txt else txt + '\n\\paper { #(set-paper-size "a4") }\n'
    open(lyfile, 'w').write(txt)
    r = subprocess.run([os.path.join(LY, 'lilypond'), '-s', '-o', os.path.join(d, 'lilypond'), lyfile], capture_output=True, text=True)
    if r.returncode != 0: print('lilypond failed', name, r.stderr[-500:])
    for eng in ['verovio', 'lilypond']:
        pdf = os.path.join(d, eng + '.pdf')
        if os.path.exists(pdf):
            subprocess.run(['pdftoppm', '-gray', '-png', '-scale-to-x', '2000', '-scale-to-y', '-1', pdf, os.path.join(d, eng)], check=True)

for name, path, nmeas, kind in CASES:
    d = os.path.join(OUT, name); os.makedirs(d, exist_ok=True)
    try:
        if kind == 'chorale':
            s = piano_reduction_chorale(path)
        elif kind == 'folk':
            s = corpus.parse(path)
            if hasattr(s, 'scores'): s = s.scores[0]
        elif kind == 'folk2':
            op = corpus.parse('oneills1850')
            s = op.scores[7]
        else:
            s = corpus.parse(path)
            if nmeas: s = s.measures(1, nmeas)
        s.metadata = s.metadata  # keep
        write_pdfs(name, s, d)
        print('ok', name, sorted(os.listdir(d)))
    except Exception as e:
        print('FAIL', name, e); traceback.print_exc(limit=2)
