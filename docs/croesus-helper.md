# Croesus Helper

In the Dungeon tab. It works out what each dungeon chest is worth, and which
runs at Croesus still have a chest worth opening.

## Prices

Prices are Odin's seven-day averages of the auction house and the bazaar,
fetched from `lb.odtheking.com` the first time a chest screen opens and again
every half hour. Nothing about you is sent. Until the prices have arrived, the
overlays are not drawn and nothing is coloured.

A chest's profit is what its items sell for, minus its coins, minus a Dungeon
Chest Key when it needs one. Essence is counted unless **Count essence** is off.

## Colours

At Croesus, each run in the list is coloured:

- **Not opened** (green): no chest opened yet.
- **Worth a key** (orange): one chest opened, and another of its chests would
  still make at least **Key profit** after its coins and a key. The slider runs
  from 100k to 500k.
- **Done** (red): both chests opened, or one opened and nothing left is worth a
  key.

A run with one chest opened can only be judged once its chests have been seen.
Cryptic remembers a run's chests when you open it, by its page and slot in the
list. A run you have not looked at since opening a chest shows orange, as worth
checking.

Inside a run, the chest to open first is green, and a second chest worth a key
is orange. Opened chests are red. A chest you click keeps its colour while it is
on its way open, rather than the colour jumping to the next one.

A chest's cost is remembered from when it showed it, so an opened chest, which
no longer says what it cost, still counts its price. That memory lasts the
session: a chest opened before the game was restarted shows without its cost.

## Profit

**Profit in the chest** writes the profit beside a chest's title when you open
it, at Croesus or in the boss room after a run.

**Profit overlay** is SkyHanni's two lists, drawn the way SkyHanni draws them,
in one place. Only one is ever on screen, so they share a single HUD element,
**Croesus Profit**:

- Over a run's screen: every chest not yet opened with its profit. Hover a
  chest for what is in it, its cost and its profit before the cost.
- Over a chest, at Croesus or in the boss room after a run: its total revenue
  and each item, its total cost and each part of it, and the profit. In a
  dungeon it ends with **All Chest Profits**, every chest you have opened there.

It is only drawn over a chest screen. Place it with `/cryptic hud`. If where
you put it would be under the chest window, it is drawn beside the window
instead, smaller if it has to be.
