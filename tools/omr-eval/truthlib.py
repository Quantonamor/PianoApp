"""Solution file truth.txt ("midi start duration hand" per line) from an engraved MusicXML score.

Start times follow what is *printed*: music21 pads a pick-up measure with hidden rests to the full
bar length, but the PDF (and the OMR) only has the visible beats. Every measure is therefore laid out
with the length of its visible content (longest part), which shifts everything after a pick-up."""
import os
from music21 import stream


def _visible_len(m):
    end = 0.0
    for e in m.recurse().notesAndRests:
        if e.style.hideObjectOnPrint or e.duration.isGrace: continue
        end = max(end, float(e.getOffsetInHierarchy(m) + e.quarterLength))
    return end


def truth_lines(s):
    parts = list(s.parts)
    sel = parts[-2:] if len(parts) > 2 else parts
    # measure index -> (original start, printed start)
    measures = [list(p.getElementsByClass(stream.Measure)) for p in sel]
    n = max(len(ms) for ms in measures)
    orig, new, t = [], [], 0.0
    for k in range(n):
        ms = [ms[k] for ms in measures if k < len(ms)]
        orig.append(float(ms[0].offset))
        new.append(t)
        nominal = max(float(m.duration.quarterLength) for m in ms)
        printed = max(_visible_len(m) for m in ms)
        t += printed if 0 < printed < nominal else nominal

    def remap(x):
        k = max([i for i, o in enumerate(orig) if o <= x + 1e-9] or [0])
        return new[k] + (x - orig[k])

    lines = []
    for i, p in enumerate(sel):
        hand = 'L' if (len(sel) == 2 and i == 1) else 'R'
        # ties are joined per voice: stripTies on a part with two voices links a tie to a note of the other voice
        voices = list(p.voicesToParts().parts) if p.hasMeasures() and any(m.hasVoices() for m in p.getElementsByClass(stream.Measure)) else [p]
        for vp in voices:
            for nn in vp.stripTies().flatten().notes:
                if nn.duration.isGrace: continue
                for pitch in nn.pitches:
                    lines.append(f"{pitch.midi} {remap(float(nn.offset)):.4f} {float(nn.quarterLength):.4f} {hand}")
    return lines


def write_truth(s, d):
    open(os.path.join(d, 'truth.txt'), 'w', newline='\n').write('\n'.join(truth_lines(s)))
