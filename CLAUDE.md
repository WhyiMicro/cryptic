# Cryptic: notes for Claude

A Hypixel SkyBlock client mod (dungeons first, then everything else the user plays). Fabric,
Kotlin with Java mixins, Minecraft 26.2, Java 25, Fabric Loader 0.19.5+. The goal is one mod that
replaces the five or so SkyBlock mods the user runs. The user plays on Windows, in a Prism
instance, alongside SkyHanni, Skyblocker and Odin, and tests in game.

This file is the map. Read only what a task needs; don't re-survey the repo.

## Layout

- `src/main/kotlin/imicro/cryptic/`
  - `CrypticClient.kt`: the entrypoint. Every feature's `initialize()` runs here, then
    `ConfigManager.initialize()` last. It also holds the `/cryptic` (and `/cc`) commands, every
    `/cryptic debug ...` subcommand, and the end-of-tick loop that calls each feature's `tick`.
  - `feature/`: one `object` per module (about 130), named after it.
  - `gui/Module.kt`: `Module`, `ModuleCategory`, `Restriction`, every `*ModuleSetting` type,
    `withMore`, and `ModuleRegistry.modules` (what the menu lists).
  - `gui/menu/`: the menu, drawn on Minecraft's own GUI renderer (no ImGui since 0.28.0; it must
    work on Vulkan and OpenGL alike). `ModuleIcons.kt`, `managers/CreditList.kt`.
  - `hud/` (`Hud`, `HudElement`), `render/WorldRender.kt` (boxes, lines, tracers, text, panels).
  - `skyblock/`: location (`SkyblockLocation`), items (`SkyblockItem`, `ItemRarity`), prices
    (`BazaarMarket`, `AuctionPrices`, `ItemPrices`, `ItemValue`), `SackContents`,
    `ParticleTrail` (Bézier trail solver for the vacuum and hotspot radar).
  - `dungeon/` (+ `dungeon/map/`), `puzzle/`, `terminal/`, `device/`, `experiment/`, `slayer/`.
  - `debug/`: `DebugOverrides` and the debug tools behind `/cryptic debug`.
  - `config/ConfigManager.kt`: profiles, saving, and `RETIRED_MODULES` for merged modules.
- `src/main/java/imicro/cryptic/mixin/`: mixins and accessors. Each must be listed in
  `src/main/resources/cryptic.mixins.json`.
- `src/main/resources/assets/cryptic/`
  - `default_profile.json`: the shipped defaults, keyed by module id and setting id. It is the
    user's own "new default" profile; `Default.json` is refreshed from it on every start.
  - `dungeon/secret_waypoints.json` (format below), `map/rooms.json` (dtMap's room database,
    copied verbatim; never reformat it), `puzzles/`, `lang/en_us.json`, `textures/`.
- `src/test/kotlin/`: unit tests for the experiment and terminal solvers.
- `docs/`: user docs. `docs/debug-commands.md` lists every `/cryptic debug` command.
- `licenses/`, `THIRD_PARTY_NOTICES.md`: licences of ported code (both ship in the jar).

## Adding or changing a module

1. In `feature/Foo.kt`, declare settings as `@JvmField val x = ToggleModuleSetting(...)` (or
   Slider, Dropdown, Color, Text, MultiSelect, Keybind, Button, Section). Then
   `@JvmField val module = Module(id = "foo", name, description, category, settings = listOf(...))`.
   `Module` also takes `enabled`, `supportsToggle`, `supportsKeybind`, `cardAction` and
   `restriction`. `ExampleModule.kt` shows every setting type.
2. Add `Foo.module` to `ModuleRegistry.modules` in `gui/Module.kt`.
3. Add an icon to `gui/menu/ModuleIcons.kt`: a FontAwesome code point (`"\uf4d8"`) that exists in
   `src/fonts/fa-solid-900.ttf`. Write `\uXXXX` with the Edit tool (see Editing).
4. Call `Foo.initialize()` in `CrypticClient.kt` (before `ConfigManager.initialize()`), and its
   `tick` in the end-of-tick loop. If it needs to know the island, add it to the list passed to
   `SkyblockLocation.tick`.
