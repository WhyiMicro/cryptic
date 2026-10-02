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
 * Queues the party for the same floor again once a run is over.
 *
 * Odin's Dungeon Queue and NoammAddons' Auto Requeue (BSD 3-Clause, Copyright
 * (c) 2025 odtheking; CC0, Noamm9): the run's score summary is the cue, and
 * after a short wait the leader's client sends the requeue itself. NoammAddons'
 * Check Party is here too — no requeue with fewer than five, or with somebody
 * having asked for downtime — and so is Odin's rule that somebody leaving the
 * party calls it off.
 *
 * Only the leader can queue, so nothing is decided until Hypixel has said who
 * that is: the run ending sends `/pl`, and the list it answers with names the
 * leader and counts the party. Anybody else, or no answer, and it stands down.
 *
 * What neither does is pick the queue back up once the break is over. With
 * Wait for ready on, whoever asked for downtime saying `r` in party chat starts
 * the countdown again, for five minutes after the run; anybody saying `nr`
 * stops a countdown that is running.
 */
object AutoRequeue {
	private const val COMMAND_REQUEUE = 0

	/** How long a break may last before nobody is listening for the end of it. */
	private const val READY_WINDOW_MILLIS = 5 * 60 * 1000L

	/** How long the party list may take to answer, and how long its count is trusted. */
	private const val LIST_TIMEOUT_MILLIS = 4_000L
	private const val LIST_FRESH_MILLIS = 60_000L

	/** A full party, which is what Check party wants to see. */
	private const val PARTY_SIZE = 5

	private val FORMATTING = Regex("§.")
	private const val RANK = """(?:\[[^\]]+] )?"""

	/** "Party > [MVP+] Name: what they said", the emblem after a name optional. */
	private val partyChat = Regex("""^Party > $RANK(\w{1,16})(?: [^\s:]+)?: ?(.*)$""")

	private val downtimeCommand = Regex("""^!(?:dt|downtime)(?:\s|$)""", RegexOption.IGNORE_CASE)
	private val undowntimeCommand = Regex("""^!(?:undt|undowntime)(?:\s|$)""", RegexOption.IGNORE_CASE)

	/** Somebody leaving, or the party ending, either of which calls a requeue off. */
	private val memberGone = listOf(
		Regex("""^$RANK(\w{1,16}) has left the party\."""),
		Regex("""^$RANK(\w{1,16}) has been removed from the party\."""),
		Regex("""^$RANK(\w{1,16}) was removed from your party because they disconnected"""),
		Regex("""^Kicked $RANK(\w{1,16}) because they were offline"""),
	)
	private val partyGone = listOf(
		Regex("""^You left the party"""),
		Regex("""^You have been kicked from the party"""),
		Regex("""^The party was disbanded"""),
		Regex("""^$RANK\w{1,16} has disbanded the party"""),
	)

	/** Hypixel's names for the floors, which is what its queue command takes. */
	private val FLOOR_NAMES = listOf("ENTRANCE", "ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN")

	@JvmField
	val delay = SliderModuleSetting(
		id = "delay",
		label = "Delay (seconds)",
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
		options = listOf("/instancerequeue", "/joininstance"),
		defaultIndex = COMMAND_REQUEUE,
		description = "How the queue is asked for. Once the party has left the dungeon, /joininstance is used either way.",
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
	val module = Module(
		id = "auto_requeue",
		name = "Auto Requeue",
		description = "Queues the same floor again after a run",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(delay, command, checkParty, waitForReady, feedback),
	)

	/** Who asked for downtime this run, until they say they are ready. */
	private val needsDowntime = LinkedHashSet<String>()

	/** Who has left the party since the run began. */
	private val leftDuringRun = LinkedHashSet<String>()

	/** The floor that was just played, for /joininstance. */
	private var floorCommand: String? = null

	/** When /pl was sent at the end of the run, or 0 while no answer is awaited. */
	private var listAskedAt = 0L

	/** When the queue command goes out, or 0 when nothing is counting down. */
	private var requeueAt = 0L

	/** Until when a break's end is listened for, or 0 when it is not. */
	private var readyUntil = 0L

	private var runEndHandled = false
	private var runStartSeen = false

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		// Only the connection ending: the party moving from the dungeon to the
		// hub is a server change, and the countdown has to survive it.
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
	}

