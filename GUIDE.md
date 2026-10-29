# Tremor: how it all works

English · [Русский](GUIDE.ru.md)

Everything the mod does, in plain words. The numbers here are the defaults: almost all of them can be changed in the config, and the difficulty also multiplies most of them (table at the end).

## Where and when it shows up

It appears by itself near players, 40–80 blocks away, somewhere in view or on your way.

Every 30 seconds there's a roll for each player. The base chance is 4%, and it grows in a cave (x2.5), in the dark (x2), deep underground (x1.5) and at night (x2). These multiply, so on the surface in daylight it's about once every 10–15 minutes, and in a dark cave at night it's a minute or two.

It doesn't appear in the first minute after you join or change dimension, within 32 blocks of the world spawn or of players' beds and respawn anchors, or on Peaceful.

In the config you can make it appear only where no sky is seen and it's dark, the way all hostile mobs spawn (`spawn.undergroundOnly`, off by default).

One creature per part of the world. A new one won't appear within 500 blocks of another, and there are at most 4 in a dimension. If two come closer than 100 blocks, the calmer one turns away, calms down and sinks into the ground. (These settings may change later.)

It lives in the Overworld and the Nether. In the Nether it's angrier: it gets angry 1.5 times faster and crawls 1.25 times faster. Also, in the Overworld, the deeper it is, the angrier and faster it gets.

A creature that appeared by itself also leaves by itself: when no player has been within 96 blocks for 2 minutes, or nothing has happened around it for 10 minutes. It sinks into the ground and disappears, and no new one appears nearby for another 10 minutes. After an Awakening it goes deep, and that part of the world stays quiet for an hour.

## How it hears

It only hears what goes through the ground: steps, jumps, landings, broken and placed blocks, explosions, and anything that falls on the ground, like arrows, snowballs and thrown items. It hears mobs too, but barely gets angry at them (it still reacts); what really makes it angry is players.

