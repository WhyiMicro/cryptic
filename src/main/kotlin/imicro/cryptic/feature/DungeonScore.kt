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
 *
 * There is no module here any more. The score was a card whose switch only ever
 * wanted to be on whenever the map's was, and whose announcements said the same
 * things [RoomAlerts] was already saying — the mimic ended up on two cards at
 * once. So the drawing settings are listed on the map's card and the
 * announcements on the alerts card, and each half is gated by the module it now
 * belongs to.
 */
object DungeonScore {
	/** Long enough to read a three-digit number, and no longer. */
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
		description = "In the boss, shows only the score. Nothing else can still change there.",
		visibleIf = { showInBoss.value },
	)

	@JvmField
	val showAtEnd = ToggleModuleSetting(
		id = "show_at_end",
		label = "Keep after the run",
		defaultValue = true,
		description = "Leaves the score up once the run has ended.",
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

	private val customSection = SectionModuleSetting("custom_section", "Customize")

	@JvmField
	val customMessages = ToggleModuleSetting(
		id = "custom_messages",
		label = "Custom wording",
		description = "Writes the announcements yourself instead of using the wording below.",
	)

	@JvmField
	val killMessage = TextModuleSetting(
		id = "kill_message",
		label = "Kill message",
		defaultValue = "<mob> killed",
		maxLength = 100,
		description = "Sent for the mimic, a prince and a bat alike. <mob> becomes whichever it was.",
		visibleIf = { customMessages.value },
	)

	@JvmField
	val scoreMessage = TextModuleSetting(
		id = "score_message",
		label = "Score message",
		defaultValue = "<score> score",
		maxLength = 100,
		description = "Sent when the run crosses 270 and 300. <score> becomes the number it crossed.",
		visibleIf = { customMessages.value },
	)

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

	/**
	 * The score's own settings, which [DungeonMap] lists.
	 *
	 * The score has no module of its own any more: it is a line under the map,
	 * drawn from the same numbers the map is, and a card for it on the Dungeon
	 * page was a switch that only ever wanted to be on whenever the map was.
	 * The settings are declared here because this is the code that reads them,
	 * and listed there because that is the card they belong on.
	 */
	val scoreSettings: List<ModuleSetting> = listOf(
		SectionModuleSetting("score_section", "Score"),
		placement,
		paulMode,
		bossSection,
		showInBoss,
		minimalInBoss,
		showAtEnd,
	)

	/**
	 * The announcements, which [RoomAlerts] lists.
	 *
	 * They sat here because the score is what notices a run crossing 270, and
	 * they belong on the alerts card because what they *do* is announce a thing
	 * — which is the whole of what that module is for. Keeping them apart from
	 * the titles on the same card was how the mimic ended up on two cards at
	 * once.
	 */
	val announcementSettings: List<ModuleSetting> = listOf(
		announceMimic,
		announcePrince,
		announceBat,
		announce270,
		announce300,
	)

	/** The two titles the score puts on your own screen, listed under Titles. */
	val titleSettings: List<ModuleSetting> = listOf(title270, title300)

	/** The wording, which is one field for the kills and one for the scores. */
	val customSettings: List<ModuleSetting> = listOf(customSection, customMessages, killMessage, scoreMessage)

	/** Everything above that a Reset button should put back. */
	fun resetSettings() {
		(scoreSettings + announcementSettings + titleSettings + customSettings).forEach {
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

	/**
	 * Announces the run's first 270 and first 300, if either is asked for.
	 *
	 * The crossing itself is noticed whether or not anything is switched on to
	 * report it, because the party message and the title are two ways of saying
	 * the same thing and one must not be able to swallow the other's moment.
	 */
	fun tick(client: Minecraft) {
		if (!RoomAlerts.module.enabled || !DungeonLocation.inDungeon) return
		val score = DungeonStats.score

		if (!sent270 && score >= 270) {
			sent270 = true
			if (announce270.value) announce(client, scoreWording(270))
			if (title270.value) showTitle(client, "§e270")
		}

		if (!sent300 && score >= 300) {
			sent300 = true
			if (announce300.value) announce(client, scoreWording(300))
			if (title300.value) showTitle(client, "§a300")
		}
	}

	/**
	 * Puts the score on screen for a moment.
	 *
	 * Where the party message tells everybody else, this tells you — and it is
	 * separate because the two are wanted at different times: a message is worth
	 * sending once, a title is worth seeing every run.
	 */
	private fun showTitle(client: Minecraft, text: String) {
		client.gui.hud.setTimes(TITLE_FADE_IN_TICKS, TITLE_STAY_TICKS, TITLE_FADE_OUT_TICKS)
		client.gui.hud.setTitle(Component.literal(text))
		// The same pling the room titles get: these are titles on the same
		// card, and one of them making a sound while the other does not is
		// exactly the sort of thing that reads as a fault.
		RoomAlerts.playAlertSound(client)
	}

	/**
	 * The mimic dying, announced once.
	 *
	 * Called from [imicro.cryptic.dungeon.DungeonStats], which is where the
	 * death is noticed, rather than from a chat line — because there is no chat
	 * line, and a party where nobody says so is exactly the party this is for.
	 */
	fun onMimicKilled() {
		if (!RoomAlerts.module.enabled || !announceMimic.value) return
		announce(Minecraft.getInstance(), killWording("Mimic"))
	}

	/** The same for a Prince, which Hypixel does announce but only to you. */
	fun onPrinceKilled() {
		if (!RoomAlerts.module.enabled || !announcePrince.value) return
		announce(Minecraft.getInstance(), killWording("Prince"))
	}

	/**
	 * And for a bat, every time — the count is what matters here rather than
	 * the kill, and only the first five are worth a point.
	 */
	fun onBatKilled() {
		if (!RoomAlerts.module.enabled || !announceBat.value) return
		announce(Minecraft.getInstance(), killWording("Bat"))
	}

	/**
	 * What a kill is announced as.
	 *
	 * One wording for all three, because they are one kind of message: a mob
	 * worth a bonus point is dead and the party cannot otherwise know. `<mob>`
	 * is where the name goes.
	 */
	private fun killWording(mob: String): String {
		val wording = if (customMessages.value) killMessage.value else killMessage.defaultValue
		return wording.replace("<mob>", mob, ignoreCase = true)
	}

	/** The same for the two scores, where `<score>` is the number crossed. */
	private fun scoreWording(score: Int): String {
		val wording = if (customMessages.value) scoreMessage.value else scoreMessage.defaultValue
		return wording.replace("<score>", score.toString(), ignoreCase = true)
	}

	private fun announce(client: Minecraft, text: String) {
		val message = text.trim()
		if (message.isEmpty()) return

		// The debug switch shows the announcement to the player instead of
		// sending it, so the wording can be checked without a party.
		if (DebugOverrides.previewScoreMessage) {
			client.gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §7Party chat: §f$message"))
			return
		}

		client.connection?.sendCommand("pc $message")
	}

	/** Shows the line the announcements would send, for `/cryptic debug score`. */
	fun preview(client: Minecraft) {
		client.gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §f${secretsLine()}   ${bonusLine()}"))
	}

	/**
	 * True while the score has anything to draw at all.
	 *
	 * The boss room is the one place it can be switched off on its own, because
	 * it is the one place the map is not there to switch off with it.
	 */
	private val wanted: Boolean
		get() = DungeonMap.module.enabled && DungeonLocation.inDungeon &&
			(!DungeonRun.inBoss || showInBoss.value || (DungeonRun.ended && showAtEnd.value))

	/** True while the boss has made the second line's numbers final. */
	private val minimal: Boolean get() = DungeonRun.inBoss && minimalInBoss.value

	/**
	 * True while the map should draw the boss room from above instead of the
	 * floor it can no longer show.
	 */
	val showsBossView: Boolean
		get() = DungeonRun.inBoss && showInBoss.value && !minimalInBoss.value

	/** How much taller the map element is because the score sits under it. */
	fun attachedHeight(): Int =
		if (DungeonMap.module.enabled && attached) Minecraft.getInstance().font.lineHeight * lineCount() + 5 else 0

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
	 *
	 * The mimic and the prince leave the line entirely once they are dead rather
	 * than staying on as a green tick. A tick is a thing to read and then decide
	 * is not a problem; an absence is nothing to read at all, and this line is
	 * looked at mid-run to answer "what is left".
	 */
	private fun bonusLine(): String = buildString {
		// Deaths are a penalty rather than a task, so nothing is owed at zero
		// and the entry stays out of the way.
		if (DungeonStats.deaths > 0) append("§7D: §c${DungeonStats.deaths}   ")
		if (DungeonLocation.floor >= 6 && !DungeonStats.mimicKilled) append("§7M: §c✖   ")
		if (princePossible() && !DungeonStats.princeKilled) append("§7P: §c✖   ")
		append("§7B: ${count(DungeonStats.batCount, 5)}   ")
		append("${count(DungeonStats.crypts, 5)}§7/§a5")
	}.trim()

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

		/**
		 * Set to follow the map, the score has no placement of its own, so the
		 * editor has nothing to offer for it but a frame that moves nothing.
		 */
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
