"""Draws the animated texture of tremor:heart_node (SPEC 9, "Узел"): a block of dark flesh with veins and a glowing
core that beats twice per cycle (lub-dub), as a vertical strip of 16x16 frames with its .mcmeta.

Usage: python tools/heart_node_texture.py  (needs Pillow; writes into src/main/resources/assets/tremor/textures/block)
Deterministic: the same files on every run.
"""
import json
import math
import os
import random

from PIL import Image

SIZE = 16
FRAMES = 16
# Ticks per frame: one cycle is FRAMES * FRAME_TIME = 32 ticks, about the node's beat when the player arrives.
FRAME_TIME = 2
OUT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'tremor', 'textures',
                   'block')

BASE = (58, 8, 16)
FLESH = (118, 22, 34)
VEIN = (176, 40, 52)
GLOW = (255, 150, 120)


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def beat(phase):
    """Strength of the beat at a phase of the cycle, 0..1: a strong beat, then a weaker one, then rest."""
    lub = math.exp(-((phase - 0.06) / 0.07) ** 2)
    dub = 0.65 * math.exp(-((phase - 0.32) / 0.07) ** 2)
    return min(1.0, lub + dub)


def layout():
    """The still parts: flesh shading per pixel, and how much of a vein each pixel is."""
    rng = random.Random(20261003)
    flesh = [[rng.random() for _ in range(SIZE)] for _ in range(SIZE)]
    # Smooth the grain a little so it reads as flesh, not noise.
    smooth = [[0.0] * SIZE for _ in range(SIZE)]
    for y in range(SIZE):
        for x in range(SIZE):
            total = 0.0
            for dy in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    total += flesh[(y + dy) % SIZE][(x + dx) % SIZE]
            smooth[y][x] = total / 9
    veins = [[0.0] * SIZE for _ in range(SIZE)]
    centre = (SIZE - 1) / 2
    for i in range(7):
        angle = i * 2 * math.pi / 7 + rng.uniform(-0.3, 0.3)
        x, y = centre, centre
        strength = 1.0
        for _ in range(12):
            angle += rng.uniform(-0.6, 0.6)
            x += math.cos(angle)
            y += math.sin(angle)
            px, py = int(round(x)), int(round(y))
            if not (0 <= px < SIZE and 0 <= py < SIZE):
                break
            veins[py][px] = max(veins[py][px], strength)
            strength *= 0.88
    return smooth, veins


def frame(smooth, veins, phase):
    pulse = beat(phase)
    image = Image.new('RGBA', (SIZE, SIZE))
    centre = (SIZE - 1) / 2
    for y in range(SIZE):
        for x in range(SIZE):
            r = math.hypot(x - centre, y - centre) / centre
            # Darker towards the edge of the block, so neighbouring nodes stay apart.
            colour = mix(BASE, FLESH, (0.15 + 1.1 * (smooth[y][x] - 0.3)) * (1.15 - 0.55 * r) + 0.2 * pulse)
            colour = mix(colour, VEIN, veins[y][x] * (0.6 + 0.4 * pulse))
            core = max(0.0, 1 - r / 0.6)
            colour = mix(colour, GLOW, core * core * (0.25 + 0.75 * pulse) + veins[y][x] * 0.35 * pulse)
            image.putpixel((x, y), tuple(int(round(c)) for c in colour) + (255,))
    return image


def main():
    smooth, veins = layout()
    strip = Image.new('RGBA', (SIZE, SIZE * FRAMES))
    for i in range(FRAMES):
        strip.paste(frame(smooth, veins, i / FRAMES), (0, i * SIZE))
    os.makedirs(OUT, exist_ok=True)
    strip.save(os.path.join(OUT, 'heart_node.png'))
    meta = {'animation': {'frametime': FRAME_TIME, 'interpolate': True}}
    with open(os.path.join(OUT, 'heart_node.png.mcmeta'), 'w', encoding='utf-8', newline='\n') as f:
        f.write(json.dumps(meta, indent=2) + '\n')


if __name__ == '__main__':
    main()