5. Update `docs/`, and credits if anything came from another project (see Licensing).

Settings style:
- Show only settings that apply: `visibleIf = { ... }`.
- Put a toggle's sub-settings behind its "⋯" with `toggle.withMore(a, b)`. Never nest them.
- Card descriptions fit about 28 characters (164 px at 12 px). No Reset button on small cards.
- Gate anything past "legit" with `restriction = Restriction.SEMI_LEGIT` or `CHEAT` (on `Module`
  or `ToggleModuleSetting`), or per option with `DropdownModuleSetting(optionRestrictions = ...)`.
- A renamed dropdown option needs `renamed = mapOf(old to new)`. A merged module needs a
  `RETIRED_MODULES` entry. MultiSelect choices are saved by name, so options can be reordered.
- New modules start disabled.

## Hooks, threads and other traps

- **Chat:** read it in `ClientPacketListenerMixin.cryptic$readSystemChat` with an
  `onSystemChat(message: Component, overlay: Boolean)`. That runs on the client thread. Don't use
  Fabric's chat events: they miss lines SkyHanni or Odin hide.
- **Hypixel keeps `§` codes** in chat, action bar, lore and names. Strip `§.` before matching.
- **Network thread:** hooks at the HEAD of a packet handler run on Netty first, then again on the
  client thread. Anything that throws on Netty disconnects the player. Particle hooks check
  `client.isSameThread` to act once.
- **Screens:** overlays beside menus go in `ScreenMixin` (it hooks just before
  `extractDeferredElements`, so tooltips stay on top). Clicks go in
  `AbstractContainerScreenMixin`, and world drawing in `LevelRenderEvents.COLLECT_SUBMITS` with
  `WorldRender`.
- **Closing a chest:** use `player.closeContainer()`. `setScreen(null)` strands `containerMenu`,
  and the inventory then refuses every click.
- **Entity positions** in move hooks: `entity.positionCodec.base`, since `entity.x` lags a tick.
- **Java 25 JIT bug:** `a ?: Vec3(...)` in per-frame code can be miscompiled to null. Pick by
  components instead.
- **Per-frame cost:** code that runs per slot per frame must be cheap.
  - Each `graphics.fill` over an item makes Minecraft search every element under it; eight per
    slot once cost half a frame. Use one tinted `blit` instead.
  - `SkyblockItem.id`/`data` read custom data without copying; avoid `copyTag()` per frame.
  - Cache by stack in a `WeakHashMap<ItemStack, …>`.
- **Hypixel only:** features that only make sense on Hypixel check `HypixelServer.connected`.

## Licensing (Cryptic is public)

- MIT, BSD, Apache and CC0 code (Odin, NoammAddons, Blade Addons, dtMap, SkyOcean, SkyHelper
  Networth) may be ported with credit: the licence in `licenses/<Name>-LICENSE.txt`, a line in
  `THIRD_PARTY_NOTICES.md`, and the in-game `CreditList.kt`.
- GPL, LGPL and AGPL code (Skyblocker, SkyHanni, NoFrills, NotEnoughUpdates, quoi, Devonian, Zen,
  BtrBz and others) is for ideas only. Never paste or closely translate it; write Cryptic's own.
  Facts such as coordinates, texture hashes and message formats may be used, credited "as facts
  only".
- Never read astoutline's code.

## Dungeon room coordinates

Room data uses room-relative coordinates: world minus the room's corner (`clayPos`, x and z only;
y stays world y), turned to NORTH by the room's rotation (`DungeonRoom.getRelativeCoords` /
`getRealCoords`, `RoomRotation.kt`). For EAST: `rel = (-(z - corner.z), y, x - corner.x)`.

`/cryptic debug room` prints the room, rotation, corner and the player's relative position. When
the user sends it with "Looking at x y z", convert with the formula; don't re-derive it.

`secret_waypoints.json` is one minified line with keys sorted:
`"Room Name":{"chest":[[x,y,z]],"essence":[[...]],"item":...,"bat":...,"lever":...}`. Wither
essence (skulls) go under `essence`. Cross-check a room's `secretDetails` in `map/rooms.json`, and
check that `git diff --word-diff` shows only the new entry.

