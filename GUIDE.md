# Tremor: how it all works

English · [Русский](GUIDE.ru.md)

Everything the mod does, in plain words. The numbers are the defaults: almost all of them can be changed in the config, and the difficulty setting scales most of them (see the table at the end).

## Where and when it shows up

It spawns by itself near players, 40 to 80 blocks away, somewhere you'd see it or along your way.

Every 30 seconds there's a roll for each player. The base chance is 4%, and it goes up in a cave (x2.5), in the dark (x2), deep underground (x1.5) and at night (x2). These stack, so walking around in daylight it's more like once every 10–15 minutes, while in a dark cave at night it's a matter of a minute or two.

It doesn't spawn in the first minute after you join or change dimension, within 32 blocks of the world spawn or of a player's bed or respawn anchor, or on Peaceful.

There's one per part of the world. A new one never appears within 500 blocks of another, and there are at most 4 per dimension. If two of them meet (closer than 100 blocks), the calmer one turns away, calms down and sinks.

It lives in the Overworld and the Nether. In the Nether it's angrier: it gets angry 1.5 times faster and moves 1.25 times faster.

A natural one leaves on its own: when no player has been within 96 blocks for 2 minutes, or when nothing has happened around it for 10 minutes. It sinks into the ground and it's gone, and no new one shows up near there for 10 minutes. After an Awakening it goes deep, and that part of the world stays quiet for an hour.

## How it hears

It only hears what goes through the ground: footsteps, jumps, landing from a fall, mining blocks, explosions, and things that hit the ground, like arrows, snowballs and thrown items. Mobs too, but it barely gets angry at them; only players really make it mad.

- What you stand on matters. Stone carries a sound far, sand and gravel less, wool and carpets almost nothing. Leaves are the other way round: walking on them rustles, louder than a step on stone.
- Sprinting is the loudest thing you do on foot, apart from falling. Riding a horse or a minecart is louder than walking.
- Sneaking makes no sound. Standing still makes no sound.
- In water or in a boat you're much quieter. In the air you make nothing at all until you land.
- It never hears further than 96 blocks, and most things it hears from much closer.
- Deeper is worse. Below y=40 it starts to get meaner, and from y=-40 down it hears 1.5 times better, gets angry 1.5 times faster and moves 1.25 times faster (not in the Nether).
- With Simple Voice Chat, talking counts: your voice goes into the ground under you, and the louder you are, the further it carries. A whisper is about a third as loud. This can be turned off.
- Boots with Muffled Steps make your steps and landings 5 times quieter.

You can use this. An arrow or a thrown item landing somewhere makes a sound there, so you can send it away from you.

## Its moods

It has an anger level from 0 to 100. Everything it hears adds to it, and it cools down by itself over time, three times faster once it's been quiet for 20 seconds.

- Calm (below 25). It wanders around its area. Only louder sounds make it react.
- Wary (25 and up). It freezes and sends rings of ripples across the ground. That's it listening, three times better than usual. If you move while it's listening, it knows where you are. If you keep still, it loses interest after a while.
- Hunting (60 and up). It's fast and goes for what it hears. If it gets to where the sound was and finds nothing, it searches around there for a bit.
- Peak (100). It goes for your sounds even faster, and its anger stays at the top while it looks for you. If its mound gets within 8 blocks of you (and not too far above or below), the Awakening starts. If it doesn't reach anyone in 35 seconds, it calms down to hunting. To lose it: stop making noise, quietly get more than about 12 blocks away from where it heard you (not towards it), and keep still.

It crawls along any surface: floors, walls, cave ceilings. It can dive through up to 4 blocks of rock to another surface, and it jumps across gaps of air: 1 block when calm, 2 when wary, 3 when hunting, 5 in a frenzy. You'll see the hill pour over from one side to the other.

When it's hunting (or at its peak) and runs into you, it hits: about 2 hearts, and it throws you up and back. Animals and monsters it runs into get pulled into the ground.

