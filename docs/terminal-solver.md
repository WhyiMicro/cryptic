# Terminal Solver

The six Floor 7 terminals, drawn as a grid of coloured squares in the middle of
the screen instead of as a chest full of items you have to read.

The chest Hypixel opens is not dimmed or annotated — it is replaced. Nothing of
it is on screen: not the slots, not the item names, not the inventory panel
behind them. What is left is the answer, at whatever size you set, and the same
darkening any in-game screen lays over the world.

Both modules live in the **Floor 7** tab.

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

**Show numbers** writes the labels on the order and rubix terminals. On the
order terminal every pane keeps its number so the run can be read ahead, but
only the next three are painted — the rest are numbers on nothing.

Rubix labels are signed. `2` means two clicks forward, `-1` means one click
back, which is quicker than four forward.

### The font

The labels are drawn in Minecraft's own letters, read straight out of the game's
built-in resource pack rather than through whatever is installed over it. A font
pack changes the terminal's numbers otherwise, and at a glance a stylised `6`
and `8` are not what you want to be telling apart.

**Resource pack font** turns that off and draws them in your pack's font
instead.

---

## First click protection

Hypixel bans for clicking a terminal faster than a person could have read it,
and a solver makes that trivially easy to do by accident — the answer is on
screen before the window has finished arriving. Every click made inside the
protection window is therefore held back or dropped, in every solving mode.

**Protection (ms)** is how long that lasts. 500 minus your ping is the usual
advice.

**Account for server lag** adds a second condition, because a clock alone
answers the wrong question. If the server is behind, it opened the window late,
so your screen has been showing it for less time than the timer thinks. Turning
this on also waits a number of *server* ticks, counted from the per-tick ping
Hypixel sends the client. **Protection (ticks)** is how many; each is 50ms on a
server that is keeping up.

If a server never sends those pings, the tick half stops applying rather than
blocking every click forever.

---

## Solving

**Solving** picks how your clicks reach the server.

### Manual terms

Each click is sent as you make it. Nothing is buffered, and a click made during
the protection window is dropped. This is the plain solver.

### Que terms

For playing on a connection where waiting a round trip per click is most of the
terminal.

Your clicks go into a queue and the board is moved on locally as each one is
added, so the terminal answers your fingers rather than your ping — nothing is
dropped, nothing feels laggy, and you never wait to decide your next move. None
of the waiting is skipped, only moved to the end: the queue is still draining
after your last click, and the count under the box tells you what is still owed.

Two rules govern what leaves:

- **One click on the wire at a time.** The server has to answer before the next
  one goes. A click carries the id of the window it was made in, so sending a
  second against a window the server has already replaced is both the part that
  could not be mistaken for a person and the part that may simply be discarded.
- **Never faster than a hand.** A gap drawn from **Click delay (ms)** has to have
  passed as well, rolled fresh every time so it never repeats.

If a click is never answered, it is given up on after 800ms and whatever was
queued behind it is thrown away rather than replayed — by then nothing knows
what the board looks like, and the next real window will say.

### Auto terms

The mod picks the clicks and makes them, at the same spacing. It takes the
nearest remaining slot to the last one it clicked, so it travels the grid the
way a hand would rather than in reading order.

This is a macro. The protections above still apply to it, and none of them
change what it is.

---

## Click delay and Reaction delay

**Click delay (ms)** is the gap between clicks in Que and Auto, drawn fresh from
the range each time. **Reaction delay** decides what that gap is measured from,
and the difference is larger than it sounds:

| | Spacing per click | What the gap does |
| --- | --- | --- |
| **On** | round trip **+** gap | applies to every click |
| **Off** | **max**(round trip, gap) | little, on a slow connection |

On, the gap starts when the window arrives, so each click reads as a reaction to
seeing the new board — which is what playing by hand produces. Off, it starts at
the last send and overlaps the round trip instead of adding to it, so on a slow
connection it has usually elapsed before the answer even arrives and the round
trip alone ends up spacing the clicks.

Only a window that answered one of your clicks starts a reaction. The window a
terminal opens with does not, because you already reacted to that by opening it.

The queue is checked once per client tick, so the effective gap is rounded up to
the next 50ms. At the default range that sits inside the noise of a real
reaction time.

---

## Two smaller switches

**Rubix left click** lets the panes that need a right click be left clicked
anyway; the right click is substituted on the way out. The whole terminal can
then be played with one finger.

**Melody click protection** only lets melody's button be pressed while the note
is on the mark. On, melody cannot be failed; off, it can, which is how the game
plays it.

---

## The simulator

**Terminal Simulator**, also `/cryptic termsim`, opens the same six terminals
client-side. They feed the solver exactly as the real ones do, so it draws over
them and clicks them the way it would in the boss room — which makes this a real
test of the solver rather than a mock-up, and the only way to exercise any of it
without a Floor 7 run.

**Ping (ms)** delays each answer by a round trip. Set it to your real ping: at
zero the fake server answers inside the same call, which makes the pacing above
behave nothing like it will in a dungeon.

**Skip click protection** ignores the first click protection while practising.
It still applies in a real dungeon.

---

## Where it came from

The solving rules for all six terminals, the custom GUI and the first click
protection are ported from [Odin](https://github.com/odtheking/Odin)
(BSD 3-Clause), as is the simulator. Que and Auto are from
[NoammAddons](https://github.com/Noamm9/NoammAddons) (CC0), where they ship only
in builds flagged as cheats — worth knowing before you turn either on.
