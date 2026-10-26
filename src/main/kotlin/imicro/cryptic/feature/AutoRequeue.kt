package imicro.cryptic.feature

import imicro.cryptic.Cryptic
import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.DungeonTeam
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Requeues the party for the same floor once a run ends. The score summary is the cue; after a
 * short wait the leader's client sends the queue. Includes Check Party (no requeue with fewer than
 * five, or if someone asked for downtime) and calls it off if someone leaves. From Odin's Dungeon
 * Queue and NoammAddons' Auto Requeue.
 *
 * The leader and the party are followed in chat during the run instead of asking with `/pl` at the
 * end: the run's start line ("Name entered The Catacombs") names the leader, and the party is as
 * many as the tab list showed, minus anyone chat says left.
 *
 * With Wait for ready on, whoever asked for downtime saying `r` restarts the countdown, within five
 * minutes; anyone saying `nr` stops one.
 */
object AutoRequeue {
	private const val COMMAND_AUTO = 0
	private const val COMMAND_REQUEUE = 1

	/** How long after a run a break's end is listened for. */
	private const val READY_WINDOW_MILLIS = 5 * 60 * 1000L

	/**
	 * The least time between this module's last command and the queue. Hypixel refuses a command
	 * too soon after another ("This command is on cooldown!"), and the queue was the one refused.
	 */
	private const val COMMAND_GAP_MILLIS = 1_500L

	/** How long the party list (asked for only when the leader is unknown) may take. */
	private const val LIST_TIMEOUT_MILLIS = 4_000L

	/** What the party list is made of, so its answer can be kept out of chat. */
	private val listLines = listOf(
		Regex("""^-{5,}$"""),
		Regex("""^Party Members \(\d+\)$"""),
		Regex("""^Party (?:Leader|Moderators|Members): """),
		Regex("""^You are not currently in a party\.$"""),
	)

	private var listAskedAt = 0L

	private var askedThisRun = false

	/** Until when a party list answer is kept out of chat, and whether its heading has been seen. */
	@Volatile
	private var hideListUntil = 0L
	private var hideSawHeading = false

	/**
	 * Whether a chat message is the party list answer this module asked for, kept out of chat.
	 * Asked after the rest of Cryptic has read it.
	 */
	@JvmStatic
	fun hidesChat(message: Component, overlay: Boolean): Boolean {
		if (overlay || hideListUntil == 0L || System.currentTimeMillis() > hideListUntil) return false
		val lines = message.string.split('\n').map { it.replace(FORMATTING, "").trim() }.filter { it.isNotEmpty() }
		if (lines.isEmpty() || !lines.all { line -> listLines.any { it.containsMatchIn(line) } }) return false
		val heading = lines.any { listLines[1].matches(it) || listLines[3].matches(it) }
		val rule = lines.any { listLines[0].matches(it) }
		// The closing rule after the heading ends it.
		if (heading) hideSawHeading = true
		if (hideSawHeading && rule && (lines.size > 1 || !heading)) hideListUntil = 0L
		return true
	}

	private const val PARTY_SIZE = 5

	private val FORMATTING = Regex("§.")
	private const val RANK = """(?:\[[^\]]+] )?"""

	/** "Party > [MVP+] Name: what they said", with an optional emblem. */
	private val partyChat = Regex("""^Party > $RANK(\w{1,16})(?: [^\s:]+)?: ?(.*)$""")

	private val downtimeCommand = Regex("""^!(?:dt|downtime)(?:\s|$)""", RegexOption.IGNORE_CASE)
	private val undowntimeCommand = Regex("""^!(?:undt|undowntime)(?:\s|$)""", RegexOption.IGNORE_CASE)

	/** Someone leaving or the party ending, which calls a requeue off. */
	private val memberGone = listOf(
		Regex("""^$RANK(\w{1,16}) has left the party\."""),
		Regex("""^$RANK(\w{1,16}) has been removed from the party\."""),
		Regex("""^$RANK(\w{1,16}) was removed from your party because they disconnected"""),
		Regex("""^Kicked $RANK(\w{1,16}) because they were offline"""),
		// The leader leaving shows only as the party changing hands.
		Regex("""^The party was transferred to $RANK\w{1,16} because $RANK(\w{1,16}) left"""),
	)
	private val partyGone = listOf(
		Regex("""^You left the party"""),
		Regex("""^You have been kicked from the party"""),
		Regex("""^The party was disbanded"""),
		Regex("""^$RANK\w{1,16} has disbanded the party"""),
	)

	/** Hypixel's floor names, as its queue command takes them. */
	private val FLOOR_NAMES = listOf("ENTRANCE", "ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN")

