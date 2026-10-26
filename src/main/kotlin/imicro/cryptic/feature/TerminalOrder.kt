package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonTeam
import imicro.cryptic.dungeon.DungeonTeam.DungeonClass
import imicro.cryptic.dungeon.Floor7
import imicro.cryptic.dungeon.Floor7Progress
import imicro.cryptic.dungeon.Floor7Tasks
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.render.CrypticRenderPipelines
import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Writes your class's assigned number over each Goldor terminal, device and lever, so "tank takes 1
 * and 3" is drawn on the things themselves. Shows the thing's own number, not its place in your
 * route. Plans and numbering are in [Floor7Tasks]; the idea is Stella's.
 */
object TerminalOrder {
	private val CLASSES = listOf(
		DungeonClass.ARCHER,
		DungeonClass.BERSERK,
		DungeonClass.HEALER,
		DungeonClass.MAGE,
		DungeonClass.TANK,
	)

	/** Within arm's reach a label fades out completely; you're looking at the terminal by then. */
	private const val FADE_TO_NOTHING = 3.0

	@JvmField
	val preset = DropdownModuleSetting(
		id = "preset",
		label = "Preset",
		options = Floor7Tasks.Preset.entries.map { it.label },
		defaultIndex = Floor7Tasks.Preset.M7_GUIDES.ordinal,
		description = "Which plan to draw.",
	)

	@JvmField
	val highlightAll = ToggleModuleSetting(
		id = "highlight_all",
		label = "Highlight all",
		defaultValue = false,
		description = "Marks everything in the section, not just yours.",
	)

	@JvmField
	val autoAssign = ToggleModuleSetting(
		id = "auto_assign",
		label = "Auto assign",
		defaultValue = true,
		description = "Reads your class from the tab list.",
		visibleIf = { !highlightAll.value && splitsByClass() },
	)

	@JvmField
	val classChoice = DropdownModuleSetting(
		id = "class",
		label = "Class",
		options = CLASSES.map { it.name.lowercase().replaceFirstChar(Char::uppercase) },
		defaultIndex = 0,
		description = "Whose assignment to draw when Auto assign is off.",
		visibleIf = { !highlightAll.value && splitsByClass() && !autoAssign.value },
	)

	@JvmField
	val hideWhenDone = ToggleModuleSetting(
		id = "hide_when_done",
		label = "Hide when done",
		defaultValue = true,
		description = "Drops a label as soon as it is done.",
	)

	private val labelSection = SectionModuleSetting(id = "label_section", label = "Label")

	@JvmField
	val fadeWhenClose = ToggleModuleSetting(
		id = "fade_when_close",
		label = "Fade when close",
		defaultValue = true,
		description = "Fades a label as you walk up to it.",
	)

	@JvmField
	val fadeDistance = SliderModuleSetting(
		id = "fade_distance",
		label = "Fade from",
		unit = "blocks",
		defaultValue = 11.0,
		min = 5.0,
		max = 30.0,
		step = 1.0,
		description = "Full strength beyond this, and gone by three blocks.",
		visibleIf = { fadeWhenClose.value },
	)

	private const val SHOW_CLASS = 1

	@JvmField
	val labelStyle = DropdownModuleSetting(
		id = "label_style",
		label = "Shows",
		options = listOf("Number", "Class"),
		defaultIndex = 0,
		description = "The terminal's number, or the class the plan gives it (\"[ Archer ]\"), in that class's colour.",
	)

	@JvmField
	val numberColor = ColorModuleSetting(
		id = "number_color",
		label = "Number",
		defaultRgb = 0xFFFFFF,
	)

	@JvmField
	val bracketColor = ColorModuleSetting(
		id = "bracket_color",
		label = "Brackets",
		defaultRgb = 0x555555,
	)

	@JvmField
	val scale = SliderModuleSetting(
		id = "scale",
		label = "Scale",
		defaultValue = 2.0,
		min = 0.5,
		max = 5.0,
		step = 0.1,
	)

	@JvmField
	val height = SliderModuleSetting(
		id = "height",
		label = "Height",
		defaultValue = 1.9,
		min = 0.0,
		max = 4.0,
		step = 0.05,
		description = "How far above the terminal the label floats.",
	)

	@JvmField
	val throughWalls = ToggleModuleSetting(
		id = "through_walls",
		label = "Phase",
		defaultValue = true,
		description = "Your assignment is across the whole quarter.",
	)

	@JvmField
	val module = Module(
		id = "terminal_order",
		name = "Terminal Order",
		description = "Marks your terminals",
		category = ModuleCategory.FLOOR_7,
		supportsKeybind = false,
		settings = listOf(
			preset, highlightAll, autoAssign, classChoice, hideWhenDone,
			labelSection, labelStyle, fadeWhenClose, fadeDistance,
			numberColor, bracketColor, scale, height, throughWalls,
		),
	)

