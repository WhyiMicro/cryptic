package imicro.cryptic.feature

import imicro.cryptic.Cryptic
import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
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
 * Party chat commands, and a party invite you answer with one key.
 *
 * The commands are those Odin's Chat Commands and NoammAddons' Party Helper share: `!w` warps the
 * party, `!m7` queues a floor, `!ping` makes everyone's client report its own. Commands that act on
 * the party are only run by the leader, tracked from chat lines; when unknown, it tries and
 * Hypixel's refusal answers.
 *
 * The invite notification is Cryptic's own: held on screen for Hypixel's sixty seconds, answered
 * with a key.
 *
 * Chat is read from the packet (as Odin and NoammAddons do), since Fabric's event misses lines
 * other mods hide.
 */
object PartyFeatures {
	private const val SOURCE = "Party"

	private const val INVITE_SECONDS = 60.0

	/** How long after a command is asked before it's answered (as Odin waits). */
	private const val REPLY_DELAY_TICKS = 4

	/** The least time between two things this sends. */
	private const val SEND_SPACING_TICKS = 12

	/** How long a !ping waits for its first measurement, when nothing was measuring. */
	private const val PING_WAIT_TICKS = 40

	/** At most this many answers waiting; a spamming party gets the first few. */
	private const val MAX_QUEUED = 4

	private val FORMATTING = Regex("§.")
	private const val RANK = """(?:\[[^\]]+] )?"""
	private const val NAME = """(\w{1,16})"""

	/** "Party > [MVP+] Name: !command", rank and emblem optional. */
	private val partyChat = Regex("""^Party > $RANK$NAME(?: [^\s:]+)?: ?(.*)$""")

	private val inviteLine = Regex("""^$RANK$NAME has invited you to join (?:their|$RANK$NAME's) party!""")
	private val inviteExpired = Regex("""^The party invite from $RANK$NAME has expired""")

	// A friend request, framed in dashes with [ACCEPT] - [DENY] - [IGNORE] under it.
	private val friendRequestLine = Regex("""^Friend request from $RANK$NAME""")
	private val friendRequestExpired = Regex("""friend request from $RANK$NAME (?:has )?expired""", RegexOption.IGNORE_CASE)
	private val nowFriends = Regex("""^You are now friends with $RANK$NAME""")

	// Who leads and who's in it, as Hypixel says it.
	private val joinedParty = Regex("""^You have joined $RANK$NAME's party!""")
	private val partyingWith = Regex("""^You'll be partying with: (.+)$""")
	private val transferredTo = Regex("""^The party was transferred to $RANK$NAME (?:by|because) """)
	private val transferredBecauseLeft = Regex("""^The party was transferred to $RANK$NAME because $RANK$NAME left""")
	private val promotedLeader = Regex("""^$RANK$NAME has promoted $RANK$NAME to Party Leader""")

	/**
	 * "[MVP+] Name entered The Catacombs, Floor VII!", shown to the whole party at each run's
	 * start. Only the leader can start one, so it names the leader.
	 */
	private val enteredDungeon = Regex("""^$RANK$NAME entered (?:MM )?The Catacombs,""")
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
	private val notLeader = Regex("""^You are not (?:this party's|the party) leader""")
	private val partyGone = listOf(
		Regex("""^You left the party"""),
		Regex("""^You have been kicked from the party"""),
		Regex("""^The party was disbanded"""),
		Regex("""^$RANK\w{1,16} has disbanded the party"""),
		Regex("""^You are not currently in a party"""),
		Regex("""^You are not in a party"""),
	)

	private val FLOOR_NAMES = listOf("ENTRANCE", "ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN")

	/** `k1` to `k5`, or `t1` to `t5` for tier. */
	private val KUUDRA_COMMAND = Regex("""[kt]([1-5])""")
	private val KUUDRA_TIERS = listOf("KUUDRA_NORMAL", "KUUDRA_HOT", "KUUDRA_BURNING", "KUUDRA_FIERY", "KUUDRA_INFERNAL")

	private val floorCommand = Regex("""^[fm][1-7]$""")

	/**
	 * Every word a command can be called by. Also what makes a line you typed a command, sent to
	 * party chat whatever channel you're in.
	 */
	private val COMMAND_WORDS = setOf(
		"w", "warp", "ai", "allinv", "allinvite", "inv", "invite", "pt", "ptme", "transfer",
		"k", "kick", "ko", "kickoffline", "dt", "downtime", "undt", "undowntime", "coords", "cords", "co", "ping", "tps", "fps",
	)

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
	val friendToast = ToggleModuleSetting(
		id = "friend_toast",
		label = "Friend request notification",
		defaultValue = true,
		description = "Holds a friend request on screen for a minute. Press Y to accept it, N to deny it. " +
			"It is one of Cryptic's notifications, so Toast Notifications has to be on.",
	)

