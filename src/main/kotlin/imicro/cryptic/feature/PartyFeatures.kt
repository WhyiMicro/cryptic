package imicro.cryptic.feature

import imicro.cryptic.Cryptic
import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.DungeonTeam
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.skyblock.ServerStats
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW
import java.util.Locale

/**
 * Party chat commands, and a party invite you can answer with one key.
 *
 * The commands are the ones Odin's Chat Commands (BSD 3-Clause, Copyright (c)
 * 2025 odtheking) and NoammAddons' Party Helper (CC0, Noamm9) share: somebody
 * types `!w` in party chat and the leader's client warps the party, `!m7` and
 * it queues the floor, `!ping` and everybody's client says its own. They exist
 * because the person who wants the party warped is rarely the person who can
 * do it, and asking takes longer than the warp.
 *
 * The ones that *do* something to the party are only acted on by its leader,
 * which is why this keeps track of who that is — from the same chat lines a
 * player would work it out from. When it has not seen enough to know, it
 * tries, and Hypixel's refusal is what tells it.
 *
 * The invite is Cryptic's own. Hypixel gives you sixty seconds and a line of
 * chat to click on, which is a small target that scrolls; this holds it on
 * screen for the sixty seconds and takes a key instead.
 *
 * Chat is read straight off the packet rather than through Fabric's message
 * event, the way Odin and NoammAddons both read it. The event is only raised
 * for a message every other mod has agreed to show, and a mod that hides or
 * rewrites a line takes it away from everybody listening after it.
 */
object PartyFeatures {
	private const val SOURCE = "Party"

	/** Hypixel's own limit on how long an invite stands. */
	private const val INVITE_SECONDS = 60.0

	/** How long after a command is asked for before it is answered. Odin waits the same. */
	private const val REPLY_DELAY_TICKS = 4

	/** The least time between two things this sends, so a burst of commands is not a burst of chat. */
	private const val SEND_SPACING_TICKS = 12

	/** How long a !ping waits for its first measurement, when nothing was measuring already. */
	private const val PING_WAIT_TICKS = 40

	/** Never more than this many answers waiting; a party spamming commands gets the first few. */
	private const val MAX_QUEUED = 4

	private val FORMATTING = Regex("§.")
	private const val RANK = """(?:\[[^\]]+] )?"""
	private const val NAME = """(\w{1,16})"""

	/** "Party > [MVP+] Name: !command", rank and the emblem after a name both optional. */
	private val partyChat = Regex("""^Party > $RANK$NAME(?: [^\s:]+)?: ?(.*)$""")

	private val inviteLine = Regex("""^$RANK$NAME has invited you to join (?:their|$RANK$NAME's) party!""")
	private val inviteExpired = Regex("""^The party invite from $RANK$NAME has expired""")

	// Who leads, and who is in it, as Hypixel says it.
	private val joinedParty = Regex("""^You have joined $RANK$NAME's party!""")
	private val partyingWith = Regex("""^You'll be partying with: (.+)$""")
	private val transferredTo = Regex("""^The party was transferred to $RANK$NAME (?:by|because) """)
	private val promotedLeader = Regex("""^$RANK$NAME has promoted $RANK$NAME to Party Leader""")
	private val listHeading = Regex("""^Party Members \((\d+)\)$""")
	private val listedLeader = Regex("""^Party Leader: $RANK$NAME""")
	private val listedRow = Regex("""^Party (?:Leader|Moderators|Members): (.+)$""")
	private val listedName = Regex("""$RANK$NAME ?●""")
	private val invitedSomeone = Regex("""^$RANK$NAME invited $RANK$NAME to the party!""")
	private val memberJoined = Regex("""^$RANK$NAME joined the party\.""")
	private val finderJoined = Regex("""^Party Finder > $NAME joined the dungeon group!""")
	private val memberGone = listOf(
		Regex("""^$RANK$NAME has left the party\."""),
		Regex("""^$RANK$NAME has been removed from the party\."""),
		Regex("""^$RANK$NAME was removed from your party because they disconnected"""),
		Regex("""^Kicked $RANK$NAME because they were offline"""),
	)
	private val finderQueued = Regex("""^Party Finder > Your party has been queued in the dungeon finder""")
	private val notLeader = Regex("""^You are not (?:this party's|the party) leader""")
	private val partyGone = listOf(
		Regex("""^You left the party"""),
		Regex("""^You have been kicked from the party"""),
		Regex("""^The party was disbanded"""),
		Regex("""^$RANK\w{1,16} has disbanded the party"""),
		Regex("""^You are not currently in a party"""),
		Regex("""^You are not in a party"""),
	)

