"""Builds the piano samples of the app from the npm package tonejs-instrument-piano-mp3 (MIT).

Every third semitone (C, D#, F#, A) from A0 to C8 is decoded with ffmpeg, converted to mono 16-bit 32 kHz,
trimmed to the note onset and shortened (long in the bass, short in the treble) with a fade-out.
Output: app/src/main/assets/piano/<midi>.pcm (raw little-endian int16) + LICENSE.txt.

Run:  mkdir -p /tmp/ps && cd /tmp/ps && npm pack tonejs-instrument-piano-mp3 && tar xzf tonejs-*.tgz
      python3 tools/samples/make_piano.py /tmp/ps/package
"""
import os, struct, subprocess, sys

src = sys.argv[1]
root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
out = os.path.join(root, "app/src/main/assets/piano")
os.makedirs(out, exist_ok=True)
RATE = 32000
NAMES = ["C", "Cs", "D", "Ds", "E", "F", "Fs", "G", "Gs", "A", "As", "B"]
total = 0
for midi in range(21, 109):
    if midi % 3 != 0 and midi not in (21, 108):  # C=60 is divisible by 3: C, D#, F#, A
        continue
    name = NAMES[midi % 12] + str(midi // 12 - 1)
    f = os.path.join(src, name + ".mp3")
    if not os.path.exists(f):
        print("missing", name); continue
    raw = subprocess.run(["ffmpeg", "-v", "quiet", "-i", f, "-ac", "1", "-ar", str(RATE), "-f", "s16le", "-"],
                         capture_output=True, check=True).stdout
    s = list(struct.unpack("<%dh" % (len(raw) // 2), raw))
    peak = max(abs(x) for x in s) or 1
    start = next(i for i, x in enumerate(s) if abs(x) > peak * 0.02)
    start = max(0, start - 32)
    k = (midi - 21) / 87.0
    seconds = 5.0 - 3.4 * k  # 5 s in the bass .. 1.6 s at the top
    s = s[start:start + int(seconds * RATE)]
    fade = int(0.25 * RATE)
    n = len(s)
    for i in range(min(fade, n)):
        s[n - 1 - i] = int(s[n - 1 - i] * i / fade)
    gain = 30000.0 / peak  # normalise every sample to the same peak (loudness is set by velocity)
    s = [max(-32767, min(32767, int(x * gain))) for x in s]
    data = struct.pack("<%dh" % len(s), *s)
    open(os.path.join(out, "%d.pcm" % midi), "wb").write(data)
    total += len(data)
open(os.path.join(out, "LICENSE.txt"), "w").write(
    "Piano samples from the npm package tonejs-instrument-piano-mp3 (tonejs-instruments, MIT License,\n"
    "https://github.com/Makefully-Studios/tonejs-instruments), converted with tools/samples/make_piano.py.\n")
print("wrote", len(os.listdir(out)) - 1, "samples,", total // 1024, "KiB")
