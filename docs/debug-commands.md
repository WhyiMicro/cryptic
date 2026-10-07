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

```
/cryptic debug team
```

Prints the party in tab-list order against the markers the map has for them.

The dungeon map says where five people are and nothing at all about who they
are: the first marker belongs to the first living teammate in the tab list, the
second to the second, and so on. That pairing is the only thing naming the heads
on the map, so when a head is drawn as the wrong person this is where it shows —
read the list against the tab list and against where people actually are.

```
/cryptic debug pet
```

Prints every row of the tab list that has anything on it, and what the Guardian
reminder reads as your pet.

A pet is only knowable from the `[Lvl 100] Guardian` row in the tab list — not
from the thing following you, because plenty of people hide theirs. This says
whether that row is being found and what it says.

```
/cryptic debug carry
```

Prints every named armour stand nearby and what the Carry Manager reads as a
slayer boss — its owner and its tier.

A boss carries its owner and tier on invisible stands whose entity ids are the
boss's plus one and plus three. Those offsets are the fragile part of carry
tracking, and this is what says whether they still hold.

```
/cryptic debug location
```

Prints the sidebar's objective name and what Cryptic reads as SkyBlock and the
current island.

Slot Binds and Nucleus QoL both refuse to act off SkyBlock, and Nucleus QoL
outside the Crystal Hollows, so when one of them does nothing this says whether
the place was misread. The scan only runs while one of those two modules is on,
so with both off it reports nothing.

```
/cryptic debug timers
```

Prints every Smart Tick Timer's raw value, how many server ticks have been
counted, and how the run is being read.

A timer that is not moving looks identical whichever of three things went wrong:
its chat line never arrived, the server's per-tick ping is not reaching the
counter, or the run is not being recognised as a run. The tick count answers the
second, the dungeon line answers the third, and a timer sitting at -1 answers the
first.

```
/cryptic debug cookie
```

Prints every tab row in display order, every line of the footer under it, and
what the Booster Cookie Reminder reads from each.

The cookie's remaining time is written as a `Cookie Buff` row with the value on
the row below, and nowhere else a client can reach. This says whether the heading
is found in either place, and whether the line under it parsed as a duration, as "no cookie",
or as neither — which is the difference between the reminder staying quiet
because there is nothing to say and staying quiet because it cannot read.

```
/cryptic debug mimic
```

Prints every zombie within twenty-four blocks: what it is, whether it is a baby,
what it is called, what it is carrying, and whether Highlight reads it as the
mimic.

Telling the mimic from the baby zombies a weapon throws up is a heuristic, and
the only place to check it is a floor that has one. This is what says whether a
mimic went unmarked because it was carrying something unexpected or because it
is not the mob the check is looking for at all.

```
/cryptic debug devices
```

Stand anywhere in a Goldor section and this prints every named armour stand
within reach — its name and where it is — followed by what each terminal, device
and lever in the section made of them: done, pending, or unknown because nothing
matched.

Those stands are how Terminal Order knows a thing has been finished, and their
names and positions are the one part of the tower that cannot be worked out from
anywhere else. When a label refuses to disappear, this says whether the stand is
out of range, sitting further from its recorded position than expected, or
carrying a name nothing here recognises.

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

## Boss timings

SkyBlock 0.27.2 sped up the Floor 7 fight, so the Smart Tick Timer's numbers
are being measured again. Two commands help.

```
/cryptic debug bosslog
```

Prints, in chat, every boss line, every lightning strike, and every wither
starting or stopping to move (Storm moving himself, Necron dropping). Each is
stamped with the server ticks since that boss's first line, what those come to
in seconds, and the real seconds — the two only differ when the server lags:

```
[Boss] Storm +0t (0.00s, 0.00s real) Pathetic Maxor, just like expected.
[Boss] Lightning volley +519t (25.95s, 26.08s real) after Storm's first line (+33t after the last line)
[Boss] Storm +548t (27.40s, 27.56s real) (+62t) ENERGY HEED MY CALL!
[Boss] Wither #412 started moving +647t (32.35s, 35.17s real) after Storm's first line (+99t after the last line)
```

Only the big lightning volley is printed, not every bolt Storm throws. A wither
line also says how long after the last boss line it came, which is the number
the PY and Necron timings are read from.

Run it again to turn it off. It is always off when the game starts.

```
/cryptic debug timing
/cryptic debug timing <name>
/cryptic debug timing <name> <540 | 540t | 27s | 27.05s | reset>
/cryptic debug timing reset
```

Lists every timing the Smart Tick Timer counts with, shows one, sets one in
ticks or seconds, or puts one or all back. A timing that has been set is kept
between sessions, in `config/cryptic/timings.json`.

| Name | Default | Counts |
|---|---|---|
| `lightning` | 519t | Storm's first line until the big lightning volley |
| `py` | 52t | Storm's PY call until the purple pillar should be lowered |
| `goldor_start` | 64t | Storm dying until terminals can be done |
| `goldor_core` | 60t | Goldor's repeating cycle |
| `necron` | 15t | "I'm afraid, your journey ends now." until Necron drops |
| `fire_freeze` | 206t | The Professor's line until Fire Freeze, with its 100-tick cast |
| `watcher_move` | 400t (20s) | The Watcher's greeting until he moves his first wave on (Blood Camp's kill title) |
| `livid` | 340t | Livid's greeting until he can be hurt (390 before 0.27.2) |

