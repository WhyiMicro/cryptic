package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonTeam
import imicro.cryptic.dungeon.DungeonTeam.DungeonClass
import imicro.cryptic.dungeon.Floor7
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ModuleSetting
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/**
 * The small things that make the Floor 7 boss, and Master Mode's, easier to read.
 *
 * One card for what would otherwise be a shelf of little ones:
 *
 * - **Section Complete**, a title for the moment a Goldor section is really
 *   finished. Hypixel counts the terminals, device and levers of a section to
 *   its (7/7), but the party cannot move on until the gate at the end of it is
 *   blown as well, and either can come first. So the title waits for both —
 *   the way Odin's Inactive Waypoints (BSD 3-Clause, Copyright (c) 2025
 *   odtheking) tells one section from the next. The last section has no gate:
 *   it is finished when the core opens, which Hypixel announces.
 * - **Better P3 titles**, NoammAddons' Terminal Titles (CC0, Noamm9): Hypixel's
 *   "Steve activated a terminal! (3/7)" title rewritten as "Steve Terminal
 *   (3/7)", the name in its class's colour, the kind of thing in its own, and
 *   the count going from red to green as the section fills.
 * - **Wish title**, for the healer, when Maxor is enraged — the moment the
 *   party's health is about to be wanted.
 * - [DoorFix], [GateHighlight] and [WitherOutline], which were cards of their
 *   own and are switches here.
 */
object F7Qol {
	private const val WHITE = 0xFFFFFFFF.toInt()
	private const val GREY = 0xFFAAAAAA.toInt()
	private const val RED = 0xFFFF5555.toInt()
	private const val ORANGE = 0xFFFFAA00.toInt()
	private const val YELLOW = 0xFFFFFF55.toInt()
	private const val GREEN = 0xFF55FF55.toInt()

	private const val SECTION_COMPLETE = "Section Complete"
	private const val WISH = "Wish!"

	/** The last section, which ends at the core rather than at a gate. */
	private const val LAST_SECTION = 4

	/** How soon after one section ends a second "(7/7)" is the same one said twice. */
	private const val REPEAT_MILLIS = 2_000L

	private val FORMATTING = Regex("§.")

	/** "Steve activated a terminal! (3/7)", "Steve completed a device! (4/7)". */
	private val taskLine = Regex("""^(\w{1,16}) (?:activated|completed) a (terminal|device|lever)! \((\d+)/(\d+)\)$""")
	private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
	private const val GATE_DESTROYED = "The gate has been destroyed!"
	private const val CORE_OPENING = "The Core entrance is opening!"
	private const val MAXOR_ENRAGED = "⚠ Maxor is enraged! ⚠"

	private val titlesSection = SectionModuleSetting("titles_section", "Titles")

	@JvmField
	val sectionComplete = ToggleModuleSetting(
		id = "section_complete",
		label = "Section Complete title",
		defaultValue = true,
		description = "Says so once every terminal, the device and the levers are done and the gate is blown. " +
			"The last section says it when the core opens.",
	)

	@JvmField
	val betterTitles = ToggleModuleSetting(
		id = "better_p3_titles",
		label = "Better P3 titles",
		defaultValue = true,
		description = "Replaces \"Steve activated a terminal! (3/7)\" with \"Steve Terminal (3/7)\": the name in " +
			"its class's colour, and the count going from red to green.",
	)

	private fun kindColor(id: String, label: String, rgb: Int) = ColorModuleSetting(
		id = id,
		label = label,
		defaultRgb = rgb,
		visibleIf = { betterTitles.value },
	)

	@JvmField
	val terminalColor = kindColor("terminal_color", "Terminal", 0xAA00AA)

	@JvmField
	val deviceColor = kindColor("device_color", "Device", 0x55FFFF)

	@JvmField
	val leverColor = kindColor("lever_color", "Lever", 0xFF5555)

	@JvmField
	val wishTitle = ToggleModuleSetting(
		id = "wish_title",
		label = "Wish title",
		defaultValue = true,
		description = "When you are the healer, says Wish the moment Maxor is enraged.",
	)

	@JvmField
	val titleSeconds = SliderModuleSetting(
		id = "title_seconds",
		label = "Title duration",
		defaultValue = 2.0,
		min = 0.5,
		max = 5.0,
		step = 0.5,
		visibleIf = { sectionComplete.value || betterTitles.value || wishTitle.value },
	)

