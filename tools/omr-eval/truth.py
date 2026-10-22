import os
from music21 import converter
D='/tmp/claude-0/corpus2'
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
    open(os.path.join(D,n,'truth.txt'),'w').write('\n'.join(lines))
    print(n, len(parts), '->', len(sel), len(lines), 'notes')
