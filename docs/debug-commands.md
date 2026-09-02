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

## Watching the Experimentation Table

```
/cryptic debug experiments
```

A toggle, and the odd one out: it does not change what Cryptic does, it reports
what Cryptic sees.

The table is a chest, and a chest is a screen, so chat cannot be typed into at
any of the moments worth knowing about. Asking after the fact is no good either —
by then the interesting menu is several menus ago. So this watches instead:
while it is on, every menu that opens, every round the solver reads, and every
decision the table runner makes is announced in chat as it happens.

It also writes a slot-by-slot dump of each menu — item id, count, name and lore
for every filled slot — into `logs/latest.log`. That dump is the point. The rules
the runner works the table with came from a mod for Minecraft 1.8.9 and none of
them could be checked against a real table, so one run with this on is enough to
correct every slot number and every line of lore at once.

Run one experiment with it switched on, then send the log.

---

## Not a debug switch

```
/cryptic                  opens the menu
/cryptic version          says which build is installed
/cryptic hud              opens the HUD placement editor
/cryptic termsim          opens the terminal simulator
/cryptic config export    copies the active profile to the clipboard
/cryptic config import    loads a profile from the clipboard, without activating it
/cryptic etherwarp sound <name>   picks the Etherwarp sound
```

The menu has its own button for the HUD editor, at the right end of the
navigation bar. Opened that way, escape returns you to the menu; opened with the
command, escape closes to the game. **Ctrl+F** opens the menu's search from
anywhere inside it.

The terminal simulator is the way to see the Terminal Solver without a Floor 7
run: it opens the same six terminals client-side, and the solver draws over them
exactly as it would in the boss room. Its own module card, in the Floor 7 tab,
has the same button plus a **Ping** slider — set that to your real ping and the
terminals answer as slowly as Hypixel would, which is the point of practising at
all. The solver's first click protection applies there too, so a click in the
first half second goes nowhere; **Skip click protection** turns that off for
practice without touching what happens in a real dungeon.