`lightning` was measured with the log on 2026-10-06, over two runs; `py` and
`necron` were then settled in game. The PY timer turns orange at 40 ticks and
red at 20, the last two times the pads move before Storm does.

The Necron timer starts before his last words, so it runs for about six
seconds instead of under one: at Goldor's "....", 104 ticks before "I'm afraid,
your journey ends now.", and it is set again on "Necron, forgive me." (52
before) and on Necron's first line (41 before). Those gaps come from the boss
log; the last of them puts the count right if one ever differs. `necron` is
only the part after his last words.

The log also says when a wither appears, and when one jumps more than two
blocks in a tick.

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

```
/cryptic debug room
```

Prints the room you are standing in, the turn Hypixel dropped it into the floor
at, and where in the room's own coordinates you are. Every puzzle solver's
answer is written in those coordinates, so when one draws in the wrong place
this is the first thing to look at: a missing turn means the marker buried at
the corner of the roof has not been found, and a wrong one means every position
in the room is being reflected.

```
/cryptic debug blaze
```

Lists every named entity around you with what the Blaze solver reads out of it,
which for a health tag is the number the puzzle is ordered by. The solver is
one regular expression applied to a nametag, and a nametag is a thing no log
ever records, so when it draws nothing this is the only way to see why.

```
/cryptic debug highlight
```

Lists everything near you that Cryptic is marking, with the reason for each —
starred, prince, one of the special group, or a glow — and whether it is one of
Hypixel's own NPCs. A box on something that is not a mob can be read off here
rather than guessed at.

---

## Inventory, party and the newer HUDs

```
/cryptic debug inventory
```

Prints the two things the game thinks are open — the screen on display and the
menu the player is holding — and the last few menus opened and shut.

They are usually the same thing. When they are not, the inventory looks normal
and takes no clicks at all until it is closed and opened again, because the game
throws away any click made in a menu other than the one the player holds. Cryptic
now notices that state and repairs it, writing a line to `logs/latest.log`
beginning `[Cryptic/inventory]` when it does; this is how to look at it directly,
and the list of recent menus is what says which one was left behind.

```
/cryptic debug party
```

Party commands and their replies are shown to you instead of being sent, and the
reply says who Cryptic thinks leads the party. With it on, a party command can
be tried on your own:

```
/tellraw @s {"text":"Party > [MVP+] Steve: !ping"}
```

```
/cryptic debug partyinvite
```

Raises a party invite from nobody, so the notification and its two keys can be
tried. Turn `/cryptic debug party` on first, or pressing Y sends a real
`/party accept`.

```
/cryptic debug terracotta
```

Lets the Terracotta Timer run anywhere and prints what it is counting. The real
thing needs Sadan's room; with this on, any flower pot placed starts a timer, and
the countdown runs on the client's own ticks where there is no server clock.

```
/cryptic debug lava
```

Prints what lava is being drawn as: the texture, the layer and the tint of the
model a chunk builder is handed for it, after every mod that swaps fluid models
has had its say — SkyHanni has a lava replacement of its own, and Sodium builds
the chunks — along with how many times Cryptic has swapped it since the chunks
were last rebuilt. A model that reads right here with lava that still looks
wrong means the chunks were never rebuilt; a model that reads wrong means
something after Cryptic changed it.

```
/cryptic debug f7
```

Prints which Goldor section the F7/M7 QOL Section Complete title thinks it is
on, and which of its two halves it is still waiting for: every terminal, device
and lever done, and the gate blown.

```
/cryptic debug leap
```

Prints which teammate the Spirit Leap menu puts in each corner and where each
face came from: the player standing in the world, their player entry, or their
row of the tab list. A face from "nowhere" is drawn as no face at all.

```
/cryptic debug waypoints
```

Prints the room Dungeon Waypoints is drawing in: its name, tiles, shape,
rotation and the corner its coordinates are measured from, and how many secret
and custom waypoints it has. A room with no rotation has nothing drawn in it.

```
/cryptic debug requeue
```

Prints what Auto Requeue knows: the team size it read, the floor it would queue,
who asked for downtime, who left the party, and whether it is counting down or
listening for `r`. Pair it with `/cryptic debug party`, which makes it print the
queue command and its party messages instead of sending them.

```
/cryptic debug melody
```

Prints whether the Melody HUD is connected to Odin's relay, the lobby it would
join, and what it has heard from each teammate — the relay's marker, note and
row, and what they last said in party chat.

```
/cryptic debug hudsample
```

Gives the Blessing Display, the Spring Boots Helper, the Puzzle HUD, the Dungeon
Warp Cooldown, the Melody HUD and the F7/M7 QOL titles made-up numbers to show,
so they can be seen and placed without a blessing, a pair of boots or a boss.

---

## Things that need no switch

A few states are easier to fake directly than to add a command for.

**The boss room.** `DungeonRun` listens for the boss's greeting, so pasting one
into your own chat is enough:

```
/tellraw @s {"text":"[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"}
```

That flips the mod into boss mode — the map hides itself, the score stays up on its
own if it is set to, and door highlighting stops.

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
exactly as it would in the boss room. Its own module card, in the Boss tab,
has the same button plus a **Ping** slider — set that to your real ping and the
terminals answer as slowly as Hypixel would, which is the point of practising at
all. The solver's first click protection applies there too, so a click in the
first half second goes nowhere; **Skip click protection** turns that off for
practice without touching what happens in a real dungeon.
