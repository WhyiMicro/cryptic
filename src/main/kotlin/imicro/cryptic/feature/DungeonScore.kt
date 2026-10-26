package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.DungeonStats
import imicro.cryptic.dungeon.MayorPaul
import imicro.cryptic.dungeon.map.DungeonFloor
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.TextModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/**
 * The score the run is heading for. Hypixel only shows it at the end, but publishes every number
 * it's made of during the run ([DungeonStats]). Ported from dtMap (score maths from Odin).
 *
 * No module of its own: drawing settings are on the map's card, announcements on Room Alerts, each
 * gated by that module.
 */
object DungeonScore {
	private const val TITLE_FADE_IN_TICKS = 0
	private const val TITLE_STAY_TICKS = 25
	private const val TITLE_FADE_OUT_TICKS = 10

	@JvmField
	val placement = DropdownModuleSetting(
		id = "placement",
		label = "Placement",
		options = listOf("Under the map", "Its own HUD element"),
		description = "Whether the score follows the map or is placed on its own.",
	)

	/** True while the map draws the score instead of its own element. */
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
		label = "Show score in boss",
		defaultValue = true,
		description = "The map is not drawn in the boss room. This keeps the score line up there on its own.",
	)

	@JvmField
	val minimalInBoss = ToggleModuleSetting(
		id = "minimal_in_boss",
		label = "Just the number",
		defaultValue = false,
		description = "In the boss, shows only the score. Nothing else can still change there.",
		visibleIf = { showInBoss.value },
	)

	@JvmField
	val showAtEnd = ToggleModuleSetting(
		id = "show_at_end",
		label = "Show final rating",
		defaultValue = true,
		description = "Once the run is over, draws Hypixel's own score screen — the grade and the final number — where the map was.",
	)

	@JvmField
	val announce270 = ToggleModuleSetting(
		id = "announce_270",
		label = "Announce 270",
		description = "Sends a party message the first time the run is worth 270.",
	)

	@JvmField
	val announce300 = ToggleModuleSetting(
		id = "announce_300",
		label = "Announce 300",
		description = "Sends a party message the first time the run is worth 300.",
	)

	@JvmField
	val announceMimic = ToggleModuleSetting(
		id = "announce_mimic",
		label = "Announce mimic",
		description = "Sends a party message when the mimic dies.",
	)

	@JvmField
	val announcePrince = ToggleModuleSetting(
		id = "announce_prince",
		label = "Announce prince",
		description = "Sends a party message when a Prince dies.",
	)

	@JvmField
	val announceBat = ToggleModuleSetting(
		id = "announce_bat",
		label = "Announce bat",
		description = "A party message for every bat you kill.",
	)

	/** A text field for one alert's wording, shown while that alert is on. */
	private fun wording(id: String, default: String, owner: ToggleModuleSetting, title: Boolean) = TextModuleSetting(
		id = id,
		label = if (title) "Title" else "Message",
		defaultValue = default,
		maxLength = 100,
		hint = default,
		description = if (title) "What the title says. & colour codes work." else "What is sent to party chat.",
		visibleIf = { owner.value },
	)

	private val announce270Text = wording("announce_270_text", "270 score", announce270, title = false)
	private val announce300Text = wording("announce_300_text", "300 score", announce300, title = false)
	private val announceMimicText = wording("announce_mimic_text", "Mimic killed", announceMimic, title = false)
	private val announcePrinceText = wording("announce_prince_text", "Prince killed", announcePrince, title = false)
	private val announceBatText = wording("announce_bat_text", "Bat killed", announceBat, title = false)

	@JvmField
	val title270 = ToggleModuleSetting(
		id = "title_270",
		label = "270 title",
		defaultValue = true,
		description = "Puts the score on your own screen the first time the run is worth 270.",
	)

	@JvmField
	val title300 = ToggleModuleSetting(
		id = "title_300",
		label = "300 title",
		defaultValue = true,
		description = "The same at 300.",
	)

	private val title270Text = wording("title_270_text", "270", title270, title = true)
	private val title300Text = wording("title_300_text", "300", title300, title = true)

	/** The score's settings, listed on [DungeonMap]'s card. */
	val scoreSettings: List<ModuleSetting> = listOf(
		SectionModuleSetting("score_section", "Score"),
		placement,
		paulMode,
		bossSection,
		showInBoss.withMore(minimalInBoss),
		showAtEnd,
	)

	/** The announcements, listed on [RoomAlerts]' card. */
	val announcementSettings: List<ModuleSetting> = listOf(
		announceMimic.withMore(announceMimicText),
		announcePrince.withMore(announcePrinceText),
		announceBat.withMore(announceBatText),
		announce270.withMore(announce270Text),
		announce300.withMore(announce300Text),
	)

	val titleSettings: List<ModuleSetting> = listOf(title270.withMore(title270Text), title300.withMore(title300Text))

	fun resetSettings() {
		(scoreSettings + announcementSettings + titleSettings).flatMap { listOf(it) + it.more }.forEach {
			when (it) {
				is ToggleModuleSetting -> it.reset()
				is TextModuleSetting -> it.reset()
				is DropdownModuleSetting -> it.reset()
				else -> Unit
			}
		}
	}

	private var initialized = false
	private var sent270 = false
	private var sent300 = false

	/** Whether the 300 title was shown, separate from [sent300] since a teammate's mod may say it first. */
	private var shown300Title = false

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
		shown300Title = false
	}

	/**
	 * A teammate's mod announcing 300. Their sum may land first, so the title shows now; our own
	 * party message still waits for our own sum.
	 */
	fun onTeammateReached300() {
		if (!RoomAlerts.module.enabled || !DungeonLocation.inDungeon || shown300Title) return
		shown300Title = true
		if (title300.value) showTitle(Minecraft.getInstance(), colored(title300Text.value, "§a"))
	}

	/**
	 * Announces the run's first 270 and 300. The crossing is tracked regardless, so the message and
	 * title can't swallow each other's moment.
	 */
	fun tick(client: Minecraft) {
		if (!RoomAlerts.module.enabled || !DungeonLocation.inDungeon) return
		val score = DungeonStats.score

		if (!sent270 && score >= 270) {
			sent270 = true
			if (announce270.value) announce(client, announce270Text.value)
			if (title270.value) showTitle(client, colored(title270Text.value, "§e"))
		}

		if (!sent300 && score >= 300) {
			sent300 = true
			if (announce300.value) announce(client, announce300Text.value)
			if (!shown300Title) {
				shown300Title = true
				if (title300.value) showTitle(client, colored(title300Text.value, "§a"))
			}
		}
	}

	/** Puts the score on your screen briefly; separate from the party message, which only goes once. */
	private fun showTitle(client: Minecraft, text: String) {
		client.gui.hud.setTimes(TITLE_FADE_IN_TICKS, TITLE_STAY_TICKS, TITLE_FADE_OUT_TICKS)
		client.gui.hud.setTitle(Component.literal(text))
		// The same sound as the room titles on this card.
		RoomAlerts.playAlertSound(client)
	}

	/**
	 * The mimic dying, announced once. Called from [imicro.cryptic.dungeon.DungeonStats], since
	 * there's no chat line for it.
	 */
	fun onMimicKilled() {
		if (!RoomAlerts.module.enabled || !announceMimic.value) return
		announce(Minecraft.getInstance(), announceMimicText.value)
	}

	/** A Prince dying, which Hypixel tells only you. */
	fun onPrinceKilled() {
		if (!RoomAlerts.module.enabled || !announcePrince.value) return
		announce(Minecraft.getInstance(), announcePrinceText.value)
	}

	/** The run's first bat, worth one bonus point; later ones are worth nothing. */
	fun onBatKilled() {
		if (!RoomAlerts.module.enabled || !announceBat.value) return
		announce(Minecraft.getInstance(), announceBatText.value)
	}

	/** A title as typed, `&` codes converted, with [default] in front when it has no colour. */
	fun colored(text: String, default: String): String {
		val coded = text.replace(COLOR_CODE) { "§" + it.groupValues[1] }
		return if (coded.startsWith("§")) coded else default + coded
	}

	private val COLOR_CODE = Regex("&([0-9a-fk-orA-FK-OR])")

	private fun announce(client: Minecraft, text: String) {
		val message = text.trim()
		if (message.isEmpty()) return

		// The debug switch shows the announcement instead of sending it.
		if (DebugOverrides.previewScoreMessage) {
			client.gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §7Party chat: §f$message"))
			return
		}

		client.connection?.sendCommand("pc $message")
	}

	fun preview(client: Minecraft) {
		client.gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §f${secretsLine()}   ${bonusLine()}"))
	}

	/**
	 * True while the score has anything to draw. The boss room can switch it off on its own, since
	 * the map isn't there.
	 */
	private val wanted: Boolean
		get() = DungeonMap.module.enabled && DungeonLocation.inDungeon &&
			(!DungeonRun.inBoss || showInBoss.value || (DungeonRun.ended && showAtEnd.value))

	/** True once the boss has made the second line's numbers final. */
	private val minimal: Boolean get() = DungeonRun.inBoss && minimalInBoss.value

	fun attachedHeight(): Int =
		if (DungeonMap.module.enabled && attached) Minecraft.getInstance().font.lineHeight * lineCount() + 5 else 0

	private fun lineCount(): Int = if (minimal) 1 else 2

	/**
	 * True while the map element carries the score. The map hides in the boss room but the score
	 * need not, so the element stays.
	 */
	fun attachedVisible(): Boolean = wanted && attached

	/** Drawn centred under the map element when it lives there. */
	fun renderAttached(context: GuiGraphicsExtractor, width: Int, height: Int) {
		if (!attachedVisible()) return
		drawLines(context, width / 2, height + 3, firstLine(), secondLine())
	}

	private fun firstLine(): String = if (minimal) minimalLine() else secretsLine()

	private fun secondLine(): String = if (minimal) "" else bonusLine()

	private fun minimalLine(): String {
		val score = DungeonStats.score
		val color = if (score < 270) "c" else if (score < 300) "e" else "a"
		return "§7Score: §$color$score"
	}

	fun renderAttachedExample(context: GuiGraphicsExtractor, width: Int, height: Int) {
		if (!DungeonMap.module.enabled || !attached) return
		drawLines(context, width / 2, height + 3, EXAMPLE_SECRETS, EXAMPLE_BONUS)
	}

	private fun drawLines(context: GuiGraphicsExtractor, centerX: Int, top: Int, first: String, second: String) {
		val font = Minecraft.getInstance().font
		context.centeredText(font, first, centerX, top, 0xFFFFFFFF.toInt())
		if (second.isNotEmpty()) {
			context.centeredText(font, second, centerX, top + font.lineHeight + 1, 0xFFFFFFFF.toInt())
		}
	}

	/** Found, still needed, and the floor's total, then the score. */
	private fun secretsLine(): String {
		val score = DungeonStats.score
		val color = if (score < 270) "c" else if (score < 300) "e" else "a"
		return "§b${DungeonStats.secretsFound}§7-§e${DungeonStats.missingSecrets}§7-" +
			"§c${DungeonStats.totalSecrets}   §$color$score"
	}

	/**
	 * The bonus points still available: deaths taken, mimic and prince not yet killed, crypts short
	 * of five. Each entry disappears once done, so the line answers "what's left". The bat isn't
	 * listed; it counts as a secret.
	 */
	private fun bonusLine(): String = buildString {
		// Deaths are a penalty, so nothing shows at zero.
		if (DungeonStats.deaths > 0) append("§7D: §c${DungeonStats.deaths}   ")
		if (DungeonLocation.floor >= 6 && !DungeonStats.mimicKilled) append("§7M: §c✖   ")
		if (princePossible() && !DungeonStats.princeKilled) append("§7P: §c✖   ")
		if (DungeonStats.crypts < MAX_CRYPTS) append("§c${DungeonStats.crypts}§7/§a$MAX_CRYPTS")
	}.trim()

	/** Crypts that count; more are worth nothing. */
	private const val MAX_CRYPTS = 5

	/** A Prince only spawns in certain rooms, which the scan can name. */
	private fun princePossible(): Boolean = DungeonFloor.rooms.any { it.data?.prince == true }

	private const val EXAMPLE_SECRETS = "§b10§7-§e12§7-§c55   §a300"
	private const val EXAMPLE_BONUS = "§7D: §c1   §7M: §a✔   §7P: §c✖   §c0§7/§a5"

	private class ScoreElement : HudElement("dungeon_score", "Dungeon Score", 0.02, 0.45, 1.0) {
		override val layer get() = imicro.cryptic.gui.ModuleCategory.DUNGEON

		private val font get() = Minecraft.getInstance().font

		override val width: Int get() = font.width(EXAMPLE_BONUS)
		override val height: Int get() = font.lineHeight * 2 + 1

		override fun isVisible(): Boolean = wanted && !attached

		/** Following the map, the score has no placement of its own. */
		override fun showInEditor(): Boolean = DungeonMap.module.enabled && !attached

		override fun render(context: GuiGraphicsExtractor) {
			drawLines(context, width / 2, 0, firstLine(), secondLine())
		}

		override fun renderExample(context: GuiGraphicsExtractor) {
			if (attached) return
			drawLines(context, width / 2, 0, EXAMPLE_SECRETS, EXAMPLE_BONUS)
		}
	}
}