	@JvmField
	val delay = SliderModuleSetting(
		id = "delay",
		label = "Delay",
		unit = "s",
		defaultValue = 5.0,
		min = 0.0,
		max = 30.0,
		step = 1.0,
		description = "How long after the run ends, or after the last person is ready, the party is queued.",
	)

	@JvmField
	val command = DropdownModuleSetting(
		id = "command",
		label = "Command",
		options = listOf("Auto", "/instancerequeue", "/joininstance"),
		defaultIndex = COMMAND_AUTO,
		description = "How the queue is asked for. Auto uses /instancerequeue while you're still in the dungeon and /joininstance for the floor once you've left.",
	)

	@JvmField
	val checkParty = ToggleModuleSetting(
		id = "check_party",
		label = "Check party",
		defaultValue = true,
		description = "Only requeues with all five still in the party: not with fewer, and not once somebody has left.",
	)

	@JvmField
	val waitForReady = ToggleModuleSetting(
		id = "wait_for_ready",
		label = "Requeue when ready",
		defaultValue = true,
		description = "After somebody asks for downtime with !dt, queues again once they say r in party chat — " +
			"for up to five minutes after the run, and not if anybody leaves.",
	)

	@JvmField
	val feedback = ToggleModuleSetting(
		id = "feedback",
		label = "Party feedback",
		defaultValue = true,
		description = "Says in party chat whether it is about to requeue, or why not. Off, it only tells you.",
	)

	@JvmField
	val hideQueueScreen = ToggleModuleSetting(
		id = "hide_queue_screen",
		label = "Hide queue screen",
		defaultValue = false,
		description = "Closes the \"Creating instance\" menu Hypixel opens after a queue, so you can keep moving while the dungeon starts.",
	)

	@JvmField
	val module = Module(
		id = "auto_requeue",
		name = "Auto Requeue",
		description = "Requeues after a run",
		category = ModuleCategory.DUNGEON,
		supportsKeybind = false,
		settings = listOf(delay, command, checkParty, waitForReady, feedback, hideQueueScreen),
	)

	/** Who asked for downtime this run, until they say ready. */
	private val needsDowntime = LinkedHashSet<String>()

	/** What each of them gave as the reason. */
	private val reasons = HashMap<String, String>()

	private fun downtimeMessage(names: Collection<String>): String =
		names.joinToString(" | ") { "$it needs downtime. Reason: ${reasons[it] ?: "no reason given"}" }

	/**
	 * Who wanted downtime when the last run ended, the break that end answered. Only these are
	 * released when the next run starts; a `!dt` sent after the end is for the run about to begin.
	 */
	private val downtimeAtEnd = HashSet<String>()

	private val leftDuringRun = LinkedHashSet<String>()

	/**
	 * The most players the tab list has shown this run, which is the party (the list fills in over
	 * the first seconds, and disconnected players drop off).
	 */
	private var teamSeen = 0

	private var lastCommandAt = 0L

	/** The floor just played, for /joininstance. */
	private var floorCommand: String? = null

	/** When the queue command goes out, or 0. */
	private var requeueAt = 0L

	/** Until when a break's end is listened for, or 0. */
	private var readyUntil = 0L

