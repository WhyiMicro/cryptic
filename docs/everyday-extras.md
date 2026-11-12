# Twitch Chat, Hotbar, Custom Scoreboard, Storage Overlay, Media Controller and prompts

Every module here starts off. Elements on the HUD are placed with `/cryptic hud`.

## Twitch Chat (Render tab)

Shows a Twitch channel's chat on the HUD. Type the channel's name (as in
`twitch.tv/<name>`) into **Channel**; it joins a moment after you stop typing.

- It reads as an anonymous guest over Twitch's chat server, so it needs no
  account and can't send anything.
- The connection runs on its own thread and comes back by itself when it drops,
  waiting a little longer after each failed try (up to a minute).
- **Show on HUD** draws the chat as a HUD element; **Lines**, **Width** and
  **Fade after** (zero keeps every line) are behind its "⋯".
- **Show in chat** puts each line in the game's chat, marked `[Twitch]`. With
  the HUD off and this on, it works in chat only, and "Joined #channel" and
  connection errors come to chat too.
- **Name colours** uses each chatter's Twitch colour, lightened where it would
  be too dark.
- Emotes show as their text.

## Hotbar (Render tab)

- **Smooth selection**: the selection box slides to the slot you pick. Its
  speed is behind the "⋯".
- **Raise held item**: the item in your hand rises out of its slot.
- **Slot numbers**: a small 1 to 9 in each slot's corner.
- **Stop at the ends**: the scroll wheel stops at slots 1 and 9 instead of
  wrapping round.

It moves the game's own hotbar sprites, so resource packs still apply.

## Custom Scoreboard (Render tab)

Redraws the sidebar as a HUD element and hides the game's own while it shows.
Move and resize it with `/cryptic hud` or **Move and resize** on the Behaviour
tab (scroll over it in the editor to scale it).

**Lines** (the module's first page) is the lines it shows, top to bottom. Drag a
line by its grip or use its arrows to move it, take it off with its cross, and
add more from **Add a line** underneath (a blank line as often as you like).
**Reset** puts back the starting list. A line with nothing to show right now
(Copper outside the Garden, Heat outside the Crystal Hollows) is left out.

- From Hypixel's sidebar: server and date, SkyBlock date, time, island, area,
  purse, motes, bits, copper, sowdust, kernels, heat, cold, North Stars, the
  objective, the slayer quest, sidebar events and everything else, and the
  website.
- From the tab list (each shows only while Hypixel lists it there, so pick the
  widgets in `/tab`): bank, gems, soulflow, the tab list's event (with when it
  ends), powder (mithril, gemstone and glacite, a line each), party, profile,
  SkyBlock level, pet, cookie buff, god potion, mayor, players in the lobby, and
  **More from the tab list**: any other labels you name on the Text tab. The
  bank is the exception: the last bank the tab list showed is kept for each
  SkyBlock profile, so it shows on every island, and updates whenever the tab
  list shows it again.
- Cryptic's own: the real time, blank lines, and **Your footer**, the text set
  on the Text tab.

**Copying CustomScoreboard**: if meowdding's CustomScoreboard is (or was)
installed, its settings are copied in once on their own: its lines in its order,
title and footer, alignment, line spacing, background, padding, corners, border,
24-hour time, coloured months, number format, purse changes, when it hides, its
scale and which edge of the screen it sits on. **Copy CustomScoreboard's
settings** on the Behaviour tab copies them again.