	/** Hypixel's names for the floors, which is what its queue command takes. */
	private val FLOOR_NAMES = listOf("ENTRANCE", "ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN")

	/** `f1` to `f7` and `m1` to `m7`. */
	private val floorCommand = Regex("""^[fm][1-7]$""")

	/**
	 * Every word a command can be asked for by, short and long alike.
	 *
	 * Also what decides that a line you typed yourself is a command at all, and
	 * so should go to party chat whichever channel you happen to be in.
	 */
	private val COMMAND_WORDS = setOf(
		"w", "warp", "ai", "allinv", "allinvite", "inv", "invite", "pt", "ptme", "transfer",
		"k", "kick", "ko", "kickoffline", "dt", "downtime", "undt", "undowntime", "coords", "cords", "co", "ping", "tps", "fps",
	)

	// ---- Invites ---------------------------------------------------------

	private val inviteSection = SectionModuleSetting("invite_section", "Invites")

	@JvmField
	val inviteToast = ToggleModuleSetting(
		id = "invite_toast",
		label = "Invite notification",
		defaultValue = true,
		description = "Holds a party invite on screen for its sixty seconds. Press Y to accept it, N to wave it " +
			"away. It is one of Cryptic's notifications, so Toast Notifications has to be on.",
	)

	@JvmField
	val inviteSound = ToggleModuleSetting(
		id = "invite_sound",
		label = "Play a sound",
		defaultValue = true,
		visibleIf = { inviteToast.value || autoAccept.value },
	)

	@JvmField
	val autoAccept = ToggleModuleSetting(
		id = "auto_accept",
		label = "Auto accept",
		defaultValue = false,
		description = "Accepts every party invite the moment it arrives, whoever it is from.",
	)

	// ---- Commands --------------------------------------------------------

	private val commandSection = SectionModuleSetting("command_section", "Party commands")

	@JvmField
	val commands = ToggleModuleSetting(
		id = "commands",
		label = "Party commands",
		defaultValue = true,
		description = "Answers commands typed in party chat with a ! in front, such as !warp or !ping.",
	)

	@JvmField
	val redirect = ToggleModuleSetting(
		id = "redirect",
		label = "Send my commands to party chat",
		defaultValue = true,
		description = "A !command you type goes to party chat whichever channel you are in, as if you had put /pc in front.",
		visibleIf = { commands.value },
	)

	private fun command(id: String, label: String, description: String, default: Boolean = true) =
		ToggleModuleSetting(
			id = id,
			label = label,
			defaultValue = default,
			description = description,
			visibleIf = { commands.value },
		)

	private val enabledSection =
		SectionModuleSetting("enabled_section", "Commands", visibleIf = { commands.value }, startsCollapsed = true)

	@JvmField
	val warp = command("warp", "!warp", "As leader: warps the party. Also !w.")

	@JvmField
	val allInvite = command("all_invite", "!allinvite", "As leader: toggles all-invite. Also !ai.")

	@JvmField
	val invite = command("invite", "!invite <name>", "As leader: invites that player. Also !inv.")

	@JvmField
	val floors = command("floors", "!f1 to !f7, !m1 to !m7", "As leader: queues the party for that floor.")

	@JvmField
	val transfer = command(
		"transfer",
		"!pt [name]",
		"As leader: !pt hands the party to whoever asked, !pt <name> to the member the name fits best. Also !ptme. " +
			"Off to begin with, since anybody in the party can ask.",
		default = false,
	)

	@JvmField
	val kick = command(
		"kick",
		"!kick <name>",
		"As leader: kicks whoever in the party the name fits best, so !kick its is enough. Also !k. " +
			"Off to begin with, since anybody in the party can ask.",
		default = false,
	)

	@JvmField
	val kickOffline = command(
		"kick_offline",
		"!kickoffline",
		"As leader: kicks everybody in the party who has gone offline. Also !ko.",
	)