	private var runEndHandled = false
	private var runStartSeen = false

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		// Only on disconnect: moving from the dungeon to the hub is a server change the countdown
		// must survive.
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
	}

	private fun reset() {
		needsDowntime.clear()
		reasons.clear()
		downtimeAtEnd.clear()
		leftDuringRun.clear()
		requeueAt = 0L
		readyUntil = 0L
		runEndHandled = false
		runStartSeen = false
	}

	private fun self(): String? = Minecraft.getInstance().player?.name?.string

	fun tick(client: Minecraft) {
		if (!module.enabled) {
			requeueAt = 0L
			readyUntil = 0L
			return
		}

		// A new run resets who asked for what, except a break asked for since the last run ended.
		if (DungeonRun.started && !runStartSeen) {
			runStartSeen = true
			runEndHandled = false
			needsDowntime.removeAll(downtimeAtEnd)
			downtimeAtEnd.clear()
			leftDuringRun.clear()
			teamSeen = 0
			askedThisRun = false
			listAskedAt = 0L
			requeueAt = 0L
			readyUntil = 0L
		}
		if (!DungeonRun.started) runStartSeen = false
		if (DungeonRun.started && !runEndHandled && DungeonLocation.inDungeon) {
			teamSeen = maxOf(teamSeen, DungeonTeam.classes.size)
		}

		if (DungeonRun.ended && !runEndHandled && DungeonLocation.inDungeon) {
			runEndHandled = true
			onRunEnded()
		}

		val now = System.currentTimeMillis()
		// The party list, asked for because the leader wasn't known.
		if (listAskedAt != 0L) {
			val answered = PartyFeatures.listedAt >= listAskedAt
			val noParty = PartyFeatures.noPartyAt >= listAskedAt
			when {
				answered -> {
					listAskedAt = 0L
					decide()
				}
				noParty -> {
					listAskedAt = 0L
					note("Not requeueing: you are not in a party.")
				}
				now - listAskedAt > LIST_TIMEOUT_MILLIS -> {
					listAskedAt = 0L
					note("Not requeueing: the party list never answered, so who leads is not known.")
				}
			}
		}
		if (readyUntil != 0L && now > readyUntil) {
			readyUntil = 0L
			needsDowntime.clear()
			tell("Stopped waiting for ready, five minutes are up. No requeue.")
		}
		if (requeueAt != 0L && now >= requeueAt) {
			requeueAt = 0L
			fire(client)
		}
	}

	private fun onRunEnded() {
		downtimeAtEnd.clear()
		downtimeAtEnd += needsDowntime
		floorCommand = DungeonLocation.floor.takeIf { it in FLOOR_NAMES.indices }?.let {
			"joininstance ${if (DungeonLocation.masterMode) "MASTER_" else ""}CATACOMBS_FLOOR_${FLOOR_NAMES[it]}"
		}
		decide()
	}

	/** The run is over: requeue, wait for a break, or say why not. */
	private fun decide() {
		val leader = PartyFeatures.leaderName()
		if (leader == null && !DebugOverrides.previewPartyCommands) {
			// Chat didn't name the leader (joined late, reconnected), so the list is asked for once
			// and the decision waits.
			if (listAskedAt == 0L && !askedThisRun) {
				askedThisRun = true
				listAskedAt = System.currentTimeMillis()
				hideListUntil = listAskedAt + LIST_TIMEOUT_MILLIS + 1_000L
				hideSawHeading = false
				send(Minecraft.getInstance(), "pl")
				return
			}
			note("Not requeueing: who leads the party is not known.")
			return
		}
		if (!leading()) {
			note("Not requeueing: $leader leads the party.")
			return
		}
		blocker()?.let {
			tell("Not requeueing: $it.")
			return
		}
		if (needsDowntime.isNotEmpty()) {
			if (waitForReady.value) readyUntil = System.currentTimeMillis() + READY_WINDOW_MILLIS
			tell(downtimeMessage(needsDowntime))
			return
		}
		schedule("Requeueing in ${seconds()} seconds. Write !dt or nr to stop the que")
	}

	/** What blocks a requeue, worded for the party, or null. */
	private fun blocker(): String? {
		if (!checkParty.value) return null
		leftDuringRun.firstOrNull()?.let { return "$it left the party" }
		// The team at its fullest; nobody joins mid-run, and leavers are caught above.
		val size = maxOf(teamSeen, DungeonTeam.classes.size)
		if (size in 1 until PARTY_SIZE) return "only $size/$PARTY_SIZE in the party"
		return null
	}

	/**
	 * Whether you lead the party, as last seen. Only a known yes counts; the debug preview lets an
	 * unknown leader pass.
	 */
	private fun leading(): Boolean {
		val leader = PartyFeatures.leaderName() ?: return DebugOverrides.previewPartyCommands
		return leader.equals(self(), ignoreCase = true)
	}

	private fun seconds(): Int = delay.value.toInt()

	private fun schedule(message: String) {
		tell(message)
		// After the message, so the gap is measured from it.
		requeueAt = maxOf(System.currentTimeMillis() + seconds() * 1000L, lastCommandAt + COMMAND_GAP_MILLIS)
	}

	private fun fire(client: Minecraft) {
		if (!leading()) {
			note("Not requeueing: ${PartyFeatures.leaderName() ?: "somebody else"} leads the party now.")
			return
		}
		blocker()?.let {
			tell("Not requeueing: $it.")
			return
		}
		val queue = when (command.selectedIndex) {
			COMMAND_AUTO -> if (DungeonLocation.inDungeon) "instancerequeue" else floorCommand ?: "instancerequeue"
			COMMAND_REQUEUE -> "instancerequeue"
			else -> floorCommand ?: "instancerequeue"
		}
		send(client, queue)
	}

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !module.enabled) return
		try {
			for (raw in message.string.split('\n')) {
				val line = raw.replace(FORMATTING, "").trim()
				if (line.isNotEmpty()) onLine(line)
			}
		} catch (error: RuntimeException) {
			Cryptic.LOGGER.error("Auto Requeue could not read a chat line", error)
		}
	}

	private fun onLine(line: String) {
		for (pattern in memberGone) {
			pattern.find(line)?.let { return onLeft(it.groupValues[1]) }
		}
		if (partyGone.any { it.containsMatchIn(line) }) return onLeft(null)

		val (sender, said) = partyChat.find(line)?.destructured ?: return
		val text = said.trim()

		when {
			downtimeCommand.containsMatchIn(text) -> {
				needsDowntime += sender
				reasons[sender] = text.split(Regex("""\s+"""), limit = 2).getOrNull(1)?.trim()?.take(40)?.ifBlank { null } ?: "no reason given"
				// Asked for after the run, while a countdown is running.
				if (requeueAt != 0L) {
					requeueAt = 0L
					downtimeAtEnd += sender
					if (waitForReady.value) readyUntil = System.currentTimeMillis() + READY_WINDOW_MILLIS
					tell(downtimeMessage(listOf(sender)))
				} else if (readyUntil != 0L) {
					// The run's end is still being answered, so this break is part of it.
					downtimeAtEnd += sender
				} else if (runEndHandled) {
					// The queue already went: kept for the end of the run about to start.
					note("$sender needs downtime after the next run; no requeue then.")
				}
			}
			undowntimeCommand.containsMatchIn(text) -> {
				if (needsDowntime.remove(sender)) onReady(sender)
			}
			text.equals("nr", ignoreCase = true) -> {
				if (requeueAt != 0L) {
					requeueAt = 0L
					readyUntil = 0L
					needsDowntime.clear()
					tell("Auto requeue stopped by $sender.")
				}
			}
			text.equals("r", ignoreCase = true) -> {
				if (readyUntil != 0L && needsDowntime.remove(sender)) onReady(sender)
			}
		}
	}

	/** Someone on a break is back; the last one starts the countdown. */
	private fun onReady(name: String) {
		if (readyUntil == 0L) return
		if (needsDowntime.isNotEmpty()) {
			tell("$name ready again, waiting for ${needsDowntime.joinToString(", ")}.")
			return
		}
		readyUntil = 0L
		blocker()?.let {
			tell("$name ready again, but not requeueing: $it.")
			return
		}
		schedule("$name ready again, requeueing in ${seconds()} seconds. Write !dt or nr to stop the que")
	}

	/** Someone left or the party ended: nothing is queued or waited for. */
	private fun onLeft(name: String?) {
		if (name != null) {
			leftDuringRun += name
			// Someone who left isn't on a break; they're why there's no queue.
			needsDowntime.remove(name)
		}
		val wasWaiting = requeueAt != 0L || readyUntil != 0L
		requeueAt = 0L
		readyUntil = 0L
		if (wasWaiting) note("Auto requeue off: ${name?.let { "$it left the party" } ?: "the party ended"}.")
	}

	/** To the party with Party feedback on, otherwise just to you. */
	private fun tell(message: String) {
		if (feedback.value) send(Minecraft.getInstance(), "pc $message") else note(message)
	}

	private fun note(message: String) {
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §7$message"))
	}

	private fun send(client: Minecraft, command: String) {
		lastCommandAt = System.currentTimeMillis()
		if (DebugOverrides.previewPartyCommands) {
			note("Would send: §f/$command")
			return
		}
		// Through Party Features' queue, which spaces Cryptic's commands.
		PartyFeatures.enqueue(command)
	}

	fun describe(): List<String> {
		val now = System.currentTimeMillis()
		return listOf(
			"§8[Cryptic] §7Auto Requeue: module ${if (module.enabled) "§aon" else "§coff"}§7, " +
				"team §f${DungeonTeam.classes.size}§7 (most seen §f$teamSeen§7), leader §f${PartyFeatures.leaderName() ?: "unknown"}§7, floor §f${floorCommand ?: "unknown"}",
			"§8[Cryptic] §7Downtime: §f${needsDowntime.ifEmpty { listOf("nobody") }.joinToString(", ")}§7, " +
				"left: §f${leftDuringRun.ifEmpty { listOf("nobody") }.joinToString(", ")}",
			"§8[Cryptic] §7Requeue in: §f" + (if (requeueAt == 0L) "not counting" else "${(requeueAt - now) / 1000}s") +
				"§7, listening for r: §f" + (if (readyUntil == 0L) "no" else "${(readyUntil - now) / 1000}s left"),
		)
	}
}
