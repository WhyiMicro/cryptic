# Cryptic

A personal Hypixel SkyBlock mod for Fabric, built to replace the handful of
dungeon mods it would otherwise take to get the same features — one mod, one
menu, one config.

Minecraft **26.1.2**, Fabric Loader, Kotlin.

## Building

```
./gradlew build
```

The jar lands in `build/libs/`.

## Documentation

- [Debug commands](docs/debug-commands.md) — how to test dungeon features
  without queueing a dungeon.
- [Terminal solver](docs/terminal-solver.md) — the Floor 7 terminals, the click
  protection, and what each solving mode sends.

## Credits

Several features are ported from other mods, whose licences travel with them:

- **[dtMap](https://github.com/ricedotwho/dtMap)** (BSD 3-Clause) — the dungeon
  map, the room database, the score arithmetic, the door-key sound and several
  map textures. Licence in [`licenses/dtMap-LICENSE.txt`](licenses/dtMap-LICENSE.txt).
  dtMap's score maths is itself from [Odin](https://github.com/odtheking/Odin)
  (BSD 3-Clause).
- **[Odin](https://github.com/odtheking/Odin)** (BSD 3-Clause) — the terminal
  solver and the terminal simulator: the solving rules for all six Floor 7
  terminals, the custom terminal GUI, the first-click protection, and the
  client-side terminals the simulator plays. Licence in
  [`licenses/Odin-LICENSE.txt`](licenses/Odin-LICENSE.txt).
- **[NoammAddons](https://github.com/Noamm9/NoammAddons)** (CC0) — door-key
  highlighting, room alerts, the map's room styles, Breaker Helper, hidden mobs,
  showing your own nametag, and the terminal solver's queued and automatic
  clicking.
- **[Devonian](https://github.com/Synnerz/devonian)** — door highlighting,
  including its rule for deciding a doorway leads nowhere worth going.
- **[NotEnoughUpdates](https://github.com/NotEnoughUpdates/NotEnoughUpdates)**
  (LGPL-3.0) — the Wither Cloak effect. Notices in
  [`src/main/resources/META-INF/notices/`](src/main/resources/META-INF/notices/).

Bundled fonts: Inter (SIL OFL) and Font Awesome Free Solid (CC BY 4.0).
