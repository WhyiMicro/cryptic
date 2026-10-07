# Dungeon Waypoints and Positional Messages

Both modules are in the Dungeon tab, and each has a `/cryptic` subcommand.

## Dungeon Waypoints

Boxes drawn in the room you are standing in. There are two kinds.

**Secret waypoints** come from Devonian's table of every chest, item, wither
essence, bat spot, redstone key and lever in each room. Each kind has its own
switch and colour. With **Hide found secrets** on, a secret's box goes away once
you take it:

- A chest, skull or lever goes when you click it. If Hypixel answers "That
  chest is locked!", the chest comes back.
- An item goes when you pick up a secret item nearby.
- A bat goes when a bat dies within 10 blocks of its spot, in the room you are
  in or the room it died in.
- The redstone key goes with the "You found a Secret Redstone Key!" message.

**Custom waypoints** are the ones you place yourself, as in Odin's editor.

1. Turn on **Edit mode** in the module, with `/cryptic dwp edit`, or with the
   **Edit mode key** you bind in the Editor section.
2. Right-click a block to place a waypoint of the **Placing** kind, and
   right-click it again to remove it. While Edit mode is on, a click does
   nothing else, so the chest or lever under it is not touched.
3. Sneak and right-click to give a waypoint a title.
4. Hold Shift and scroll to change which kind the next click places, without
   moving the hotbar.
5. With **Allow floating waypoints** on, a click with the crosshair on nothing
   places a waypoint in the air 5 blocks ahead, as in Odin. Aim the same way
   to remove it.

There are five kinds. Each has a switch to show or hide it, with its colour
beside the switch:

- **Normal:** always shown.
- **Secret:** hidden once the secret there is taken.
- **Etherwarp:** hidden once you etherwarp onto it. Always drawn as a solid
  fill without an outline, whatever the style, so it does not cover the
  crosshair.
- **Dungeon Breaker:** hidden once its block is broken.
- **Start:** where a route begins. Always an outline drawn through walls, so it
  can be seen from anywhere in the room.

Etherwarp and Dungeon Breaker waypoints are never drawn through walls, because
the block you can see is the one that matters for both. Start waypoints always
are. A Dungeon Breaker box's sides that are against a solid block are drawn
just inside the block, so the outline never shows on the block beside it.

A waypoint's title, and its kind with **Labels** on, is written in white with a
shadow in the middle of its block, and can be read through walls.

Waypoints are stored per room in the room's own coordinates, so one placed in
Atlas shows in every Atlas, however the room is turned.

The entrance room takes no waypoints: there is nothing to find there, and a
waypoint placed beside Mort could land outside the room where it cannot be
clicked off. Any kept under "Entrance" are dropped when packs are read.

### Packs

Custom waypoints are grouped into packs, as in Odin. `/cryptic dwp` opens the
pack manager:

- **Create** and **Import** are at the top. Import reads an exported string
  from the clipboard and asks for a name.
- The tick box on each row shows or hides that pack.
- **Edit** picks the pack Edit mode writes into. The pack being edited is
  marked with a star.
- **Export** copies a pack to the clipboard, in the format Odin uses.
- ✎ renames a pack. **X** deletes it after a second click to confirm.

**Default** is Devonian's secret spots. It can be hidden with its tick box,
but it cannot be edited, renamed, exported or deleted.

Packs are kept in `config/cryptic/waypoint-packs.json`. Waypoints made before
packs existed were moved into a pack called "My Waypoints".

**Style** (outline, fill or both), fill opacity, line width, drawing through
walls and labels apply to every waypoint of both kinds, apart from the
exceptions above for Etherwarp and Dungeon Breaker.

