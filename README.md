# Cryptic

A personal Hypixel SkyBlock mod for Fabric, built to replace the handful of
dungeon mods it would otherwise take to get the same features — one mod, one
menu, one config.

Minecraft **26.2**, Fabric Loader, Kotlin. Cryptic's menus are drawn with Dear ImGui's OpenGL
renderer, so they need the **OpenGL** graphics API (Options → Video Settings →
Graphics API); on Vulkan the modules still run, but the menus cannot open.

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
- [Floor 7 devices](docs/floor-7-devices.md) — the arrow, lights and Simon Says
  devices, the terminal ESP, and which clicks each of them will swallow.

## Credits

Several features are ported from other mods, whose licences travel with them:

- **[dtMap](https://github.com/ricedotwho/dtMap)** (BSD 3-Clause) — the dungeon
  map, the room database, the score arithmetic, the door-key sound and several
  map textures. Licence in [`licenses/dtMap-LICENSE.txt`](licenses/dtMap-LICENSE.txt).
  dtMap's score maths is itself from [Odin](https://github.com/odtheking/Odin)
  (BSD 3-Clause).
- **[Odin](https://github.com/odtheking/Odin)** (BSD 3-Clause) — the terminal
  solver, Terminal Times and the terminal simulator: the solving rules for all
  six Floor 7 terminals, all three render types, the client prediction and its
  resolve timeout, the first-click protection, and the client-side terminals the
  simulator plays. Also the Simon Says device solver,
  Slot Binds, the Smart Tick Timer, SkyBlock and island detection, the
  starred-mob half of Highlight and part of Render Optimizer.
  Licence in [`licenses/Odin-LICENSE.txt`](licenses/Odin-LICENSE.txt).
- **[NoammAddons](https://github.com/Noamm9/NoammAddons)** (CC0) — door-key
  highlighting, room alerts, the map's room styles, Breaker Helper, hidden mobs,
  showing your own nametag, most of Render Optimizer, the Arrow Align and Sharp Shooter device
  solvers, the terminal ESP, the Secrets module — its hitboxes, its auto-close
  and its clicked-secret marker — and nine smaller ones: Arrow Fix, Arrow Hit
  Sound, Block Overlay, Camera, No Item Place, SB Kick, Time Changer, Lava to
  Water and the Floor 7 Door Fix.
- **[Athen](https://github.com/skies-starred/Athen)** (BSD 3-Clause,
  Copyright (c) 2025-2026 Starred) — the Lag Detector's format, Arrow Hitboxes,
  Custom Scale including its chibi styles, and the Carry Manager: the shape of a
  carry tracker, and the armour stands a slayer boss carries its owner and tier
  on. Licence in [`licenses/Athen-LICENSE.txt`](licenses/Athen-LICENSE.txt).
- **[Devonian](https://github.com/Synnerz/Devonian)** — door highlighting,
  including its rule for deciding a doorway leads nowhere worth going, and
  colouring the blood portal by the score you are on.
- **[Astrail Experiment](https://github.com/AzureSky0116/astrail-experiment)**
  (MIT) — the Experimentation Table rules: the control pane read by its item
  rather than its name, Chronomatron gaining one note a round, and
  Ultrasequencer ordered by stack size. Licence in
  [`licenses/astrail-experiment-LICENSE.txt`](licenses/astrail-experiment-LICENSE.txt).
- **[NotEnoughUpdates](https://github.com/NotEnoughUpdates/NotEnoughUpdates)**
  (LGPL-3.0) — the Wither Cloak effect. Notices in
  [`src/main/resources/META-INF/notices/`](src/main/resources/META-INF/notices/).

Seven more mods were read rather than ported — no code of theirs is in Cryptic:
**[Skyblocker](https://github.com/SkyblockerMod/Skyblocker)** (LGPL-3.0), for
the Lights On device — its six lever positions are facts about the wall they sit
on rather than code, and the solver around them is Cryptic's own, and for the
Booster Cookie Reminder — which pair of tab list rows the cookie's remaining
time is written on;
**[Stella](https://github.com/Eclipse-5214/stella)** (LGPL-3.0), for Terminal
Order — likewise only its published terminal numbering and class assignments,
which are the M7 Guides community's convention and cross-check against
NoammAddons' own terminal positions;
**[Debugify](https://github.com/isXander/Debugify)** (LGPL-3.0), for the
riding input fix in Camera — MC-206540, whose fix is one paragraph of turning
the rider with its mount in the same tick;
**[Zoomify](https://modrinth.com/mod/zoomify)** (LGPL-3.0), for what a zoom
ought to offer; **NoJumpDelay** (1.8.9), for which cooldown to clear; and
**[RavenB++](https://github.com/OlziYT/RavenBS-Plus-Plus)** (1.8.9), for the
Auto Clicker — holding the button for part of the gap, drifting the rate
rather than drawing it flat, and running straight off the attack and use
buttons instead of a bind of its own; and
**[RBDT V5](https://github.com/V5-Client/V5)** (ChatTriggers, 1.8.9), for how
the Experiment Solver works the table between games — the stakes, the renew and
the bottle shop.

Bundled fonts: Inter (SIL OFL) and Font Awesome Free Solid (CC BY 4.0).
