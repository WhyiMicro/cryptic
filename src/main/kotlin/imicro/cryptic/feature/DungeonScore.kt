package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.DungeonStats
import imicro.cryptic.dungeon.MayorPaul
import imicro.cryptic.dungeon.map.DungeonFloor
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.TextModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/**
 * Works out the score the run is heading for, and says so.
 *
 * Ported from dtMap (BSD 3-Clause, Copyright (c) 2026 rice.who), whose score
 * arithmetic came from Odin (BSD 3-Clause, Copyright (c) 2026 odtheking); the
 * full licence is in `licenses/dtMap-LICENSE.txt`. Hypixel only tells you the
 * score once the run is over, by which point knowing it is no use — the numbers
 * it is made of are all published while you play, and [DungeonStats] collects
 * them.
 *
 * The line can sit under the map or anywhere else on the HUD, and can announce
 * itself to the party when it crosses 270 or 300.
 */
object DungeonScore {
	@JvmField
	val placement = DropdownModuleSetting(
		id = "placement",
		label = "Placement",
		options = listOf("Under the map", "Its own HUD element"),
		description = "Whether the score follows the map or is placed on its own.",
	)

	/** True while the score is drawn by the map rather than by its own element. */
	private val attached: Boolean get() = placement.selectedIndex == 0

	@JvmField
	val paulMode = DropdownModuleSetting(
		id = "paul",
		label = "Mayor Paul",
		options = listOf("Auto", "Always", "Never"),
		description = "Paul's EZPZ perk is worth ten bonus score. Auto asks Hypixel who won.",
	)

	private val bossSection = SectionModuleSetting("boss_section", "In boss")

	@JvmField
	val showInBoss = ToggleModuleSetting(
		id = "show_in_boss",
		label = "Show in boss",
		defaultValue = true,
		description = "The map goes away in the boss room; this decides whether the score follows it.",
	)

	@JvmField
	val minimalInBoss = ToggleModuleSetting(
		id = "minimal_in_boss",
		label = "Just the number",
		defaultValue = false,
		description = "In the boss, drops to \"Score: 300\" — nothing on the second line can still change.",
		visibleIf = { showInBoss.value },
	)

	private val messagesSection = SectionModuleSetting("messages_section", "Party announcements")

	@JvmField
	val announce270 = ToggleModuleSetting(
		id = "announce_270",
		label = "Announce 270",
		description = "Sends a party message the first time the run is worth 270.",
	)

	@JvmField
	val message270 = TextModuleSetting(
		id = "message_270",
		label = "270 message",
		defaultValue = "270",
		maxLength = 100,
		visibleIf = { announce270.value },
	)

	@JvmField
	val announce300 = ToggleModuleSetting(
		id = "announce_300",
		label = "Announce 300",
		description = "Sends a party message the first time the run is worth 300.",
	)

	@JvmField
	val message300 = TextModuleSetting(
		id = "message_300",
		label = "300 message",
		defaultValue = "300",
		maxLength = 100,
		visibleIf = { announce300.value },
	)

	private val configurableSettings = listOf(
		placement,
		paulMode,
		showInBoss,
		minimalInBoss,
		announce270,
		message270,
		announce300,
		message300,
	)

	private val reset = ButtonModuleSetting("reset", "Reset", action = {
		configurableSettings.forEach {
			when (it) {
				is ToggleModuleSetting -> it.reset()
				is TextModuleSetting -> it.reset()
				is DropdownModuleSetting -> it.reset()
				else -> Unit
			}
		}
	})

	@JvmField
	val module = Module(
		id = "dungeon_score",
		name = "Dungeon Score",
		description = "Tracks the score your run is heading for",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(
			SectionModuleSetting("score_section", "Score"),
			placement,
			paulMode,
			bossSection,
			showInBoss,
			minimalInBoss,
			messagesSection,
			announce270,
			message270,
			announce300,
			message300,
			reset,
		),
	)

	private var initialized = false
	private var sent270 = false
	private var sent300 = false

	val element: HudElement = ScoreElement()

