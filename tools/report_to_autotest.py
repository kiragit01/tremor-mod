"""Builds an autotest that lays the ground of a Tremor report (its "## ground" section) again, offset into the
autotest world, spawns the tremor where the report had it and shoots from where the player stood.

usage: report_to_autotest.py report.txt out.txt dx dy dz
"""
import re
import sys

report, out, dx, dy, dz = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
text = open(report, encoding='utf-8').read()

m = re.search(r'## ground \(client\): (\d+) x (\d+) x (\d+) blocks from (-?\d+) (-?\d+) (-?\d+)', text)
sx, sy, sz, x0, y0, z0 = map(int, m.groups())
pal_line = re.search(r'^palette: (.*)$', text, re.M).group(1)
palette = {'.': 'minecraft:air'}
for token in re.findall(r'(\S)=(\S+)', pal_line):
    palette[token[0]] = token[1]

layers = {}
cur = None
for line in text[m.end():].splitlines():
    lm = re.match(r'^y (-?\d+)(?:\.\.(-?\d+))?(?: all (\S))?$', line)
    if lm:
        a = int(lm.group(1))
        b = int(lm.group(2)) if lm.group(2) else a
        if lm.group(3):
            for y in range(a, b + 1):
                layers[y] = {z: lm.group(3) * sx for z in range(z0, z0 + sz)}
            cur = None
        else:
            cur = a
            layers[cur] = {}
        continue
    rm = re.match(r'^\s*(-?\d+) (\S+)$', line)
    if rm and cur is not None:
        layers[cur][int(rm.group(1))] = rm.group(2)

pos = re.search(r'^position (-?[\d.]+) (-?[\d.]+) (-?[\d.]+), normal', text, re.M)
tx, ty, tz = (float(v) for v in pos.groups())
pl = re.search(r'^player at (-?[\d.]+) (-?[\d.]+) (-?[\d.]+), yaw (-?[\d.]+), pitch (-?[\d.]+)', text, re.M)
px, py, pz, yaw, pitch = (float(v) for v in pl.groups())
changed = re.search(r'^settings not at their default: (.*)$', text, re.M).group(1)
configs = [] if changed.strip() == 'none' else [c.strip() for c in changed.split(';') if c.strip()]
stage = re.search(r'^Tremor #\d+: (\w+)', text, re.M).group(1)

lines = ['# temporary: the ground of a report laid again (offset %d %d %d)' % (dx, dy, dz),
         'waitchunks', 'cmd gamerule commandModificationBlockLimit 2147483647', 'cmd gamemode spectator',
         'cmd time set noon', 'cmd tremor despawn all', 'cmd kill @e[type=!player]',
         'cmd tp @s %.1f %.1f %.1f' % (x0 + dx + sx / 2, y0 + dy + sy + 5, z0 + dz + sz / 2), 'wait 40', 'waitchunks',
         'cmd fill %d %d %d %d %d %d minecraft:air' % (x0 + dx - 10, y0 + dy - 2, z0 + dz - 10, x0 + dx + sx + 10,
                                                      y0 + dy + sy + 12, z0 + dz + sz + 10),
         'cmd fill %d %d %d %d %d %d minecraft:stone' % (x0 + dx - 10, y0 + dy - 6, z0 + dz - 10, x0 + dx + sx + 10,
                                                        y0 + dy - 3, z0 + dz + sz + 10)]
for y in sorted(layers):
    for z, row in sorted(layers[y].items()):
        x = 0
        while x < len(row):
            ch = row[x]
            end = x
            while end + 1 < len(row) and row[end + 1] == ch:
                end += 1
            if ch != '.':
                lines.append('cmd fill %d %d %d %d %d %d %s' % (x0 + x + dx, y + dy, z + dz, x0 + end + dx, y + dy,
                                                               z + dz, palette.get(ch, 'minecraft:stone')))
            x = end + 1
for c in configs:
    k, v = c.split('=', 1)
    lines.append('config %s %s' % (k, v))
lines += ['hud off', 'cmd tremor spawn %d %d %d' % (int(tx // 1) + dx, int(ty // 1) + dy, int(tz // 1) + dz),
          'cmd tremor ai off', 'cmd tremor stage %s' % stage, 'cmd tremor hold', 'wait 60',
          'cmd tp @s %.3f %.3f %.3f %.1f %.1f' % (px + dx, py + dy, pz + dz, yaw, pitch), 'wait 10',
          'screenshot r1_player', 'tint on', 'screenshot r1_player_tint', 'tint off',
          'cmd tp @s %.3f %.3f %.3f %.1f %.1f' % (tx + dx + 0.5, ty + dy + 4, tz + dz + 9, 180, 25), 'wait 10',
          'screenshot r2_front', 'tint on', 'screenshot r2_front_tint', 'tint off',
          'probe report', 'wait 20', 'cmd tremor despawn all']
lines.append('quit')
open(out, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
print(len(lines), 'lines;', 'tremor at', tx + dx, ty + dy, tz + dz, 'configs', configs, 'stage', stage)