	@JvmField
	val inviteSound = ToggleModuleSetting(
		id = "invite_sound",
		label = "Play a sound",
		defaultValue = true,
		visibleIf = { inviteToast.value || autoAccept.value || friendToast.value },
	)

	@JvmField
	val autoAccept = ToggleModuleSetting(
		id = "auto_accept",
		label = "Auto accept",
		defaultValue = false,
		description = "Accepts every party invite the moment it arrives, whoever it is from.",
	)

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
	val kuudra = command("kuudra", "!k1 to !k5", "As leader: queues the party for that Kuudra tier, Basic to Infernal. !t1 to !t5 do the same.")

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
		description = "Party commands and invites",
		category = ModuleCategory.GENERAL,
		supportsKeybind = false,
		settings = listOf(
			inviteSection, inviteToast, autoAccept, friendToast, inviteSound,
			commandSection, commands, redirect,
			enabledSection, warp, allInvite, invite, floors, kuudra, transfer, kick, kickOffline, downtime, coords, ping, tps, fps,
		),
	)

	/** The leader's name, or null while unknown. */
	private var leader: String? = null

	private var refusedAsLeader = false

	/**
	 * Everyone known to be in the party, gathered from the party list, joins, leaves and party
	 * chat. Only needs to be good enough to complete a typed name.
	 */
	private val members = LinkedHashSet<String>()

	private fun self(): String? = Minecraft.getInstance().player?.name?.string

	/**
	 * Whether a leader's command is worth trying: yes when you lead, no when someone else does, and
	 * yes when unknown (a party joined before launch or via the dungeon finder never said). The
	 * first refusal settles it.
	 */
	private fun mayLead(): Boolean {
		val known = leader
		return if (known != null) known == self() else !refusedAsLeader
	}

	fun leaderName(): String? = leader

	/** [mayLead], for other modules' leader commands. */
	fun mayLeadParty(): Boolean = mayLead()

	/** How many the last /party list counted, and when. */
	@Volatile
	var listedSize: Int? = null
		private set

	@Volatile
	var listedAt = 0L
		private set

	/** When Hypixel last said there's no party, or that you left. */
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
	 * The member a half-typed name most likely means: an exact name, then a prefix, then a
	 * substring, the shortest match winning. Case-insensitive.
	 */
	private fun closestMember(typed: String): String? {
		val wanted = typed.lowercase(Locale.ROOT)
		val me = self()
		val candidates = (members + DungeonTeam.classes.keys).filter { it != me }

		candidates.firstOrNull { it.equals(typed, ignoreCase = true) }?.let { return it }
		candidates.filter { it.lowercase(Locale.ROOT).startsWith(wanted) }.minByOrNull { it.length }?.let { return it }
		return candidates.filter { wanted in it.lowercase(Locale.ROOT) }.minByOrNull { it.length }
	}

	/**
	 * Something waiting to be sent. [command] is asked for when its turn comes, for answers not
	 * known yet when queued.
	 */
	private class Queued(val command: () -> String, var ticks: Int)

	private val queue = ArrayDeque<Queued>()
	private var sendCooldown = 0

	/** Queues a command (without its slash) to send shortly. */
	private fun send(command: String) = sendLater(REPLY_DELAY_TICKS) { command }

	private fun sendLater(ticks: Int, command: () -> String) {
		if (queue.size >= MAX_QUEUED) return
		queue.addLast(Queued(command, ticks))
	}

	private fun say(message: String) = send("pc $message")

	/**
	 * Queues a command for another module, so every command Cryptic sends keeps the same spacing
	 * (two at the end of a run had the second refused).
	 */
	fun enqueue(command: String) = sendLater(0) { command }

	private fun dispatch(client: Minecraft, command: String) {
		// The debug switch shows what would be sent, so commands can be tried without a party.
		if (DebugOverrides.previewPartyCommands) {
			client.gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §7Would send: §f/$command"))
			return
		}
		client.connection?.sendCommand(command)
	}

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

		// The break is due when the run ends.
		if (DungeonRun.ended && !endHandled) {
			endHandled = true
			remindOfBreaks(client)
		}
	}

	private fun remindOfBreaks(client: Minecraft) {
		if (!module.enabled || breaks.isEmpty()) return
		val summary = breaks.entries.joinToString(", ") { (name, reason) -> "$name ($reason)" }
		val toastSummary = breaks.entries.joinToString("§7, ") { (name, reason) -> "§f$name §8(§7$reason§8)" }
		breaks.clear()

		client.gui.hud.setTimes(0, 60, 10)
		client.gui.hud.setTitle(Component.literal("§cDowntime"))
		client.gui.hud.setSubtitle(Component.literal("§f$summary"))
		Toasts.show(SOURCE, "Downtime for $toastSummary", life = 10.0, color = Toasts.Colors.PARTY)
		// Only the leader tells the party, and Auto Requeue says it itself when it's on.
		if (mayLead() && !AutoRequeue.module.enabled) say("Downtime needed: $summary")
	}

	/** The command a line asks for, lowercased, or null. */
	private fun commandWord(text: String): String? {
		if (!text.startsWith("!")) return null
		val word = text.drop(1).trim().substringBefore(' ').lowercase(Locale.ROOT)
		return word.takeIf { it in COMMAND_WORDS || floorCommand.matches(it) || KUUDRA_COMMAND.matches(it) }
	}

	/**
	 * A chat line on its way out. False stops it, which is how a command typed in all chat goes to
	 * party chat instead. Only actual commands are touched.
	 */
	private fun allowOutgoing(message: String): Boolean {
		if (!module.enabled || !commands.value || !redirect.value) return true
		commandWord(message.trim()) ?: return true
		dispatch(Minecraft.getInstance(), "pc ${message.trim()}")
		return false
	}

	/** A chat packet on the client thread, before anything decides whether it's shown. */
	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay) return
		// Inside a packet handler, where a throw drops the connection.
		try {
			readChat(message)
		} catch (error: RuntimeException) {
			Cryptic.LOGGER.error("Party Features could not read a chat line", error)
		}
	}

	private fun readChat(message: Component) {
		// One message can hold several lines (Hypixel frames invites in dash rules).
		for (raw in message.string.split('\n')) {
			val line = raw.replace(FORMATTING, "").trim()
			if (line.isEmpty()) continue
			trackParty(line)
			if (!module.enabled) continue
			onInviteLine(line, message)
			onFriendLine(line, message)

			val (sender, said) = partyChat.find(line)?.destructured ?: continue
			members += sender
			if (commands.value && commandWord(said) != null) onCommand(sender, said.drop(1))
		}
	}

	/** Followed whether or not the module is on, so switching it on mid-party already knows. */
	private fun trackParty(line: String) {
		retryAcceptIfRefused(line)
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
		// "...because Name left" is also someone leaving, and Hypixel's only word of it.
		transferredBecauseLeft.find(line)?.let {
			members -= it.groupValues[2]
			return setLeader(it.groupValues[1])
		}
		transferredTo.find(line)?.let { return setLeader(it.groupValues[1]) }
		enteredDungeon.find(line)?.let { return setLeader(it.groupValues[1]) }
		promotedLeader.find(line)?.let { return setLeader(it.groupValues[2]) }

		// The party list, the one complete account.
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
		finderJoined.find(line)?.let {
			val name = it.groupValues[1]
			members += name
			// Joining someone's party: it's theirs, but who leads isn't said yet.
			if (name.equals(self(), ignoreCase = true)) {
				if (leader == self()) leader = null
				refusedAsLeader = true
			}
			return
		}
		for (pattern in memberGone) pattern.find(line)?.let { members -= it.groupValues[1]; return }

		// "Your party has been queued in the dungeon finder" is shown to every member, so it
		// doesn't mean you lead.
		if (notLeader.containsMatchIn(line)) {
			if (leader == self()) leader = null
			refusedAsLeader = true
			return
		}
		if (partyGone.any { it.containsMatchIn(line) }) {
			noPartyAt = System.currentTimeMillis()
			return forgetParty()
		}

		// Inviting someone without a party creates one with you as leader. With a leader known it
		// says nothing (all-invite lets anyone invite).
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
		// The command behind "Click here to join!", so accepting does exactly what clicking would.
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
		val whose = if (owner == inviter) "their party" else "§f$owner§7's party"

		if (inviteSound.value) {
			client.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.0f))
		}

		if (autoAccept.value) {
			join(client, accept)
			Toasts.show(SOURCE, "Joined §f$owner§7's party", color = Toasts.Colors.PARTY, head = inviter)
			return
		}

		val id = inviteId(inviter)
		pendingInvites += id
		Toasts.ask(
			source = SOURCE,
			id = id,
			life = INVITE_SECONDS,
			color = Toasts.Colors.PARTY,
			head = inviter,
			footer = "§a[Y] §fAccept   §c[N] §fIgnore",
			keys = mapOf(
				GLFW.GLFW_KEY_Y to {
					pendingInvites -= id
					join(client, accept)
				},
				// Hypixel has no command to decline an invite; it just runs out. So this only
				// removes the notice.
				GLFW.GLFW_KEY_N to { pendingInvites -= id },
			),
		) { "§f$inviter §7invited you to $whose" }
	}

	/** The accept last sent, while Hypixel may still answer that you're already in a party. */
	private var lastAccept: String? = null
	private var lastAcceptAt = 0L

	/** How long after accepting a refusal is taken to be its answer. */
	private const val ACCEPT_ANSWER_MILLIS = 5_000L

	private val alreadyInParty = Regex("""^You are already in a party""")

	/** Whether you're known to be in a party; unknown counts as no, and Hypixel's refusal covers it. */
	private fun inParty(): Boolean = leader != null || members.any { !it.equals(self(), ignoreCase = true) }

	/**
	 * Accepts an invite, leaving the party you're in first, since Hypixel refuses to join a second.
	 * Queued, so the leave goes out first with the usual spacing.
	 */
	private fun join(client: Minecraft, accept: String) {
		if (inParty()) {
			enqueue("party leave")
			enqueue(accept)
			lastAccept = null
			return
		}
		dispatch(client, accept)
		lastAccept = accept
		lastAcceptAt = System.currentTimeMillis()
	}

	/** Hypixel said no because of a party Cryptic didn't know about: leave it and accept again. */
	private fun retryAcceptIfRefused(line: String) {
		val accept = lastAccept ?: return
		if (System.currentTimeMillis() - lastAcceptAt > ACCEPT_ANSWER_MILLIS) {
			lastAccept = null
			return
		}
		if (!alreadyInParty.containsMatchIn(line)) return
		lastAccept = null
		enqueue("party leave")
		enqueue(accept)
	}

	/** An invite from nobody, for `/cryptic debug partyinvite`. */
	fun simulateInvite(inviter: String) = raiseInvite(inviter, inviter, "party accept $inviter")

	private fun acceptCommandIn(component: Component): String? = commandIn(component, "party accept", "p accept")

	/** The first click command in [component] starting with one of [starts], without its slash. */
	private fun commandIn(component: Component, vararg starts: String): String? {
		val click = component.style.clickEvent
		if (click is ClickEvent.RunCommand) {
			val command = click.command().removePrefix("/")
			if (starts.any { command.startsWith(it, ignoreCase = true) }) return command
		}
		for (sibling in component.siblings) commandIn(sibling, *starts)?.let { return it }
		return null
	}

	/**
	 * Hypixel's "Friend request from Name" with [ACCEPT] - [DENY] - [IGNORE], held on screen like
	 * an invite: Y accepts, N denies.
	 */
	private fun onFriendLine(line: String, message: Component) {
		if (!friendToast.value) return
		friendRequestExpired.find(line)?.let { return Toasts.dismiss(friendId(it.groupValues[1])) }
		nowFriends.find(line)?.let { return Toasts.dismiss(friendId(it.groupValues[1])) }

		val sender = friendRequestLine.find(line)?.groupValues?.get(1) ?: return
		// The commands behind [ACCEPT] and [DENY], so a key does exactly what clicking would.
		val accept = commandIn(message, "friend accept", "f accept") ?: "friend accept $sender"
		val deny = commandIn(message, "friend deny", "f deny") ?: "friend deny $sender"
		raiseFriendRequest(sender, accept, deny)
	}

	private fun friendId(sender: String): String = "friend_request_${sender.lowercase(Locale.ROOT)}"

	private fun raiseFriendRequest(sender: String, accept: String, deny: String) {
		val client = Minecraft.getInstance()
		if (inviteSound.value) {
			client.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.0f))
		}
		Toasts.ask(
			source = "Friends",
			id = friendId(sender),
			life = FRIEND_REQUEST_SECONDS,
			color = Toasts.Colors.FRIEND,
			head = sender,
			footer = "§a[Y] §fAccept   §c[N] §fDeny",
			keys = mapOf(
				GLFW.GLFW_KEY_Y to { dispatch(client, accept) },
				GLFW.GLFW_KEY_N to { dispatch(client, deny) },
			),
		) { "§f$sender §7sent you a friend request" }
	}

	/**
	 * How long the notification stays. Hypixel keeps the request longer; it can still be answered
	 * in chat.
	 */
	private const val FRIEND_REQUEST_SECONDS = 60.0

	/** A friend request from nobody, for `/cryptic debug friendrequest`. */
	fun simulateFriendRequest(sender: String) = raiseFriendRequest(sender, "friend accept $sender", "friend deny $sender")

	/** [text] is the command without its "!": "kick its", "m7". */
	private fun onCommand(sender: String, text: String) {
		val words = text.trim().split(Regex("\\s+"))
		val name = words.firstOrNull()?.lowercase(Locale.ROOT) ?: return
		// Only a name, never a sentence, since it ends up inside one of this client's commands.
		val argument = words.getOrNull(1)?.takeIf { it.matches(Regex("""\w{1,16}""")) }
		val client = Minecraft.getInstance()
		val player = client.player ?: return

		when (name) {
			"w", "warp" -> if (warp.value && mayLead()) send("party warp")
			"ai", "allinvite", "allinv" -> if (allInvite.value && mayLead()) send("party settings allinvite")
			"inv", "invite" -> if (invite.value && mayLead() && argument != null) send("party invite $argument")
			"pt", "ptme", "transfer" -> if (transfer.value && mayLead()) {
				// "!pt" alone transfers to whoever asked; "!pt name" to the matching member.
				val target = if (argument != null && name != "ptme") closestMember(argument) ?: argument else sender
				if (!target.equals(self(), ignoreCase = true)) send("party transfer $target")
			}
			"k", "kick" -> if (kick.value && mayLead() && argument != null) {
				// The member the typed name fits, so a prefix is enough; otherwise sent as typed
				// for Hypixel to answer.
				val target = closestMember(argument) ?: argument
				if (!target.equals(self(), ignoreCase = true)) send("party kick $target")
			}
			"ko", "kickoffline" -> if (kickOffline.value && mayLead()) send("party kickoffline")

			"dt", "downtime" -> if (downtime.value) {
				if (DungeonRun.ended || !DungeonLocation.inDungeon) {
					// The run is over, so the break is wanted now and Auto Requeue stops for it.
					// Not kept, or it would repeat next run.
					Toasts.show(SOURCE, "§f$sender §7wants downtime now", color = Toasts.Colors.PARTY, head = sender)
				} else {
					val reason = words.drop(1).joinToString(" ").take(40).ifBlank { "no reason given" }
					breaks[sender] = reason
					Toasts.show(SOURCE, "§f$sender §7wants downtime after this run", color = Toasts.Colors.PARTY, head = sender)
				}
			}
			"undt", "undowntime" -> if (downtime.value && breaks.remove(sender) != null) {
				Toasts.show(SOURCE, "§f$sender §7no longer needs downtime", color = Toasts.Colors.PARTY, head = sender)
			}

			"coords", "cords", "co" ->
				if (coords.value) say("x: ${player.blockX}, y: ${player.blockY}, z: ${player.blockZ}")
			"ping" -> if (ping.value) {
				// With nothing showing ping, measuring may only just have started, so the answer
				// waits for a number.
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
	 * `f1` to `f7` and `m1` to `m7` queue that floor via `/joininstance`, the command Hypixel's
	 * dungeon menu runs.
	 */
	private fun queueFloor(name: String) {
		KUUDRA_COMMAND.matchEntire(name)?.let { match ->
			if (kuudra.value && mayLead()) send("joininstance ${KUUDRA_TIERS[match.groupValues[1].toInt() - 1]}")
			return
		}
		if (!floors.value || !floorCommand.matches(name) || !mayLead()) return
		val floor = name[1].digitToInt()
		when (name[0]) {
			'f' -> send("joininstance CATACOMBS_FLOOR_${FLOOR_NAMES[floor]}")
			'm' -> send("joininstance MASTER_CATACOMBS_FLOOR_${FLOOR_NAMES[floor]}")
		}
	}

	fun describe(): List<String> = listOf(
		"§8[Cryptic] §7Party leader: §f${leader ?: "not known"}" +
			if (refusedAsLeader) " §7(Hypixel has said it is not you)" else "",
		"§8[Cryptic] §7Known members: §f${members.ifEmpty { listOf("none") }.joinToString(", ")}",
		"§8[Cryptic] §7Leader commands would ${if (mayLead()) "§abe tried" else "§cbe left alone"}§7. " +
			"Breaks asked for: §f${breaks.size}§7, waiting to send: §f${queue.size}",
	)
}
