"""Synthesize clean UI cues from measured pitch/envelope characteristics.

This generator never reads the phone recording or copies its waveform.
The three original oscillator arrangements use independently chosen phases.
"""
import json
import math
from pathlib import Path
import struct
import wave

RATE = 48000
DEST = Path(__file__).resolve().parent.parent / "app/assets/sounds"


def smooth(x):
    x = min(1.0, max(0.0, x))
    return x * x * (3 - 2 * x)


def swell(t, peak):
    if t <= 0:
        return 0.0
    x = t / peak
    return x * x * math.exp(2 * (1 - x)) * math.exp(-max(0, t - peak) / 0.40)


def tone(t, frequency, phase=0):
    return math.sin(2 * math.pi * frequency * t + phase)


def cue(kind, seconds, peak):
    samples = []
    for i in range(round(RATE * seconds)):
        t = i / RATE
        if kind == "focus":
            # Rounded 400 Hz focus pulse with a brief release, without a bell overtone.
            env = smooth(t / .006) * math.exp(-max(0, t - .012) / .020)
            env *= smooth((seconds - t - 1 / RATE) / .025)
            phase = 2 * math.pi * (402 * t + .060 * (1 - math.exp(-t / .012)))
            value = env * (math.sin(phase) + .015 * math.sin(2 * phase + .4))
        elif kind == "confirm":
            # A gentle arrival: the lower root blooms after the upper note.
            value = (swell(t, .240) * tone(t, 203.0)
                     + .57 * swell(t, .145) * tone(t, 241.0, .7)
                     + .20 * swell(t, .235) * tone(t, 363.5, -1.1))
        else:
            # Matching lower chord, with the lower voice sustained a little longer.
            value = (swell(t, .215) * tone(t, 216.0, .2)
                     + .75 * swell(t, .245) * tone(t, 181.0, -.6)
                     + .20 * swell(t, .245) * tone(t, 324.5, 1.2))
        if kind != "focus":
            value *= smooth(t / .004) * smooth((seconds - t - 1 / RATE) / .160)
        samples.append(value)
    gain = peak / max(abs(v) for v in samples)
    pcm = [round(v * gain * 32767) for v in samples]
    assert pcm[0] == pcm[-1] == 0 and max(abs(v) for v in pcm) < 32767
    name = f"{kind}-reference-v1.wav"
    with wave.open(str(DEST / name), "wb") as out:
        out.setparams((1, 2, RATE, len(pcm), "NONE", "not compressed"))
        out.writeframes(struct.pack("<" + "h" * len(pcm), *pcm))
    return {"asset": name, "durationMs": round(seconds * 1000),
            "peakDbFS": round(20 * math.log10(max(abs(v) for v in pcm) / 32767), 2),
            "zeroEndpoints": True, "clippedSamples": 0}


results = [cue("focus", .110, .48), cue("confirm", .850, .56), cue("back", .850, .56)]
print(json.dumps(results, indent=2))
