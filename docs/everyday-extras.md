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
  **More from the tab list**: any other labels you name on the Text tab.
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
  are greyed out. Behind the "⋯": **Place** (bottom centre, just above the
  hotbar and hearts, with everything centred; or a corner) and **Cover** to show or hide the art.
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
  Bazaar for it is the fallback.
- **Nucleus Features**: the All tools alert plays a sound (Level Up to begin
  with, or None) set behind its "⋯".
- **Mining**: ping offset (and the tick counter) only time blocks while you hold
  a mining tool: a pickaxe, or one of SkyBlock's drills, pickaxes and gauntlets.

## Better Chat: answer prompts with a key

**Answer prompts with a key** (Better Chat, off by default): when Hypixel asks
something in chat with a clickable **[YES]** (or "Click here to accept"), a
notification shows the question; press the **Accept key** (Y to begin with) to
answer yes. The key is only taken while a prompt waits, in the world or in a
chest, never while you type. Party and friend invites are left to Party
Features' own notifications. With notifications off, the question comes as a
chat line with a clickable answer instead.
