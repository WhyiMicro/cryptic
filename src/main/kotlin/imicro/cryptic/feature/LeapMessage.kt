package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.TextModuleSetting
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Announces in party chat who you just leaped to.
 *
 * Ported from Blade Addons (MIT). Hypixel confirms a leap with a game message
 * naming the target, which is the only signal the client gets, so that message
 * is what triggers the announcement.
 */
object LeapMessage {
	/** Hypixel's confirmation, e.g. "You have teleported to Steve!". */
	private val leapPattern = Regex("^You have teleported to (.*)!$")

	/** Party chat is Hypixel's own command, not a Minecraft one. */
	private const val PARTY_CHAT_COMMAND = "pc"

	const val PLAYER_TOKEN = "<player>"

	@JvmField
	val message = TextModuleSetting(
		id = "message",
		label = "Message ($PLAYER_TOKEN becomes their name)",
		defaultValue = "Leaped to $PLAYER_TOKEN!",
		maxLength = 200,
		hint = "Leaped to $PLAYER_TOKEN!",
	)

	@JvmField
	val module = Module(
		id = "leap_message",
		name = "Leap Message",
		description = "Says who you leaped to",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(message),
	)

	/**
	 * Hypixel rate-limits commands, and a second announcement for one leap would
	 * only earn a cooldown error, so repeats inside this window are dropped.
	 */
	private const val REPEAT_WINDOW_MS = 3_000L

	private var initialized = false
	private var lastTarget = ""
	private var lastSentAt = 0L

	fun initialize() {
		if (initialized) return
		initialized = true

		ClientReceiveMessageEvents.GAME.register { text, _ -> onGameMessage(text.string) }
	}

	private fun onGameMessage(text: String) {
		if (!module.enabled) return

		// No location check: the only item that produces this message is the
		// Infinileap, which Hypixel refuses to use outside a dungeon.
		val target = leapPattern.find(text.trim())?.groupValues?.get(1) ?: return

		val now = System.currentTimeMillis()
		if (target == lastTarget && now - lastSentAt < REPEAT_WINDOW_MS) return
		lastTarget = target
		lastSentAt = now

		val body = message.value.replace(PLAYER_TOKEN, target).trim()
		if (body.isEmpty()) return

		// Hypixel rejects a command line over 256 characters outright.
		val command = "$PARTY_CHAT_COMMAND $body".take(255)

		// In preview the line is shown to the player instead, which is the only
		// way to read it back without a party to send it to.
		if (DebugOverrides.previewLeapMessage) {
			preview(command)
			return
		}

		Minecraft.getInstance().connection?.sendCommand(command)
	}

	/** Runs one announcement as if [target] had just been leaped to. */
	fun simulate(target: String) {
		lastTarget = ""
		onGameMessage("You have teleported to $target!")
	}

	private fun preview(command: String) {
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(
			Component.literal("Leap Message would send: /$command"),
		)
	}
}
