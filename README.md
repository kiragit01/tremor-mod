# Tremor

English · [Русский](README.ru.md)

A horror mod for Minecraft about something that lives under the ground. You never see it. You only see the ground move where it crawls: a mound going through grass, stone and cave walls, following every sound you make.

You can't fight it. You can stay quiet, you can run, or you can get caught.

For NeoForge 1.21.1 and Forge 1.20.1.

## How it plays

It hears you through the ground. Steps, jumps, landings, mined blocks, explosions, arrows and thrown items hitting the ground. Stone carries a sound far, sand and gravel less, wool and carpets almost kill it, and leaves rustle under your feet. Sneak and your steps make no sound at all. Stand still and you don't exist for it.

When it hears nothing, it just wanders around. When it hears something, it gets angry. First it gets wary: it freezes and sends rings of ripples across the ground. That's it listening, and if you move right then, it knows where you are. Then it hunts: it's fast and goes for every sound. At the peak of its anger it's looking for you, and if it reaches you, the Awakening starts.

All of it in detail, with numbers and the small stuff, is in the [guide](GUIDE.md).

## Questions people ask

**How often does it show up?**

Every now and then, 40 to 80 blocks from you. On the surface in daylight it's rare, on average about once every 10–15 minutes if there isn't one around already. At night, in the dark, in caves and deep underground it's a lot more likely. If no one comes near it for a while, or nothing happens for a long time, it sinks into the ground and leaves.

**Does it follow me everywhere?**

No, it isn't tied to you. It kind of has its own part of the world, the one you're in, and it roams there and goes for sounds. Go far enough away and it stays behind, and after a while it leaves. There can be several of them, one for each part of the world where players are (at least 500 blocks apart, up to 4 per dimension).

**What happens when it reaches me?**

That's the Awakening, and it goes in two steps. First the whole area around you (32 blocks) becomes it: the sounds go quiet, the ground breathes, and you have about 7 seconds to get out of that circle while the ground grabs at your feet. Make it out and you got away. Don't, and a hill rises under you and swallows you.

**And what's "getting inside it"?**

Once you're swallowed, you end up in the hollow: a dark copy of the same place, just for you, in a separate dimension. There's black fog, you can't sprint, and the edges slowly close in. Somewhere 40–60 steps away, through tunnels with dead ends, its heart is beating (the node). You have about a minute. Break the node and you come back out where you were taken, with a shard. There's one more way out, through the edge, but you have to find it, and the place you were taken from still gets a crater. Run out of time and a real crater opens where you stood; by default you die, and your things end up in caches at the bottom of that crater.

**What are the shards for?**

A shard is what you get for breaking the node. Shards make a seismograph (its needle points at the creature), a geophone (a redstone signal when it's near) and a Muffled Steps book for your boots. And never throw a shard on the ground: the ground takes it right away, and the creature comes straight for you in a frenzy.

**Can I play it with friends?**

Yes, it's made for servers too. Install it on the server and on every player's client. Everyone who gets swallowed gets their own hollow.

**Voice chat?**

If you have Simple Voice Chat, it hears you talk: your voice goes into the ground under you, like a step. Whispering is quieter.

**Is it heavy?**

There's a quality preset in the client config (Low / Medium / High) for weaker PCs. Works with Sodium and Iris; I haven't really tested shader packs.

## Installing

1. Install NeoForge for 1.21.1 or Forge for 1.20.1.
2. Put the mod's jar into `mods`. For a server: on the server and for every player.
3. Optional: Simple Voice Chat.

It's on CurseForge (Modrinth is still in review).

## Settings

Difficulty is one switch: Calm, Normal, Hard, Nightmare. It's `difficulty` at the top of `config/tremor-common.toml`, or `/tremor difficulty` in game (operators). Everything else can be tuned too: hearing, anger, speed, spawning, the Awakening, the hollow, the crater, the items. The difficulty table is in the guide, and every key in the config file has a comment.

## For server admins and testing

The `/tremor` commands are for operators: `spawn`, `despawn`, `info`, `stage`, `awaken`, `restore`, `difficulty` and more (Tab completes the rest). There's also a multitool in the Operator Utilities creative tab: in your hand it shows what the nearest creature is doing, right click lures it to where you're looking, sneak + right click opens its menu.

## Building from source

`main` is NeoForge 1.21.1 (JDK 21), `forge-1.20.1` is Forge 1.20.1 (JDK 17).

```bash
./gradlew build
```

The jar ends up in `build/libs`. Dev notes (in Russian) are in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Modpacks, videos, rights

Feel free to put Tremor in any modpack, public or private, and to make videos or streams with it, monetized or not. Credit is appreciated but not required. Please don't re-upload the mod to other sites or share modified versions without my permission. All rights reserved.
