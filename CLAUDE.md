# Cryptic — notes for Claude

Personal Hypixel SkyBlock (mostly Dungeons) client mod. Fabric, Kotlin + Java
mixins, Minecraft 26.2, Java 25. Menus are Dear ImGui (needs OpenGL). The user
plays on Windows and tests in game; Claude cannot launch the game.

Read only what the task needs. Don't re-survey the repo: this file is the map.

## Layout

- `src/main/kotlin/imicro/cryptic/`
  - `CrypticClient.kt` — client entrypoint. Every feature's `initialize()` is
    called here, then `ConfigManager.initialize()` last. `/cryptic` commands
    (incl. `/cryptic debug ...`) are registered here too.
  - `feature/` — one `object` per module (~90 files, named after the module).
  - `gui/Module.kt` — `Module`, `ModuleCategory`, every `*ModuleSetting` type,
    and `ModuleRegistry.modules` (the list the menu shows).
  - `dungeon/` — dungeon state; `dungeon/map/` — room scan, `DungeonRoom`,
    `RoomData`, `RoomRotation.kt`.
  - `puzzle/`, `terminal/` (+ `terminal/sim/`), `device/`, `experiment/`,
    `skyblock/`, `hud/`, `render/`, `config/ConfigManager.kt`.
- `src/main/java/imicro/cryptic/mixin/` — mixins; each must be listed in
  `src/main/resources/cryptic.mixins.json`.
- `src/main/resources/assets/cryptic/`
  - `default_profile.json` — the shipped default settings, keyed by module id
    and setting id.
  - `dungeon/secret_waypoints.json` — default secret waypoints (see below).
  - `map/rooms.json` — dtMap's room database, copied verbatim. Don't reformat it.
  - `puzzles/` — puzzle answer files. `lang/en_us.json` — key bind names.
- `src/test/kotlin/` — unit tests (experiment solver, terminal types only).
- `docs/` — user docs; `docs/debug-commands.md` lists every `/cryptic debug`
  switch for testing features outside a real run.
- Root `assets/`, `META-INF/`, `fabric.mod.json`,
  `fabric-key-mapping-api-v1.mixins.json`, `LICENSE-fabric-key-mapping-api-v1`
  are leftovers from an unpacked jar, not part of the build. Ignore them.

## Adding or changing a module

1. In `feature/Foo.kt`: settings as `@JvmField val x = ToggleModuleSetting(...)`
   etc., then `@JvmField val module = Module(id = "foo", name, description,
   category, hasDemoSettings = false, settings = listOf(...))`. `ExampleModule.kt`
   shows every setting type.
2. Add `Foo.module` to `ModuleRegistry.modules` in `gui/Module.kt`.
3. Call `Foo.initialize()` in `CrypticClient.kt` (before `ConfigManager`).
4. If it needs defaults other than the code's, add them to `default_profile.json`.
5. Credit ported code in `README.md`'s Credits and add the licence to `licenses/`.

## Dungeon room coordinates

Room data uses room-relative coordinates: world minus the room's corner
(`clayPos`, x/z only; y stays world y), turned to NORTH by the room's rotation
(`DungeonRoom.getRelativeCoords` / `getRealCoords`, `RoomRotation.kt`).
For EAST: `rel = (-(z - corner.z), y, x - corner.x)`.

`/cryptic debug room` prints the room name, rotation, corner and the player's
relative position. When the user sends a screenshot of it plus "Looking at x y z",
convert with the formula above. Do not read code to re-derive it.

`secret_waypoints.json` is one minified line, keys sorted alphabetically:
`"Room Name":{"chest":[[x,y,z]],"essence":[[...]],"item":...,"bat":...,"lever":...}`.
Wither essence (skulls) → `essence`. Cross-check against the room's
`secretDetails` in `map/rooms.json`. Edit it with a small Python script
(`json.load`, add, `json.dumps(..., separators=(",",":"))`, sorted keys, no
trailing newline) and check `git diff --word-diff` shows only the new entry.

## Code style

- Tabs. Kotlin features are `object`s with `initialize()`.
- Comments are full sentences in KDoc explaining *why*, in British spelling
  (colour, licence). Match the density around the code you touch.
- Commit messages: short imperative summary; release commits look like
  `0.21.3: feature, feature, feature`. Version lives in `gradle.properties`.

## Building and testing

- `./gradlew build` (jar in `build/libs/`), `./gradlew test`.
- `gradlew` isn't executable in a fresh checkout here: run `sh gradlew ...`.
- Cloud sessions can't build: the network policy blocks `maven.fabricmc.net`
  (Loom fails to resolve), and the container has Java 21, not 25. Don't spend
  turns retrying; say the change wasn't compiled. Data-only changes (JSON)
  can be checked with Python.
- In-game behaviour can only be verified by the user.

## Working with the user

- Keep replies short. Ask for a cropped screenshot of the debug overlay rather
  than guessing positions.
- Don't use subagents for searches here; the repo is small enough for grep.