	/**
	 * Whether the chosen plan depends on class. Read every frame the menu is open, so it's one enum
	 * lookup.
	 */
	private fun splitsByClass(): Boolean =
		Floor7Tasks.Preset.entries.getOrNull(preset.selectedIndex)?.splitsByClass ?: true

	val needsTeamTracking: Boolean
		get() = module.enabled && autoAssign.value && !highlightAll.value && splitsByClass()

	val needsProgressTracking: Boolean get() = module.enabled && hideWhenDone.value

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		CrypticRenderPipelines.touch()
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
	}

	private fun chosenPreset(): Floor7Tasks.Preset =
		Floor7Tasks.Preset.entries.getOrNull(preset.selectedIndex) ?: Floor7Tasks.Preset.M7_GUIDES

	/**
	 * Whose assignment to draw, or null. Auto reads the tab list and says nothing when it doesn't
	 * know; a wrong assignment is worse than none.
	 */
	private fun assignedClass(): DungeonClass? {
		if (!autoAssign.value) return CLASSES.getOrNull(classChoice.selectedIndex)

		val name = Minecraft.getInstance().player?.name?.string ?: return null
		return DungeonTeam.classOf(name)?.takeIf { it != DungeonClass.UNKNOWN }
	}

	private fun render(context: LevelRenderContext) {
		if (!module.enabled) return
		// The section you're in says which numbers these are; every section repeats them.
		val section = Floor7.p3Section ?: return

		val tasks = if (highlightAll.value) {
			Floor7Tasks.tasksIn(section)
		} else {
			// The class is only needed for plans that split by it.
			val chosen = chosenPreset()
			Floor7Tasks.assignedIn(section, if (chosen.splitsByClass) assignedClass() else null, chosen)
		}
		if (tasks.isEmpty()) return

		val camera = Minecraft.getInstance().gameRenderer.mainCamera().position()
		val orientation = context.levelState().cameraRenderState.orientation

		for (task in tasks) {
			if (hideWhenDone.value && Floor7Progress.isDone(section, task)) continue

			val alpha = alphaAt(sqrt(task.anchor.distanceToSqr(camera)))
			if (alpha == 0) continue

			WorldRender.drawText(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				orientation = orientation,
				text = label(task, section),
				x = task.anchor.x,
				y = task.anchor.y + height.value,
				z = task.anchor.z,
				scale = scale.value.toFloat(),
				seeThrough = throughWalls.value,
				argb = (alpha shl 24) or 0xFFFFFF,
			)
		}
	}

	/** A label's alpha at [distance], linear between the two ends. */
	private fun alphaAt(distance: Double): Int {
		if (!fadeWhenClose.value) return 0xFF
		val from = fadeDistance.value.coerceAtLeast(FADE_TO_NOTHING + 1.0)
		if (distance >= from) return 0xFF
		if (distance <= FADE_TO_NOTHING) return 0

		val fraction = (distance - FADE_TO_NOTHING) / (from - FADE_TO_NOTHING)
		return (fraction * 0xFF).roundToInt().coerceIn(0, 0xFF)
	}

	/** `[ 3 ]`, with quieter brackets. */
	private fun label(task: Floor7Tasks.Task, section: Int): Component {
		val name = when (task.kind) {
			Floor7Tasks.Kind.TERMINAL -> task.number.toString()
			// Devices and levers are named too, but plans are written in their numbers.
			Floor7Tasks.Kind.DEVICE -> "Device ${task.number}"
			Floor7Tasks.Kind.LEVER -> "Lever ${task.number}"
		}
		val inside = classesFor(task, section)?.let { classes ->
			// Each class in its own colour, several joined by a slash.
			classes.foldIndexed(Component.empty()) { index, text, dungeonClass ->
				if (index > 0) text.append(Component.literal("/").withColor(bracketColor.rgb))
				text.append(Component.literal(dungeonClass.name.lowercase().replaceFirstChar { it.uppercase() }).withColor(ClassColors.getClassColor(dungeonClass)))
			}
		} ?: Component.literal(name).withColor(numberColor.rgb)

		return Component.literal("[ ").withColor(bracketColor.rgb)
			.append(inside)
			.append(Component.literal(" ]").withColor(bracketColor.rgb))
			.withStyle(ChatFormatting.BOLD)
	}

	/** The classes the chosen plan gives [task], with the Class style; null shows its number. */
	private fun classesFor(task: Floor7Tasks.Task, section: Int): List<DungeonClass>? {
		if (labelStyle.selectedIndex != SHOW_CLASS) return null
		val chosen = chosenPreset()
		if (!chosen.splitsByClass) return null
		return CLASSES.filter { task in Floor7Tasks.assignedIn(section, it, chosen) }.takeIf { it.isNotEmpty() }
	}
}
