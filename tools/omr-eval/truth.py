import os
from music21 import converter
D=os.path.join(os.environ.get('OMR_WORK', '/tmp/omr-work'), 'corpus2')
for n in sorted(os.listdir(D)):
    s=converter.parse(os.path.join(D,n,'score.musicxml'))
    parts=list(s.parts)
    piano=[p for p in parts if 'PartStaff' in p.classes]
    sel = parts[-2:] if len(parts)>2 else parts
    lines=[]
    for i,p in enumerate(sel):
        hand = 'L' if (len(sel)==2 and i==1) else 'R'
        for nn in p.stripTies().flatten().notes:
            if nn.duration.isGrace: continue
            for pitch in (nn.pitches):
                lines.append(f"{pitch.midi} {float(nn.offset):.4f} {float(nn.quarterLength):.4f} {hand}")
    # music21 pads a pickup measure to a full bar (gap after the first notes); the app counts the pickup as
    # its real length, so the gap is removed
    rows=[l.split() for l in lines]
    if rows:
        on=sorted(set(float(r[1]) for r in rows))
        first_end=max(float(r[1])+float(r[2]) for r in rows if float(r[1])<on[0]+1e-6)
        nxt=min((o for o in on if o>=first_end-1e-6), default=None)
        if nxt is not None and on[0]==0 and nxt-first_end>0.9:
            gap=nxt-first_end
            lines=[f"{r[0]} {float(r[1])-(gap if float(r[1])>=nxt-1e-6 else 0):.4f} {r[2]} {r[3]}" for r in rows]
    open(os.path.join(D,n,'truth.txt'),'w').write('\n'.join(lines))
    print(n, len(parts), '->', len(sel), len(lines), 'notes')
