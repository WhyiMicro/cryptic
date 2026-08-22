# Debug commands

Most of Cryptic lives inside a Catacombs run: a floor number on the scoreboard, a
party in the tab list, a boss that has greeted you. Waiting for a real run to see
whether a change worked costs minutes per attempt, so every dungeon-gated feature
has a switch that makes it testable from anywhere — a creative world, the lobby,
wherever you happen to be.

All of them are **memory only**. Nothing here is written to your profile, and
every switch is gone the moment the game closes, so a forgotten one cannot follow
you into a real run.

Most are **toggles**: run the command once to turn the override on, run it again
to turn it off. The chat reply always says which state you landed in.

---

## The floor override

```
/cryptic debug floor <E|F1…F7|M1…M7>
```

Makes Cryptic read you as standing on that floor, instead of reading the
scoreboard.

This is the one to reach for first. Nearly everything else — the score, the map,
the door highlights, room alerts, hidden mobs — refuses to do anything unless it
believes you are in a dungeon, so without this the other switches have nothing to
act on. `E` is the entrance floor; `M3` is Master Mode 3, which matters because
the floor decides how much each secret is worth.

Repeat the same floor to turn the override off.

---

## The map

```
/cryptic debug map
```

Loads a stand-in floor — rooms, doors, states and all — so the map draws anywhere.
It is a real map as far as the rest of the mod is concerned, which makes it the
easiest way to place the HUD element or look at a colour change. A live map
update overwrites it the moment one arrives, so leaving it on cannot hide a real
dungeon.

Run it again to clear the sample; the reply then reports what the last real map
update did.

```
/cryptic debug scan
```

Prints what the world scan has worked out: the floor's size, every room with its
name, shape, state and tiles, and every door with its type and the rooms it
joins.

The scan is invisible when it works and equally invisible when it does not — a
room simply has no name. This is how you tell the difference between "the scan
has not reached that room yet" and "that room is not in `rooms.json`". It is also
the fastest way to check a door is where you think it is.

---

## The score

```
/cryptic debug score
```

Switches the 270 and 300 party announcements from being **sent** to being **shown
to you**, and prints the current score line straight away so the wording can be
read back without a party to send it to.

The reply also says whether Mayor Paul has been looked up yet, since his EZPZ
perk is worth ten points and the answer comes from a web request that may not
have landed.

Pair it with `/cryptic debug floor F7` — the score does nothing outside a
dungeon.

---

## Rooms and doors

```
/cryptic debug roomalerts
```

Shows the Cleared title once, so its wording and position can be checked without
clearing a room.

```
/cryptic debug keys
```

Treats **every armour stand** as a door key, anywhere, and plays the pickup pling
once. A real key only exists behind a wither door, so `/summon armor_stand` plus
this switch is how the highlight, the tracer and the colours get looked at.

Run it again to go back to real wither and blood keys.

---

## Classes and Floor 7

```
/cryptic debug setclass <archer|berserk|healer|mage|tank>
```

Makes Cryptic read you as being in the Catacombs on that class, instead of
reading the tab list. Class colours, the class letter over your head and the
ring around your head on the map all follow it.

Repeat the same class to turn it off.

```
/cryptic debug witheroutline
```

Outlines **every** wither anywhere, instead of only the four Floor 7 bosses.

```
/cryptic debug withercloak
```

Holds the Creeper Veil shields on without waiting for Hypixel to grant it.

```
/cryptic debug leapmessage
```

Shows leap announcements to you instead of sending them to party chat, and
prints one immediately so the wording can be read back without a teammate to
leap to.

---

## Things that need no switch

A few states are easier to fake directly than to add a command for.

**The boss room.** `DungeonRun` listens for the boss's greeting, so pasting one
into your own chat is enough:

```
/tellraw @s {"text":"[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"}
```

That flips the mod into boss mode — the map hides itself, the score switches to
its in-boss form, and door highlighting stops.

**A Dungeon Breaker**, for testing Breaker Helper:

```
/give @s minecraft:stick[minecraft:custom_data={id:"DUNGEONBREAKER"}]
```

Cryptic identifies Skyblock items by that `id`, not by what the item actually is,
so a stick works as well as the real pickaxe. The same trick works for a leap:
use `SPIRIT_LEAP` or `INFINITE_SPIRIT_LEAP` to make player names appear on the
map.

---

## Not a debug switch

```
/cryptic                  opens the menu
/cryptic hud              opens the HUD placement editor
/cryptic config export    copies the active profile to the clipboard
/cryptic config import    loads a profile from the clipboard, without activating it
/cryptic etherwarp sound <name>   picks the Etherwarp sound
```

The menu has its own button for the HUD editor, at the right end of the
navigation bar. Opened that way, escape returns you to the menu; opened with the
command, escape closes to the game. **Ctrl+F** opens the menu's search from
anywhere inside it.
