# Terminal Solver

The six Floor 7 terminals, with the answer painted onto them.

Both this and **Terminal Times** live in the **Boss** tab.

---

## Render types

The solver draws in one of three ways, and the choice changes what the terminal
looks like rather than how it is solved.

**Odin** paints on the chest itself. Every item in the terminal is hidden, the
slots the answer names are filled with colour, and the whole window is scaled up
for as long as the terminal is open. This is the default and the fastest to
read: there is nothing on screen but the answer.

**Normal** also paints on the chest, but leaves the items where they are and
only colours the answer, for anyone who would rather still see what they are
clicking.

**Custom GUI** throws the chest away entirely and draws the puzzle on its own —
a grid of rounded squares in the middle of the screen, at its own size, over the
dimming any in-game screen lays over the world.

### Size

The two chest-based types get bigger by asking the game to change its GUI scale
while a terminal is open. **Term scale** is that number, 1 to 5, and **Auto**
leaves your own scale alone. Nothing is written to your settings — the moment
the terminal closes the game's own scale is back.

The custom GUI scales itself instead, with **Term size** and **Slot gap**.
**Slot roundness** and **Container roundness** are separate, so the box can be
rounded while the slots stay square, or the other way about; the box takes on
padding to match its own rounding, so a corner slot is never clipped by it.
Melody gets its own **Melody size** because its grid is wider than the rest.

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

**Protection (ms)** is how long, from the terminal opening. 500 is the number to
work from, minus your ping.

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
rules for all six terminals, the three render types, the click prediction and
its resolve timeout, the first click protection, the simulator, and Terminal
Times. The rounded corners are drawn out of vanilla's own filled rectangles
rather than through Odin's shader and pipeline — smoothed the same way a shader
would, by laying the curve out in screen pixels and fading the pixel at each end
of every row by how much of it the curve covers — and the labels come from
Cryptic's copy of Minecraft's glyph sheet rather than the font stack.
