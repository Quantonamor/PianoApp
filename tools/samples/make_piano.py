"""Builds the piano samples of the app: Salamander Grand Piano V3 (Alexander Holm, CC BY 3.0) in three velocity
layers, from the npm packages @audio-samples/piano-mp3-velocity{4,9,14} (MIT, Jan Forst).

Every third semitone (C, D#, F#, A) from A0 to C8, per layer: decoded with ffmpeg to mono 44.1 kHz, cut at the
note onset, shortened (long in the bass, short in the treble) with a fade-out, and stored as mono MP3 (128 kbit/s).
One common gain for all files keeps the natural balance between keys and layers. The app decodes the MP3s at
start (MediaCodec) and trims the decoder delay again (PianoTones.trimOnset).
Output: app/src/main/assets/piano/<layer>_<midi>.mp3 with layer p (soft, v4), m (medium, v9), f (loud, v14).

Run:  mkdir -p /tmp/sal && cd /tmp/sal && for v in 4 9 14; do npm pack @audio-samples/piano-mp3-velocity$v; done \
        && for f in *.tgz; do mkdir -p ${f%.tgz}; tar xzf $f -C ${f%.tgz}; done
      python3 tools/samples/make_piano.py /tmp/sal
"""
import glob, os, struct, subprocess, sys

src = sys.argv[1]
root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
out = os.path.join(root, "app/src/main/assets/piano")
os.makedirs(out, exist_ok=True)
for f in glob.glob(os.path.join(out, "*")):
    os.remove(f)
RATE = 44100
NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
LAYERS = [("p", 4), ("m", 9), ("f", 14)]
GAIN = 1.35  # loudest file (C4 v14) peaks at about 28000


def decode(path):
    raw = subprocess.run(["ffmpeg", "-v", "quiet", "-i", path, "-ac", "1", "-ar", str(RATE), "-f", "s16le", "-"],
                         capture_output=True, check=True).stdout
    return list(struct.unpack("<%dh" % (len(raw) // 2), raw))


total = 0
for tag, vel in LAYERS:
    pkg = glob.glob(os.path.join(src, "*velocity%d-*" % vel, "package", "audio"))[0]
    for midi in range(21, 109):
        if midi % 3 != 0:  # A0=21, C1=24, ... C8=108
            continue
        f = os.path.join(pkg, "%s%dv%d.mp3" % (NAMES[midi % 12], midi // 12 - 1, vel))
        if not os.path.exists(f):
            print("missing", f); continue
        s = decode(f)
        peak = max(abs(x) for x in s) or 1
        start = max(0, next(i for i, x in enumerate(s) if abs(x) > peak * 0.02) - 64)
        k = (midi - 21) / 87.0
        seconds = 5.5 - 3.8 * k  # 5.5 s in the bass .. 1.7 s at the top
        s = s[start:start + int(seconds * RATE)]
        n = len(s)
        fade = int(0.35 * RATE)
        for i in range(min(fade, n)):
            s[n - 1 - i] = int(s[n - 1 - i] * (i / fade) ** 2)
        s = [max(-32767, min(32767, int(x * GAIN))) for x in s]
        pcm = struct.pack("<%dh" % len(s), *s)
        dst = os.path.join(out, "%s_%d.mp3" % (tag, midi))
        subprocess.run(["ffmpeg", "-v", "quiet", "-y", "-f", "s16le", "-ar", str(RATE), "-ac", "1", "-i", "-",
                        "-c:a", "libmp3lame", "-b:a", "128k", dst], input=pcm, check=True)
        total += os.path.getsize(dst)
open(os.path.join(out, "LICENSE.txt"), "w").write(
    "Salamander Grand Piano V3 by Alexander Holm, CC BY 3.0 (https://creativecommons.org/licenses/by/3.0/),\n"
    "https://archive.org/details/SalamanderGrandPianoV3 - via the npm packages @audio-samples/piano-mp3-velocity*\n"
    "(MIT, Jan Forst). Shortened, mono, re-encoded with tools/samples/make_piano.py.\n")
print("wrote", len(os.listdir(out)) - 1, "samples,", total // 1024, "KiB")