	private val goldorSection = SectionModuleSetting("goldor_section", "Goldor")

	private val witherSection = SectionModuleSetting("wither_section", "Withers")

	@JvmField
	val module = Module(
		id = "f7_qol",
		name = "F7/M7 QOL",
		description = "Titles, the s3 doors, the gates and the withers",
		category = ModuleCategory.FLOOR_7,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf<ModuleSetting>(titlesSection, sectionComplete, betterTitles, terminalColor, deviceColor, leverColor, wishTitle, titleSeconds) +
			listOf(goldorSection, DoorFix.enabled) + GateHighlight.settings +
			witherSection + WitherOutline.settings,
	)

	/** A piece of a title and the colour it is drawn in. */
	private class Part(val text: String, val color: Int)

	private class Title(val parts: List<Part>, val until: Long) {
		val text: String = parts.joinToString("") { it.text }
	}

	private var bigTitle: Title? = null
	private var taskTitle: Title? = null

	// ---- The section being worked through --------------------------------

	private var section = 1
	private var tasksDone = false
	private var gateDown = false
	private var lastCompleteAt = 0L

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		Hud.register(BigTitleElement())
		Hud.register(TaskTitleElement())
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
	}

	private fun reset() {
		section = 1
		tasksDone = false
		gateDown = false
		bigTitle = null
		taskTitle = null
	}

	private val inFloor7: Boolean get() = module.enabled && DungeonLocation.inFloor7

	/** A chat packet, read on the client thread. */
	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !inFloor7) return
		try {
			onLine(message.string.replace(FORMATTING, "").trim())
		} catch (_: RuntimeException) {
			// A line not understood is a line ignored, never a disconnect.
		}
	}

	private fun onLine(line: String) {
		when (line) {
			GOLDOR_START -> {
				section = 1
				tasksDone = false
				gateDown = false
				return
			}
			GATE_DESTROYED -> {
				gateDown = true
				if (tasksDone) completeSection()
				return
			}
			CORE_OPENING -> {
				if (sectionComplete.value) showBig(SECTION_COMPLETE)
				section = 1
				tasksDone = false
				gateDown = false
				return
			}
			MAXOR_ENRAGED -> {
				if (wishTitle.value && ownClass() == DungeonClass.HEALER) showBig(WISH)
				return
			}
		}

		val match = taskLine.matchEntire(line) ?: return
		val (name, kind, doneText, totalText) = match.destructured
		val done = doneText.toIntOrNull() ?: return
		val total = totalText.toIntOrNull() ?: return
		if (betterTitles.value) showTask(name, kind, done, total)

		if (done < total) return
		// The count is full. The last section is finished by the core, which
		// says so itself; any other waits for its gate as well.
		// Counted rather than read off where the player stands: the berserk is
		// already in s4 while the rest finish s1, and the mage is in the core.
		if (section == LAST_SECTION) return
		if (System.currentTimeMillis() - lastCompleteAt < REPEAT_MILLIS) return
		if (gateDown) completeSection() else tasksDone = true
	}

	/**
	 * Whether Hypixel's own title or subtitle should not be shown, because
	 * Better P3 titles is drawing the same news its own way.
	 */
	@JvmStatic
	fun hidesTitle(text: Component): Boolean {
		if (!betterTitles.value || !inFloor7) return false
		return taskLine.matches(text.string.replace(FORMATTING, "").trim())
	}

	private fun completeSection() {
		if (sectionComplete.value) showBig(SECTION_COMPLETE)
		lastCompleteAt = System.currentTimeMillis()
		section = (section + 1).coerceAtMost(LAST_SECTION)
		tasksDone = false
		gateDown = false
	}

	private fun ownClass(): DungeonClass? {
		DebugOverrides.dungeonClass?.let { return it }
		val name = Minecraft.getInstance().player?.name?.string ?: return null
		return DungeonTeam.classOf(name)
	}

	private fun until(): Long = System.currentTimeMillis() + (titleSeconds.value * 1000).toLong()

	private fun showBig(text: String) {
		bigTitle = Title(listOf(Part(text, WHITE)), until())
	}

	private fun showTask(name: String, kind: String, done: Int, total: Int) {
		taskTitle = Title(taskParts(name, kind, done, total), until())
	}

	private fun taskParts(name: String, kind: String, done: Int, total: Int): List<Part> {
		val nameColor = DungeonTeam.classOf(name)
			?.let { 0xFF000000.toInt() or (ClassColors.getClassColor(it) and 0xFFFFFF) }
			?: GREY
		val (label, kindColor) = when (kind) {
			"device" -> "Device" to deviceColor.argb
			"lever" -> "Lever" to leverColor.argb
			else -> "Terminal" to terminalColor.argb
		}
		return listOf(
			Part("$name ", nameColor),
			Part("$label ", kindColor),
			// The brackets stay green whatever the count is, so the number is what changes.
			Part("(", GREEN),
			Part("$done/$total", countColor(done, total)),
			Part(")", GREEN),
		)
	}

	/** Red, then orange, then yellow as the section fills, and green once it is full. */
	private fun countColor(done: Int, total: Int): Int {
		if (total <= 0 || done >= total) return GREEN
		val fraction = done.toDouble() / total
		return when {
			fraction < 1.0 / 3 -> RED
			fraction < 2.0 / 3 -> ORANGE
			else -> YELLOW
		}
	}

	private fun current(title: Title?): Title? = title?.takeIf { it.until > System.currentTimeMillis() }

	private val EXAMPLE_BIG = Title(listOf(Part(SECTION_COMPLETE, WHITE)), Long.MAX_VALUE)

	private val EXAMPLE_TASK: Title
		get() = Title(
			listOf(Part("Steve ", 0xFFFFAA00.toInt()), Part("Terminal ", terminalColor.argb), Part("(", GREEN), Part("5/7", YELLOW), Part(")", GREEN)),
			Long.MAX_VALUE,
		)

	/** One line of coloured text, centred on the element. */
	private fun drawCentered(context: GuiGraphicsExtractor, title: Title, width: Int) {
		val font = Minecraft.getInstance().font
		var x = (width - font.width(title.text)) / 2
		for (part in title.parts) {
			context.text(font, part.text, x, 0, part.color)
			x += font.width(part.text)
		}
	}

	private class BigTitleElement : HudElement("f7_title", "F7/M7 Title", 0.36, 0.28, 3.0) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int get() = font.width(SECTION_COMPLETE)
		override val height: Int get() = font.lineHeight

		override fun isVisible(): Boolean =
			module.enabled && (DebugOverrides.sampleHudValues || current(bigTitle) != null)

		override fun showInEditor(): Boolean = module.enabled && (sectionComplete.value || wishTitle.value)

		override fun render(context: GuiGraphicsExtractor) {
			val title = current(bigTitle) ?: if (DebugOverrides.sampleHudValues) EXAMPLE_BIG else return
			drawCentered(context, title, width)
		}

		override fun renderExample(context: GuiGraphicsExtractor) = drawCentered(context, EXAMPLE_BIG, width)
	}

	private class TaskTitleElement : HudElement("p3_title", "P3 Titles", 0.39, 0.40, 2.0) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int get() = font.width(EXAMPLE_TASK.text)
		override val height: Int get() = font.lineHeight

		override fun isVisible(): Boolean =
			module.enabled && (DebugOverrides.sampleHudValues || current(taskTitle) != null)

		override fun showInEditor(): Boolean = module.enabled && betterTitles.value

		override fun render(context: GuiGraphicsExtractor) {
			val title = current(taskTitle) ?: if (DebugOverrides.sampleHudValues) EXAMPLE_TASK else return
			drawCentered(context, title, width)
		}

		override fun renderExample(context: GuiGraphicsExtractor) = drawCentered(context, EXAMPLE_TASK, width)
	}

	/** For `/cryptic debug f7`. */
	fun describe(): List<String> = listOf(
		"§8[Cryptic] §7F7/M7 QOL: module ${if (module.enabled) "§aon" else "§coff"}§7, Floor 7 §f${DungeonLocation.inFloor7}" +
			"§7, section §f$section §7(standing in §f${Floor7.p3Section ?: "none"}§7), tasks done §f$tasksDone" +
			"§7, gate down §f$gateDown",
	)
}
