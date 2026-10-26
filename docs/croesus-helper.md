# Croesus Helper

In the Dungeon tab. It works out what each dungeon and Kuudra chest is worth,
and which runs at Croesus still have a chest worth opening.

Vesuvius, Croesus's Kuudra counterpart, gets the same treatment: Vesuvius's runs are
coloured the same way and the chests get the same overlays.

## Prices

Prices are Odin's seven-day averages of the auction house and the bazaar,
fetched from `lb.odtheking.com` the first time a chest screen opens and again
every half hour. Nothing about you is sent. Until the prices have arrived, the
overlays are not drawn and nothing is coloured.

A chest's profit is what its items sell for, minus its coins, minus a Dungeon
Chest Key when it needs one. Essence is counted unless **Count essence** is off.

## Kuudra

Croesus also keeps Kuudra runs, listed as **Kuudra's Hollow**. Only one of a
Kuudra run's two chests can be opened, and never with a Dungeon Chest Key, so a
Kuudra run is only ever **Not opened** (green) or **Done** (red), never orange.
Its tooltip doesn't say "No chests opened yet!" the way a dungeon run's does;
it's green until a chest is opened.

On a run's screen (**Kuudra - …**), the better of the **Free Chest** and the
**Paid Chest** is green until one is opened. Both chests, and the chest screens
themselves, get the same overlays as a dungeon's.

The Paid Chest costs a Kuudra Key. Its price is worked out the way Odin does it:
the key's coins (before any faction discount), plus Enchanted Red Sand or
Enchanted Mycelium, whichever is cheaper (2, 4, 16, 40 or 80 of them by tier),
plus two Corrupted Nether Stars. A Kuudra chest never needs a Dungeon Chest Key.

Items are priced by name, so Kuudra Teeth, Heavy Pearls and other stacks written
as "x4" count every one. The Vitality books are priced under their real ids
(Strong Vitality is Strong Mana). The Kuudra pet's essence bonus is not counted.

When Kuudra dies ("KUUDRA DOWN!"), the run is put at the front of Cryptic's
copy of the Croesus list, as a finished dungeon is. Croesus's list doesn't show a
Kuudra run's tier, so all of them are filed as one kind.

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

**Profit overlays** draws a list to the left of the chest window, its middle
level with the window's middle. A run's window and a chest's are different
heights, and each list is centred on its own window. Values are lined up on the
right: gains in green, losses in red.

- Over a run's screen: every chest not yet opened, best first. The chest worth
  opening is starred, and the price of a Dungeon Chest Key is shown underneath.
  Hover a chest to see what is in it, its cost and its profit.
- Over a chest, at Croesus or in the boss room after a run: each item and its
  value, each cost, and the profit. In a dungeon, a **This run** list follows
  with every chest you have opened there, the open one marked.

The list belongs to the chest window, so it isn't a HUD element. When the gap
beside the window is too narrow for it, it is drawn smaller.
