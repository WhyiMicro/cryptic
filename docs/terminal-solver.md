# Terminal Solver

The six Floor 7 terminals, with the answer painted onto them.

Both this and **Terminal Times** live in the **Boss** tab.

---

## How it is drawn

The solver throws the chest away entirely and draws the puzzle on its own — a
grid of rounded squares in the middle of the screen, at its own size, over the
dimming any in-game screen lays over the world. There is nothing on screen but
the answer.

Odin, which this is ported from, has two other ways of drawing a terminal that
paint over the chest's own slots instead. Cryptic had both and no longer does:
they were never the ones in use here.

### Size

**Term size** and **Slot gap** set how big the grid is and how far apart its
squares sit. **Slot roundness** and **Container roundness** are separate, so
the box can be rounded while the slots stay square, or the other way about; the
box takes on padding to match its own rounding, so a corner slot is never
clipped by it. Melody gets its own **Melody size** because its grid is wider
than the rest.

---

## Reading it

Each terminal paints its own colour over the slots you need:

| Terminal | What is painted |
| --- | --- |
| Correct all the panes | every red pane |
| Change all to same colour | every pane, labelled with the clicks it needs |
| Click in order | the next three, labelled with their number |
| What starts with | every item that starts with the letter |
| Select all the … items | every item of that colour |
| Click the button on time | the marked column, the note, and the button |

On the order terminal every pane keeps its number so the run can be read ahead,
but only the next three are painted — the rest are numbers on nothing.

Rubix labels are signed. `2` means two clicks forward, `-1` means one click
back, which is quicker than four forward.

### The font

The labels are drawn in Minecraft's own letters, read straight out of the game's
built-in resource pack rather than through whatever is installed over it. A font
pack changes the terminal's numbers otherwise, and at a glance a stylised `6`
and `8` are not what you want to be telling apart. **Resource pack font** opts
back in.

---

## First click protection

Hypixel bans for clicking a terminal faster than a human could have read it, and
a solver makes that trivially easy to do by accident. So no click leaves inside
the protection window — it is dropped, not queued.

**Protection (ms)** is how long, from the terminal opening: 200 to begin with,
as Odin has it since SkyBlock 0.27.2, and as low as 100.

**Account for server lag** adds a second condition: a number of server ticks as
well as a number of milliseconds. The clock alone is not enough when the server
is behind, because it opened the window late and your screen has been up for
less time than the timer thinks. **Protection (ticks)** is that count, at 50ms
each on a server keeping up. The ticks come from Hypixel's own per-tick packet,
and if a server never sends one the tick half stands down rather than blocking
every click forever.

The simulator can turn the whole thing off, and only the simulator.

---

## Client prediction

**Client prediction** shows a click as taken the moment it is sent, rather than
when the server answers it. On a high ping that is the difference between a
terminal that keeps up with you and one that runs half a second behind your
hands.

Nothing about the click itself changes — one click is still one click, sent when
you make it. It is only the picture that runs ahead, and the picture is always
replaced by what the server sends back: when a slot you clicked comes back from
the server, that click and everything sent before it is retired, and the board
is solved again from what actually arrived.

**Resolve timeout** is the one guard around it. A click that is never answered
would otherwise sit on the board forever, hiding a slot that still needs
clicking; after this long the guesses are thrown away and the board is read
again from the chest. 600ms by default, worth raising on a bad connection.

---

## The other switches

**Rubix mode** has three settings. **Fewest clicks** mixes in right clicks for
the panes that are quicker to walk backwards, and asks you to click them that
way. **Left clicks only** sends every pane the long way round, so nothing needs
a right click — more clicks, one button. **One button** is both: the answer
still takes the fewest clicks, and the right ones are sent for you whichever
button you press.

A pane may only be clicked the way the answer wants it, so a slip of the hand
cannot lengthen the terminal — except in One button, where either button is
accepted because the answer is choosing anyway.