	private fun reset() {
		needsDowntime.clear()
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

		// A new run starts the count of who asked for what afresh.
		if (DungeonRun.started && !runStartSeen) {
			runStartSeen = true
			runEndHandled = false
			needsDowntime.clear()
			leftDuringRun.clear()
			requeueAt = 0L
			readyUntil = 0L
		}
		if (!DungeonRun.started) runStartSeen = false

		if (DungeonRun.ended && !runEndHandled && DungeonLocation.inDungeon) {
			runEndHandled = true
			onRunEnded(client)
		}

		val now = System.currentTimeMillis()
		if (listAskedAt != 0L) {
			when {
				PartyFeatures.listedAt >= listAskedAt -> {
					listAskedAt = 0L
					decide()
				}
				PartyFeatures.noPartyAt >= listAskedAt -> {
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

	private fun onRunEnded(client: Minecraft) {
		floorCommand = DungeonLocation.floor.takeIf { it in FLOOR_NAMES.indices }?.let {
			"joininstance ${if (DungeonLocation.masterMode) "MASTER_" else ""}CATACOMBS_FLOOR_${FLOOR_NAMES[it]}"
		}

		// Who leads, and how many are left, asked of Hypixel rather than assumed:
		// only the leader can queue, and the party can have changed during the
		// run without anybody saying so where it could be heard. The answer is
		// read off the list as it arrives, in [tick].
		if (DebugOverrides.previewPartyCommands) {
			note("Would send: §f/pl")
			decide()
			return
		}
		listAskedAt = System.currentTimeMillis()
		send(client, "pl")
	}

	/** The party list has answered: requeue, wait for a break to end, or say why not. */
	private fun decide() {
		if (!leading()) {
			note("Not requeueing: ${PartyFeatures.leaderName() ?: "somebody else"} leads the party.")
			return
		}
		blocker()?.let {
			tell("Not requeueing: $it.")
			return
		}
		if (needsDowntime.isNotEmpty()) {
			val who = needsDowntime.joinToString(", ")
			if (waitForReady.value) {
				readyUntil = System.currentTimeMillis() + READY_WINDOW_MILLIS
				tell("Not requeueing: $who needs downtime. Write r when ready (5 min).")
			} else {
				tell("Not requeueing: $who needs downtime.")
			}
			return
		}
		schedule("Requeueing in ${seconds()} seconds, write \"nr\" to stop auto requeue.")
	}

	/** What stops a requeue outright, as the party would be told it, or null for nothing. */
	private fun blocker(): String? {
		if (!checkParty.value) return null
		leftDuringRun.firstOrNull()?.let { return "$it left the party" }
		// The party list when it has just been read, the run's team otherwise.
		val fresh = System.currentTimeMillis() - PartyFeatures.listedAt < LIST_FRESH_MILLIS
		val size = (if (fresh) PartyFeatures.listedSize else null) ?: DungeonTeam.classes.size
		if (size in 1 until PARTY_SIZE) return "only $size/$PARTY_SIZE in the party"
		return null
	}

	/**
	 * Whether you lead the party, as last seen. Only a known yes counts: a
	 * requeue sent by somebody who does not lead does nothing but get refused.
	 * The debug preview has no party to ask, so there an unknown leader passes.
	 */
	private fun leading(): Boolean {
		val leader = PartyFeatures.leaderName() ?: return DebugOverrides.previewPartyCommands
		return leader.equals(self(), ignoreCase = true)
	}

	private fun seconds(): Int = delay.value.toInt()

	private fun schedule(message: String) {
		requeueAt = System.currentTimeMillis() + seconds() * 1000L
		tell(message)
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
		val queue = if (command.selectedIndex == COMMAND_REQUEUE && DungeonLocation.inDungeon) {
			"instancerequeue"
		} else {
			floorCommand ?: "instancerequeue"
		}
		send(client, queue)
	}

	// ---- Chat ------------------------------------------------------------

	/** A chat packet, on the client thread. */
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
				// Asked for after the run, while the countdown was already going.
				if (requeueAt != 0L) {
					requeueAt = 0L
					if (waitForReady.value) {
						readyUntil = System.currentTimeMillis() + READY_WINDOW_MILLIS
						tell("Requeue stopped: $sender needs downtime. Write r when ready (5 min).")
					} else {
						tell("Requeue stopped: $sender needs downtime.")
					}
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

	/** One of the people on a break is back. The last of them starts the countdown. */
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
		schedule("$name ready again, que starts in ${seconds()} seconds, write \"nr\" to stop auto requeue")
	}

	/** Somebody left the party, or it ended. Either way nobody is queued and nobody is waited for. */
	private fun onLeft(name: String?) {
		if (name != null) leftDuringRun += name
		val wasWaiting = requeueAt != 0L || readyUntil != 0L
		requeueAt = 0L
		readyUntil = 0L
		if (wasWaiting) note("Auto requeue off: ${name?.let { "$it left the party" } ?: "the party ended"}.")
	}

	// ---- Saying so -------------------------------------------------------

	/** To the party if Party feedback is on, otherwise only to you. */
	private fun tell(message: String) {
		if (feedback.value) send(Minecraft.getInstance(), "pc $message") else note(message)
	}

	private fun note(message: String) {
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §7$message"))
	}

	private fun send(client: Minecraft, command: String) {
		if (DebugOverrides.previewPartyCommands) {
			note("Would send: §f/$command")
			return
		}
		client.connection?.sendCommand(command)
	}

	/** For `/cryptic debug requeue`. */
	fun describe(): List<String> {
		val now = System.currentTimeMillis()
		return listOf(
			"§8[Cryptic] §7Auto Requeue: module ${if (module.enabled) "§aon" else "§coff"}§7, " +
				"team §f${DungeonTeam.classes.size}§7, floor §f${floorCommand ?: "unknown"}",
			"§8[Cryptic] §7Downtime: §f${needsDowntime.ifEmpty { listOf("nobody") }.joinToString(", ")}§7, " +
				"left: §f${leftDuringRun.ifEmpty { listOf("nobody") }.joinToString(", ")}",
			"§8[Cryptic] §7Requeue in: §f" + (if (requeueAt == 0L) "not counting" else "${(requeueAt - now) / 1000}s") +
				"§7, listening for r: §f" + (if (readyUntil == 0L) "no" else "${(readyUntil - now) / 1000}s left"),
		)
	}
}
