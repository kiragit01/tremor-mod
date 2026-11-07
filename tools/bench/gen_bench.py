"""Writes the scale benchmark scripts into autotest/ (bench-scale, bench-many, bench-many-ai, bench-distance). Run from the repo root."""
import math

HEAD = """waitchunks
cmd gamerule commandModificationBlockLimit 2147483647
cmd gamerule doMobSpawning false
cmd gamemode spectator
cmd time set noon
cmd weather clear
cmd tremor despawn all
cmd tremor awaken stop
cmd kill @e[type=!player]
"""
FIELD = """cmd forceload add 1020 1020 1180 1180
wait 60
waitchunks
cmd fill 1020 190 1020 1180 199 1180 minecraft:stone
cmd fill 1020 200 1020 1180 200 1180 minecraft:grass_block
cmd fill 1020 201 1020 1180 240 1180 minecraft:air
cmd fill 1020 241 1020 1180 280 1180 minecraft:air
cmd kill @e[type=minecraft:item]
"""


def shape(s):
    lines = []
    for key, base, cap in (("amplitude", 2.0, 8.0), ("sigmaFront", 1.6, 16), ("sigmaBack", 2.6, 16),
                           ("sigmaSide", 2.2, 16), ("trailSigma", 3.0, 16), ("trailLag", 4.0, 32)):
        lines.append(f"config shape.{key} {min(cap, base * s):.2f}")
    return "\n".join(lines) + "\n"


def scale():
    out = ["# Scale benchmark: one tremor of growing size (shape x1..x4, the config's cap), still and crawling: client frame",
           "# times, the renderer's cost, copies and vertices (bench), the server's tick (/tick query). Budget 20000.",
           "# Run: gradlew runClientAutotest -Ptremor_autotest_script=bench-scale.txt", HEAD,
           "cmd tp @s 1100.5 245.0 1050.5 0 40", "wait 40", "waitchunks", FIELD,
           "config client:render.maxDeformedBlocks 20000", "config spawn.maxEntities 64", "hud off", "fps 260",
           "wait 40", "log --- baseline", "bench baseline 100", "cmd tick query"]
    for s in (1, 2, 3, 4):
        out += [f"log --- size x{s}", shape(s).rstrip(),
                "cmd tremor spawn 1100 200 1100", "cmd tremor ai off", "cmd tremor stage hunting", "cmd tremor hold",
                "wait 60", f"screenshot scale_x{s}", f"bench still_x{s} 100", "cmd tick query",
                "cmd tremor goto 1100 200 1150", "wait 10", f"bench crawl_x{s} 100", "cmd tick query",
                "cmd tremor despawn all", "wait 40"]
    out += ["cmd forceload remove all", "quit"]
    return "\n".join(out) + "\n"


def many():
    out = ["# Many tremors of the default size crawling at once (1..64): the server's tick (/tick query) and client frame",
           "# times (bench), with the budget raised to 20000 and, for the most, the default 1500.",
           "# Run: gradlew runClientAutotest -Ptremor_autotest_script=bench-many.txt", HEAD,
           "cmd tp @s 1100.5 270.0 1060.5 0 55", "wait 40", "waitchunks", FIELD,
           "config client:render.maxDeformedBlocks 20000", "config spawn.maxEntities 64", "config spawn.spacing 0", "hud off",
           "fps 260", "wait 40", "log --- baseline", "bench baseline 100", "cmd tick query"]
    for n in (1, 4, 8, 16, 32, 64):
        side = math.ceil(math.sqrt(n))
        out.append(f"log --- {n} tremors")
        k = 0
        for i in range(side):
            for j in range(side):
                if k >= n:
                    break
                x, z = 1068 + i * 9, 1070 + j * 7
                out.append(f"cmd tremor spawn {x} 200 {z}")
                out.append(f"cmd execute positioned {x} 201 {z} run tremor ai off")
                out.append(f"cmd execute positioned {x} 201 {z} run tremor stage hunting")
                out.append(f"cmd execute positioned {x} 201 {z} run tremor hold")
                k += 1
        out.append("wait 40")
        k = 0
        for i in range(side):
            for j in range(side):
                if k >= n:
                    break
                x, z = 1068 + i * 9, 1070 + j * 7
                out.append(f"cmd execute positioned {x} 201 {z} run tremor goto {x} 200 {z + 70}")
                k += 1
        out += ["wait 10", f"bench many_{n} 100", "cmd tick query"]
        if n == 64:
            out += ["config client:render.maxDeformedBlocks 1500", "bench many_64_budget1500 60", "cmd tick query",
                    "config client:render.maxDeformedBlocks 20000"]
        out += ["cmd tremor despawn all", "wait 40"]
    out += ["cmd forceload remove all", "quit"]
    return "\n".join(out) + "\n"