- What you walk on matters. Stone carries sound far, sand and gravel less, wool and carpets almost nothing. Leaves rustle under your feet louder than a step on stone.
- Sprinting is the loudest thing, apart from falls. Riding a horse or a minecart is louder than walking.
- Sneaking and standing still make no sound.
- It never hears further than 96 blocks, and most sounds it hears from much closer.
- The deeper, the worse. Below y=40 it starts to get meaner, and from y=-40 down it hears 1.5 times better, gets angry 1.5 times faster and crawls 1.25 times faster (in the Nether it's like that by default).
- With Simple Voice Chat, talking counts too: your voice goes into the ground under you, and the louder you talk, the further it carries. A whisper is about a third as loud. This can be turned off.
- Boots with Muffled Steps make your steps and landings 5 times quieter.
- An arrow or a thrown item makes noise where it lands, so you can lead the creature away from you.

## Its moods

It has an anger level from 0 to 100. Everything it hears adds to it, and the anger cools down by itself over time, three times faster after 20 seconds of quiet.

- Calm (below 25). It wanders around its area. It reacts only to louder sounds.
- Wary (from 25). It freezes and sends waves and pulses across the ground. That's it listening, three times better than usual. If you make noise at that moment, it goes for the source of the vibrations. If you freeze, it loses interest after a while.
- Hunting (from 60). Fast, goes for everything it hears. If it comes to where the sound was and finds no one, it searches the area around for a while.
- Peak (100). It goes for sounds even faster and more aggressively, and while it searches, its anger stays at the maximum. If its mound gets closer than 8 blocks to you (and not much higher or lower than you), the Awakening begins. If it reaches no one in 35 seconds, it cools down to hunting. How to shake it off: stop making noise and quietly walk more than 12 blocks away from where it heard you, or freeze and hope it doesn't reach you.

It crawls over any surface: floors, walls, cave ceilings. It can go through up to 4 blocks of rock to another surface and jumps across air: 1 block when calm, 2 when wary, 3 when hunting, 5 in a frenzy. It looks like the hill pouring over from one side to the other.

If it runs into you while hunting (or at its peak), it hits: about 2 hearts, and it knocks you back, which makes you make a sound. Animals and monsters it runs into get pulled under the ground.

Items lying on the ground get pulled under too when it crawls over them (when it isn't calm). There's no getting them back. It only leaves alone things the mod itself gave back to a player, after the hollow for example: those never despawn either. This can be turned off in the config (`contact.devourItems`).

## Ambush

When it's wary or hunting and senses a player walking towards it who can't see it, it may lie in wait instead (about a third of the time). It spreads into a wide, low mound under the floor and breathes. It only does this where there are at least four blocks of earth or stone under the floor.

Step on it and the ground turns into quicksand. You have about 2 seconds to get out onto solid ground. After that it holds you tight, and in about 5 seconds it pulls you straight into the hollow.

If you dig up the spot where it lies in wait, it goes into a rage and comes for whoever dug. If you got out of the quicksand in time, it starts hunting. It waits for up to 40 seconds and can lie in wait again after 90.

## The Awakening

This is when it reached you at the peak of its anger (or almost at once after a thrown shard, more on that below).

1. The build-up. The whole area within 32 blocks of where you stood becomes the creature. Sounds go quiet, the ground breathes, and every step there spreads across the ground in rings. You have 7 seconds to get out of the circle. The ground grabs at your feet: the longer it goes, the slower you are and the weaker your jumps, and for the last third your vision goes dark. Got out — that's it, you got away.
2. The swallow. If you didn't escape in time, a hill rises under you, holds you in place and closes over you. The screen goes dark.

However it ends, after an Awakening the creature itself is gone: it goes deep.

## Inside: the hollow

You end up in a copy of the place you were taken from, in this creature's own dimension. Other players see none of it; for them the ground where you stood is flat again, and you vanished without a trace.

- It's dark there. Black fog, you see about 5 blocks, you can't sprint. A placed torch gives light, but the fog doesn't go anywhere.
- You have 60 seconds. The bar at the top of the screen shows how much is left. Time's up — the ground takes you.
- Somewhere 40–60 steps away, through tunnels dug in this place, is its heart, the node. There are dead ends too.
- The heart beats. Every beat sends a ring through the ground (you hear a dull thud and see dust), and the more the walls have closed, the faster the beats. Go the way the pulses come from.
- To break the heart, break the block: a pickaxe is faster, but you can do it by hand.
- After 10 seconds the edges start closing in. The more noise you make, the faster. They stop a few blocks from where you arrived, and the way to the node always stays open.
- Don't stand still. After 3 seconds in one spot the ground under you softens and starts pulling you in; stay on it for 10 seconds and the ground swallows you. Step off and it sets again.
- You can dig, but it's very slow there.
- There's a ceiling over you, so you can't escape over the top.
- The coordinates in F3 won't help you there: random characters flicker in their place. The same during the Awakening, while you're being pulled in.

There's one more way out: a narrow tunnel that starts a couple of blocks from where you arrived and leads to the edge. It isn't marked in any way. You only see the light at its end once you're already in it, and when the walls close in, it's just a dark hole in one of them.

## How it ends

- You broke the heart — victory. You come back out where you were swallowed: a hill rises and lets you go, and there's a shard in your inventory.
- You got out through the tunnel at the edge — you're alive, you keep everything, and you come out a little away from where you were taken. But a crater forms in that place. The heart saves the place, the edge only saves you.
- Time ran out or the soft ground pulled you in — defeat. In the real world, where you were swallowed, a crater forms: an uneven funnel with steep walls, about 12 blocks in radius and 20 deep in the middle, caving in from the top down. On the surface it also takes everything standing over it, hills and trees too. By default you die, and your things end up in rubble caches scattered over the bottom of the crater, some of them buried. Your experience lies there too.

The crater never touches chests and other blocks with block entities, unbreakable blocks, spawn protection or blocks tagged `#tremor:protected`, and it doesn't let water or lava in. A totem of undying still saves you; `keepInventory` and the curse of vanishing work as usual. If the config says a defeat doesn't kill (`awakening.lethal`), or a totem saved you, you come out on the bottom of the crater alive and with all your things, but badly weakened: 1 health, blindness, nausea, weakness and slowness for half a minute, plus hunger, so you can't sprint until you eat.

## Shards and what they make

You get a shard for a broken heart.

- Seismograph: a shard, 3 copper ingots and redstone. Its needle points at the creature within 64 blocks and shakes harder the angrier it is.
- Geophone: a shard, an amethyst shard, 2 redstone and 3 cobbled deepslate. A block that gives a redstone signal (1 to 15), the stronger the closer the creature is, within 32 blocks; a comparator next to it shows its mood. Wire it to a lamp or a bell and you'll know when the creature is near.
- Muffled Steps: a book, 2 shards, 2 diamonds and any wool give an enchanted book. Put it on boots with an anvil: steps and landings are 5 times quieter.

Never throw a shard on the ground. Nobody can pick it back up: where it fell, a little mound rises and takes it in a second and a half. And the creature of that area (or a new one that crawls out about 24 blocks from you) goes into a frenzy and comes straight for you, 4.5 times faster than when it hunts and much taller. If it gets to you, the Awakening starts almost at once, you won't have time to run, and the hollow will be a nightmare one: only 30 seconds, the heart further away, the walls close four times faster and with no pause, the ground softens faster, there's no way out through the edge, a victory gives no shard, and a defeat kills you and takes everything you had. If it hasn't reached you in 90 seconds, the frenzy dies down.

## Difficulty

One setting for the whole mod: `difficulty` in `config/tremor-common.toml`, the command `/tremor difficulty <calm|normal|hard|nightmare>` in the game (operators), or the multitool menu. It multiplies the other settings rather than replacing them, so whatever you tuned by hand stays tuned, just softer or harsher.

| | Calm | Normal | Hard | Nightmare |
|---|---|---|---|---|
| Loudness it needs to hear you | x1.5 (hears less) | x1 | x0.75 | x0.6 |
| Anger from what it hears | x0.6 | x1 | x1.3 | x1.6 |
| Speed | x0.85 | x1 | x1.15 | x1.3 |
| Chance to show up | x0.5 | x1 | x1.5 | x2 |
| Most creatures in a dimension | 2 fewer (at least 1) | as in the config | 1 more | 2 more |
| Time to get out of the zone | x1.7 | x1 | x0.7 | x0.55 |
| Time in the hollow | x1.5 | x1 | x0.83 | x0.67 |
| Distance to the heart | x0.75 | x1 | x1.17 | x1.33 |
| How fast the walls close | x0.75 | x1 | x1.25 | x1.5 |
| Ambush chance | x0.45 | x1 | x1.4 | x2 |
| Damage of its hit | x0.5 | x1 | x1.5 | x2 |
| Defeat in the hollow | doesn't kill | as in the config (kills by default) | kills | kills and takes everything |

## With other players

Install it on the server and for every player. Each part of the world where players are can have its own creature. Everyone nearby sees the mound, the ripples and the Awakening, but the hollow is per player: only one gets in.

## Settings for your PC

In `config/tremor-client.toml`: `render.quality` (LOW / MEDIUM / HIGH) for weaker PCs and `render.style`: BLOCKS (the blocks themselves move) or WARP (a smooth hill).
