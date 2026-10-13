"""Synthesises the sounds of the mod (no recordings): mono 44.1 kHz, written as WAV and Ogg Vorbis.

Every sound is made of filtered noise, short grains of noise (crumbling and grinding rock), low oscillators (thumps,
growls, drones) and a small cave reverb. Loops (rustle, hum) are built periodic, so they repeat without a seam.

usage: python tools/sounds/synth.py <out dir> [--wav] [name ...]     (ffmpeg on the PATH for the .ogg files)
The mod's own: python tools/sounds/synth.py src/main/resources/assets/tremor/sounds
"""
import os
import subprocess
import sys
import wave

import numpy as np

SR = 44100


def samples(seconds):
    return int(round(seconds * SR))


def spectrum_filter(x, lo=None, hi=None, order=2, peak=None):
    """Zero-phase filter in the frequency domain (circular: pad one-shots first). peak = (freq, q, gain)."""
    n = len(x)
    f = np.fft.rfftfreq(n, 1 / SR)
    f_safe = np.maximum(f, 1e-3)
    h = np.ones_like(f)
    if hi:
        h /= np.sqrt(1 + (f_safe / hi) ** (2 * order))
    if lo:
        h /= np.sqrt(1 + (lo / f_safe) ** (2 * order))
    if peak:
        fc, q, gain = peak
        h *= 1 + (gain - 1) / (1 + ((f_safe - fc) / (fc / q)) ** 2)
    return np.fft.irfft(np.fft.rfft(x) * h, n)


def filt(x, lo=None, hi=None, order=2, peak=None, pad=0.5):
    """Filter for a one-shot: zero padding takes the ringing instead of wrapping it round."""
    p = samples(pad)
    y = spectrum_filter(np.concatenate([np.zeros(p), x, np.zeros(p)]), lo, hi, order, peak)
    return y[p:p + len(x)]