**Text tab**: **Tidy blank lines** (never two in a row, none at the bottom, and
none at the top unless under the title), More from the tab list, the title
(Hypixel's, your own, or none) and your footer, both with `&` colour codes.

**Format tab**: 24-hour time, **Coloured season** (the date in spring green,
summer yellow, autumn gold or winter aqua), hide the lobby code on the server
line, short numbers (12.5M for purse, bits, bank, gems and other amounts), and
hide the (+5) after your purse. Lines keep their colours, and spaces at their
ends are trimmed so alignment lines up.

**Look tab**: alignment of the title and of the lines, text shadow, padding,
line spacing and a minimum width (behind the padding's "⋯"), background colour,
smooth rounded corners, a border with its colour and width, and a thin top line.

**Behaviour tab**: **Only on SkyBlock** (on by default) leaves every other
sidebar alone; hide it while chat is open, with F3, or while the tab list shows.

## Slayer XP overlay (Slayer Features, General tab)

**Slayer XP overlay** under Progress shows your slayer's level, XP to the next
level, bosses to go and XP an hour. It reads the line Hypixel sends when a quest
is done, so it fills in from the first boss you finish. What one boss is worth
comes from two of those lines in a row, perks included; across a level-up the
last boss's worth stands in. The hourly rate shows from the second boss of a
session, and a 15-minute pause starts a new session.

## Storage Overlay (General tab)

Replaces `/storage` and every Ender Chest page and backpack with one scrolling
grid of all your pages, with your inventory underneath.

- Open `/storage` once so it learns which pages you have. Pages you haven't
  opened yet read "Click to load".
- **Storage Overview**, left of your inventory, is the Storage menu itself at
  full size: its Ender Chest pages, backpacks and Toolkits button, without the
  filler, headings, Go Back and Close. In `/storage` they are the real buttons.
  From a page, a page icon opens that page, and Toolkits opens `/storage` and
  presses Toolkits there for you.
- **Reopen the last page** (on by default): `/storage` goes straight on to the
  page you were last in.
- Skyblocker's buttons that sit around a chest (quick navigation, container
  value) are hidden here, since the chest they line up with is gone, and come
  back as soon as you leave. Other mods' widgets stay.
- With **SkyBlock Item List** installed, the overlay leaves room either side and
  tells the list where its edges are, so the list sits to the right of the
  pages and its favourites to the left.
- The page you're in has a green frame and works like the chest: click, shift-
  click, drag, number keys and Q all work. The other pages show what they held
  when you last saw them; click one to open it. The grid stays up while Hypixel
  swaps menus, so nothing flickers.
- Click the open page's title to rename it. `&` colour codes work, and Enter
  saves. A blank name brings back the default one.
- **Search box** (beside your inventory): matching items light up in every page
  and in your inventory, and pages without any are dimmed, or left out with
  **Hide pages without matches**. While a box has focus it takes every key, so E
  and the number keys type. Esc or Enter lets go; a right-click clears it.
- **Columns** (up to 6, fewer when the window is narrow) and **Max height** are
  behind the "⋯". The scroll position stays put as you move between pages.
- **Remember between sessions** saves pages and names per SkyBlock profile in
  `config/cryptic/storage.json`.

## Media Controller (General tab, Windows only)

Shows what your computer is playing (Spotify, a YouTube tab, or anything else
that shows up in Windows' volume flyout) and lets you control it in game.

- **Show on HUD**: title, artist, the app, the cover and a progress bar. The
  cover, the bar, the width and **Hide while paused** are behind the "⋯".
- **Controls in the pause menu**: a panel on the Esc menu with previous,
  play/pause and next. Click the bar to jump; buttons the player doesn't support
  are greyed out. The panel and its buttons use your resource pack's button
  texture. It hides while nothing is playing. Behind the "⋯": **Place** (under the Disconnect button and as wide as
  it, or a corner) and **Cover** to show or hide the art.
- It follows the session Windows treats as current, the same one the media keys
  control.
- It works through a small PowerShell script that ships with Cryptic and is
  written to `config/cryptic/media/` on start. The script only reads Windows'
  media sessions and passes your clicks on. It stops when you turn the module
  off or close the game.

## Status Bars (Render tab)

SkyBlock's health, mana, Rift vitality, defense, speed and experience as HUD
elements of their own. Place them with `/cryptic hotbar` (or `/cc hotbar`), or
**Edit bars** in the module: that editor shows only these.

- Each stat has a **Show** choice: Cryptic's, the game's own where there is one
  (hearts for health, the armour bar for defense, the experience bar), or
  **Neither**, which hides both. **Hunger** can be hidden too (hidden to begin
  with).
- **Health**, **Mana** and **Vitality** bars each have their own settings
  behind their "⋯": colour, **Numbers** (off leaves just the bar), and **Hide
  until it drops** with its **Show below** threshold (90% to begin with). Health
  past full (absorption) shows in gold, and overflow mana in cyan. Vitality shows
  in the Rift only.
- They read Hypixel's action bar as it arrives, before any other mod changes
  it, and know both Hypixel's old stat symbols and its new icons. Between
  updates the health bar follows your hearts, so it moves the moment you're hit.
- **Defense** and **Speed** are numbers; speed is your movement speed without
  sprinting (100 is base).
- **Experience** is your level as a number, with or without **"Lv"** in front;
  **As a bar** adds a thin bar of the way to the next level.
- **Take them off the action bar** (on) leaves only Hypixel's other action bar
  text.
- If Skyblocker's own status bars are on as well, turn one set off.

## Text Outline (Render tab)

Outlines text by drawing its shadow on every side of each letter instead of
only below it, the look of SkyOcean's Full Text Shadow. It applies to all text
drawn with a shadow: chat, the HUD, menus, buttons and tooltips. Text drawn
without one, such as chest titles, stays as it is.

- **Outline shape**: **Square** (eight copies of each letter) or **Rounded**,
  which leaves out the corners and costs about half as much.
- **Outline colour**: **Text shadow** (each letter's own colour darkened, as
  Minecraft's shadow is) or **Black**.
- **Bold all text** (off to begin with) makes every letter bold. Bold text is
  wider, so chat wraps sooner and some labels scroll or no longer fit.

It skips the copies the letter itself covers, so bold text needs 10 copies
rather than 16, and an underline's outline is a single rectangle. Even so,
Square makes building text about three times the work of plain text, and
Rounded about twice.

## Anti-AFK (General tab, Cheat)

Once you've been idle a while, it does something small now and then so Hypixel
doesn't send you to Limbo. It sends input you didn't make, which Hypixel's
rules don't allow, so it is a Cheat module: Client Restrictions has to allow
Cheat before it shows.

- **Actions**: **Look around** (turns a little and back), **Jump**, **Sneak**,
  **Strafe** (a step to one side and back, crouched so it can't walk off a
  ledge) and **Swing**. One of the ticked ones is picked at random each time.
  Look around and Jump to begin with. Swinging can fire a held item's left-click
  ability.
- **Interval (seconds)**: the gap between actions, drawn from the band each
  time (20 to 45).
- **Start after**: how long you have to be idle first (60 seconds).
- **Look distance**: how far Look around turns (15°).
- **Only when tabbed out** (off): stays off while the game window has focus.
- **Pause in menus** (on): waits while a chest, the chat or any other screen is
  open.
- **Only on Hypixel** (on) and **Notify when it starts** (on).

Any key, click, wheel turn or mouse movement stops it at once and starts the
idle wait again. Jump, sneak and strafe are laid over your movement for those
ticks rather than pressed on your keys, so nothing stays held when it stops,
and toggle sneak isn't flipped.

## X-ray (Nucleus Features, General tab, Cheat)

For setting up a route in a fresh Crystal Hollows lobby. It shows only when
Client Restrictions allows Cheat. Two keys, set behind the X-ray toggle's "⋯",
turn each mode on and off:

- **Coal x-ray key**: coal ore, deepslate coal ore and coal blocks.
- **Gemstone x-ray key**: gemstones, which grow as stained glass, so both full
  glass blocks and panes, every colour.

While a mode is on, every other block (stone, lava, water and the rest) stops
being drawn, so only the targets are left, lit fully and seen from any
distance. Both modes can be on together. Turning them off, or leaving the
Hollows, brings the world back. This works with Sodium and with vanilla's
renderer.

- **Hide other blocks** (on): turn it off to keep the world and only box the
  targets through walls.
- **Highlight**: how the targets are boxed: **Both** (outline and fill, to
  begin with), **Outline**, **Filled** or **None** (just the blocks).
- **Range** (32 blocks), **Coal color** and **Fill** (25%): the boxes drawn
  round each target. Gemstones are boxed in their own colour.

## Combat (General tab)

- **Flare & orb timer**: a HUD element with the flare and the power orb you are
  standing in, each with its head and the time it has left. Only the best of
  each kind in range is shown: flares reach 40 blocks, Radiant, Mana Flux and
  Overflux 18 and Plasmaflux 20. Under its "⋯", **Flares** and **Power orbs**
  turn each row off. Move it with `/cryptic hud`.
- **Warn when it runs out**: one notification per flare or orb, **Warn ahead**
  (10 s) before it ends; at 0 it says when it has ended. With Notifications off
  it is a chat line instead.

- **Pearl trajectory** (on with the module): while you hold an ender pearl, a
  line shows where it would fly and a box where it would land. If a mob is in
  the way, the line stops there and the mob is outlined. Under its "⋯", the
  **Line** and **Mob it hits** colours.

A flare's three minutes are counted from when your game first sees it, so one
that was up before you arrived reads longer than it has.

## Bow trajectory (Bow Tweaks)

- **Bow trajectory** (on with the module): the path your arrows would take.
  A shortbow shows it whenever you hold it; any other bow shows it while you
  draw, growing with the draw. The Terminator shows all three of its arrows: the side ones leave 5.5°
  either side of your aim, a tick after the middle one and half a block below
  it, as Hypixel fires them.
  Like the pearl, a line stops at the first mob whose hitbox the arrow would
  clip, and that mob is outlined.
  Under its "⋯", **Show path** turns the line off and leaves only the landing
  box and the outlined mob, then the **Line** and **Mob it hits** colours.

Both are stepped the way Hypixel's server moves them (Minecraft 1.8): arrows
fall 0.05 and pearls 0.03 a tick, both lose 1% of their speed a tick, and an
arrow hits a mob when it passes within 0.3 blocks of its hitbox. Real players
don't stop the line, since SkyBlock's arrows pass through them; Hypixel's
player-shaped mobs do. `/cryptic debug bow` checks the prediction against real
shots.

## Flipping (General tab)

Bazaar flipping: buy with a buy order, sell with a sell offer, keep the
difference. Off by default (on if you had the Bazaar's Flips HUD on, which now
lives here). Everything is worked out on your machine from Hypixel's public
Bazaar data; nothing about you is sent anywhere, and every click is yours.

- **Flip finder** (on): a panel left of Bazaar menus with the eight products
  that would make the most right now. A flip is a buy order a tenth of a coin
  over the best one, then a sell offer a tenth under the cheapest, after the
  Bazaar's **Sell tax**. Its size is the smallest of what your budget buys,
  what your free inventory slots hold, and a quarter of what trades both ways
  within **Fill within** (from the week's instant buys and sells). Hover a row
  for the numbers; click it to open the product and have the guide take it.
  Enchanted books, essences and shards are left out.
- **Flips at once** (4), **Budget** (80% of your purse, split between the free
  flips), **Fill within** (30 min) and **Largest margin** (35%: wider gaps are
  usually a price about to move) decide what the finder offers.
- **Guide** (on): start it with **Start/stop key**, the panel's Start button,
  or by clicking a flip. It shows one step at a time on the Flips HUD and on
  the panel, and outlines the slot to click: `/bz <product>`, Create Buy Order,
  Custom Amount, Best Order +0.1, confirm; then, as orders fill, Manage
  Orders, the filled order to claim, your bought items (clicking them in a
  Bazaar menu opens their page), Create Sell Offer, Best Offer -0.1, confirm.
  When a flip is done it starts the next, up to **Flips at once**. **Skip** on
  the panel passes over the flip being set up for ten minutes.
  - **Send command key** sends the command on show, so you don't type it.
  - **Amount sign**: what happens when Custom Amount's sign opens. The amount
    is shown for you to type, typed in for you to confirm, or typed in and the
    sign closed (the default, as the Bazaar's amount presets do).
  - **Relist after** (3 min): a flip's order that has stood undercut this long
    is cancelled and placed again, step by step. 0 leaves undercut orders alone.
  - Only flips the guide started are sold or relisted; your other orders are
    only ever claimed. **Forget flips** drops the ones under way.
- **Week summary** (on): the panel opens with what your flips made this week
  in large amber figures, this session's profit and rate under it, a stepped
  chart of the week with its days, the item that made the most, and your goal.
  Under it come the guide's step, the flips on offer (six with the summary
  shown, eight without) and the guide's Start, Stop and Skip buttons. The
  week's sales are kept in `config/cryptic/bazaar-flips-history.json`; older
  ones are dropped. The panel shrinks to fit beside the menu and on screen.
- **Goal**: coins to flip your way to, such as `500m` or `1.5b Hyperion`. It
  shows as a bar with a percentage in the summary and on the Flips HUD, and
  counts what your flips make from when it was set or last restarted
  (**Restart goal**). Empty for none.
- **Flips HUD** (on): the guide's step, or with the guide stopped what your
  orders want next, this session's profit in large figures with its rate per
  hour, and the goal's bar.

The look (near-black panels, amber money, dim labels, the stepped chart) is
after FlipperX's summary screens.

The estimates assume your orders stay on top about a quarter of the time. They
are a ranking, not a promise: prices move, and a flip can end at a loss.

## Other changes in these modules

- **Experiment Solver**: **Play every free charge** (on by default, with Run the
  table) plays every free charge the table has saved up (one a day, up to three)
  before any renew; off, it plays one round. Renews still follow their slider.
  After Superpairs' rewards are claimed it goes back to the table by itself.
- **Auction House**: **Reopen after a failed buy** (on by default): when someone
  else buys an item first, Hypixel closes the menu and refunds the escrow; this
  opens `/ah` and presses the Auctions Browser (the gold block) for you.
- **Bazaar**: the reopen button for a cancelled order first looks for that
  product in your inventory and clicks it, which opens its Buy/Sell page
  directly (a cancelled sell offer's items come back to you). Searching the
  Bazaar for it is the fallback. After you cancel a buy order, its unfilled
  amount is offered as **Last order** in "How many do you want?" for five
  minutes, however you get back to that product.
- **Flipping** (was under Bazaar): **Flips HUD** (on by default) is a panel with what your trades
  made this session and per hour, and what your orders want next (claim the
  filled ones, relist an undercut one, cancel an expired one). Profit counts
  what you sell of things you bought on the Bazaar, against what you paid, from
  Hypixel's claim and instant buy/sell messages; selling your own drops doesn't
  count. What you paid is kept across restarts. **Reset session** sits behind
  its "⋯". The Price history chart and the Experiment Solver's panel now share
  its look: a near-black fill with a dark grey edge.
- **Nucleus Features**: the All tools alert plays a sound (Level Up to begin
  with, or None) set behind its "⋯".
- **Mining**: ping offset (and the tick counter) only time blocks while you hold
  a mining tool: a pickaxe, or one of SkyBlock's drills, pickaxes and gauntlets.

## Better Chat: answer prompts with a key

**Answer prompts with a key** (Better Chat, off by default): when Hypixel asks
something in chat with a clickable **[YES]** (or "Click here to accept"), or an
NPC asks you to "Select an option", a notification shows the question and the
answer; press the **Accept key** (Y to begin with) to give it. For NPCs that is
their first option, the one that goes along with them ("[Sure thing,
partner!]"). The key is only taken while a prompt waits, in the world or in a
chest, never while you type. Party and friend invites are left to Party
Features' own notifications. With notifications off, the question comes as a
chat line with a clickable answer instead.

## Better Chat: hide profile ID

**Hide profile ID** (Better Chat, off by default): the "Profile ID: ..." line
Hypixel sends when you join a server shows as "Profile ID: HIDDEN", for streams
and screenshots. Any player's profile ID is hidden, and a click or hover on that
line that would give the ID away is dropped. Only the chat window changes.