## Style

- Tabs. Features are Kotlin `object`s with `initialize()` and `tick()`.
- Comments are short and say why, not what. No history ("used to", "was changed"). Match the
  density around the code; British spelling (colour, licence).
- Commit messages: a short summary. Release commits read `0.92.3: feature, feature and more`.
  End commits with the `Co-Authored-By` line the session gives.

## Building, versions and installing

- Build: `./gradlew build -q`. The jar is `build/libs/cryptic-<version>-26.2.jar`.
- **Bump `version=` in `gradle.properties` for every jar you hand the user**, even a one-line fix.
- Install: copy the jar to
  `C:\Users\imicro\AppData\Roaming\PrismLauncher\instances\Hypixel Skyblock\minecraft\mods\` and
  delete the old Cryptic jar. If the game holds it, delete it in a background loop
  (`while [ -f "$OLD" ]; do rm -f "$OLD" 2>/dev/null || sleep 3; done`).
- **Commit and push only when the user asks.** Work happens on `terminals-and-general-modules`;
  `master` is the default branch and is fast-forwarded to it on release.
- **Release:** commit, push, then `git push origin terminals-and-general-modules:master`.
  - Create it with `gh release create v<ver>-26.2 build/libs/cryptic-<ver>-26.2.jar --target master
    --title "Cryptic <ver> (Minecraft 26.2)" --notes-file <file> --latest`.
  - The jar's name must stay `cryptic-<ver>-26.2.jar`; Update Checker looks for it.
  - Notes: no tables, since the website's own Markdown parser can't render them.
- **Website:** modworks.tf (repo `modworks-tf/website`, cloned at
  `C:\Users\imicro\Downloads\website`, Astro on Cloudflare Pages) only reads releases when it is
  built. After a release, push a website commit to `main` and check the Pages deploy. The user
  lets you commit and push the website without asking.

## Testing

- **Cloud sessions can't build:** the network blocks `maven.fabricmc.net`, and the container has
  Java 21. Say the change wasn't compiled; check JSON with a script.
- **Locally, run the dev client:**
  1. Add a temporary `debug/DevHarness.kt` with a `tick(client)` that does the setup and takes
     screenshots (`Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget()) { ... }`),
     then calls `client.stop()`.
  2. Call it from `CrypticClient` after `MenuScreen.openIfRequested(client)`.
  3. Run `CRYPTIC_HARNESS=1 ./gradlew runClient --args="--quickPlaySingleplayer Home"`.
  4. Back up `run/config/cryptic` first. Afterwards restore it, delete the harness and remove the
     call.

  `SkyblockLocation.onSkyblock` can be forced by reflection. Test with the user's Sodium before
  shipping render changes.
- `DebugOverrides` and `/cryptic debug ...` fake dungeon floors, classes, the Hollows zone and
  more (see `docs/debug-commands.md`).
- **Profiling:** set `JAVA_TOOL_OPTIONS=-XX:StartFlightRecording=filename=<dir>/rec-%p.jfr,settings=profile`
  on `runClient`. Then run `jfr print --stack-depth 80 --events jdk.ExecutionSample`. The render
  thread is named `main`.
- **Minecraft sources:**
  `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/26.2/*-sources.jar`. Read
  one class with `unzip -p <jar> net/minecraft/.../Class.java`.
- The user's game log is `...\Hypixel Skyblock\minecraft\logs\latest.log` (older ones are
  `.log.gz`). Check it for crashes and Hypixel's exact chat lines.

## Editing

- Scripted edits through bash heredocs (perl, node) eat backslashes and mangle `§`, `—`, `$` and
  `@`. For anything with a regex or escape, use the Edit tool, and check regexes after any
  scripted edit.
- Never pipe into a heredoc that waits on stdin; it stalls the shell.

## Working with the user

- Replies are short and plain: what changed, what was tested, what wasn't. Ask for a screenshot or
  a `/cryptic debug` dump rather than guessing positions.
- Don't use subagents for searches; grep is enough here.