def noise(n, slope=0.0, rng=None):
    """Periodic noise whose amplitude spectrum goes as f^(slope/6.02) (0 white, -3 pink, -6 brown)."""
    spec = rng.normal(size=n // 2 + 1) + 1j * rng.normal(size=n // 2 + 1)
    f = np.fft.rfftfreq(n, 1 / SR)
    f[0] = f[1]
    spec *= (f / 1000.0) ** (slope / 6.02)
    x = np.fft.irfft(spec, n)
    return x / np.max(np.abs(x))


def smooth(n, rate, rng, periodic=False):
    """Slowly wandering control signal in -1..1 (noise low-passed at {@code rate} Hz)."""
    x = spectrum_filter(rng.normal(size=n), hi=rate, order=4) if periodic else filt(rng.normal(size=n), hi=rate,
                                                                                         order=4, pad=2)
    return x / (np.max(np.abs(x)) + 1e-12)


def env(n, attack, decay, hold=0.0, curve=1.0):
    """Linear attack, hold, then exponential decay with time constant {@code decay} (seconds)."""
    t = np.arange(n) / SR
    e = np.where(t < attack, (t / max(attack, 1e-6)) ** curve, 1.0)
    after = np.maximum(t - attack - hold, 0)
    return e * np.exp(-after / decay)


def swell(n, attack, release):
    """Up over {@code attack} seconds (raised cosine), down over the last {@code release} seconds."""
    t = np.arange(n) / SR
    dur = n / SR
    up = np.clip(t / attack, 0, 1)
    down = np.clip((dur - t) / release, 0, 1)
    return (0.5 - 0.5 * np.cos(np.pi * up)) * (0.5 - 0.5 * np.cos(np.pi * down))


def grains(n, rate, dur, freq, amp, rng, width=1.0, circular=False, density_env=None):
    """Short decaying bursts of band-passed noise: crumbling, grinding, gravel. dur/freq/amp are (min, max)."""
    out = np.zeros(n)
    count = int(rate * n / SR)
    for _ in range(count):
        start = int(rng.integers(n))
        if density_env is not None and rng.random() > density_env[start]:
            continue
        length = max(16, samples(rng.uniform(*dur)))
        burst = rng.normal(size=length) * np.exp(-np.arange(length) / (length / 4))
        fc = np.exp(rng.uniform(np.log(freq[0]), np.log(freq[1])))
        burst = spectrum_filter(np.concatenate([burst, np.zeros(length)]), lo=fc / (1 + width), hi=fc * (1 + width),
                                order=2)
        burst *= rng.uniform(*amp) / (np.max(np.abs(burst)) + 1e-12)
        idx = np.arange(start, start + len(burst))
        if circular:
            np.add.at(out, idx % n, burst)
        else:
            keep = idx < n
            out[idx[keep]] += burst[keep]
    return out


def osc(freq, phase0=0.0, shape='sine'):
    """Oscillator over a frequency curve (Hz per sample)."""
    phase = phase0 + 2 * np.pi * np.cumsum(freq) / SR
    if shape == 'sine':
        return np.sin(phase)
    return 2 * ((phase / (2 * np.pi)) % 1) - 1  # saw


def thump(seconds, f_start, f_end, decay, rng, click=0.3):
    """A deep thud: a sine whose pitch falls, with a dull click of noise on its front."""
    n = samples(seconds)
    t = np.arange(n) / SR
    f = f_end + (f_start - f_end) * np.exp(-t / 0.05)
    body = osc(f) * env(n, 0.004, decay)
    front = filt(rng.normal(size=n), lo=40, hi=700) * env(n, 0.001, 0.02) * click
    return body + front


def growl(seconds, f_start, f_end, rng, jitter=0.06, formants=((260, 4, 3.0), (620, 5, 2.0)), cutoff=700):
    """A low, rough voice of rock: a jittering saw through formant peaks, softly saturated."""
    n = samples(seconds)
    f = np.linspace(f_start, f_end, n) * (1 + jitter * smooth(n, 6, rng)) * (1 + 0.02 * rng.normal(size=n))
    saw = osc(f, shape='saw') + 0.6 * osc(f * 0.5, shape='saw')
    voice = filt(saw, lo=25, hi=cutoff, order=2)
    for peak in formants:
        voice = voice + 0.6 * filt(saw, peak=peak, lo=peak[0] / 2, hi=peak[0] * 2, order=1)
    return np.tanh(1.5 * voice / (np.max(np.abs(voice)) + 1e-12))


def cave(x, rt=1.4, wet=0.3, lp=2500, circular=False, rng=None):
    """A small cave: convolution with decaying, darkened noise (-60 dB after {@code rt} seconds)."""
    m = samples(rt * 1.2)
    ir = rng.normal(size=m) * np.exp(-6.91 * np.arange(m) / (rt * SR))
    ir = filt(ir, lo=50, hi=lp, order=1)
    ir = np.concatenate([np.zeros(samples(0.012)), ir])
    ir /= np.sqrt(np.sum(ir ** 2))
    if circular:
        n = len(x)
        y = np.fft.irfft(np.fft.rfft(x) * np.fft.rfft(np.pad(ir, (0, max(0, n - len(ir))))[:n]), n)
        return (1 - wet) * x + wet * y * 0.5
    n = len(x) + len(ir)
    y = np.fft.irfft(np.fft.rfft(x, n) * np.fft.rfft(ir, n), n)
    dry = np.concatenate([x, np.zeros(len(ir))])
    return (1 - wet) * dry + wet * y * 0.5


def presence(x, amount, circular=False):
    """Harmonics of the lows (a saturated copy, band-passed 110..900 Hz): small speakers that cannot play the deep
    fundamentals still let the ear hear them through these."""
    h = np.tanh(4 * x / (np.max(np.abs(x)) + 1e-12))
    h = spectrum_filter(h, lo=110, hi=900, order=2) if circular else filt(h, lo=110, hi=900, order=2)
    return x + amount * h * np.std(x) / (np.std(h) + 1e-12)


def finish(x, peak_db=-1.0, fade=0.03, loop=False):
    """Removes the DC, fades the ends of a one-shot, normalises the peak."""
    x = x - np.mean(x)
    if not loop:
        f = samples(fade)
        x[:f] *= np.linspace(0, 1, f) ** 2
        x[-f:] *= np.linspace(1, 0, f) ** 2
        nz = np.nonzero(np.abs(x) > 1e-4 * np.max(np.abs(x)))[0]
        x = x[:nz[-1] + samples(0.05)] if len(nz) else x
    return x * (10 ** (peak_db / 20) / (np.max(np.abs(x)) + 1e-12))


# ---- the sounds ------------------------------------------------------------------------------------------------

def rustle(rng):
    """Loop: the ground grinding as the bump crawls through it. Low and soft, no tone to tire the ear."""
    n = samples(6.0)
    body = spectrum_filter(noise(n, -6, rng), lo=28, hi=140, order=3)
    body *= 1 + 0.35 * np.sin(2 * np.pi * np.arange(n) / n * 3 + 1.3) + 0.25 * smooth(n, 1.5, rng, periodic=True)
    gravel = grains(n, 90, (0.006, 0.035), (300, 1400), (0.2, 1.0), rng, width=0.6, circular=True)
    gravel = spectrum_filter(gravel, hi=2200, order=2)
    crunch = grains(n, 3, (0.06, 0.16), (90, 350), (0.5, 1.0), rng, width=0.8, circular=True)
    x = body / np.std(body) * 1.0 + gravel / np.std(gravel) * 0.35 + crunch / (np.std(crunch) + 1e-12) * 0.22
    x = presence(cave(x, rt=0.9, wet=0.25, circular=True, rng=rng), 0.5, circular=True)
    return finish(x, peak_db=-4, loop=True)


def rumble(rng):
    """A deep rumble of the rock, swelling and dying away: the entity grows alert."""
    n = samples(3.2)
    body = filt(noise(n, -6, rng), lo=22, hi=95, order=3)
    sub = osc(np.full(n, 31.0) * (1 + 0.15 * smooth(n, 3, rng))) * 0.5
    crumble = filt(grains(n, 25, (0.01, 0.05), (150, 900), (0.2, 1.0), rng, width=0.7), hi=1800)
    x = body / np.std(body) + sub + crumble / (np.std(crumble) + 1e-12) * 0.15
    x *= swell(n, 0.45, 2.2)
    return finish(presence(cave(x, rt=1.8, wet=0.35, rng=rng), 0.6))


def crack(rng):
    """Rock cracking: a hard snap, a few smaller ones running off, a dull thud and grit settling."""
    n = samples(1.6)
    x = np.zeros(n)
    t0 = samples(0.02)
    snaps = [(0, 1.0)] + [(rng.uniform(0.015, 0.25), rng.uniform(0.15, 0.55)) for _ in range(6)]
    for at, a in snaps:
        m = samples(rng.uniform(0.004, 0.012))
        s = rng.normal(size=m) * np.exp(-np.arange(m) / (m / 5)) * a
        i = t0 + samples(at)
        x[i:i + m] += s
    x = filt(x, lo=900, hi=6500, order=2) * 1.2
    body = thump(1.2, 85, 42, 0.12, rng, click=0.6)
    grit = filt(grains(n, 30, (0.008, 0.03), (250, 1600), (0.1, 0.6), rng, width=0.6), hi=2500)
    grit *= env(n, 0.05, 0.35)
    thud = np.zeros(n)
    thud[t0:t0 + len(body)] = body[:n - t0]
    y = x / np.max(np.abs(x)) * 1.6 + thud * 0.45 + grit / (np.max(np.abs(grit)) + 1e-12) * 0.4
    return finish(cave(y, rt=1.2, wet=0.3, rng=rng))


def pulse(rng):
    """A heartbeat deep in the ground: lub-dub."""
    n = samples(1.1)
    lub = thump(0.6, 62, 38, 0.14, rng, click=0.25)
    dub = thump(0.6, 70, 44, 0.11, rng, click=0.2) * 0.75
    x = np.zeros(n)
    x[:len(lub)] += lub
    s = samples(0.27)
    x[s:s + len(dub)] += dub[:n - s]
    x = filt(x, hi=400, order=2)
    return finish(presence(cave(x, rt=1.0, wet=0.25, lp=1200, rng=rng), 1.5))


def thump_sound(rng):
    """A faint deep thump of the ground under a player in the hollow as a ring of the node runs under them."""
    x = thump(0.9, 55, 34, 0.18, rng, click=0.15)
    soil = filt(rng.normal(size=len(x)), lo=60, hi=300) * env(len(x), 0.01, 0.08) * 0.25
    return finish(presence(cave(filt(x + soil, hi=300), rt=0.9, wet=0.3, lp=900, rng=rng), 1.5))


def strike(rng):
    """The bump strikes: a heavy blow from below, rock bursting, rubble falling back."""
    n = samples(2.0)
    boom = thump(1.5, 70, 26, 0.3, rng, click=1.0) * 1.2
    burst = filt(rng.normal(size=n), lo=60, hi=900, order=2) * env(n, 0.002, 0.18)
    rubble = filt(grains(n, 60, (0.01, 0.06), (180, 1500), (0.2, 1.0), rng, width=0.6), hi=2500)
    rubble *= env(n, 0.08, 0.5)
    x = np.zeros(n)
    x[:len(boom)] += boom
    x += burst / np.max(np.abs(burst)) * 0.7 + rubble / (np.max(np.abs(rubble)) + 1e-12) * 0.5
    x = np.tanh(1.3 * x)
    return finish(presence(cave(x, rt=1.4, wet=0.3, rng=rng), 0.5))


def sigh(rng):
    """The ground breathes out: a long, low exhale of air through soil, its colour sinking."""
    n = samples(2.6)
    air = noise(n, -3, rng)
    bright = filt(air, lo=180, hi=900, order=2)
    dark = filt(air, lo=80, hi=320, order=2)
    blend = np.linspace(0, 1, n) ** 0.8
    breath = (bright / np.std(bright)) * (1 - blend) + (dark / np.std(dark)) * blend
    low = filt(noise(n, -6, rng), lo=25, hi=90)
    x = (breath * 0.8 + low / np.std(low) * 0.5) * swell(n, 0.7, 1.6)
    return finish(cave(x, rt=1.6, wet=0.35, rng=rng))


def awaken(rng):
    """The Awakening: the earth heaves up around the player, a growl rising out of the grinding, then a deep drop."""
    n = samples(4.2)
    rise = np.linspace(0, 1, n)
    body = filt(noise(n, -6, rng), lo=22, hi=160, order=3)
    voice = growl(4.2, 34, 56, rng, cutoff=500)
    grind = filt(grains(n, 120, (0.006, 0.04), (200, 1300), (0.1, 1.0), rng, width=0.6, density_env=rise), hi=2400)
    x = body / np.std(body) * (0.4 + 0.8 * rise) + voice * 0.55 * rise ** 1.5 + grind / (np.std(grind) + 1e-12) * 0.2
    x *= swell(n, 1.2, 0.6)
    drop = thump(2.0, 50, 24, 0.5, rng, click=0.6)
    y = np.zeros(n + len(drop))
    y[:n] += x
    y[n - samples(0.3):n - samples(0.3) + len(drop)] += drop * 1.4
    return finish(presence(cave(np.tanh(y), rt=2.0, wet=0.35, rng=rng), 0.45))


def rage(rng):
    """The entity rises to its peak: a roar of the rock, heard far and muffled by the ground in between."""
    n = samples(3.0)
    voice = growl(3.0, 46, 38, rng, jitter=0.1, cutoff=650)
    body = filt(noise(n, -6, rng), lo=25, hi=120, order=3)
    x = (voice * 0.9 + body / np.std(body) * 0.5) * swell(n, 0.5, 1.6)
    x = filt(x, hi=1100, order=2)
    return finish(presence(cave(x, rt=2.4, wet=0.45, lp=1500, rng=rng), 0.5))


def release(rng):
    """The ground lets go: a long groan sinking away, then grit settling."""
    n = samples(3.6)
    voice = growl(3.6, 48, 26, rng, jitter=0.05, cutoff=420) * swell(n, 0.3, 2.4)
    body = filt(noise(n, -6, rng), lo=22, hi=90, order=3) * swell(n, 0.2, 3.0)
    grit = filt(grains(n, 20, (0.01, 0.04), (300, 1500), (0.1, 0.6), rng, width=0.6), hi=2200)
    grit *= np.clip((np.arange(n) / SR - 1.8) / 1.2, 0, 1) * swell(n, 0.01, 0.8)
    x = voice * 0.8 + body / np.std(body) * 0.5 + grit / (np.std(grit) + 1e-12) * 0.15
    return finish(presence(cave(x, rt=2.0, wet=0.35, rng=rng), 0.5))


def hum(rng):
    """Loop: the low hum of the ground in the zone of an Awakening. Beating low tones over a faint rumble."""
    n = samples(8.0)
    t = np.arange(n)
    tones = sum(a * np.sin(2 * np.pi * (round(f * n / SR) / n) * t + rng.uniform(0, 6.28))
                for f, a in [(41, 1.0), (55, 0.7), (61.6, 0.5), (82, 0.25)])
    tones *= 1 + 0.2 * np.sin(2 * np.pi * 2 * t / n)
    body = spectrum_filter(noise(n, -6, rng), lo=25, hi=110, order=3)
    x = tones / np.std(tones) * 0.6 + body / np.std(body) * 0.5
    return finish(presence(x, 1.0, circular=True), peak_db=-5, loop=True)


def pull(rng):
    """The soft ground of the hollow drawing a player down: a slow, wet squelch and a few bubbles."""
    n = samples(1.3)
    t = np.arange(n) / SR
    mud = noise(n, -3, rng)
    a = filt(mud, lo=90, hi=600, peak=(320, 3, 4.0))
    b = filt(mud, lo=60, hi=300, peak=(140, 3, 4.0))
    blend = np.clip(t / 0.9, 0, 1)
    x = (a / np.std(a) * (1 - blend) + b / np.std(b) * blend) * swell(n, 0.25, 0.6)
    for _ in range(4):
        at = rng.uniform(0.2, 1.0)
        m = samples(rng.uniform(0.03, 0.06))
        f = np.linspace(rng.uniform(140, 220), rng.uniform(320, 480), m)
        blip = osc(f) * np.sin(np.linspace(0, np.pi, m)) * rng.uniform(0.3, 0.6)
        i = samples(at)
        x[i:i + m] += blip * np.std(x) * 3
    return finish(cave(filt(x, hi=1600), rt=0.7, wet=0.2, rng=rng))


def ripple(rng):
    """A listening entity sends a ring through the ground: a soft, low swell with a faint creak of stone."""
    n = samples(1.8)
    t = np.arange(n) / SR
    wob = osc(48 + 6 * np.sin(2 * np.pi * 1.5 * t)) * swell(n, 0.35, 1.2)
    creak = filt(noise(n, 0, rng), lo=600, hi=1300, order=3) * (0.5 + 0.5 * smooth(n, 12, rng)) ** 2
    creak *= swell(n, 0.5, 1.0) * 0.12
    body = filt(noise(n, -6, rng), lo=25, hi=100) * swell(n, 0.4, 1.2)
    x = wob * 0.8 + body / np.std(body) * 0.4 + creak / (np.std(creak) + 1e-12) * 0.1
    return finish(presence(cave(x, rt=1.6, wet=0.4, lp=1400, rng=rng), 0.6))


def escape(rng):
    """Out into the light: a rush of air toward the opening, rising and brightening, the ground falling away behind,
    gone as the screen turns white. Short and dry: the move out of the hollow stops every sound."""
    n = samples(1.1)
    k = np.arange(n) / (n - 1)
    air = noise(n, -3, rng)
    low = filt(air, lo=120, hi=600, order=2)
    mid = filt(air, lo=500, hi=1800, order=2)
    high = filt(air, lo=1500, hi=5000, order=2)
    rush = (low / np.std(low) * (1 - k) ** 1.5 + mid / np.std(mid) * np.sin(np.pi * k) * 0.9
            + high / np.std(high) * k ** 2 * 0.6) * swell(n, 0.75, 0.3)
    behind = filt(noise(n, -6, rng), lo=25, hi=110, order=3) * swell(n, 0.15, 0.9)
    x = rush * 0.8 + behind / np.std(behind) * 0.45
    return finish(presence(cave(x, rt=0.5, wet=0.2, rng=rng), 0.3))


#: New sounds go at the end: the seed of each comes from its place here.
SOUNDS = {'rustle': rustle, 'rumble': rumble, 'crack': crack, 'pulse': pulse, 'thump': thump_sound, 'strike': strike,
          'sigh': sigh, 'awaken': awaken, 'rage': rage, 'release': release, 'hum': hum, 'pull': pull, 'ripple': ripple,
          'escape': escape}


def write_wav(path, x):
    pcm = (np.clip(x, -1, 1) * 32767).astype('<i2')
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


#: Sounds heard again and again get variants (other seeds of the same recipe): name_1.ogg ... name_k.ogg.
VARIANTS = {'crack': 3, 'pulse': 3, 'thump': 3, 'pull': 3, 'ripple': 3, 'strike': 2, 'rumble': 2, 'sigh': 2}


def main():
    args = sys.argv[1:]
    keep_wav = '--wav' in args
    args = [a for a in args if a != '--wav']
    out = args[0]
    names = args[1:] or list(SOUNDS)
    os.makedirs(out, exist_ok=True)
    for name in names:
        count = VARIANTS.get(name, 1)
        for variant in range(1, count + 1):
            rng = np.random.default_rng(1000 + list(SOUNDS).index(name) + 100 * (variant - 1))
            x = SOUNDS[name](rng)
            file = name if count == 1 else f'{name}_{variant}'
            wav = os.path.join(out, file + '.wav')
            write_wav(wav, x)
            subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', wav, '-c:a', 'libvorbis', '-q:a', '4', '-ac', '1',
                            os.path.join(out, file + '.ogg')], check=True)
            if not keep_wav:
                os.remove(wav)
            print(f'{file}: {len(x) / SR:.2f} s, rms {20 * np.log10(np.sqrt(np.mean(x ** 2))):.1f} dBFS')


if __name__ == '__main__':
    main()