	@JvmField
	val downtime = command(
		"downtime",
		"!dt <reason>",
		"Remembers who asked for a break, and reminds the party when the run ends. !undt takes it back.",
	)

	@JvmField
	val coords = command("coords", "!coords", "Says where you are standing.")

	@JvmField
	val ping = command("ping", "!ping", "Says your ping.")

	@JvmField
	val tps = command("tps", "!tps", "Says the server's TPS.")

	@JvmField
	val fps = command("fps", "!fps", "Says your FPS.")

	@JvmField
	val module = Module(
		id = "party_features",
		name = "Party Features",
		description = "Party chat commands and one-key invites",
		category = ModuleCategory.GENERAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(
			inviteSection, inviteToast, autoAccept, inviteSound,
			commandSection, commands, redirect,
			enabledSection, warp, allInvite, invite, floors, transfer, kick, kickOffline, downtime, coords, ping, tps, fps,
		),
	)

	// ---- Who leads, and who is in it --------------------------------------

	/** The leader's name, or null while nothing seen so far has said. */
	private var leader: String? = null

	/** True once Hypixel has refused something for not being the leader. */
	private var refusedAsLeader = false

	/**
	 * Everybody known to be in the party, as they spell their names.
	 *
	 * Gathered from whatever says so — the party list, people joining and
	 * leaving, anybody who speaks in party chat — because there is no one place
	 * that lists a party without being asked. It only has to be good enough to
	 * finish a name somebody typed the start of.
	 */
	private val members = LinkedHashSet<String>()

	private fun self(): String? = Minecraft.getInstance().player?.name?.string

	/**
	 * Whether a leader's command is worth trying.
	 *
	 * Yes when you are known to lead, no when somebody else is, and yes again
	 * when nothing is known — a party joined before the game started, or through
	 * the dungeon finder, never said who leads it. Trying costs one refused
	 * command, and the refusal settles the question from then on.
	 */
	private fun mayLead(): Boolean {
		val known = leader
		return if (known != null) known == self() else !refusedAsLeader
	}

	/**
	 * The same answer for other modules: whether you lead the party, or might.
	 * Followed whether or not Party Features itself is switched on.
	 */
	fun mightLead(): Boolean = mayLead()

	/** Who leads the party, or null when nothing seen has said. */
	fun leaderName(): String? = leader

	/** How many the last /party list counted, and when it arrived. */
	@Volatile
	var listedSize: Int? = null
		private set

	@Volatile
	var listedAt = 0L
		private set

	/** When Hypixel last said there is no party, or that you have left it. */
	@Volatile
	var noPartyAt = 0L
		private set

	private fun setLeader(name: String?) {
		leader = name
		refusedAsLeader = false
		if (name != null) members += name
	}

	private fun forgetParty() {
		leader = null
		refusedAsLeader = false
		members.clear()
	}

	/**
	 * The party member a half-typed name most likely means, or null for none.
	 *
	 * A whole name first, then one that starts with what was typed, then one
	 * that merely contains it — and the shortest of those, which is the one the
	 * fewest extra letters away. Case is ignored throughout: nobody types the
	 * capitals in somebody else's name.
	 */
	private fun closestMember(typed: String): String? {
		val wanted = typed.lowercase(Locale.ROOT)
		val me = self()
		val candidates = (members + DungeonTeam.classes.keys).filter { it != me }

		candidates.firstOrNull { it.equals(typed, ignoreCase = true) }?.let { return it }
		candidates.filter { it.lowercase(Locale.ROOT).startsWith(wanted) }.minByOrNull { it.length }?.let { return it }
		return candidates.filter { wanted in it.lowercase(Locale.ROOT) }.minByOrNull { it.length }
	}

	// ---- Sending ---------------------------------------------------------

	/**
	 * Something waiting to be sent. [command] is asked for when its turn comes
	 * rather than when it is queued, for the one answer that is not known yet
	 * at the moment it is asked for.
	 */
	private class Queued(val command: () -> String, var ticks: Int)

	private val queue = ArrayDeque<Queued>()
	private var sendCooldown = 0

	/** Queues a command, without its slash, to be sent a moment from now. */
	private fun send(command: String) = sendLater(REPLY_DELAY_TICKS) { command }

	private fun sendLater(ticks: Int, command: () -> String) {
		if (queue.size >= MAX_QUEUED) return
		queue.addLast(Queued(command, ticks))
	}