	fun initialize() {
		if (initialized) return
		initialized = true

		Hud.register(element)
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> resetRun() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> resetRun() }
	}

	private fun resetRun() {
		sent270 = false
		sent300 = false
	}

	/** Announces the run's first 270 and first 300, if either is asked for. */
	fun tick(client: Minecraft) {
		if (!module.enabled || !DungeonLocation.inDungeon) return
		val score = DungeonStats.score

		if (announce270.value && !sent270 && score >= 270) {
			sent270 = true
			announce(client, message270.value)
		}

		if (announce300.value && !sent300 && score >= 300) {
			sent300 = true
			announce(client, message300.value)
		}
	}

	private fun announce(client: Minecraft, text: String) {
		val message = text.trim()
		if (message.isEmpty()) return

		// The debug switch shows the announcement to the player instead of
		// sending it, so the wording can be checked without a party.
		if (DebugOverrides.previewScoreMessage) {
			client.gui.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §7Party chat: §f$message"))
			return
		}

		client.connection?.sendCommand("pc $message")
	}

	/** Shows the line the announcements would send, for `/cryptic debug score`. */
	fun preview(client: Minecraft) {
		client.gui.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §f${secretsLine()}   ${bonusLine()}"))
	}

	/**
	 * True while the score has anything to draw at all.
	 *
	 * The boss room is the one place it can be switched off on its own, because
	 * it is the one place the map is not there to switch off with it.
	 */
	private val wanted: Boolean
		get() = module.enabled && DungeonLocation.inDungeon &&
			(!DungeonRun.inBoss || showInBoss.value)

	/** True while the boss has made the second line's numbers final. */
	private val minimal: Boolean get() = DungeonRun.inBoss && minimalInBoss.value

	/** How much taller the map element is because the score sits under it. */
	fun attachedHeight(): Int =
		if (module.enabled && attached) Minecraft.getInstance().font.lineHeight * lineCount() + 5 else 0

	private fun lineCount(): Int = if (minimal) 1 else 2

	/**
	 * True while the map element is carrying the score.
	 *
	 * The map hides itself in the boss room but the score need not, so the
	 * element stays on screen for it even once its floor is gone.
	 */
	fun attachedVisible(): Boolean = wanted && attached

	/** Drawn by the map element, centred under it, when that is where it lives. */
	fun renderAttached(context: GuiGraphicsExtractor, width: Int, height: Int) {
		if (!attachedVisible()) return
		drawLines(context, width / 2, height + 3, firstLine(), secondLine())
	}

	private fun firstLine(): String = if (minimal) minimalLine() else secretsLine()

	private fun secondLine(): String = if (minimal) "" else bonusLine()

	/** Everything the second line tracks is settled by the boss, so it goes. */
	private fun minimalLine(): String {
		val score = DungeonStats.score
		val color = if (score < 270) "c" else if (score < 300) "e" else "a"
		return "§7Score: §$color$score"
	}

	fun renderAttachedExample(context: GuiGraphicsExtractor, width: Int, height: Int) {
		if (!module.enabled || !attached) return
		drawLines(context, width / 2, height + 3, EXAMPLE_SECRETS, EXAMPLE_BONUS)
	}

	private fun drawLines(context: GuiGraphicsExtractor, centerX: Int, top: Int, first: String, second: String) {
		val font = Minecraft.getInstance().font
		context.centeredText(font, first, centerX, top, 0xFFFFFFFF.toInt())
		if (second.isNotEmpty()) {
			context.centeredText(font, second, centerX, top + font.lineHeight + 1, 0xFFFFFFFF.toInt())
		}
	}

	/** Found, still needed, and how many the floor holds, then the score itself. */
	private fun secretsLine(): String {
		val score = DungeonStats.score
		val color = if (score < 270) "c" else if (score < 300) "e" else "a"
		return "§b${DungeonStats.secretsFound}§7-§e${DungeonStats.missingSecrets}§7-" +
			"§c${DungeonStats.totalSecrets}   §$color$score"
	}

	/**
	 * The bonus points still on the table, and nothing else.
	 *
	 * Everything already earned is in the score above, so the second line only
	 * lists what is missing: deaths taken, the mimic and prince not yet killed,
	 * bats short of five, and crypts short of five.
	 */
	private fun bonusLine(): String = buildString {
		// Deaths are a penalty rather than a task, so nothing is owed at zero
		// and the entry stays out of the way.
		if (DungeonStats.deaths > 0) append("§7D: §c${DungeonStats.deaths}   ")
		if (DungeonLocation.floor >= 6) append("§7M: ${tick(DungeonStats.mimicKilled)}   ")
		if (princePossible()) append("§7P: ${tick(DungeonStats.princeKilled)}   ")
		append("§7B: ${count(DungeonStats.batCount, 5)}   ")
		append("${count(DungeonStats.crypts, 5)}§7/§a5")
	}.trim()

	/** A bonus either earned or still owed; earned ones stay on as a green tick. */
	private fun tick(done: Boolean): String = if (done) "§a✔" else "§c✖"

	/** The same for the two that are counted rather than simply done. */
	private fun count(found: Int, needed: Int): String =
		if (found >= needed) "§a$found" else "§c$found"

	/** A Prince only spawns in certain rooms, which the world scan can name. */
	private fun princePossible(): Boolean = DungeonFloor.rooms.any { it.data?.prince == true }

	private const val EXAMPLE_SECRETS = "§b10§7-§e12§7-§c55   §a300"
	private const val EXAMPLE_BONUS = "§7D: §c1   §7M: §a✔   §7P: §c✖   §7B: §c3   §c0§7/§a5"

	private class ScoreElement : HudElement("dungeon_score", "Dungeon Score", 0.02, 0.45, 1.0) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int get() = font.width(EXAMPLE_BONUS)
		override val height: Int get() = font.lineHeight * 2 + 1

		override fun isVisible(): Boolean = wanted && !attached

		override fun render(context: GuiGraphicsExtractor) {
			drawLines(context, width / 2, 0, firstLine(), secondLine())
		}

		override fun renderExample(context: GuiGraphicsExtractor) {
			if (attached) return
			drawLines(context, width / 2, 0, EXAMPLE_SECRETS, EXAMPLE_BONUS)
		}
	}
}
