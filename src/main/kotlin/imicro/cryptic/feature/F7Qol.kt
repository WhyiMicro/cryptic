package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonTeam
import imicro.cryptic.dungeon.DungeonTeam.DungeonClass
import imicro.cryptic.dungeon.Floor7
import imicro.cryptic.dungeon.PersonalBests
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ModuleSetting
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import java.util.Locale

/**
 * The Floor 7 boss, phase by phase (the card is "F7/M7 Features"):
 * - **Section Complete**: a title when a Goldor section is really done. The terminals, device and
 * levers must reach (7/7) and the gate must be blown, in either order, as Odin's Inactive Waypoints
 * tells sections apart. The last section ends when the core opens.
 * - **Better P3 titles**: NoammAddons' Terminal Titles, rewriting "Steve activated a terminal!
 * (3/7)" as "Steve Terminal (3/7)" with class and count colours.
 * - **Wish title** for the healer when Maxor is enraged.
 * - Maxor's crystals: titles, timers, and boxes around them.
 * - **Goldor tracer** once the core opens, and the **Necron 3x3** that must stay mined out.
 * - [DoorFix], [GateHighlight] and [WitherOutline] as switches.
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

	private const val LAST_SECTION = 4

	/** How soon a second "(7/7)" after a section ends is the same one repeated. */
	private const val REPEAT_MILLIS = 2_000L

	private val FORMATTING = Regex("§.")

	/** "Steve activated a terminal! (3/7)", "Steve completed a device! (4/7)". */
	private val taskLine = Regex("""^(\w{1,16}) (?:activated|completed) a (terminal|device|lever)! \((\d+)/(\d+)\)$""")
	private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
	private const val GATE_DESTROYED = "The gate has been destroyed!"
	private const val CORE_OPENING = "The Core entrance is opening!"
	private const val MAXOR_ENRAGED = "⚠ Maxor is enraged! ⚠"

	private val titlesSection = SectionModuleSetting("titles_section", "Titles")
	private val stormSection = SectionModuleSetting("storm_section", "Storm")
	private val necronSection = SectionModuleSetting("necron_section", "Necron")

	private val BOX_STYLES = listOf("Outline", "Fill", "Fill + Outline")
	private const val BOX_OUTLINE = 0
	private const val BOX_FILL = 1

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
		visibleIf = { sectionComplete.value || betterTitles.value || wishTitle.value || crystalTitles.value },
	)

	// ---- Maxor's crystals ------------------------------------------------
	//
	// From NoammAddons' F7 Titles and Maxor's Crystals.

	private val maxorSection = SectionModuleSetting("maxor_section", "Maxor")

	@JvmField
	val crystalTitles = ToggleModuleSetting(
		id = "crystal_titles",
		label = "Crystal titles",
		defaultValue = true,
		description = "Shows \"Crystal (1/2)\" in place of Hypixel's crystal titles.",
	)

	@JvmField
	val crystalColor = ColorModuleSetting(
		id = "crystal_color",
		label = "Crystal",
		defaultRgb = 0x00AAAA,
		visibleIf = { crystalTitles.value },
	)

	@JvmField
	val hideLaserTitle = ToggleModuleSetting(
		id = "hide_laser_title",
		label = "Hide laser title",
		defaultValue = true,
		description = "Drops \"The Energy Laser is charging up!\".",
		visibleIf = { crystalTitles.value },
	)

	@JvmField
	val crystalSpawnTimer = ToggleModuleSetting(
		id = "crystal_spawn_timer",
		label = "Crystal spawn timer",
		defaultValue = false,
		description = "Counts down to the crystals coming back once Maxor is stunned.",
	)

	@JvmField
	val crystalPlaceTimer = ToggleModuleSetting(
		id = "crystal_place_timer",
		label = "Crystal place timer",
		defaultValue = false,
		description = "Says in chat how long you took to place a crystal you picked up, with your best.",
	)

	@JvmField
	val crystalAlert = ToggleModuleSetting(
		id = "crystal_alert",
		label = "Unplaced crystal alert",
		defaultValue = false,
		description = "A warning while you are holding an Energy Crystal you have not placed.",
	)

	@JvmField
	val crystalAlertColor = ColorModuleSetting(
		id = "crystal_alert_color",
		label = "Alert",
		defaultRgb = 0xFFFF55,
		visibleIf = { crystalAlert.value },
	)

	@JvmField
	val crystalHitboxes = ToggleModuleSetting(
		id = "crystal_hitboxes",
		label = "Crystal hitboxes",
		defaultValue = false,
		description = "Draws a box around Maxor's Energy Crystals, so they're easy to find and pick up.",
	)

	@JvmField
	val crystalBoxStyle = DropdownModuleSetting(
		id = "crystal_box_style", label = "Style", options = BOX_STYLES, defaultIndex = 2,
		visibleIf = { crystalHitboxes.value },
	)

	@JvmField
	val crystalFillColor = ColorModuleSetting(
		id = "crystal_box_color", label = "Fill", defaultRgb = 0x55FFFF, supportsAlpha = true, defaultAlpha = 0x60,
		visibleIf = { crystalHitboxes.value && crystalBoxStyle.selectedIndex != BOX_OUTLINE },
	)

	@JvmField
	val crystalOutlineColor = ColorModuleSetting(
		id = "crystal_outline_color", label = "Outline", defaultRgb = 0x55FFFF, supportsAlpha = true,
		visibleIf = { crystalHitboxes.value && crystalBoxStyle.selectedIndex != BOX_FILL },
	)

	@JvmField
	val crystalBoxWidth = SliderModuleSetting(
		id = "crystal_box_width", label = "Line width", defaultValue = 2.0, min = 0.5, max = 5.0, step = 0.5,
		visibleIf = { crystalHitboxes.value && crystalBoxStyle.selectedIndex != BOX_FILL },
	)

	@JvmField
	val crystalBoxPhase = ToggleModuleSetting(
		id = "crystal_box_phase", label = "Through walls", defaultValue = true,
		visibleIf = { crystalHitboxes.value },
	)

	private val crushTimes = imicro.cryptic.gui.PanelModuleSetting("crush_times", "Crush times", note = "Coming soon")

	private val goldorSection = SectionModuleSetting("goldor_section", "Goldor")

	@JvmField
	val goldorTracer = ToggleModuleSetting(
		id = "goldor_tracer",
		label = "Goldor tracer after core",
		defaultValue = false,
		description = "Once \"The Core entrance is opening!\" shows, a line from your crosshair to Goldor until the phase ends.",
	)

	@JvmField
	val goldorTracerColor = ColorModuleSetting(
		id = "goldor_tracer_color", label = "Colour", defaultRgb = 0xFFAA00,
		visibleIf = { goldorTracer.value },
	)

	@JvmField
	val goldorTracerWidth = SliderModuleSetting(
		id = "goldor_tracer_width", label = "Line width", defaultValue = 2.0, min = 0.5, max = 5.0, step = 0.5,
		visibleIf = { goldorTracer.value },
	)

	@JvmField
	val platform = ToggleModuleSetting(
		id = "necron_3x3",
		label = "3x3 highlight",
		defaultValue = false,
		description = "Marks the 3x3 under Necron's platform that gets mined out, so the platform doesn't disappear.",
	)

	@JvmField
	val platformStyle = DropdownModuleSetting(
		id = "necron_3x3_style", label = "Style", options = BOX_STYLES, defaultIndex = 0,
		visibleIf = { platform.value },
	)

	@JvmField
	val platformFillColor = ColorModuleSetting(
		id = "necron_3x3_color", label = "Fill", defaultRgb = 0xFF5555, supportsAlpha = true, defaultAlpha = 0x50,
		visibleIf = { platform.value && platformStyle.selectedIndex != BOX_OUTLINE },
	)

	@JvmField
	val platformOutlineColor = ColorModuleSetting(
		id = "necron_3x3_outline_color", label = "Outline", defaultRgb = 0xFF5555, supportsAlpha = true,
		visibleIf = { platform.value && platformStyle.selectedIndex != BOX_FILL },
	)

	@JvmField
	val platformWidth = SliderModuleSetting(
		id = "necron_3x3_width", label = "Line width", defaultValue = 2.0, min = 0.5, max = 5.0, step = 0.5,
		visibleIf = { platform.value && platformStyle.selectedIndex != BOX_FILL },
	)

	@JvmField
	val platformPhase = ToggleModuleSetting(
		id = "necron_3x3_phase", label = "Through walls", defaultValue = true,
		visibleIf = { platform.value },
	)

	private val witherSection = SectionModuleSetting("wither_section", "Withers")

	@JvmField
	val module = Module(
		id = "f7_qol",
		name = "F7/M7 Features",
		description = "Every phase of the F7 boss",
		category = ModuleCategory.FLOOR_7,
		supportsKeybind = false,
		// In the order a run meets them.
		settings = listOf<ModuleSetting>(
			maxorSection, wishTitle, crystalTitles.withMore(crystalColor, hideLaserTitle), crystalSpawnTimer, crystalPlaceTimer,
			crystalAlert.withMore(crystalAlertColor), crystalHitboxes.withMore(crystalBoxStyle, crystalFillColor, crystalOutlineColor, crystalBoxWidth, crystalBoxPhase),
			stormSection, crushTimes,
			goldorSection, sectionComplete, betterTitles.withMore(terminalColor, deviceColor, leverColor), DoorFix.enabled,
			GateHighlight.enabled.withMore(
				GateHighlight.style, GateHighlight.lineWidth, GateHighlight.fillColor, GateHighlight.outlineColor,
				GateHighlight.showDestroyed, GateHighlight.destroyedFillColor, GateHighlight.destroyedOutlineColor,
			),
			goldorTracer.withMore(goldorTracerColor, goldorTracerWidth),
			necronSection, platform.withMore(platformStyle, platformFillColor, platformOutlineColor, platformWidth, platformPhase),
			witherSection,
		) + WitherOutline.settings + listOf(titlesSection, titleSeconds),
	)

	private class Part(val text: String, val color: Int)

	private class Title(val parts: List<Part>, val until: Long) {
		val text: String = parts.joinToString("") { it.text }
	}

	private var bigTitle: Title? = null
	private var taskTitle: Title? = null

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
		Hud.register(CrystalTimerElement())
		Hud.register(CrystalAlertElement())
		ClientEntityEvents.ENTITY_LOAD.register { entity, _ -> onEntityLoad(entity) }
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
	}

	private fun reset() {
		tracing = false
		section = 1
		tasksDone = false
		gateDown = false
		bigTitle = null
		taskTitle = null
		crystalTicks = -1
		pickedUpAt = 0L
	}

	private val inFloor7: Boolean get() = module.enabled && DungeonLocation.inFloor7

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !inFloor7) return
		try {
			onLine(message.string.replace(FORMATTING, "").trim())
		} catch (_: RuntimeException) {
			// A line not understood is ignored, never a disconnect.
		}
	}

	private fun onLine(line: String) {
		if (line in MAXOR_STUNNED && crystalSpawnTimer.value) {
			crystalTicks = CRYSTAL_RESPAWN_TICKS
			return
		}
		crystalPickup.matchEntire(line)?.let { match ->
			val self = Minecraft.getInstance().player?.name?.string
			if (crystalPlaceTimer.value && match.groupValues[1] == self) pickedUpAt = System.currentTimeMillis()
			return
		}
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
				tracing = true
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
		// The count is full. The last section waits for the core; others wait for their gate too.
		// Counted rather than read from position, since players are spread across sections.
		if (section == LAST_SECTION) return
		if (System.currentTimeMillis() - lastCompleteAt < REPEAT_MILLIS) return
		if (gateDown) completeSection() else tasksDone = true
	}

	/** Whether to hide Hypixel's title, because Better P3 titles draws it. */
	@JvmStatic
	fun hidesTitle(text: Component): Boolean {
		if (!inFloor7) return false
		val line = text.string.replace(FORMATTING, "").trim()
		if (crystalTitles.value) {
			if (line == LASER_CHARGING) return hideLaserTitle.value
			crystalLine.matchEntire(line)?.let { match ->
				showCrystal(match.groupValues[1].toInt(), match.groupValues[2].toInt())
				return true
			}
		}
		return betterTitles.value && taskLine.matches(line)
	}

	/** "1/2 Energy Crystals are now active!". */
	private val crystalLine = Regex("""^(\d+)/(\d+) Energy Crystals are now active!$""")
	private const val LASER_CHARGING = "The Energy Laser is charging up!"
	private val crystalPickup = Regex("""^(\w{1,16}) picked up an Energy Crystal!$""")

	/** Maxor stunned, either wording; the crystals come back a fixed time later. */
	private val MAXOR_STUNNED = setOf("[BOSS] Maxor: THAT BEAM! IT HURTS! IT HURTS!!", "[BOSS] Maxor: YOU TRICKED ME!")
	private const val CRYSTAL_RESPAWN_TICKS = 34

	private const val CRYSTAL_Y = 224
	private const val PLACE_REACH = 5.0
	private const val PLACE_RECORD = "F7 crystal placement"

	/** Server ticks until the crystals return, or -1. Counted on the network thread. */
	@Volatile
	private var crystalTicks = -1

	private var pickedUpAt = 0L

	/** One server tick. Only counts down, so safe off the client thread. */
	@JvmStatic
	fun onServerTick() {
		val ticks = crystalTicks
		if (ticks >= 0) crystalTicks = ticks - 1
	}

	private fun showCrystal(done: Int, total: Int) {
		taskTitle = Title(
			listOf(
				Part("Crystal ", crystalColor.argb),
				Part("(", GREY),
				Part("$done/$total", countColor(done, total)),
				Part(")", GREY),
			),
			until(),
		)
	}

	/**
	 * A crystal appearing on a pylon near you after a pickup: the one you placed. Timed from the
	 * pickup and kept as a best.
	 */
	private fun onEntityLoad(entity: Entity) {
		if (pickedUpAt == 0L || !crystalPlaceTimer.value || !inFloor7) return
		if (entity !is EndCrystal || entity.blockY != CRYSTAL_Y) return
		val player = Minecraft.getInstance().player ?: return
		val dx = entity.x - player.x
		val dz = entity.z - player.z
		if (dx * dx + dz * dz >= PLACE_REACH * PLACE_REACH) return

		val seconds = (System.currentTimeMillis() - pickedUpAt) / 1000.0
		pickedUpAt = 0L
		val (previous, best) = PersonalBests.record(PLACE_RECORD, seconds)
		val time = String.format(Locale.ROOT, "%.3f", seconds)
		val against = when {
			previous == null -> " §d§l(PB)"
			best -> " §d§l(PB) §8was ${String.format(Locale.ROOT, "%.3f", previous)}s"
			else -> " §8(best ${String.format(Locale.ROOT, "%.3f", previous)}s)"
		}
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §aCrystal placed in §e${time}s§a.$against"))
	}

	private fun holdingCrystal(): Boolean {
		val inventory = Minecraft.getInstance().player?.inventory ?: return false
		return (0 until 9).any { slot ->
			val stack = inventory.getItem(slot)
			!stack.isEmpty && stack.hoverName.string.replace(FORMATTING, "").trim().equals("Energy Crystal", ignoreCase = true)
		}
	}

	private const val CRYSTAL_WARNING = "⚠ Crystal ⚠"

	private class CrystalTimerElement : HudElement("crystal_timer", "Crystal Spawn", 0.47, 0.53, 2.5) {
		private val font get() = Minecraft.getInstance().font
		override val width: Int get() = font.width("0.00s")
		override val height: Int get() = font.lineHeight

		override fun isVisible(): Boolean = module.enabled && crystalSpawnTimer.value && crystalTicks >= 0
		override fun showInEditor(): Boolean = module.enabled && crystalSpawnTimer.value

		override fun render(context: GuiGraphicsExtractor) = draw(context, crystalTicks.coerceAtLeast(0))
		override fun renderExample(context: GuiGraphicsExtractor) = draw(context, 27)

		private fun draw(context: GuiGraphicsExtractor, ticks: Int) {
			val text = String.format(Locale.ROOT, "%.2fs", ticks / 20.0)
			context.text(font, text, (width - font.width(text)) / 2, 0, crystalColor.argb or 0xFF000000.toInt())
		}
	}

	/** The warning while a picked-up crystal is still unplaced during Maxor. */
	private class CrystalAlertElement : HudElement("crystal_alert", "Crystal Alert", 0.43, 0.2, 3.0) {
		private val font get() = Minecraft.getInstance().font
		override val width: Int get() = font.width(CRYSTAL_WARNING)
		override val height: Int get() = font.lineHeight

		override fun isVisible(): Boolean =
			module.enabled && crystalAlert.value && DungeonLocation.inFloor7 && Floor7.phase == 1 && holdingCrystal()

		override fun showInEditor(): Boolean = module.enabled && crystalAlert.value

		override fun render(context: GuiGraphicsExtractor) = draw(context)
		override fun renderExample(context: GuiGraphicsExtractor) = draw(context)

		private fun draw(context: GuiGraphicsExtractor) {
			context.text(font, CRYSTAL_WARNING, 0, 0, crystalAlertColor.argb or 0xFF000000.toInt())
		}
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
			// The brackets stay green so only the number changes.
			Part("(", GREEN),
			Part("$done/$total", countColor(done, total)),
			Part(")", GREEN),
		)
	}

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

		override fun showInEditor(): Boolean = module.enabled && (betterTitles.value || crystalTitles.value)

		override fun render(context: GuiGraphicsExtractor) {
			val title = current(taskTitle) ?: if (DebugOverrides.sampleHudValues) EXAMPLE_TASK else return
			drawCentered(context, title, width)
		}

		override fun renderExample(context: GuiGraphicsExtractor) = drawCentered(context, EXAMPLE_TASK, width)
	}

	fun describe(): List<String> = listOf(
		"§8[Cryptic] §7F7/M7 Features: module ${if (module.enabled) "§aon" else "§coff"}§7, Floor 7 §f${DungeonLocation.inFloor7}" +
			"§7, section §f$section §7(standing in §f${Floor7.p3Section ?: "none"}§7), tasks done §f$tasksDone" +
			"§7, gate down §f$gateDown",
	)

	// ---- Boxes and the tracer ---------------------------------------------------------------

	/** From the core opening until the Goldor phase is left. */
	private var tracing = false

	/** The 3x3 under Necron's platform, from the F7/M7 boss waypoints. */
	private val PLATFORM_HOLE = net.minecraft.world.phys.AABB(53.0, 63.0, 113.0, 56.0, 64.0, 116.0)

	private fun render(context: net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext) {
		if (!inFloor7) return
		val level = Minecraft.getInstance().level ?: return
		if (crystalHitboxes.value && Floor7.inBossRoom) {
			for (entity in level.entitiesForRendering()) {
				if (entity !is EndCrystal) continue
				box(context, entity.boundingBox, crystalBoxStyle.selectedIndex, crystalOutlineColor.argb, crystalFillColor.argb, crystalBoxWidth.value, crystalBoxPhase.value)
			}
		}
		if (platform.value && Floor7.phase == NECRON_PHASE) {
			box(context, PLATFORM_HOLE, platformStyle.selectedIndex, platformOutlineColor.argb, platformFillColor.argb, platformWidth.value, platformPhase.value)
		}
		if (tracing && Floor7.phase != GOLDOR_PHASE) tracing = false
		if (goldorTracer.value && tracing) {
			val goldor = findGoldor(level) ?: return
			val at = goldor.getPosition(Minecraft.getInstance().deltaTracker.getGameTimeDeltaPartialTick(false))
			imicro.cryptic.render.WorldRender.drawTracer(
				context.poseStack(), context.submitNodeCollector(),
				at.x, at.y + goldor.bbHeight / 2, at.z,
				goldorTracerColor.argb or (0xFF shl 24), goldorTracerWidth.value.toFloat(), true,
			)
		}
	}

	/**
	 * Goldor himself: the wither under the nametag that names him (it has symbols around the name,
	 * so the name is looked for inside it). Other withers, like the one some armour summons, have
	 * no such tag. None once he's dead.
	 */
	private fun findGoldor(level: net.minecraft.client.multiplayer.ClientLevel): net.minecraft.world.entity.boss.wither.WitherBoss? {
		val withers = level.entitiesForRendering().filterIsInstance<net.minecraft.world.entity.boss.wither.WitherBoss>()
			.filter { it.isAlive && it.health > 0f }
		if (withers.isEmpty()) return null
		withers.firstOrNull { named(it.customName) }?.let { return it }
		val tag = level.entitiesForRendering().firstOrNull { it is net.minecraft.world.entity.decoration.ArmorStand && named(it.customName) } ?: return null
		return withers.filter { it.distanceToSqr(tag) < GOLDOR_TAG_RANGE * GOLDOR_TAG_RANGE }.minByOrNull { it.distanceToSqr(tag) }
	}

	private fun named(name: Component?): Boolean =
		name != null && name.string.replace(FORMATTING, "").contains("Goldor", ignoreCase = true)

	/** How far below his nametag Goldor can be. */
	private const val GOLDOR_TAG_RANGE = 6.0

	private const val GOLDOR_PHASE = 3
	private const val NECRON_PHASE = 4

	private fun box(
		context: net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext,
		box: net.minecraft.world.phys.AABB, style: Int, outlineArgb: Int, fillArgb: Int, width: Double, phase: Boolean,
	) {
		imicro.cryptic.render.WorldRender.drawBox(
			poseStack = context.poseStack(),
			collector = context.submitNodeCollector(),
			minX = box.minX, minY = box.minY, minZ = box.minZ,
			maxX = box.maxX, maxY = box.maxY, maxZ = box.maxZ,
			outlineArgb = outlineArgb,
			fillArgb = fillArgb,
			outline = style != BOX_FILL,
			fill = style != BOX_OUTLINE,
			phase = phase,
			lineWidth = width.toFloat(),
		)
	}

}