	private fun say(message: String) = send("pc $message")

	private fun dispatch(client: Minecraft, command: String) {
		// The debug switch shows what would have been sent, so every command can
		// be tried without a party to try it on.
		if (DebugOverrides.previewPartyCommands) {
			client.gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §7Would send: §f/$command"))
			return
		}
		client.connection?.sendCommand(command)
	}

	// ---- Downtime --------------------------------------------------------

	/** Who asked for a break this run, and why. */
	private val breaks = LinkedHashMap<String, String>()
	private var endHandled = false

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		ClientSendMessageEvents.ALLOW_CHAT.register(::allowOutgoing)
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> endHandled = false }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> queue.clear() }
	}

	fun tick(client: Minecraft) {
		if (sendCooldown > 0) sendCooldown--
		queue.forEach { if (it.ticks > 0) it.ticks-- }
		if (sendCooldown == 0) {
			val next = queue.firstOrNull()
			if (next != null && next.ticks == 0) {
				queue.removeFirst()
				sendCooldown = SEND_SPACING_TICKS
				dispatch(client, next.command())
			}
		}

		// The run ending is when the break somebody asked for is due.
		if (DungeonRun.ended && !endHandled) {
			endHandled = true
			remindOfBreaks(client)
		}
	}

	private fun remindOfBreaks(client: Minecraft) {
		if (!module.enabled || breaks.isEmpty()) return
		val summary = breaks.entries.joinToString(", ") { (name, reason) -> "$name ($reason)" }
		breaks.clear()

		client.gui.hud.setTimes(0, 60, 10)
		client.gui.hud.setTitle(Component.literal("§cDowntime"))
		client.gui.hud.setSubtitle(Component.literal("§f$summary"))
		Toasts.show(SOURCE, "Downtime: $summary", life = 10.0)
		say("Downtime needed: $summary")
	}

	// ---- What you type ---------------------------------------------------

	/** The command a line is asking for, lowercased, or null if it is not one of these. */
	private fun commandWord(text: String): String? {
		if (!text.startsWith("!")) return null
		val word = text.drop(1).trim().substringBefore(' ').lowercase(Locale.ROOT)
		return word.takeIf { it in COMMAND_WORDS || floorCommand.matches(it) }
	}

	/**
	 * A line of chat on its way out. False stops it, which is how a command
	 * typed into all chat ends up in party chat instead.
	 *
	 * Only a line that is one of these commands is touched: anything else
	 * beginning with an exclamation mark is somebody being emphatic, and goes
	 * where they sent it.
	 */
	private fun allowOutgoing(message: String): Boolean {
		if (!module.enabled || !commands.value || !redirect.value) return true
		commandWord(message.trim()) ?: return true
		dispatch(Minecraft.getInstance(), "pc ${message.trim()}")
		return false
	}

	// ---- What arrives ----------------------------------------------------

	/**
	 * A chat packet, on the client thread, before anything else has had a say
	 * in whether it is shown.
	 */
	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay) return
		// Called from inside a packet handler, where anything thrown is the
		// connection's problem. A chat line is never worth that.
		try {
			readChat(message)
		} catch (error: RuntimeException) {
			Cryptic.LOGGER.error("Party Features could not read a chat line", error)
		}
	}

	private fun readChat(message: Component) {
		// One message can carry several lines — Hypixel frames an invite in rules
		// of dashes — and every pattern here is written for a single line.
		for (raw in message.string.split('\n')) {
			val line = raw.replace(FORMATTING, "").trim()
			if (line.isEmpty()) continue
			trackParty(line)
			if (!module.enabled) continue
			onInviteLine(line, message)

			val (sender, said) = partyChat.find(line)?.destructured ?: continue
			members += sender
			if (commands.value && commandWord(said) != null) onCommand(sender, said.drop(1))
		}
	}

	/** Followed whether or not the module is on, so switching it on mid-party already knows. */
	private fun trackParty(line: String) {
		joinedParty.find(line)?.let {
			members.clear()
			return setLeader(it.groupValues[1])
		}
		partyingWith.find(line)?.let { match ->
			match.groupValues[1].split(", ").forEach { entry ->
				entry.trim().substringAfterLast(' ').takeIf { it.matches(Regex("""\w{1,16}""")) }?.let(members::add)
			}
			return
		}
		transferredTo.find(line)?.let { return setLeader(it.groupValues[1]) }
		promotedLeader.find(line)?.let { return setLeader(it.groupValues[2]) }

		// The party list, which is the one complete account there is.
		listHeading.matchEntire(line)?.let {
			members.clear()
			listedSize = it.groupValues[1].toIntOrNull()
			listedAt = System.currentTimeMillis()
			return
		}
		listedRow.find(line)?.let { row ->
			listedName.findAll(row.groupValues[1]).forEach { members += it.groupValues[1] }
			listedLeader.find(line)?.let { setLeader(it.groupValues[1]) }
			return
		}

		memberJoined.find(line)?.let { members += it.groupValues[1]; return }
		finderJoined.find(line)?.let { members += it.groupValues[1]; return }
		for (pattern in memberGone) pattern.find(line)?.let { members -= it.groupValues[1]; return }

		if (finderQueued.containsMatchIn(line)) return setLeader(self())
		if (notLeader.containsMatchIn(line)) {
			if (leader == self()) leader = null
			refusedAsLeader = true
			return
		}
		if (partyGone.any { it.containsMatchIn(line) }) {
			noPartyAt = System.currentTimeMillis()
			return forgetParty()
		}

		// Inviting somebody with no party of your own is how one is made, and
		// makes you its leader. With a leader already known this says nothing
		// new: a party with all-invite on lets anybody invite.
		invitedSomeone.find(line)?.let {
			if (leader == null && it.groupValues[1] == self()) setLeader(self())
		}
	}

	private fun onInviteLine(line: String, message: Component) {
		if (!inviteToast.value && !autoAccept.value) return
		inviteExpired.find(line)?.let { return Toasts.dismiss(inviteId(it.groupValues[1])) }
		if (joinedParty.containsMatchIn(line)) return dismissInvites()

		val match = inviteLine.find(line) ?: return
		val inviter = match.groupValues[1]
		val owner = match.groupValues[2].ifEmpty { inviter }
		// The command Hypixel put behind "Click here to join!", when the message
		// has it, so that accepting does exactly what clicking would have.
		val accept = acceptCommandIn(message) ?: "party accept $inviter"
		raiseInvite(inviter, owner, accept)
	}

	private val pendingInvites = LinkedHashSet<String>()

	private fun inviteId(inviter: String): String = "party_invite_${inviter.lowercase(Locale.ROOT)}"

	private fun dismissInvites() {
		pendingInvites.forEach(Toasts::dismiss)
		pendingInvites.clear()
	}

	private fun raiseInvite(inviter: String, owner: String, accept: String) {
		val client = Minecraft.getInstance()
		val whose = if (owner == inviter) "their party" else "$owner's party"

		if (inviteSound.value) {
			client.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.0f))
		}

		if (autoAccept.value) {
			dispatch(client, accept)
			Toasts.show(SOURCE, "Accepted $inviter's invite to $whose")
			return
		}

		val id = inviteId(inviter)
		pendingInvites += id
		Toasts.ask(
			source = SOURCE,
			id = id,
			life = INVITE_SECONDS,
			keys = mapOf(
				GLFW.GLFW_KEY_Y to {
					pendingInvites -= id
					dispatch(client, accept)
				},
				// Hypixel has no command for turning an invite down; one that is
				// ignored simply runs out. So this only takes the notice away.
				GLFW.GLFW_KEY_N to { pendingInvites -= id },
			),
		) { left -> "$inviter invited you to $whose. Y to accept, N to ignore (${left}s)" }
	}

	/** Shows an invite from nobody, for `/cryptic debug partyinvite`. */
	fun simulateInvite(inviter: String) = raiseInvite(inviter, inviter, "party accept $inviter")

	/** The `/party accept …` a message's click runs, without its slash. */
	private fun acceptCommandIn(component: Component): String? {
		val click = component.style.clickEvent
		if (click is ClickEvent.RunCommand) {
			val command = click.command().removePrefix("/")
			if (command.startsWith("party accept", ignoreCase = true) || command.startsWith("p accept", ignoreCase = true)) {
				return command
			}
		}
		for (sibling in component.siblings) acceptCommandIn(sibling)?.let { return it }
		return null
	}

	/** [text] is the command without its exclamation mark: "kick its", "m7". */
	private fun onCommand(sender: String, text: String) {
		val words = text.trim().split(Regex("\\s+"))
		val name = words.firstOrNull()?.lowercase(Locale.ROOT) ?: return
		// A name at most, never a sentence: whatever follows the command ends up
		// inside a command of this client's own.
		val argument = words.getOrNull(1)?.takeIf { it.matches(Regex("""\w{1,16}""")) }
		val client = Minecraft.getInstance()
		val player = client.player ?: return

		when (name) {
			"w", "warp" -> if (warp.value && mayLead()) send("party warp")
			"ai", "allinvite", "allinv" -> if (allInvite.value && mayLead()) send("party settings allinvite")
			"inv", "invite" -> if (invite.value && mayLead() && argument != null) send("party invite $argument")
			"pt", "ptme", "transfer" -> if (transfer.value && mayLead()) {
				// "!pt" alone hands the party to whoever asked; "!pt name" to the
				// member that name fits, which is also how the leader passes it on.
				val target = if (argument != null && name != "ptme") closestMember(argument) ?: argument else sender
				if (!target.equals(self(), ignoreCase = true)) send("party transfer $target")
			}
			"k", "kick" -> if (kick.value && mayLead() && argument != null) {
				// Whoever in the party the typed name fits, so the start of a
				// name is enough. With nobody it fits, it is sent as it was
				// typed and Hypixel is left to say it knows nobody by that name.
				val target = closestMember(argument) ?: argument
				if (!target.equals(self(), ignoreCase = true)) send("party kick $target")
			}
			"ko", "kickoffline" -> if (kickOffline.value && mayLead()) send("party kickoffline")

			"dt", "downtime" -> if (downtime.value) {
				val reason = words.drop(1).joinToString(" ").take(40).ifBlank { "no reason given" }
				breaks[sender] = reason
				Toasts.show(SOURCE, "$sender wants a break after this run")
			}
			"undt", "undowntime" -> if (downtime.value && breaks.remove(sender) != null) {
				Toasts.show(SOURCE, "$sender no longer needs a break")
			}

			"coords", "cords", "co" ->
				if (coords.value) say("x: ${player.blockX}, y: ${player.blockY}, z: ${player.blockZ}")
			"ping" -> if (ping.value) {
				// Asking is what starts the measuring, so with nothing on screen
				// showing a ping there may be no number yet. The answer waits
				// long enough for one to come back rather than saying nought.
				val wait = if (ServerStats.ping < 0) PING_WAIT_TICKS else REPLY_DELAY_TICKS
				sendLater(wait) {
					val millis = ServerStats.ping
					if (millis < 0) "pc Ping: no answer from the server" else "pc Ping: ${millis}ms"
				}
			}
			"tps" -> if (tps.value) say(String.format(Locale.ROOT, "TPS: %.1f", ServerStats.tps))
			"fps" -> if (fps.value) say("FPS: ${client.fps}")

			else -> queueFloor(name)
		}
	}

	/**
	 * `f1`…`f7` and `m1`…`m7`, which queue the party for that floor.
	 *
	 * Through `/joininstance`, Hypixel's own command for it — the same one the
	 * dungeon menu runs.
	 */
	private fun queueFloor(name: String) {
		if (!floors.value || !floorCommand.matches(name) || !mayLead()) return
		val floor = name[1].digitToInt()
		when (name[0]) {
			'f' -> send("joininstance CATACOMBS_FLOOR_${FLOOR_NAMES[floor]}")
			'm' -> send("joininstance MASTER_CATACOMBS_FLOOR_${FLOOR_NAMES[floor]}")
		}
	}

	/** Who this thinks leads and who is in the party, for `/cryptic debug party`. */
	fun describe(): List<String> = listOf(
		"§8[Cryptic] §7Party leader: §f${leader ?: "not known"}" +
			if (refusedAsLeader) " §7(Hypixel has said it is not you)" else "",
		"§8[Cryptic] §7Known members: §f${members.ifEmpty { listOf("none") }.joinToString(", ")}",
		"§8[Cryptic] §7Leader commands would ${if (mayLead()) "§abe tried" else "§cbe left alone"}§7. " +
			"Breaks asked for: §f${breaks.size}§7, waiting to send: §f${queue.size}",
	)
}
