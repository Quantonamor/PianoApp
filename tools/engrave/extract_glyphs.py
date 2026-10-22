"""Extracts the SMuFL glyph outlines the engraver needs from Verovio's copy of the Bravura font.

Output: core/src/main/resources/engrave/bravura.txt, one line per glyph: CODE <tab> advance <tab> SVG path
(font units, 1000 per em = 4 staff spaces, y pointing up). Bravura is (c) Steinberg Media Technologies GmbH,
SIL Open Font License 1.1 (see BRAVURA-OFL.txt next to the output).

Run: /tmp/omr-work/lyenv/bin/python tools/engrave/extract_glyphs.py
"""
import os, re, sys
import verovio

data = os.path.join(os.path.dirname(verovio.__file__), "data")
CODES = [
    "E000",  # brace
    "E050", "E062",  # G and F clef
    "E080", "E081", "E082", "E083", "E084", "E085", "E086", "E087", "E088", "E089", "E08A", "E08B",  # time sig
    "E0A2", "E0A3", "E0A4",  # noteheads whole, half, black
    "E1E7",  # augmentation dot
    "E240", "E241", "E242", "E243", "E244", "E245",  # flags 8th/16th/32nd up/down
    "E260", "E261", "E262",  # flat, natural, sharp
    "E4E3", "E4E4", "E4E5", "E4E6", "E4E7", "E4E8",  # rests whole … 32nd
    "E1D5",  # metronome quarter note
    "E883",  # tuplet 3
]
bbox = open(os.path.join(data, "Bravura.xml"), encoding="utf-8").read()
root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
out = os.path.join(root, "core/src/main/resources/engrave/bravura.txt")
with open(out, "w") as f:
    for c in CODES:
        g = open(os.path.join(data, "Bravura", c + ".xml"), encoding="utf-8").read()
        d = re.search(r' d="([^"]+)"', g).group(1)
        m = re.search(r'<g c="%s"[^>]*h-a-x="([-0-9.]+)"' % c, bbox)
        adv = m.group(1) if m else "0"
        f.write("%s\t%s\t%s\n" % (c, adv, " ".join(d.split())))
print("wrote", out, len(CODES), "glyphs")
