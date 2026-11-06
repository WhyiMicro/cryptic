# Pack Tweaks

In the Render tab. It reads resource packs made for
[Firmament](https://github.com/FirmamentMC/Firmament)'s `firmskyblock` format,
so they work without Firmament installed. Turn the module on and enable the pack
as usual in Minecraft's resource pack screen.

## Item models

Draws a SkyBlock item with the pack's model for its SkyBlock ID, in hand, in
menus, on the ground and in item frames. A pack can give the model in either
form:

- a client item at `assets/firmskyblock/items/<id>.json`, as Minecraft's own
  items use;
- the older form, a plain model at `assets/firmskyblock/models/item/<id>.json`.
  Cryptic wraps it in a client item itself.

When both exist, the client item wins.

The ID is the one in the item's custom data, turned into a path: lower case,
`:` written as `___`, `;` as `__`, and any other character a path can't hold as
`__` and its four-digit hex code. So `ASPECT_OF_THE_END` is
`firmskyblock:aspect_of_the_end`. Pets, enchanted books and runes are looked up
by their NEU ID first and their plain ID after: a legendary wolf is
`firmskyblock:wolf__4` (tiers count from common = 0), an Ultimate Wise V book
`firmskyblock:ultimate_wise__5`, a Music rune III `firmskyblock:music_rune__3`.

`/cryptic debug pack` shows what the held item is looked up by.

## Model overrides

Switches an item's model by what the item is. Two forms are read:

- an old-format model's `overrides`: a list of `{"predicate": {...}, "model":
  "<model id>"}`;
- a client item of type `firmament:predicates/legacy`, with a `fallback` model
  and the same `overrides` list (each `model` either a model id or a whole item
  model). Cryptic leaves this type to Firmament when Firmament is installed.

As with vanilla's old overrides, the last override whose predicate holds wins.
Every entry in a `predicate` object must hold. The predicates read are:

- `firmament:display_name` and `firmament:lore` (any line), with a string
  matcher: a plain string must equal the text with its colour codes removed, or
  `{"regex": ...}` / `{"equals": ...}` with `"color": "strip"` (the default) or
  `"preserve"`. A regex must match the whole text.
- `firmament:item`: the item type, such as `minecraft:clock`.
- `firmament:extra_attributes`: a `path` into the item's SkyBlock data and an
  NBT matcher, inline or under `match`: `{"string": <string matcher>}`, or a
  number type (`int`, `long`, `double` and so on) with a value or
  `{"min", "max", "minExclusive", "maxExclusive"}` (min included and max left
  out by default). Paths are dot-separated; `*` takes every child, and `*json`
  and `*base64` decode a string.
- `firmament:component`: the same, on any item component (`"component":
  "minecraft:dyed_color"`) encoded as NBT.
- `firmament:pet`: `id`, `exp`, `tier`, `level` and `candyUsed`, with number
  matchers such as `100`, `">=50"` or `"[50,100)"` (tiers by name). The level is
  read from the item's `[Lvl N]` name.
- `firmament:skull`: `profileId`, `textureProfileId`, `skinUrl` and
  `textureValue`.
- `firmament:any`, `firmament:all` (lists of predicate objects) and
  `firmament:not` (one object).
- At the top level only, vanilla's old predicates `pulling`, `pull`, `cast`,
  `damage`, `damaged`, `cooldown`, `lefthanded` and `custom_model_data` (true
  when the value is at least the number given).

A predicate Cryptic can't read never matches, and the game log says which file
and key it was. Results that depend only on the item are kept per item, so lore
regexes don't run every frame.

## Tint overrides

An old-format model's layers are tinted the way the item's own model tints
them (leather by its dye, potions by their colour), as Minecraft did before
client items. `"firmament:tint_overrides": {"0": -1}` sets layer 0 to a fixed
colour instead (`-1` is white, so no tint). Overrides are inherited from parent
models; a child's `"0": null` drops its parent's override for that layer.

## Global item overrides

For items without a SkyBlock ID. Each file in `firmskyblock:overrides/item/`
holds a `model` (an item ID, read from `models/item/<id>.json` or
`items/<id>.json`), a `predicate` and a `screen`: one or a list of screen filter
IDs. A filter is `assets/<ns>/filters/screen/<id>.json` with a `title` string
matcher, matched against the open screen's title; `firmament:always` matches
everywhere, the hotbar and the world included. An item's SkyBlock ID model is
tried first.

## Head models

`"firmament:head_model": "<model id>"` on an old-format model draws that model
on the wearer's head in place of the helmet. It is read after the model's
overrides, so a recombobulated variant can have its own.

## Armour textures

Each file in `firmskyblock:overrides/armor_models/` lists `item_ids` (SkyBlock
IDs) and `layers`. A layer's `identifier` becomes
`assets/<ns>/textures/models/armor/<path>_layer_<1 or 2><suffix>.png` (2 for
leggings); `"tint": true` takes the leather dye colour. `overrides` switch the
layers by predicate, last match winning. Older packs that put
`firmskyblock:textures/models/armor/<id>_layer_1.png` (and an optional
`_overlay`) straight at the ID work too. Armour trims are not drawn on
retextured pieces.

## Placed skulls

A placed player head whose skin URL ends in a hash is drawn with
`firmskyblock:textures/placedskulls/<hash>.png` when the pack has it. Worn and
held heads are not changed.

## Screen layouts

Each file in `firmskyblock:overrides/screen_layout/` matches a screen by
`predicates.label` (its title) and optionally `predicates.screenType` (a menu
type such as `minecraft:furnace`, or `firmskyblock:sign` and
`firmskyblock:hanging_sign`), then may:

- replace the background with `background` (`texture`, `x`, `y`, `width`,
  `height`, relative to where the menu's own texture starts). Clicks on a
  background wider than the menu's own count as inside it.
- move `slots` by their index in the screen.
- move or replace `containerTitle`, `playerTitle` and `repairCostTitle`
  (`x`, `y`, `align` of left, center or right, `replace`).
- move an anvil's `nameField` (`x`, `y`, `width`, `height`) and a sign's four
  `signLines` (offsets).

On signs the background is placed from the point the sign is drawn around.

## Menu text colours

`firmskyblock:overrides/text_colors.json` recolours the titles and labels drawn
in menus: `defaultColor`, and `overrides` of `{"predicate": <string matcher>,
"override": <colour>}`.

## Text replacements

Each file in `firmskyblock:overrides/texts/` has a `match` for the whole text
and `replacements`, each with a `match` found anywhere in one part of the text,
an optional `style` that part must have, and the `replace` text (`${name}`
fills from the regex's named groups). They apply to text drawn on screen and to
item tooltips; chat is not changed.

## Block models

Each file in `firmskyblock:overrides/blocks/` names the `modes` it applies in,
optional `area` boxes, and `replacements` from a block ID to a blockstates file
or block model (`<ns>:blockstates/<path>.json` first, then
`<ns>:models/block/<path>.json`), with an optional mining hit `sound`. Works
with vanilla's and Sodium's chunk builders.

Cryptic reads the island from the tab list rather than `/locraw`, and turns it
into the mode: `hub`, `dynamic` (Private Island), `garden`, `dungeon_hub`,
`dungeon`, `farming_1`, `foraging_1`, `foraging_2`, `mining_1`, `mining_2`,
`mining_3`, `crystal_hollows`, `mineshaft`, `combat_1`, `crimson_isle`,
`combat_3`, `kuudra`, `winter`, `rift`, `fishing_1` and `dark_auction`. Chunks
are rebuilt when the island changes.
