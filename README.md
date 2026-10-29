# Tremor

English · [Русский](README.ru.md)

A Minecraft mod about a creature that lives under the ground. You never see the thing itself, only how it moves. It can crawl over any surface at any angle (ceilings, walls, floors, pillars). It reacts to sound, a bit like the Warden.

It takes no damage. You can hide from it or quietly walk away, or it swallows you.

For NeoForge 1.21.1 and Forge 1.20.1.

## How it plays

The creature hears sounds through the ground: jumps, steps, breaking and placing blocks, explosions, dropped items, and arrows, snowballs and eggs when they hit the ground. Sound travels differently depending on the surface: far through stone, more muffled through sand and gravel, and wool and carpets make almost no sound at all. If you sneak or stand still, it won't hear you.

While it hears nothing, it just wanders around nearby. If there are sounds close by, it starts getting irritated and becomes wary. At that moment it may send rings of waves around itself, listening, and if you make noise right then, it starts hunting and goes for the last sound it heard. While hunting it can react to mobs too and pull them under the ground. Keep making noise and it reaches its last stage of anger, where it gets even faster and more aggressive, and if it reaches a player, the Awakening begins.

Everything in detail, with numbers and all the small stuff, is in the [guide](GUIDE.md).

## Some answers to questions

**How often does it show up?**

By itself, every now and then, 40 to 80 blocks from you. On the surface in daylight it's rare, on average about once every 10–15 minutes. At night, in the dark, in caves and deep underground it's much more often. If no player comes near it for a long time, or nothing happens for a long time, it sinks into the ground and disappears. In the config you can tune its spawning: make it appear only underground in unlit places, and change how often it shows up.

**Does it follow me all the time?**

No, it isn't tied to a player. It can be right nearby, never hear you, and just leave.

**What happens if it reaches me?**

The Awakening begins, and it has two stages. First the whole area around you (32 blocks) comes "alive": sounds go quiet, the ground breathes, and you have about 7 seconds to run out of that circle, while the ground grabs at your feet. Make it out and you got away. Don't, and a hill rises under you and swallows you.

**And what does "getting inside it" mean?**

Once swallowed, you end up in the hollow. It's a dark copy of the place where the creature pulled you in, but in a separate dimension of its own. There's black fog, you can't sprint, and the edges slowly close in. Somewhere 40–60 steps away, through tunnels with dead ends, its heart (the node) is beating. You have about a minute. Break the node and you come back out where you were taken, carrying a shard. There's one more way out, through the edge, but you have to find it, and then the place you were taken from still gets a crater. Run out of time and a crater opens where you stood; by default you die, and your things end up in caches at the bottom of it.

**What are shards?**

A shard is what a broken node gives you. Shards craft a seismograph (its needle points at the creature), a geophone (a redstone signal when it's near) and a Muffled Steps book for your boots. And never throw a shard on the ground: the ground takes it right away, and the creature comes straight for you in a frenzy.

**Can I play with friends?**

Yes, the mod is made for servers too. Install it on the server and for every player. Everyone who gets swallowed gets their own hollow.

**What about voice chat?**

If you have Simple Voice Chat, it hears you talk: the voice goes into the ground under whoever speaks, like a step. Whispering is quieter.

**Is it heavy?**

The client settings have a quality preset (Low / Medium / High) for weaker PCs. Works with Sodium and Iris. With shaders there can be bugs: no darkness effect, and the area isn't lit the way it should be.

## Installing

1. Install NeoForge for 1.21.1 or Forge for 1.20.1.
2. Put the mod's jar into the `mods` folder. For a server: on the server and for every player.
3. Optional: Simple Voice Chat.

The mod is on CurseForge (Modrinth is still in review).

## Settings

Difficulty is one switch: Calm, Normal, Hard, Nightmare. It's `difficulty` at the very top of `config/tremor-common.toml`, or `/tremor difficulty` right in the game (operators). Everything else can be tuned too: hearing, anger, speed, spawning, the Awakening, the hollow, the crater, the items. The difficulty table is in the guide, and every key in the config file has a comment.

## For admins and testing

The `/tremor` commands are for operators only: `spawn`, `despawn`, `info`, `stage`, `awaken`, `restore`, `difficulty` and more, Tab will suggest the rest. There's also a multitool (`/give @s tremor:debug_probe`): in your hand it shows what the nearest creature is doing, right click lures it to where you're looking, sneak + right click opens its menu.

## Building from source

The `main` branch is NeoForge 1.21.1 (needs JDK 21), the `forge-1.20.1` branch is Forge 1.20.1 (JDK 17).

```bash
./gradlew build
```

The jar ends up in `build/libs`. Development notes (in Russian) are in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Modpacks, videos, rights

Feel free to put the mod in any modpack, public or private, and to make videos and streams with it, monetized ones included. Credit is appreciated but not required. Please don't re-upload the mod to other sites or share modified versions without my permission. All rights reserved.