## Ambush

When it's wary or hunting and senses a player walking towards it who can't see it, it may lie in wait instead (about a third of the time). It flattens into a wide, low mound under the floor and breathes. It only does this where there are at least four blocks of earth or stone under the floor.

Step on it and the ground turns into quicksand that looks exactly like the ground around it. You have about 2 seconds to back out onto solid ground. After that it holds you, and in about 5 seconds it pulls you under, straight into its hollow. While it's pulling, nobody can break that ground.

If you dig up the spot where it's lying in wait, it goes into a rage and comes for whoever dug. If you get out of the quicksand in time, it starts hunting. It waits for up to 40 seconds and can lie in wait again after 90.

## The Awakening

This happens when it reaches you at the peak of its anger (or almost at once after a thrown shard, see below).

1. The build-up. The whole area within 32 blocks of where you stood becomes the creature. Sounds go quiet, the ground breathes, and every step anyone takes in there rings out across the ground. You have 7 seconds to get out of the circle. The ground grabs at your feet: you get slower and your jumps get weaker as it goes on, and the last third is dark. Get out and it's over, you got away.
2. The swallow. If you're still inside, a hill rises under you, holds you in place and closes over you. The screen goes dark.

Whatever happens, after an Awakening the creature itself is gone: it goes deep.

## Inside: the hollow

You end up in a copy of the place you were taken from, made just for you, in a separate dimension. Other players don't see any of it; for them the ground where you stood is just flat again.

- It's dark. There's black fog, you only see about 5 blocks around you, and you can't sprint. A placed torch lights things up, but the fog stays.
- You have 60 seconds. The bar at the top of the screen shows how much is left, and eating or drinking doesn't change it. When it runs out, the ground takes you.
- Somewhere 40–60 steps away, through tunnels dug through the copy, there's a chamber with its heart, the Heart Node. There are dead ends too.
- The heart beats. Every beat sends a ring through the ground (you hear the thud and see the dust), and the beats get faster as the walls close. Follow the rings back to where they come from.
- To break the heart, hold the attack button on it: with an iron pickaxe it takes under a second, by hand about 4 seconds. Let go and it starts over.
- After 10 seconds the edges start closing in. They close faster the more noise you make: walking on stone more than doubles it, sneaking adds nothing. They stop a few blocks from where you arrived, and the way to the node always stays open.
- Throwing an item or shooting an arrow somewhere away from you is a lure: the closing stops for 4 seconds (not more often than every 8 seconds).
- Don't stand still. After 3 seconds in one spot the ground under you goes soft and starts pulling you in; stay on it for 10 seconds and that's the end. Step off and it sets again.
- You can dig, but it's very slow in there.
- The walls around you bulge and breathe.

There's one more way out: a narrow tunnel (one block wide, two high) that starts a few blocks from where you arrived and winds out to the edge. Nothing marks it. You only see the light at its end once you're already in it, and as the walls close in it's just a dark hole in one of them.

## How it ends

- You break the heart: you win. You come back out where you were swallowed, a hill rises and lets you go, and there's a shard in your inventory.
- You get out through the tunnel at the edge: you're alive and keep everything, and you come out a little away from where you were taken. But a crater still opens there. Breaking the heart saves the place; the edge only saves you.
- Time runs out, or the soft ground gets you: you lose. A real crater opens in the world where you were swallowed: an uneven funnel with steep walls, about 12 blocks in radius and 20 deep in the middle, caving in from the top down. On the surface it also takes whatever stands over it, hills and trees included. By default you die, and your things end up in rubble caches scattered over the bottom of the crater, some of them buried; your experience lies there too.