**Stop melody solver** leaves melody alone and plays it as the chest.

**Hide numbers** takes the labels off the Numbers terminal and leaves its three
colours to say the order: the next pane, the one after, and the one after that.
Some people read the colour faster than the digit.

**Melody HUD** is about other people's melody rather than yours. Every mod that
plays melody says how far it has got in party chat — "Melody 67%", "I ❤ Melody
2/3" — and this holds the latest from each teammate on screen for a few seconds
as `ARCHER has melody! 1/3`, the class in its colour, so the answer to "do we
wait for it?" does not scroll away. It is a HUD element and can be placed like
any other.

**Live through Odin's relay** (off to begin with) adds the terminal itself above
that line: the marker's square on top, the five note squares under it with the
note in green as it moves, and the row they are on beside both. That is Odin's
Progress GUI, and it only works because every Odin user in the lobby joins the
same room of Odin's server (`ws.odtheking.com`) during the Goldor phase and
reports their board to it. Switched on, Cryptic joins that room too, shows what
teammates report, and reports yours back — which means sending your name and
the lobby's server id to Odin's server. When the relay cannot be reached,
only the chat line is shown. `/cryptic debug melody` says whether it is
connected.

**Send melody progress** tells the party when you open melody and as each row
is done — `Melody 0/3`, `1/3`, `2/3`. **Progress format** switches that to
Odin's percentages, `Melody 33%` and `67%`. Melody has had three rows since
SkyBlock 0.27.2; a teammate whose mod still says `2/4` or `50%` is read as rows
of three. **Send coords** says where you are
standing when you open melody, the way Odin's Melody Send Coords does, so the
party knows which melody is taken. **Show player** picks how a teammate is
named on the HUD: by class, by name, or both.

Every colour the solver paints with is a setting, including melody's column,
pointer and resting slot.

---

## Clicking

Every click in a terminal goes through the solver whichever way it is being
drawn, because the solver is what holds the first click protection. A left click
is sent as a middle click, which is what Hypixel's terminals expect; rubix is the
one that reads the button, so its right clicks stay right clicks.

The drop key and the hotbar number keys click the slot under the cursor, so a
terminal can be played without moving the mouse off it.

---

## The simulator

`/cryptic termsim` opens a terminal that behaves like Hypixel's, run entirely on
the client, with a ping you choose. It feeds the same tracker the real terminals
feed, so the solver draws over it and clicks it exactly as it would in a run —
which makes it the only way to exercise any of this without a Floor 7 run.

Its own module card, in the **Boss** tab, can turn off first click protection
for practice. That switch has no effect on a real terminal.

---

## Terminal Times

A module of its own, beside the solver.

**Terminal times** says how long each terminal took the moment it is solved, and
whether it beat your best. Bests are kept per terminal in
`config/cryptic/terminal-records.json`, and the simulator's are kept apart from
the real ones — a terminal played with no server in the way is not the same
terminal. **Reset PBs** clears them.

**Terminal splits** rewrites every terminal, lever and device message to carry
two clocks: how long the section has been running, and how long the phase has.
When the core opens it totals the sections. The message is rewritten rather than
followed by one of its own, because seven terminals, three devices and four
levers is a lot of chat for something that belongs on the line already there.

**Use real time** picks which clock. Real time is what a stopwatch would have
said; off, it runs on server ticks, which is what the run actually cost when the
server is behind.

---

## Where it came from

All of it is Odin's (BSD 3-Clause, Copyright (c) 2025 odtheking): the solving
rules for all six terminals, the custom terminal GUI, the click prediction and
its resolve timeout, the first click protection, the simulator, and Terminal
Times. The rounded corners are drawn out of vanilla's own filled rectangles
rather than through Odin's shader and pipeline — smoothed the same way a shader
would, by laying the curve out in screen pixels and fading the pixel at each end
of every row by how much of it the curve covers — and the labels come from
Cryptic's copy of Minecraft's glyph sheet rather than the font stack.
