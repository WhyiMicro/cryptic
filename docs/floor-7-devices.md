# Floor 7 devices

Three modules, all on the **Floor 7** page: **Device Solver** for the four
things in Goldor's tower that are not terminals, **Terminal ESP** for the
terminals themselves, and **Terminal Order** for which of them are yours.

All three work out where you are from your own position rather than from a boss
bar or a chat line, so they come up correctly for somebody who reconnects in the
middle of the fight. Floor 7's boss room is one shaft with the five phases
stacked in it: height alone says which phase is being fought, and inside the
Goldor phase four boxes say which section of it you are in. That last part
matters because every section holds its devices and terminals under numbers
that repeat.

The names, since the docs and the code both have to pick some: the Goldor phase
is **p3**, the third of the boss's four. It is a square of four **sections** —
**s1** to **s4**, walked in that order — around a middle called the **core**,
which is where the mage goes once s2 is done. Each section holds four or five
terminals, one device and one or two levers.

---

## Device Solver

Four devices, each with a switch of its own, so the ones your party does not
run can be left off.

### Arrow Align

Twenty-five item frames, each holding an arrow at one of eight rotations, that
have to be turned until they spell out a shape. A right click turns one frame
one step clockwise, and nothing tells you which of the nine shapes is being
asked for — that is worked out from the frames that are *empty*, since every
shape leaves a different set of squares blank.

Each frame that still needs turning is written with the number of clicks it
wants. **Colour style** decides how: *Dynamic* colours each count by how much
work is left — green under three, amber under five, red beyond — so the frames
worth starting on can be picked out without reading a number. *Custom* writes
them all in one colour of your choosing.

A click you make is applied on screen at once and held for a second, so the
count answers your hand rather than your ping.

**Block wrong clicks** swallows a click on a frame that is already pointing the
right way. **Invert sneak** turns the escape hatch round: normally sneaking
disables the blocking, with this on it is the only thing that enables it.

### Lights On

Six levers in Goldor's tower that all have to be flipped before the section will
open. There is nothing to solve — a lever is either on or it is not — and the
only hard part is that six levers on a dark wall are easy to miss one of. So the
ones still off are the ones marked, and each one stops being marked the moment
it is flicked. **Lever fill** and **lever outline** are coloured separately;
either can be turned off by dragging its alpha to zero.

Unlike the other two, this one is not gated on the Goldor phase — only on being
in the boss room at all, which is what Skyblocker does. The levers are two
phases below the fight when a party goes down to flip them early, so asking
which phase is being fought would hide the answer from the person who went to
get it.

A lever that is not loaded at all is skipped rather than drawn as off, so
nothing is highlighted before you are close enough for it to be true.

### Simon Says

A sequence of lights is played back and the buttons in front of them have to be
pressed in the same order. The sequence is never sent to the client as a
sequence — it is sixteen sea lanterns behind the buttons, lit and unlit one at a
time — so the order is recorded as it is shown. A lantern going *out* is what
marks its place; a lantern lighting up can be the display being redrawn.

The buttons still to press are boxed in order: **first button**, **second
button**, and **later buttons** for everything after that, drawn in the
**style** you pick.

**Block wrong clicks** swallows a press on any button but the next one in the
sequence.

**Block wrong on start** is a different guard for a different mistake. Pressing
the start button while the device is still showing its sequence does not fail
it — it starts it over, costing the party the whole sequence again — and a
handful of presses is normal, because everybody taps it to open the round. So
this counts them instead of blocking outright, and swallows everything past
**max start clicks**. The count resets when Goldor greets the party and when the
device finishes showing its sequence.

Since SkyBlock 0.27.2 the sequence is four rounds long, not five.

**Block clicks on lag** (on by default) holds back a button press while the
server has gone quiet for longer than **Lag threshold** (300ms). Hypixel sends a
ping every tick, one every 50ms, so a gap that long is the server stalling, and
presses sent during it reach the device together, which fails it. The action
bar says when a press is held back. It goes by the ping rather than by the
button answering, because the game shows a button pressed before the server
has heard of it.

**Send progress** says "SS 3/4" in party chat as you finish each round of the
sequence. **Send "SS broke!"** says "SS broke!" when the device throws an
attempt away, whoever was doing it: NoammAddons' check (CC0), which is every
button going while no light is lit, at least twelve ticks after the last one.
It stops once the device is done. Both are off by default, because enabling a
module for its boxes should not quietly start typing in your party chat.

Sneaking overrides every one of these blocks, on both devices. A solver that has
lost track must never be able to stop a device being played by hand.

### Sharp Shooter (i4)

Nine blue terracotta blocks on the s4 wall. One turns to emerald; you shoot it;
it goes back to terracotta and counts as done, and another lights up. "i4" is
*early device 4* — the strat where the berserk runs here the moment p3 opens and
shoots the whole thing before anyone has reached s1.

The lit block is marked in **Target**, the ones already shot in **Shot
already**, and — with **Show prediction** on — the guess at which lights up next
in **Prediction**, so the shot can be lined up before it exists. The guess is a
guess: it prefers a block sitting two along from another in the same row, picks
at random among those, and passes over anything it has already guessed wrongly
twice. NoammAddons' heuristic, kept as it stands, because a wrong guess costs
nothing but a re-aim.

**Terminator mode** marks the block *between* two lights instead, as Odin's
Arrows Device does: a Terminator fires three arrows in a flat spread, so aiming
between two lights in a row hits both. Two aims are shown. The first,
in **Target**, is the pair holding the lit block; the second, in
**Prediction** while Show prediction is on, is the pair that adds the most
blocks not yet shot. The lit block itself is left unmarked, so there is one
thing to aim at.