The crater never takes chests or other blocks with block entities, unbreakable blocks, spawn protection or blocks tagged `#tremor:protected`, and it doesn't let water or lava pour in. A totem of undying still saves you; `keepInventory` and the curse of vanishing work as usual. If the config says a defeat doesn't kill (`awakening.lethal`), or a totem saved you, you come out on the bottom of the crater alive with everything, but badly weakened: 1 health, blind, dizzy, weak and slow for half a minute, and hungry, so you can't sprint until you eat.

## Shards and what they make

You get a shard for breaking the heart.

- Seismograph: a shard, 3 copper ingots and a redstone. Its needle points at the creature within 64 blocks and trembles more the angrier it is.
- Geophone: a shard, an amethyst shard, 2 redstone and 3 cobbled deepslate. A block that gives a redstone signal (1 to 15) the closer the creature is, within 32 blocks; a comparator next to it reads its mood. Wire it to a lamp or a bell and your base warns you.
- Muffled Steps: a book, 2 shards, 2 diamonds and any wool make the enchanted book. Put it on boots with an anvil: steps and landings are 5 times quieter.

Never throw a shard on the ground. Nobody can pick it back up: a little mound rises over it where it lands and takes it in a second and a half. The creature of that area (or a new one that rises about 24 blocks away) goes into a frenzy and comes straight for you, 4.5 times faster than when it hunts and much taller. If it reaches you, the Awakening starts almost at once, with no time to run, and the hollow is a nightmare one: only 30 seconds, the heart further away, the walls close four times faster with no pause, the ground goes soft quicker, there's no way out through the edge, winning gives no shard, and losing kills you and takes everything you carry. If it hasn't reached you in 90 seconds, the frenzy dies down.

## Difficulty

One setting for the whole mod: `difficulty` in `config/tremor-common.toml`, `/tremor difficulty <calm|normal|hard|nightmare>` in game (operators) or the multitool menu. It multiplies the other settings rather than replacing them, so whatever you tuned by hand stays tuned, just softer or harsher.

| | Calm | Normal | Hard | Nightmare |
|---|---|---|---|---|
| Loudness it needs to hear you | x1.5 (hears less) | x1 | x0.75 | x0.6 |
| Anger from what it hears | x0.6 | x1 | x1.3 | x1.6 |
| Speed | x0.85 | x1 | x1.15 | x1.3 |
| Chance to show up | x0.5 | x1 | x1.5 | x2 |
| Most creatures in a dimension | 2 fewer (at least 1) | as set | 1 more | 2 more |
| Time to get out of the zone | x1.7 | x1 | x0.7 | x0.55 |
| Time in the hollow | x1.5 | x1 | x0.83 | x0.67 |
| Distance to the heart | x0.75 | x1 | x1.17 | x1.33 |
| How fast the walls close | x0.75 | x1 | x1.25 | x1.5 |
| Ambush chance | x0.45 | x1 | x1.4 | x2 |
| Damage of its hit | x0.5 | x1 | x1.5 | x2 |
| Losing in the hollow | doesn't kill | as set (kills by default) | kills | kills and takes everything |

## Playing with others

Install it on the server and on every client. Each part of the world where players are can have its own creature. Everyone nearby sees the mound, the ripples and the Awakening, but the hollow is per player: you go in alone.

## Small things worth knowing

- When it ripples, freeze. That's when it hears best.
- Sneaking is silent. When it's close, sneak away instead of running.
- During the build-up, run straight for the nearest edge of the circle. It only gets harder to move the longer you wait.
- A base on wool or carpet is a quiet base. A geophone wired to a lamp tells you when something's coming.
- Mining deep is loud and it's meaner down there. Keep a way out in mind.
- In the hollow: follow the rings, sneak to slow the walls, keep moving, and throw something away from you when the walls get close. While you walk along the walls, look for a dark hole: that might be the tunnel out.

## Settings for your PC

In `config/tremor-client.toml`: `render.quality` (LOW / MEDIUM / HIGH) for weaker PCs, and `render.style`: BLOCKS (the blocks themselves move) or WARP (a smooth hill).
