<p align="center">
  <img src="src/main/resources/assets/cryptic/icon.png" alt="Cryptic" width="128" height="128">
</p>

# Cryptic

A Hypixel SkyBlock mod for Fabric, built to replace the handful of dungeon mods
it would otherwise take to get the same features: one mod, one menu, one config.

Minecraft **26.2**, Fabric Loader, Kotlin. Everything Cryptic draws goes through
Minecraft's own renderer, so it works on the OpenGL and Vulkan graphics APIs alike.

**[Download](https://github.com/WhyiMicro/cryptic/releases/latest)** ·
[Website](https://modworks.tf/cryptic) ·
[Changelog](https://github.com/WhyiMicro/cryptic/releases)

## What's in it

- **Dungeons:** map with room names and secret counts, secret waypoints and
  hitboxes, puzzle solvers, Party Finder, duplicate-class and blacklist checks,
  Spirit Leap with teammate views, and boss tools from Floor 3 up.
- **Floor 7:** terminal solver, device solvers, Wither Dragons, Relics and
  tick-accurate timers.
- **Crystal Hollows:** a Hollows map, structure and crystal scanning, the Metal
  Detector and Wishing Compass solvers, and lock-pick helpers.
- **Everyday:** Bazaar orders, Auction House alerts, tooltip prices (lowest
  BIN, 3-day average, estimated value), Item Protection, Slayer and carry
  tools, fishing and mining helpers, Better Chat, Item Outline, capes, Smooth
  teleport, and a HUD editor for all of it.
- **Client Restrictions** (Settings tab) switches between Legit, Semi-legit and
  Cheats. Anything above the mode you pick is hidden and off, and comes back with
  its settings when you allow it again.

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/) 0.19.5 or newer for
   Minecraft 26.2.
2. Add [Fabric API](https://modrinth.com/mod/fabric-api) and
   [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin).
   Cryptic needs Java 25.
3. Put the Cryptic jar from the [latest release](https://github.com/WhyiMicro/cryptic/releases/latest)
   in your `mods` folder. Right Shift opens the menu.

Update Checker (Settings tab) can install new releases for you after the game
closes.

## Building

```
./gradlew build
```

The jar lands in `build/libs/`.

## Documentation

- [Debug commands](docs/debug-commands.md): how to test dungeon features
  without queueing a dungeon.
- [Terminal solver](docs/terminal-solver.md): the Floor 7 terminals, the click
  protection, and what each solving mode sends.
- [Floor 7 devices](docs/floor-7-devices.md): the arrow, lights and Simon Says
  devices, the terminal ESP, and which clicks each of them will swallow.
- [Waypoints and messages](docs/waypoints-and-messages.md): Dungeon Waypoints,
  its editor and `/cryptic dwp`, Boss Waypoints with `/cryptic bwp`, and Positional
  Messages with `/cryptic posmsg`.
- [Croesus Helper](docs/croesus-helper.md): the profit lists beside Croesus's
  chests, for dungeons and Kuudra.
- [Keybinds, aliases, sounds and updates](docs/settings-managers.md): the
  Keybinds, Alias and Sound managers in the General tab, and the Update Checker
  in Settings.

## Credits

Cryptic was inspired by [Odin](https://github.com/odtheking/Odin),
[NoammAddons](https://github.com/Noamm9/NoammAddons),
[Skyblocker](https://github.com/SkyblockerMod/Skyblocker),
[SkyHanni](https://github.com/hannibal002/SkyHanni),
[dtMap](https://github.com/ricedotwho/dtMap) and many others. The in-game
Credits page (Settings → Credits) lists them all with what each inspired, and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) has the licences of the parts
that come from other projects.

## Licence

Cryptic is under the [Cryptic Non-Commercial Licence](LICENSE): use it, change
it and share it, with or without credit, as long as you don't make money from it.