def distance():
    out = ["# How far a giant tremor (shape x4) is drawn: still in the middle of the field, seen from 24 to 190 blocks",
           "# off (bench: copies drawn), with the deformation's draw distance at its default and at 512.",
           "# Run: gradlew runClientAutotest -Ptremor_autotest_script=bench-distance.txt", HEAD,
           "cmd tp @s 1100.5 230.0 1080.5 0 30", "wait 40", "waitchunks", FIELD,
           "config client:render.maxDeformedBlocks 20000", shape(4).rstrip(), "hud off", "fps 260",
           "cmd tremor spawn 1100 200 1100", "cmd tremor ai off", "cmd tremor stage hunting", "cmd tremor hold",
           "wait 60"]
    for far in ("default", "512"):
        if far == "512":
            out.append("config client:render.renderDistance 512")
        for d in (24, 48, 96, 144, 190):
            out += [f"cmd tp @s 1100.5 {200 + max(8, d // 4)}.0 {1100 - d}.5 0 0", "wait 20", "waitchunks 200",
                    "lookat 1100.5 203 1100.5", f"screenshot far_{far}_{d}", f"bench far_{far}_{d} 40"]
    out += ["cmd forceload remove all", "quit"]
    return "\n".join(out) + "\n"


def many_ai():
    out = ["# Many tremors with their minds on (1..64), hunting and wandering over the field (paths found again and again):",
           "# the server's tick (/tick query) and client frame times (bench). The player is a spectator above (not prey).",
           "# Run: gradlew runClientAutotest -Ptremor_autotest_script=bench-many-ai.txt", HEAD,
           "cmd tp @s 1100.5 270.0 1060.5 0 55", "wait 40", "waitchunks", FIELD,
           "config client:render.maxDeformedBlocks 20000", "config spawn.maxEntities 64", "config spawn.spacing 0",
           "config ambush.enabled false", "config spawn.crowdDistance 0", "hud off", "fps 260", "wait 40",
           "log --- baseline", "bench baseline 100",
           "cmd tick query"]
    for n in (1, 8, 16, 32, 64):
        side = math.ceil(math.sqrt(n))
        out.append(f"log --- {n} tremors, minds on")
        k = 0
        for i in range(side):
            for j in range(side):
                if k >= n:
                    break
                x, z = 1040 + i * 12, 1040 + j * 12
                out.append(f"cmd tremor spawn {x} 200 {z}")
                out.append(f"cmd execute positioned {x} 201 {z} run tremor stage hunting")
                k += 1
        out += ["wait 100", f"bench ai_{n} 200", "cmd tick query", "cmd tremor cache", "wait 400", "cmd tremor cache",
                "cmd tick query", "cmd tremor despawn all", "wait 40"]
    out += ["cmd forceload remove all", "quit"]
    return "\n".join(out) + "\n"


for name, text in (("bench-scale", scale()), ("bench-many", many()), ("bench-distance", distance()), ("bench-many-ai", many_ai())):
    open(f"autotest/{name}.txt", "w", encoding="utf-8").write(text)
    print(name, text.count("\n"), "lines")
