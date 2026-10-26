"""Chime Bells: modal synthesis of the 24 bell strokes (design `audio`), standard library only.

    python tools/synth_bells.py [out_dir]

Writes app/src/main/assets/bells/B<n>_<zheng|ce>.wav: mono 16-bit 44.1 kHz. Each stroke is six
exponentially decaying partials (ratios 1.00 / 1.52 / 2.06 / 2.75 / 3.40 / 4.10, amplitudes 1.0 / 0.45 /
0.35 / 0.20 / 0.12 / 0.07); the 2nd and 3rd partials are split into a +-0.15 % twin pair (the two peaks of
the almond-shaped bell, a slow beat). Fundamental T60 4.5 s (B1-B3), 3.2 s (B4-B8), 2.2 s (B9-B12); partial
T60 = fundamental x 1 / 0.35 / 0.22 / 0.15 / 0.10 / 0.08. A 4 ms band-passed (2 - 5 kHz) mallet noise at
-18 dB and a 2 ms onset ramp. The side-drum stroke carries the front-drum fundamental at -14 dB, the
front-drum stroke the side-drum fundamental at -18 dB (one bell, two notes, each lightly ringing the other).
Peak -3 dBFS for the lower tier, -5 dBFS for the upper (big bells 2 dB louder). Deterministic (seeded).
"""
import cmath
import math
import os
import random
import struct
import sys
import wave

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RATE = 44100

# id, zheng Hz, ce Hz (design bells.list)
BELLS = [
    ("B1", 130.81, 164.81), ("B2", 146.83, 174.61), ("B3", 164.81, 196.00), ("B4", 196.00, 246.94),
    ("B5", 220.00, 261.63), ("B6", 261.63, 329.63), ("B7", 293.66, 349.23), ("B8", 329.63, 392.00),
    ("B9", 392.00, 493.88), ("B10", 440.00, 523.25), ("B11", 523.25, 659.26), ("B12", 587.33, 698.46),
]
RATIOS = [1.00, 1.52, 2.06, 2.75, 3.40, 4.10]
AMPS = [1.0, 0.45, 0.35, 0.20, 0.12, 0.07]
T60_SHARE = [1.0, 0.35, 0.22, 0.15, 0.10, 0.08]
TWIN = 0.0015


def base_t60(index):
    if index < 3:
        return 4.5
    if index < 8:
        return 3.2
    return 2.2


def db(x):
    return 10 ** (x / 20.0)


def partials(f0, t60, gain):
    """(frequency, amplitude, t60) of one tone's partials."""
    out = []
    for k, (r, a, s) in enumerate(zip(RATIOS, AMPS, T60_SHARE)):
        f = f0 * r
        if f > RATE * 0.45:
            continue
        if k in (1, 2):
            out.append((f * (1 - TWIN), a * gain * 0.6, t60 * s))
            out.append((f * (1 + TWIN), a * gain * 0.6, t60 * s))
        else:
            out.append((f, a * gain, t60 * s))
    return out


def render(index, f_main, f_other, other_db, seed):
    t60 = base_t60(index)
    length = min(4.5, t60)
    n = int(length * RATE)
    comps = partials(f_main, t60, 1.0)
    comps.append((f_other, db(other_db), t60))
    buf = [0.0] * n
    for f, a, tt in comps:
        rot = cmath.exp(2j * math.pi * f / RATE) * math.exp(-6.9078 / (tt * RATE))
        z = complex(0.0, a)
        for i in range(n):
            buf[i] += z.imag
            z *= rot
    # onset ramp 2 ms
    ramp = int(0.002 * RATE)
    for i in range(ramp):
        buf[i] *= i / ramp
    # mallet noise: 4 ms, band-passed 2 - 5 kHz by two one-pole filters, -18 dB of the peak so far
    rnd = random.Random(seed)
    peak = max(abs(v) for v in buf[: int(0.05 * RATE)]) or 1.0
    hp_a = math.exp(-2 * math.pi * 2000 / RATE)
    lp_a = math.exp(-2 * math.pi * 5000 / RATE)
    lp = 0.0
    prev_x = 0.0
    hp = 0.0
    m = int(0.004 * RATE)
    noise = []
    for i in range(m):
        x = rnd.uniform(-1, 1)
        hp = hp_a * (hp + x - prev_x)
        prev_x = x
        lp = (1 - lp_a) * hp + lp_a * lp
        env = math.sin(math.pi * i / m)
        noise.append(lp * env)
    npk = max(abs(v) for v in noise) or 1.0
    for i in range(m):
        buf[i] += noise[i] / npk * peak * db(-18)
    # fade the last 60 ms
    fade = int(0.06 * RATE)
    for i in range(fade):
        buf[n - fade + i] *= 1 - i / fade
    return buf


def write(path, buf, peak_db):
    pk = max(abs(v) for v in buf) or 1.0
    k = db(peak_db) / pk
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1.0, min(1.0, v * k)) * 32767)) for v in buf))


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "app", "src", "main", "assets", "bells")
    os.makedirs(out, exist_ok=True)
    total = 0
    for i, (bid, fz, fc) in enumerate(BELLS):
        level = -3.0 if i < 6 else -5.0
        for zone, f_main, f_other, other_db in (("zheng", fz, fc, -18.0), ("ce", fc, fz, -14.0)):
            buf = render(i, f_main, f_other, other_db, seed=i * 2 + (zone == "ce"))
            path = os.path.join(out, "%s_%s.wav" % (bid, zone))
            write(path, buf, level)
            total += os.path.getsize(path)
            print("wrote", path, "%.2f s" % (len(buf) / RATE))
    print("total %.2f MB" % (total / 1e6))


if __name__ == "__main__":
    main()
