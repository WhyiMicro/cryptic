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
- [Waypoints and messages](docs/waypoints-and-messages.md) — Dungeon Waypoints,
  its editor and `/cryptic dwp`, Boss Waypoints with `/cryptic bwp`, and Positional
  Messages with `/cryptic posmsg`.

## Credits

Several features are ported from other mods, whose licences travel with them:

- **[dtMap](https://github.com/ricedotwho/dtMap)** (BSD 3-Clause) — the dungeon
  map, the room database, the score arithmetic, the door-key sound and several
  map textures, the grey a room is drawn in until it is opened, and the door
  colours: grey into the unknown, the special room's own colour once it is known.
  Licence in [`licenses/dtMap-LICENSE.txt`](licenses/dtMap-LICENSE.txt).
  dtMap's score maths is itself from [Odin](https://github.com/odtheking/Odin)
  (BSD 3-Clause).
- **[Odin](https://github.com/odtheking/Odin)** (BSD 3-Clause) — the terminal
  solver, Terminal Times and the terminal simulator: the solving rules for all
  six Floor 7 terminals, the custom terminal GUI, the client prediction and its
  resolve timeout, the first-click protection, and the client-side terminals the
  simulator plays. Also the Simon Says device solver,
  Slot Binds, the Smart Tick Timer, SkyBlock and island detection, the
  starred-mob half of Highlight and part of Render Optimizer. Also the Puzzle
  Solver's eight puzzles and the answer files they read, the Invincibility
  Timer and its HUD, the cooldown colouring in Gyro Helper, and the shape of
  Mage Beam — the run of particles read as one line between its two ends. The Spirit
  Leap Overlay's menu and its sortings. The Performance HUD and its settings, the
  Terracotta Timer, the Blessing Display, the Spring Boots Helper's table of
  heights, and the party chat commands in Party Features. F7/M7 QOL's Section
  Complete title, which waits for the gate the way its Inactive Waypoints tells
  one section from the next; and the Melody HUD's live view, which joins Odin's
  own relay through a port of its websocket connection and speaks its messages.
  Auto Requeue's cue, the end-of-run line, and calling it off when somebody
  leaves the party, from its Dungeon Queue.
  The Spirit Bear timer, the Livid Solver and its invulnerability timer,
  Positional Messages and its cylinder, the Dungeon Waypoints editor, its /dwp
  command and its waypoint packs,
  and the melody coordinates and percentages in the Terminal Solver. The
  Croesus Helper's run colours, its reading of a run's chests, and the price
  table it values them with.
  Licence in [`licenses/Odin-LICENSE.txt`](licenses/Odin-LICENSE.txt).
- **[Blade Addons](https://github.com/BladeMasterGabe/blade-addons)** (CC0) — the
  secret spawn timer, the leap count in the Spirit Leap Overlay, and the height
  Class Colors' name labels are drawn at.
- **[NoammAddons](https://github.com/Noamm9/NoammAddons)** (CC0) — door-key
  highlighting, room alerts, the map's room styles, Breaker Helper, hidden mobs,
  showing your own nametag, most of Render Optimizer, the Arrow Align and Sharp Shooter device
  solvers, the terminal ESP and its flash on click, the Secrets module — its hitboxes, its auto-close
  and its clicked-secret marker — and ten smaller ones: Arrow Fix, Arrow Hit
  Sound, Block Overlay, Camera, No Item Place, SB Kick, Time Changer, Lava to
  Water, and the Floor 7 Door Fix and Gate Highlight in F7/M7 QOL, with its Better P3
  titles, which are its F7 Titles' terminal titles. Blood Camp, which times the Watcher and where
  each of his mobs will stop, the Gyro Helper, and Mage Beam's hidden sheep and
  its fade. The list of ways the party's mods word a mimic, prince or bat kill,
  and the charm message that means a mimic died. Dark Mode, Scrollable
  Tooltips, the Melody HUD in the Terminal Solver, and the same Terracotta
  Timer, Blessing Display, Spring Boots Helper and party commands Odin has. The
  Example Module is its Comp Test in idea. I love glass, which is its I Hate
  Doors and I Hate Diorite on one card; the three teleports Etherwarp's fake
  zpew and no rotate cover, with its Instant Transmission prediction; and
  sending melody progress to the party. Auto GFS, and Auto Requeue with its
  Check Party.
  The Spirit Bear, Spirit Bow and Thorn highlights, and the Livid Solver's
  tracer, health, hiding of the wrong Livids and Ice Spray alert. The Croesus
  Helper's profit beside a chest's title and its list of chests.
- **[Athen](https://github.com/skies-starred/Athen)** (BSD 3-Clause,
  Copyright (c) 2025-2026 Starred) — the Lag Detector's format, Arrow Hitboxes,
  Custom Scale including its chibi styles, and the Carry Manager: the shape of a
  carry tracker, and the armour stands a slayer boss carries its owner and tier
  on. Licence in [`licenses/Athen-LICENSE.txt`](licenses/Athen-LICENSE.txt).
- **[Devonian](https://github.com/Synnerz/Devonian)** (GPL-3.0) — door
  highlighting, including its rule for deciding a doorway leads nowhere worth
  going, colouring the blood portal by the score you are on, the Puzzle HUD,
  the Dungeon Warp Cooldown, and hiding every sheep in a dungeon for Mage Beam
  rather than only the one at your feet. Dungeon Waypoints' secret spots: its
  table of every room's chests, items, essence, bats, redstone keys and levers,
  and the colour per kind. Licence in
  [`licenses/Devonian-LICENSE.txt`](licenses/Devonian-LICENSE.txt).
- **[Astrail Experiment](https://github.com/AzureSky0116/astrail-experiment)**
  (MIT) — the Experimentation Table rules: the control pane read by its item
  rather than its name, Chronomatron gaining one note a round, and
  Ultrasequencer ordered by stack size. Licence in
  [`licenses/astrail-experiment-LICENSE.txt`](licenses/astrail-experiment-LICENSE.txt).
- **[Skyblocker](https://github.com/SkyblockerMod/Skyblocker)** (LGPL-3.0) — the
  two puzzles Odin does not solve, Tic Tac Toe and the silverfish's Ice Path,
  and the Water Board's two previews: where the water will run, and what a lever
  would move. Also the Water Board's reset — the table of where every gate
  starts on each of the four boards, and the rule that turns a half-played
  board back into a list of pulls. Its six Lights On lever positions are facts about the wall they
  sit on rather than code, and the solver around them is Cryptic's own; the
  Booster Cookie Reminder reads the pair of tab list rows Skyblocker found the
  cookie's remaining time on. The Croesus Helper's table of drops whose names
  are not their ids, and its rule for a second chest worth a key. Licence in
  [`licenses/Skyblocker-LICENSE.txt`](licenses/Skyblocker-LICENSE.txt).
- **[NotEnoughUpdates](https://github.com/NotEnoughUpdates/NotEnoughUpdates)**
  (LGPL-3.0) — the Wither Cloak effect. Notices in
  [`src/main/resources/META-INF/notices/`](src/main/resources/META-INF/notices/).

Terminal Order's numbering and its class assignments are the M7 Guides
community's, as published in Stella's terminal data. They are facts about the
boss room and a party's convention about who goes where, not code or artwork,
and nothing else of Stella's is in Cryptic.

Six more mods were read rather than ported — no code of theirs is in Cryptic:
**[SkyHanni](https://github.com/hannibal002/SkyHanni)** (LGPL-2.1), for telling
Croesus' runs apart by their page and slot, and for the look of the Croesus
Helper's two profit lists — their layout, colours and number format;
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