| Command | Does |
|---|---|
| `/cryptic dwp` | Opens the pack manager |
| `/cryptic dwp info` | Shows the status and the room you are in |
| `/cryptic dwp edit` | Turns Edit mode on or off |
| `/cryptic dwp type <normal\|secret\|etherwarp\|breaker\|start>` | Sets what the next click places |
| `/cryptic dwp size <0.1-3>` | Makes new waypoints a fixed size, not the block's |
| `/cryptic dwp useblocksize` | Switches between block size and fixed size |
| `/cryptic dwp title <text>` | Titles the waypoint you are looking at |
| `/cryptic dwp clear` | Removes the edited pack's waypoints from the current room |
| `/cryptic dwp resetsecrets` | Shows every taken secret again |

`/cryptic debug waypoints` prints the room's name, its shape and rotation, and
how many waypoints it has. If nothing is drawn in a room, the rotation is the
first thing to check: boxes can only be placed once Cryptic has found the blue
terracotta that marks the room's corner, and for a larger room every one of its
tiles has to be known first.

## Boss Waypoints

In the Boss tab. It works like the Dungeon Waypoints editor, but in boss rooms,
where there is no room rotation to work out. A boss waypoint is a plain world
position, kept per floor (`F7`, `M7` and so on).

1. Turn on **Edit mode**, with `/cryptic bwp edit`, or with the **Edit mode key**.
2. Right-click a block to place or remove a waypoint.
3. Sneak and right-click to give it a title.
4. Hold Shift and scroll to change the style of the next waypoint.
5. **Allow floating waypoints** works as in Dungeon Waypoints.

Each waypoint keeps the look the editor had when you placed it: **Style**
(outline, fill or both), **Phase** (drawn through walls or not) and **Color**.
The colour's opacity is used for the fill, and the outline is always solid.

`/cryptic bwp` opens the same pack manager as Dungeon Waypoints, without a
Default pack. Packs are kept in `config/cryptic/boss-waypoint-packs.json`.

| Command | Does |
|---|---|
| `/cryptic bwp` | Opens the pack manager |
| `/cryptic bwp info` | Shows the status and the boss you are in |
| `/cryptic bwp edit` | Turns Edit mode on or off |
| `/cryptic bwp style <outline|fill|both>` | Sets the style of the next waypoint |
| `/cryptic bwp phase` | Switches the next waypoint between through walls and not |
| `/cryptic bwp color <AARRGGBB>` | Sets the colour of the next waypoint |
| `/cryptic bwp title <text>` | Titles the waypoint you are looking at |
| `/cryptic bwp clear` | Removes the edited pack's waypoints for this boss |

## Positional Messages

Odin's module. It says something in party chat when you reach a place: within
a radius of a point (**at**), or inside a box between two corners (**in**). With
**Only in boss** on, which is the default, nothing triggers outside a dungeon
boss fight. Each message is sent once per server.

| Command | Does |
|---|---|
| `/cryptic posmsg add here <delay> <distance> <color> <send> <message>` | At the middle of the block you stand on |
| `/cryptic posmsg add at <x> <y> <z> <delay> <distance> <color> <send> <message>` | At a point |
| `/cryptic posmsg add in <x> <y> <z> <x2> <y2> <z2> <delay> <color> <send> <message>` | Inside a box |
| `/cryptic posmsg remove <message>` | Removes it |
| `/cryptic posmsg list` | Lists every message |
| `/cryptic posmsg clear` | Removes them all |

- **delay** is in ticks.
- **send** `false` only draws the spot and never says anything.
- **color** is one of Minecraft's sixteen colours, written as one word:
  `darkblue`, `lightpurple`, `white` and so on.

Odin suggests the corner of the block you are standing on as the coordinates,
which leaves the circle half a block off from where you stood. Cryptic suggests
x and z rounded to the nearest half block (139.2 becomes 139, 139.42 becomes
139.5), and `add here` uses the same position. The circle is drawn the way Odin
draws it, as a band, and the message above it is always white.

The list is kept in `config/cryptic/positional-messages.json`, so it does not
change when you switch profiles.
