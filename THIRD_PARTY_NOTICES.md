# Third-party notices

Cryptic's own code is under the [Cryptic Non-Commercial Licence](LICENSE). The
parts listed here come from other projects and stay under those projects'
licences, whose full texts are in [`licenses/`](licenses/). Both this file and
the licence texts are also inside the mod's jar, under `META-INF/`.

As these parts are rewritten as Cryptic's own, they leave this list; the
in-game Credits page (Settings → Credits) keeps saying what inspired what.

Several features are ported from other mods, whose licences travel with them:

- **[dtMap](https://github.com/ricedotwho/dtMap)** (BSD 3-Clause) — the dungeon
  map, the room database, the score arithmetic, the door-key sound and several
  map textures, the grey a room is drawn in until it is opened, and the door
  colours: grey into the unknown, the special room's own colour once it is known.
  Licence in [`licenses/dtMap-LICENSE.txt`](licenses/dtMap-LICENSE.txt).
  dtMap's score maths is itself from [Odin](https://github.com/odtheking/Odin)
  (BSD 3-Clause).
- **[OdinLegacy](https://github.com/odtheking/OdinLegacy)** (BSD 3-Clause,
  Copyright (c) 2023-2025 odtheking) — the Chocolate Factory module: clicking the
  cookie, catching strays, buying the best rabbit, muting the eating sound and the
  hunt's egg waypoints, with the eggs' head textures. Licence in
  [`licenses/OdinLegacy-LICENSE.txt`](licenses/OdinLegacy-LICENSE.txt).
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
  The Spirit Bear timer, the Livid finder (F5/M5 Features) and its invulnerability timer,
  Positional Messages and its cylinder, the Dungeon Waypoints editor, its /dwp
  command and its waypoint packs,
  and the melody coordinates and percentages in the Terminal Solver. The
  Croesus Helper's run colours, its reading of a run's chests, and the price
  table it values them with, and the Kuudra Key's price, from Vesuvius. The Loadout Manager's keys in the loadout menu,
  from Loadout Keybinds, and its key that opens it, from Command Keybinds.
  Sharp Shooter's Terminator mode, from its Arrows Device's aim positions
  between two lights.
  Auto Sprint, which holds the sprint key where the game reads it.
  The new menu's rounded shapes and shadows: its `RoundedRectRenderer` and
  its rounded-rectangle shaders, which draw through Minecraft's own GUI
  renderer and so work on Vulkan as well as OpenGL.
  Licence in [`licenses/Odin-LICENSE.txt`](licenses/Odin-LICENSE.txt).
- **[Blade Addons](https://github.com/BladeMasterGabe/blade-addons)** (CC0) — the
  secret spawn timer, the leap count (now on Positional Messages), and the height
  Class Colors' name labels are drawn at.
- **[NoammAddons](https://github.com/Noamm9/NoammAddons)** (CC0) — door-key
  highlighting, room alerts, the map's room styles, Breaker Helper, hidden mobs,
  showing your own nametag, Wither Dragons and Relics, most of Render Optimizer, the Arrow Align and Sharp Shooter device
  solvers, the terminal ESP and its flash on click, the Secrets module — its hitboxes, its auto-close
  and its clicked-secret marker — and ten smaller ones: Bow Tweaks' arrow fix and arrow hit
  sound, Block Overlay, Camera, No Item Place, SB Kick, Time Changer, Lava to
  Water, and the Floor 7 Door Fix and Gate Highlight in F7/M7 QOL, with its Better P3
  titles, which are its F7 Titles' terminal titles, and the Maxor crystal titles, spawn timer, place
  timer and unplaced-crystal alert from its F7 Titles and Maxor's Crystals. Blood Camp, which times the Watcher and where
  each of his mobs will stop, the Gyro Helper, and Mage Beam's hidden sheep and
  its fade. The list of ways the party's mods word a mimic, prince or bat kill,
  and the charm message that means a mimic died. Dark Mode, Tooltip
  Features' scrolling, the Melody HUD in the Terminal Solver, and the same Terracotta
  Timer, Blessing Display, Spring Boots Helper and party commands Odin has. The
  Example Module is its Comp Test in idea. I love glass, which is its I Hate
  Doors and I Hate Diorite on one card; the three teleports Teleport Features'
  fake zpew and no rotate cover; and
  sending melody progress to the party. Auto GFS, and Auto Requeue with its
  Check Party.
  The Spirit Bear, Spirit Bow and Thorn highlights, and the Livid finder's
  tracer, health, hiding of the wrong Livids and Ice Spray alert. The Croesus
  Helper's profit beside a chest's title and its list of chests.
  The Alias Manager, from its Command Shortcuts, and the Sound Manager: a
  volume per sound, its categories and its list of recent sounds.
- **[Athen](https://github.com/skies-starred/Athen)** (BSD 3-Clause,
  Copyright (c) 2025-2026 Starred) — the Lag Detector's format, Bow Tweaks' arrow hitboxes,
  Custom Scale including its chibi styles, and the Carry Manager: the shape of a
  carry tracker, the armour stands a slayer boss carries its tier on, and telling
  a boss's owner by the player it turns to face when it spawns. The Keybinds Manager: a binding's keys, its command and its conditions
  (where it works, islands, floors, classes, phases), and its categories. Slayer
  Features' laser timer: the 8.2 seconds counted from the stand the boss rides. Licence in [`licenses/Athen-LICENSE.txt`](licenses/Athen-LICENSE.txt).
- **[Meridian](https://github.com/chillypine/meridian)** (MIT, Copyright (c)
  2026 ChillyPine) — the Carry Manager's fallback for a boss's owner: the nearest
  player when it spawns, and nobody owning two bosses at once. Licence in
  [`licenses/Meridian-LICENSE.txt`](licenses/Meridian-LICENSE.txt).
- **[IQ](https://github.com/iqaddons/IQ)** (Apache 2.0) — Slayer Features' Fire
  Veil overlay: the wand's 3.5-block ring for the five seconds it burns, drawn as
  a circle or a low wall, and its flames hidden for nine seconds from a cast.
  Rewritten for Cryptic's settings and renderer. Licence in
  [`licenses/IQ-LICENSE.txt`](licenses/IQ-LICENSE.txt).
- **[RSM](https://github.com/rs-mod/rsm)** (BSD 3-Clause, Copyright (c) 2026
  rice.who) — Spirit Leap Features' two rotate fixes, from its Leap Rotate Fix:
  arriving from a leap facing the way the teammate does, and being put into a
  boss room facing into it, with its table of where each floor's entrance is. Licence in
  [`licenses/RSM-LICENSE.txt`](licenses/RSM-LICENSE.txt).
- **[OdinClient](https://github.com/skies-starred/OdinClient)** (BSD 3-Clause,
  Copyright (c) 2026 Starred) — the Experiment Solver keeping your own clicks
  out of a game it is playing, from its Auto Experiments. Licence in
  [`licenses/OdinClient-LICENSE.txt`](licenses/OdinClient-LICENSE.txt).
- **[Astrail Experiment](https://github.com/AzureSky0116/astrail-experiment)**
  (MIT) — the Experimentation Table rules: the control pane read by its item
  rather than its name, Chronomatron gaining one note a round, and
  Ultrasequencer ordered by stack size. Licence in
  [`licenses/astrail-experiment-LICENSE.txt`](licenses/astrail-experiment-LICENSE.txt).
- **[SkyOcean](https://github.com/meowdding/SkyOcean)** (MIT, for its code) —
  Fishing's hotspots: which particles mark a hotspot's edge on each island, its
  radius taken as the most common distance they land at (to the half block) with
  a cap per island, hiding those particles, the perk colours, and a filled circle
  with a low rim. Licence in [`licenses/SkyOcean-LICENSE.txt`](licenses/SkyOcean-LICENSE.txt).

Lumen, DailyRewards, Devonian, Custom Block Highlight, Skyblocker, SkyHanni and
NotEnoughUpdates each had features ported into Cryptic. Those features have
since been written again as Cryptic's own, so none of those projects' code is
left. What is still theirs is data and artwork, kept as it is:

- **[Devonian](https://github.com/Synnerz/Devonian)** (GPL-3.0) — the table of
  every dungeon room's secret spots (`dungeon/secret_waypoints.json`), behind
  Dungeon Waypoints and the Default waypoint pack. Licence in
  [`licenses/Devonian-LICENSE.txt`](licenses/Devonian-LICENSE.txt).
- **[quoi](https://github.com/jcnlk/quoi)** (GPL-3.0) — as facts only, the
  column of blocks that marks each Crystal Hollows place, the quarter it lies in
  and the step from it to the place, for Nucleus Features' scanner (quoi has them
  from GumTuneClient's world scanner); and the ideas of the Bazaar's escrow fix, the
  autoclicker's blacklist, and the Chocolate Factory's Time Tower and Coach upgrades.
  The search and the fix are Cryptic's own; no
  code was taken.
- **[Skyblocker](https://github.com/SkyblockerMod/Skyblocker)** (LGPL-3.0) — the
  table of where every Water Board gate starts on each of the four boards, the six Lights On lever
  positions, and the Croesus Helper's list of drops whose names are not their
  ids (with Odin's); and, as facts only, the pests' head textures for Farming's
  pest tracers, the Wishing Compass solver's zones,
  what each zone's compass leads to and the Jungle Temple door's offset. Licence in
  [`licenses/Skyblocker-LICENSE.txt`](licenses/Skyblocker-LICENSE.txt).
- **[SkyHanni repository](https://github.com/hannibal002/SkyHanni-REPO)** (MIT,
  Copyright (c) 2022 hannibal2) — the 39 places the Mines of Divan treasure
  turns up, for Nucleus Features' Metal Detector solver. Licence in
  [`licenses/SkyHanni-REPO-LICENSE.txt`](licenses/SkyHanni-REPO-LICENSE.txt).
- **[SkyHelper Networth](https://github.com/Altpapier/SkyHelper-Networth)** (MIT,
  Copyright (c) 2022 Altpapier) — the share of its price each applied upgrade
  keeps, which stone gives each reforge, which item takes an enchantment past its
  top book, and which enchantments level up by use, for Tooltip Features'
  estimated value. Licence in
  [`licenses/SkyHelper-Networth-LICENSE.txt`](licenses/SkyHelper-Networth-LICENSE.txt).
- **[NotEnoughUpdates](https://github.com/NotEnoughUpdates/NotEnoughUpdates)**
  (LGPL-3.0) — the Wither Cloak effect's shield texture. Notice in
  [`src/main/resources/META-INF/notices/`](src/main/resources/META-INF/notices/).

Terminal Order's numbering and its class assignments are the M7 Guides
community's, as published in Stella's terminal data. They are facts about the
boss room and a party's convention about who goes where, not code or artwork,
and nothing else of Stella's is in Cryptic.

Seven more mods were read rather than ported — no code of theirs is in Cryptic:
**[RewardClaim](https://github.com/meowdding/RewardClaim)** (all rights
reserved, ThatGravyBoat), for how the daily reward picker should behave —
closing Hypixel's book while it opens, and waiting out Hypixel's ad before a
card can be claimed;
**[Essential](https://essential.gg)**, for the look and feel of the notifications —
their size, colours, timer bar and how they slide, stack and are dismissed;
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

Bundled fonts: Poppins (SIL OFL; licence in [`licenses/Poppins-OFL.txt`](licenses/Poppins-OFL.txt)), and Font Awesome Free Solid and Regular (CC BY 4.0; licence in `META-INF/licenses/font-awesome-free.txt`).
