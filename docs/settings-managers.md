# Keybinds, Aliases, Sounds and Updates

The Keybinds, Alias and Sound managers are in the menu's General tab, and the
Update Checker is on the Settings tab. Each manager is on its module's page:
open the card with its **⋮** (or a right-click), or use its `/cryptic` command,
which opens the menu straight on it. A manager works whether or not its module
is on; the switch in the page's title row turns the module on.

| Command | Opens |
|---|---|
| `/cryptic keybinds` | Keybinds Manager |
| `/cryptic aliases` | Alias Manager |
| `/cryptic sounds` | Sound Manager |
| `/cryptic update` | Checks for an update now |
| `/cryptic changelog` | Changelog |
| `/cryptic blacklist` | Party Blacklist |
| `/cryptic ahwatch` | Auction House watch list |

## Keybinds Manager

Keys that run a command or say something in chat, modelled on Athen's Keybinds.

- **New binding** opens the editor.
- **Command** starts with `/` for a command. Anything else is said in chat.
  Commands go through the client's own commands first, so a binding can run
  `/cryptic …` or one of your aliases.
- **Keys**: click the box, then press the keys. Hold several together for a
  combination such as Ctrl+K. Mouse side buttons work too. The binding is set
  when the first key comes up. Esc or a left click cancels.
- **Category** groups bindings in the list. Each group has a switch that turns
  the whole group off.
- **Works** sets where the binding fires:
  - **Outside GUI:** no screen open.
  - **In a GUI:** a chest or inventory open.
  - **Everywhere:** both.

  A binding never fires while you type in chat or on a sign.
- **Islands**, **Dungeon floors**, **Dungeon classes**, **Floor 7 phases** and
  **Kuudra tiers** limit where it fires. Leave a group empty to allow anything.

A binding fires once each time its keys go down. If you hold Ctrl+K and also
have a binding on K, only the Ctrl+K one fires.

Each row has a switch, an edit button and a delete button. Delete asks again
with **?** before it removes anything.

Bindings are kept in `config/cryptic/keybinds.json`.

## Alias Manager

Commands of your own that stand for other commands, as in NoammAddons' Command
Shortcuts. For example, `/pk` can stand for `/party kick` and `/dh` for
`/warp dungeon_hub`.

- **Arguments:** whatever you type after the alias goes on the end of the
  command it stands for. Use `{args}` to put it somewhere else, and `{1}`,
  `{2}` and so on for single words.
- **Several commands:** separate them with `;`, e.g. `p disband; p invite {1}`.
  They run half a second apart, so Hypixel does not refuse the second one.
- **Chaining:** an alias can call another alias, up to five deep. Past that,
  Cryptic treats it as a loop and stops.
- **Names:** an alias can't take the name of another mod's client command, or
  `/cryptic`.
- **Suggestions:** each alias is a real client command, so it shows up in
  chat's suggestions as soon as you add it.
- **Removed aliases:** removing one can't take it out of the current server's
  commands. Until you change server, typing it sends exactly what you typed to
  Hypixel, as if the alias had never existed.

Aliases are kept in `config/cryptic/aliases.json`.

## Sound Manager

A volume for any sound, from 0% (silent) to 200%, in steps of 5. This is
NoammAddons' Sound Manager. The difference is that it can be used even with
the module off.

- **Search** matches any part of a sound's id, e.g. `enderman` or `teleport`.
- **Recent** lists the last hundred sounds played, newest first. Use it to find
  a sound you just heard.
- **Changed** lists the sounds you have set.
- The other tabs sort sounds by what their id starts with: Blocks, Mobs,
  Items, Music, Ambient, UI, Other.
- The play button previews a sound at half volume, so 200% can be heard as
  twice as loud.
- The arrow button resets a sound to 100%.

Minecraft caps a sound at full volume, so going over 100% only helps sounds
that play quieter than full to begin with. Most of Hypixel's do.

Volumes are kept in `config/cryptic/sound-volumes.json`.

## Settings tab

**Settings**, at the foot of the menu's sidebar, holds Cryptic's own settings on
one page, under a heading each: Client Restrictions, Click GUI (with the key
that opens the menu), Update Checker, Toast Notifications and the HUD Editor.
**Credits**, at the bottom, opens the list of projects Cryptic learned from.
The search box finds any of these settings, and a search from the module list
shows a Settings card that opens the tab on what matched.

**Config** sits above it in the sidebar, with your saved setups.

## Update Checker

On by default (**Check for updates** on the Settings tab). Once per session, on
the first server you join, it reads the latest release on GitHub and compares
it with the version you have installed.

Only a release with a jar for your Minecraft version is offered. If it's
newer, what happens depends on **Install updates automatically**:

- **Off (the default):** a notification asks "A new update for Cryptic is
  available! (0.87.0 -> 0.88.0) Would you like to install it now?". Press
  **Y** to download it, or **N** for not now, which leaves the release page in
  chat. With notifications off, the question is a chat line with clickable
  **[Y]** and **[N]**.
- **On:** it downloads without asking.

Either way, once the download is done you're told whether it worked. A
downloaded update is installed as the game closes, so restart the game to apply
it. A failed one says why and leaves the release page in chat.

**Check now**, or `/cryptic update`, checks straight away and also tells you
when you're already up to date. `/cryptic debug update` shows the whole thing
with a made-up next version, and `/cryptic debug update fail` makes its pretend
download fail; neither downloads anything.

## Changelog

**Read all** on the Profile view, or `/cryptic changelog`, shows the release
notes for the version you have, read from its GitHub release. A build newer
than the last release, such as one you built yourself, shows that release's
notes and says so. **All releases** opens the GitHub releases page with every
older changelog.

Turn on **Offer what's new** under Update Checker on the Settings tab. While
it's on, the first time you join a world or server after Cryptic updates, a
notification says so; press **Y** to open the Changelog. Off (the default),
there's no notification, though the Changelog still opens from the Profile view
or the command.

## Profile

**Profile**, at the top of the menu's sidebar, is an overview of your
SkyBlock time: how long you've played today and in all, runs finished, slayer
bosses, the week's Bazaar flip profit and how many modules are on; a chart of
the last 30 days (rest the pointer on a day for its time); where your time went
by island; the latest release notes (**Read all** opens the Changelog); and this
session.

The numbers are counted by Cryptic from then on, while you're on SkyBlock, and
kept per Minecraft account in `config/cryptic/stats.json`. Nothing is sent
anywhere. Days older than 90 are dropped; the totals are kept.

## Party Blacklist

In the Dungeon tab. Players you won't run with, each with a reason and the day
they were added. The module's switch turns the blacklist on.

- **Add a player** takes a name and a reason. Adding a name that is already on
  the list updates its reason.
- Each row shows the player's face, name, reason and date, with an edit button
  and a delete button. Delete asks again first.
- **Kick them** (on by default) kicks a blacklisted player as soon as they join
  your party, while you lead it. **Say why in party chat** first says they are
  blacklisted, with your reason.
- When a blacklisted player joins, a toast names them and your reason, even
  when you don't lead and can't kick.

From chat:

| Command | Does |
|---|---|
| `/cryptic blacklist add <name> [reason]` | Adds a player, or updates their reason |
| `/cryptic blacklist remove <name>` | Takes a player off |
| `/cryptic blacklist list` | Lists everyone, with reasons and dates |

The list is kept in `config/cryptic/blacklist.json`.
