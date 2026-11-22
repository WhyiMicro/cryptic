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
- Cryptic's own: the real time, **Rift time** (the time left in the Rift, from
  the action bar; grey while it stands still), blank lines, and **Your
  footer**, the text set on the Text tab.

The **slayer quest** stays up for the whole quest, not only while Hypixel's
sidebar shows it: from the tab list's Slayer widget when that's on, else as last
seen, and it goes when the quest is completed, failed or cancelled. In the Rift,
the island line reads Rift Dimension with the area under it, and the bank,
gems, soulflow, pet, mayor and god potion lines are left out.

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

## Slayer boss stats and Boss HUD (Slayer Features, General tab)

**Stats on boss** puts a slayer boss's stage, what it's doing, its health and
its fight's time on the boss itself. **Whose bosses** picks which get it: your
own, carried players' (both on at first) and everyone else's. **Boss HUD** shows the same for your
own boss (or, with no boss of your own up, the nearest carried player's) as a
small HUD element, with a health bar and the time left before it despawns;
place it with `/cryptic hud`.

- Voidgloom: stage 1/3 to 3/3 (1/6 to 6/6 for tier IV) by health, the hits
  shield, and the laser's time left.
- Inferno Demonlord: stage 1/2 to 2/2 (1/3 to 3/3 for tiers III and IV) by
  health, the attunement and its hits, IMMUNE while its demons are up, and the
  nearest fire pillar.
- Tarantula: 1/2 for the Broodfather V and 2/2 for the Conjoined Brood, timed
  as one fight; "Kill the hatchlings" while yours can't be hurt.
- Sven: "Pups!" while it calls its pups and can't be hurt.
- Revenant: the Atoned Horror's "Boom!".
- Riftstalker: the health left until it can be staked (20%), "Steak!" once it
  can, Twinclaws, and Mania's time left.

**Health colour** picks whether a staged boss's health goes green to red over
each stage or over the whole fight.

**Highlight your boss** marks your own boss in the colour beside it. Its "⋯"
picks the **Style**: Glow (the game's own outline, always through walls),
Outline, Fill or Fill + Outline, a box around the boss with its own fill colour,
line width and **Through walls**. On the Riftstalker tab, **Stakeable colour**
switches the highlight to another colour once your Bloodfiend is under 20%
health and can be staked.

Also on the Riftstalker tab:

- **Hide boss particles** hides the dripping lava, purple spell and portal
  particles around Bloodfiends, the hearts flying between you and yours, and the
  dark smoke puffs anywhere within 16 blocks of yours.
- **Impel display** (on by default) replaces Hypixel's Impel title with a
  bigger one: the order flashing, `↑ CLICK UP ↑` or `↓ CLICK DOWN ↓` for the
  clicks, and the seconds left under it. It's a HUD element, so move or scale it
  with `/cryptic hud`. Orders the Mania helper answers and hides aren't shown.
- **Line to Ichor** draws a line (red, or the colour beside it) from your tier
  V Bloodfiend to the Blood Ichor it spawned. Only the Ichor that appears as
  your boss's `ICHOR` countdown runs out counts, so another boss's never does.
- **Twinclaws path** draws a line from your Bloodfiend towards you, at your
  chest height (so it stays level with you while the boss floats in Mania),
  while it counts down `TWINCLAWS`; when the count ends, the line stays where
  it was for a moment, showing where the claws go.
- **Stonewrath lock-on** puts a box at your feet where your Bloodfiend locked
  on for Stonewrath. The `STONEWRATH` count runs down to the boss rising; it
  then hangs in the air until three quick, rising note-block plings play, and
  that's where you stood then. The box goes once the boss is back down to the
  height it rose from (or after eight seconds, whatever happens).

## Slayer automation (Slayer Features, Cheat)

Each of these finds its item in your hotbar, uses it and selects the slot you
were on again the tick after. Nothing is used while a menu is open.

- **Auto stake** (Riftstalker tab): a hit on your Bloodfiend, or on a thrall,
  splatter or fledgling, at 20% health or less goes out with the Steak Stake,
  and **Swap back after** (300 ms at first) you're back on what you held. Hits
  in between are the stake's too. A mob still alive and stakeable after that
  gets the stake again on your next hit, to finish it. The first hit after a
  stake kill also takes the stake out, for the Sharp Steak Stake's Bloodcrave.
  **Double hit without Bloodcrave** (on by default): when no Bloodcrave is
  stored, the stake stays out for a second hit once the mob can be hit again
  (about half a second), so the kill is the stake's and stores one for the next
  kill. It goes back after 1.2 seconds even without that second hit.
- **Auto Holy Ice**: uses Holy Ice when the Twinclaws countdown over your boss
  (`TWINCLAWS 0.6s`) reaches **Use at**. The ice protects for 1.5 seconds, so
  the claws land inside it.
- **Auto heal**: while your Bloodfiend is up, eats a Healing Melon when your
  health drops below the share you set.
- **Mania helper**: jumps or sneaks for your Bloodfiend's Impel title
  (`Impel: SNEAK 1.1s`), the **Delay** you set after it first shows (2.5
  seconds are given at tier III, two after). **Impel prompts** hides the jump
  and sneak titles it answers (the default), every Impel title, or none.
- **Auto Killer Spring**: while you look at the Killer Spring, clicks it left or
  right as it asks, or alternately when it doesn't say.
- **Auto tuba**: when your Bloodfiend spawns, blows the Weirder or Weird Tuba,
  then again every **Every** seconds while it's up. That's 30 at first: the
  Weirder Tuba's buff lasts 30 seconds and its cooldown is 20, so blowing it as
  the buff ends saves the most mana.

Thralls, splatters and fledglings are found by those words in their names, Impel by its
title, and the Killer Spring by its head. If one isn't picked up, run
`/cryptic debug rift` while looking at it (or just after the prompt) and send
the output.

The HUD editor (`/cryptic hud`) has a **Slayer** tab with the slayer elements:
Slayer Boss, Impel Display, Slayer XP and the Carry Counter.

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

## Chunk Cache (Render tab)

Keeps drawing the chunks Hypixel has unloaded, out to your own render distance
instead of the server's. Walk away from a place and it stays on the horizon;
without the module the game forgets a chunk the moment the server does.

A chunk is copied as the server lets go of it. While it is in view it stands
there as a cached chunk; once it is out of view only its bytes are kept,
deflated, and it is unpacked again when you come back within range. A chunk is
replaced by the server's own as soon as that arrives, so what you stand in and
interact with is always the real thing. Cached chunks show the blocks as they
were when you last saw them, without chests, skulls, signs or mobs.

- **Keep across lobbies** (on) keeps an island's chunks until you leave
  Hypixel, so swapping lobby or warping back shows the whole island at once.
  Its "⋯" lists the islands this applies to: the ones built the same on every
  server. Anything else (dungeons, the Crystal Hollows, private islands, the
  Garden, other game modes) is only kept for as long as you stay on that
  server. Off, every island is dropped with the server.
- **Distance** caps how far cached chunks are drawn. They never reach further
  than your render distance, which Hypixel otherwise cuts short.
- **Memory limit** is the most memory spent on chunks out of view. Past it,
  islands you have left go first, the longest gone first, and then the chunks
  furthest from you.
- **Clear cache** forgets everything.

F3's chunk line ends with `Cryptic: F` (cached chunks shown) and `S` (chunks
stored). Raising the render distance now costs frames on Hypixel, as far
chunks are really drawn. With Bobby installed the module stays off, since both
do the same job with the same hooks. It does nothing in a single-player world,
which already sends as far as you render.

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

## Movement (General tab)

Auto Sprint and No Jump Delay, once two modules, are switches in one card.
Profiles that had either on carry over.

- **Auto sprint** (on by default): holds the sprint key for you. The game's
  own rules still apply, so you don't sprint while sneaking, hungry, blind,
  using an item or walking backwards.
- **No jump delay** (Semi-legit): jump again the moment you land, instead of
  vanilla's ten-tick wait.

## Combat (General tab)

- **Flare & orb timer**: a HUD element with the flare and the power orb you are
  standing in, each with its head and the time it has left. Only the best of
  each kind in range is shown: flares reach 40 blocks, Radiant, Mana Flux and
  Overflux 18 and Plasmaflux 20. Under its "⋯", **Flares** and **Power orbs**
  turn each row off. Move it with `/cryptic hud`.
- **Warn when it runs out**: one notification for the flare or orb you placed
  yourself (not other players'), **Warn ahead** (10 s) before it ends; at 0 it
  says when it has ended. Yours is the one that appears next to you just after
  you use the item, or after "Your previous ... was removed!". Replacing it, or
  it disappearing because you went too far away, doesn't count as running out. With Notifications off it is a chat line
  instead.

- **Pearl trajectory** (on with the module): while you hold an ender pearl, a
  line shows where it would fly and a box where it would land. If a mob is in
  the way, the line stops there and the mob is outlined. Under its "⋯", the
  **Line** and **Mob it hits** colours.

- **Fire Veil overlay** (off by default): a 3.5-block ring round you for the
  five seconds the Fire Veil Wand burns, on any island. Under its "⋯",
  **Style** (Circle or a low Wall), **Colour**, **Line width** and **Through
  walls**.
- **Hide Fire Veil flames** (on): hides the wand's flame particles near you for
  nine seconds from the cast.

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

## Reminders (General tab)

Was Cookie Reminder. It now holds every reminder, as notifications:

- **Buffs:** the Booster Cookie and the God Potion, as before: running low,
  none running, and a countdown through the last minute.
- **Rift:** Ubik's Cube, moved here from the Rift module (his game's tips stay
  there). Its repeat and "outside the Rift" options are under its "⋯".
- **Events:** the **New Year Cake** (on by default) reminds you to grab the free
  cake from the Baker during the New Year Celebration, again every 10 minutes
  until a cake of that year is in your inventory. The rest start off: the
  **Dark Auction**, **Jacob's Farming Contest**, the **Traveling Zoo**, the
  **Spooky Festival**, the **Season of Jerry**, **Hoppity's Hunt** and the
  **Mayor election** (booth opens and closes). Each is said **Warn ahead**
  minutes before it starts, and again when it does.

Times come from SkyBlock's calendar, worked out from the clock, so nothing is
fetched. Fishing Festivals and Fallen Stars no longer keep to the calendar, so
they are left out.
`/cryptic debug calendar` prints the SkyBlock date and when each event is next.

## Horsezooka (Rift, General tab)

- **Hide Horsezooka** hides every horse while you hold a Horsezooka. Its "⋯"
  has **Opacity**: 0% (the default) leaves them undrawn, more draws them
  see-through.
- **Mute Horsezooka** silences horse sounds, the firework launch and the ghast
  screech while you hold one.

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
- **Flips shown**: which flips the finder lists and the guide picks. The
  panel's tabs (Orders, NPC, Shops, Crafts) change it too.
  - **Order flips**: the above.
  - **NPC flips**: a buy order under what NPCs pay for the item, then a sale
    to an NPC. What NPCs pay doesn't move, so only the buy order has to fill;
    the size goes by the week's instant sells. The guide has you place and
    claim the order, then sell at any NPC shop or the Trades menu; in a shop
    it outlines the items to click. Sales count against Hypixel's 500M coins
    a day, which the tooltip shows what's left of.
  - **Shop flips**: buy from an NPC's shop, then a sell offer on the Bazaar.
    NPCs sell 640 of an item a day. The guide names the NPC and where it is,
    outlines the item in its shop, and once you close the shop walks you
    through the sell offer. Shop prices ship with the mod and are put right
    by what a shop's own menu shows whenever you open one.
  - **Craft flips**: buy orders for a recipe's ingredients, craft, then a sell
    offer for what's made. Only recipes made wholly of Bazaar products count
    (314 of them). A flip is sized in whole crafts, to what your budget buys,
    what each ingredient's trading allows, and what your inventory holds, as
    the ingredients have to be on you. The guide places one buy order per
    ingredient, has you claim them, then shows `/craft`; in the crafting menu
    it outlines the product in the quick-craft slots. Once the product is on
    you it walks you through the sell offer. The tooltip lists the ingredients
    and, where the recipe needs unlocking, what it needs ("Diamond VIII");
    Cryptic can't tell whether you have it. What the ingredients cost carries
    over to the product, so the sale counts in the Flips HUD's profit. A
    product you already carry isn't offered.
  - Purchases from NPCs and sales to them are read from chat ("You bought",
    "You sold") and count in the Flips HUD's profit like Bazaar trades.
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
- **Auction House**: a **Watch list** (its own tab on the module's page, or
  `/cryptic ahwatch`). Add items with the most you'd pay (`800m`, `1.5b`,
  `250k`, or empty for any price) and whether BIN only; when one is put up for
  sale at or under that, you get a notification, and with **Also in chat** a
  line with an **[Open]** that runs `/viewauction` for it. A name the items
  list knows (`Hyperion`, or `Pure Black Dye` and other dyes and skins, whose
  names come from the NEU item repo) is matched by its ID, so reforges and stars don't
  matter; anything else matches listings whose name contains it. **Watch
  hovered item** (a key, behind **Notify on new listings**'s "⋯") puts the item
  under the mouse in any menu on the list, or takes it off; a pet is watched at
  every level. Hypixel's newest auctions are read about once a minute (about
  1 MB each time), only while something is watched and you're on Hypixel. Your
  own auctions are never reported. Listings that went up while you were away
  (the game closed, another server, or more than five minutes without a read)
  are reported when you're back: the first read after a gap goes through every
  page of the Auction House once (about 40 pages, some 50 MB, read a page at a time), and a
  notification says how many went up meanwhile. Only auctions started after the
  watch was added count. Each auction is told about once, even across restarts;
  what was already told is kept in `config/cryptic/auction-watch-seen.json`.

  **Filters** narrow a watch down, and all of them must match. Each is
  `field:condition`, typed into the filter box (suggestions show as you type)
  or added after the item in the command. Values complete from the items list
  in both: `dye:DYE_PURE` offers `DYE_PURE_BLACK`, and `skin:`, `pet_skin:`,
  `pet_item:`, `power_scroll:`, `ability_scroll:`, `id:` and `enchant.` work
  the same way. Leave the item empty to watch any item that passes the
  filters. Some examples:

  - `skin:GOLDOR_CELESTIAL`: the Celestial Goldor skin applied
  - `quality:50 tier:10`: a 50/50 drop from M7
  - `stars:>=5`, `hot_potato:15`, `recombobulated:yes`, `reforge:!heroic`
  - `enchant.ultimate_wise:5`, `gem.combat_0:PERFECT`, `rune.music:3`
  - `pet_level:100 pet_item:PET_ITEM_TIER_BOOST candy:0`
  - `ability_scroll:IMPLOSION_SCROLL,SHADOW_WARP_SCROLL,WITHER_SHIELD_SCROLL`
  - `rarity:>=MYTHIC`, `price:1b-2b`, `seller:<name>`, `id:*_CELESTIAL`
  - `attr.<key>` for anything else Hypixel stores on an item

  Conditions take a value, several (`a,b`, any of them; for lists, all of
  them), `>=n`, `<=n`, `>n`, `<n`, `n+`, a range `n-m`, `any`, `none`, `yes`
  or `no`, with `!` in front for the opposite. Text takes `*` as a wildcard, and
  a missing number counts as 0. `/cryptic ahwatch filters` lists every field,
  grouped, with what each reads on hover. The fields cover what Coflnet's
  filters do: stars, reforge, rarity, recombobulator, potato books, skins, dyes
  and colours, enchantments (and `no_other_enchants`), attributes, runes, gem
  slots and counts, dungeon quality, floor tier and requirement, pet level, held
  item, skin, candy and exp, scrolls, enrichments, drill and rod parts, kill and
  crop counters, cake and party hat years, raffle items, editions, creation
  date, seller, count, price per unit and the item's name and description.

  | Command | Does |
  |---|---|
  | `/cryptic ahwatch` | Opens the watch list |
  | `/cryptic ahwatch add <item> [max price] [filters]` | Watches an item, e.g. `add Storm's Helmet skin:GOLDOR_CELESTIAL` |
  | `/cryptic ahwatch remove <item>` | Stops watching it |
  | `/cryptic ahwatch list` | Lists what you watch |
  | `/cryptic ahwatch filters` | Lists every filter |

  The list is kept in `config/cryptic/auction-watch.json`.
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
NPC offers an answer that agrees ("[Yes]", "[Sure thing, partner!]", "[Aye sure
do!]"), a notification shows the question and the answer; press the **Accept
key** (Y to begin with) to give it. NPC options that aren't a yes ("[Why?]",
"[Enter Time Chamber]", questions about Cold) never pop up, so the key can't
pick one by accident. The key is only taken while a prompt waits, in the world or in a
chest, never while you type. Party and friend invites are left to Party
Features' own notifications. With notifications off, the question comes as a
chat line with a clickable answer instead.

## Better Chat: hide profile ID

**Hide profile ID** (Better Chat, off by default): the "Profile ID: ..." line
Hypixel sends when you join a server shows as "Profile ID: HIDDEN", for streams
and screenshots. Any player's profile ID is hidden, and a click or hover on that
line that would give the ID away is dropped. Only the chat window changes.

## Better Chat: sound on mention

**Sound on mention** (Better Chat, off by default): plays a sound when someone
says your username in chat (party, guild, all chat, PMs). It matches whole
words, ignoring case, and only in the message itself, so your own messages and
lobby notices don't ring; a burst of mentions rings once. Behind its "⋯": the
sound (Pling to begin with, or Custom for any sound id, resource packs
included), volume, pitch, **Other names** (nicknames that count as you,
separated by commas) and a button to hear it.