The tally starts over whenever the device does. Stepping off the plate throws
the attempt away on Hypixel's side, so Cryptic forgets what was shot when the
plate is pressed again, or when you step back onto the platform. Before this,
the last person's progress was still marked, and the block that went dark as
they stepped off counted as shot.

Marks only draw while you are stood on the platform the device is shot from.
From anywhere else in s4 the nine blocks are decoration on a far wall, and
marking them there is clutter over a section that has four terminals in it.

**Announce finish** shows a title when the device reports itself done, with how
many of the nine you were counted as having shot — the client's own tally, which
can be a block out either way, so it is shown as what it is rather than as
gospel.

Two things here are fixes rather than a port. NoammAddons moves the target
*onto* the block just shot, so the green mark jumps to a finished block and sits
there until the server lights the next one — which is what makes it look slow to
update. Here the target is cleared instead and the next block to light claims
it. And the wall is re-read from the world once a tick as well as being watched
for changes, so a block already lit when you arrived, or a change that landed
while the section was not being watched, is still found. The change hook is what
makes it quick; the tick scan is what makes it right.

---

## Terminal ESP

A terminal is a chest that has to be right-clicked, and the part of it that
answers a right click is an invisible armour stand rather than the block anybody
can see — so a terminal missed at range is usually a terminal that was aimed at
and not hit. What is boxed here is that armour stand's own hitbox, which is the
thing the click has to land on.

Hypixel names the stand "Inactive Terminal" until the terminal is finished, so
only the ones still to do are drawn, and only the ones in the section of the
tower you are standing in.

**Mode** picks outline, fill, or both, and the **fill** and **outline** colours
are set separately. **Through walls** is off by default: on, every terminal in
your section shows through the tower, including the ones behind you.

---

## Terminal Order

A Goldor section is four or five terminals scattered across a corner of the
tower, and the party's plan is a sentence — "tank takes 1 and 3" — that has to
be turned back into places while you are running. This draws your own half of
it, on the things themselves:

```
[ 3 ]
```

What is written is the thing's **name**, not its place in an order. Nobody says
"do your second one", they say "I've got 3 and 4", so a terminal called 3 reads
`[ 3 ]` wherever it comes in your route. Devices and levers say what they are as
well as which — `[ Device 2 ]`, `[ Lever 1 ]` — which matters because in two of
the four sections the healer's whole assignment is a device and a pair of
levers.

Terminals and levers are numbered **within their section**; devices are numbered
**across the whole phase**, because there is one to a section and the party
calls it by the number of the section it is in: 1 is Simon Says, 2 is Lights On,
3 is Arrow Align, 4 is Sharp Shooter.

Only the section you are standing in is drawn, because every section numbers its
terminals 1 upwards and they would otherwise be ambiguous.

### Which plan

**Preset** picks the plan. *M7 Guides* is the community assignment the term
order comes from; *All terminals* gives everybody every terminal, for a party
running without one. Adding another is a table in `Floor7Tasks.kt` and one enum
entry.

**Auto assign** is on by default and takes your class from the tab list, so the
right half of the plan comes up without being told and cannot be left set to the
wrong thing after a run where you swapped. When it cannot tell — outside a
dungeon, or before the tab list has been read — it draws nothing rather than
guessing, because a wrong assignment sends somebody to a terminal that is not
theirs, which is worse in a run than no assignment at all. Turn it off and a
**Class** dropdown appears to pick from. Both are hidden under a plan that does
not split by class, since neither would change anything.

**Highlight all** ignores the plan and your class and marks everything in the
section, each with its own number. That is what to read off when writing a plan:
it is the only view that names every terminal, device and lever at once.

### Falling away

**Hide when done** is on by default and drops a label the moment its terminal,
device or lever is finished, by you or by anyone else. Hypixel marks all three
the same way — an invisible armour stand named for the state it is in. Unfinished
it reads "Inactive Terminal", "Inactive" or "Not Activated"; finished it is
renamed, to "Terminal Active" or "Active".

Both halves of that are read, and the second half is what makes a teammate's
work show up. Absence alone cannot tell *done* from *not sent yet* — a chunk can
be loaded while its entities have not been, because the far end of a section is
further than the server tracks entities for — so arriving in a section somebody
else had already been through left everything drawn as still to do.

Four cases, in order: the unfinished name settles it; the finished name settles
it; nothing standing there at all leaves the last answer alone; and a stand that
is there but says neither counts as done only where the unfinished one was seen
first. That last case is the fallback for a lever, whose finished name is the
one not confirmed here.

Chat is no use for any of this. Hypixel announces each one — "activated a
terminal! (5/7)" — but never says *which*, and the count is per section without
naming the section, so two of them can read (5/7) at the same moment for
different corners of the tower.

**Fade when close** thins a label out as you walk up to it, so what is left on
screen is the part of your assignment you still have to find. **Fade from**
sets where it starts, and it is gone by three blocks — at the default of 11 that
is about half at seven blocks and a quarter at five.

### Drawing

**Number** and **Brackets** are coloured separately, and **Scale**, **Height**
and **Through walls** place the label. Through walls is on by default: your
assignment is spread across the whole section, so being able to see it is most
of the point.

---

## Where the sources are

The arrow device and the terminal ESP are NoammAddons' (CC0) and Simon Says is
Odin's (BSD 3-Clause). Two of them are facts rather than code, taken from
LGPL-3.0 mods whose source is therefore not here: the Lights On lever positions
are Skyblocker's, and Terminal Order's numbering and assignment table come from
Stella's published terminal data, credited there to the M7 Guides community.
Those positions cross-check against NoammAddons' own terminal list — the same
terminals, offset a block, because that list points at the armour stand and this
one at the block you click. Each file names its own source.
