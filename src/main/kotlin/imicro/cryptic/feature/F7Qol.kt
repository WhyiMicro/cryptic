package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonTeam
import imicro.cryptic.dungeon.DungeonTeam.DungeonClass
import imicro.cryptic.dungeon.Floor7
import imicro.cryptic.dungeon.PersonalBests
import imicro.cryptic.gui.ColorModuleSetting
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
		visibleIf = { sectionComplete.value || betterTitles.value || wishTitle.value || crystalTitles.value },
	)

	// ---- Maxor's crystals ------------------------------------------------
	//
	// NoammAddons' F7 Titles crystal half and its Maxor's Crystals (CC0, Noamm9).

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

	private val goldorSection = SectionModuleSetting("goldor_section", "Goldor")

	private val witherSection = SectionModuleSetting("wither_section", "Withers")

	@JvmField
	val module = Module(
		id = "f7_qol",
		name = "F7/M7 QOL",
		description = "Titles, gates, doors, withers",
		category = ModuleCategory.FLOOR_7,
		supportsKeybind = false,
		settings = listOf<ModuleSetting>(titlesSection, sectionComplete, betterTitles, terminalColor, deviceColor, leverColor, wishTitle, titleSeconds) +
			listOf(maxorSection, crystalTitles.withMore(crystalColor, hideLaserTitle), crystalSpawnTimer, crystalPlaceTimer, crystalAlert.withMore(crystalAlertColor)) +
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
		Hud.register(CrystalTimerElement())
		Hud.register(CrystalAlertElement())
		ClientEntityEvents.ENTITY_LOAD.register { entity, _ -> onEntityLoad(entity) }
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
	}

	private fun reset() {
		section = 1
		tasksDone = false
		gateDown = false
		bigTitle = null
		taskTitle = null
		crystalTicks = -1
		pickedUpAt = 0L
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

	// ---- Maxor's crystals ------------------------------------------------

	/** "1/2 Energy Crystals are now active!", Hypixel's title as each goes in. */
	private val crystalLine = Regex("""^(\d+)/(\d+) Energy Crystals are now active!$""")
	private const val LASER_CHARGING = "The Energy Laser is charging up!"
	private val crystalPickup = Regex("""^(\w{1,16}) picked up an Energy Crystal!$""")

	/** Maxor stunned, either way he says it: the crystals come back a fixed time after. */
	private val MAXOR_STUNNED = setOf("[BOSS] Maxor: THAT BEAM! IT HURTS! IT HURTS!!", "[BOSS] Maxor: YOU TRICKED ME!")
	private const val CRYSTAL_RESPAWN_TICKS = 34

	/** Where a placed crystal appears: on the pylons, at this height. */
	private const val CRYSTAL_Y = 224
	private const val PLACE_REACH = 5.0
	private const val PLACE_RECORD = "F7 crystal placement"

	/** Server ticks until the crystals are back, or -1 when none are coming. Counted on the network thread. */
	@Volatile
	private var crystalTicks = -1

	/** When you picked a crystal up, or 0 when you are not carrying one. */
	private var pickedUpAt = 0L

	/** One server tick. Only counts down, so it is safe off the client thread. */
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
	 * A crystal turning up on a pylon near you, after you picked one up: the
	 * one you just placed. Timed from the pickup line, and kept as a best.
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

	/** Whether there is an Energy Crystal in your hotbar, which is where Hypixel puts one. */
	private fun holdingCrystal(): Boolean {
		val inventory = Minecraft.getInstance().player?.inventory ?: return false
		return (0 until 9).any { slot ->
			val stack = inventory.getItem(slot)
			!stack.isEmpty && stack.hoverName.string.replace(FORMATTING, "").trim().equals("Energy Crystal", ignoreCase = true)
		}
	}

	private const val CRYSTAL_WARNING = "⚠ Crystal ⚠"

	/** The respawn countdown, big and centred. */
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

	/** The warning while a picked-up crystal has not been placed, during Maxor. */
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

		override fun showInEditor(): Boolean = module.enabled && (betterTitles.value || crystalTitles.value)

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
