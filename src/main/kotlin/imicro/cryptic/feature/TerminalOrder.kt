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
 * Writes over each terminal, device and lever the number your class was given.
 *
 * A Goldor section is four or five terminals scattered across a quarter of the
 * tower, and a party's plan is a sentence — "tank takes 1 and 3" — that has to
 * be turned back into places while you are running. This is that: your own
 * assignment, drawn on the things themselves.
 *
 * What is written is the thing's **name**, not its place in an order. Nobody
 * says "do your second one", they say "I've got 3 and 4", so a terminal called
 * 3 reads `[ 3 ]` wherever it comes in your route.
 *
 * The plans and the numbering live in [Floor7Tasks]; the idea is Stella's
 * (LGPL-3.0, so none of its code is here).
 */
object TerminalOrder {
	/** The classes the menu offers, in the order [classChoice] lists them. */
	private val CLASSES = listOf(
		DungeonClass.ARCHER,
		DungeonClass.BERSERK,
		DungeonClass.HEALER,
		DungeonClass.MAGE,
		DungeonClass.TANK,
	)

	/**
	 * How close a label has to be before it is gone completely.
	 *
	 * Arm's reach, near enough: by the time a terminal is this close you are
	 * looking at the terminal rather than at a label telling you where it is,
	 * and the label is only in the way.
	 */
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
		label = "Fade from (blocks)",
		defaultValue = 11.0,
		min = 5.0,
		max = 30.0,
		step = 1.0,
		description = "Full strength beyond this, and gone by three blocks.",
		visibleIf = { fadeWhenClose.value },
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
		description = "Marks the terminals assigned to you",
		category = ModuleCategory.FLOOR_7,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(
			preset, highlightAll, autoAssign, classChoice, hideWhenDone,
			labelSection, fadeWhenClose, fadeDistance,
			numberColor, bracketColor, scale, height, throughWalls,
		),
	)

	/**
	 * Whether the chosen plan cares who you are.
	 *
	 * Declared beside the settings that hang off it because a setting's
	 * visibility is read every frame the menu is open, and the answer is one
	 * enum lookup.
	 */
	private fun splitsByClass(): Boolean =
		Floor7Tasks.Preset.entries.getOrNull(preset.selectedIndex)?.splitsByClass ?: true

	/** True while the module wants to know the party's classes. */
	val needsTeamTracking: Boolean
		get() = module.enabled && autoAssign.value && !highlightAll.value && splitsByClass()

	/** True while the module wants to know what has already been finished. */
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
	 * Whose assignment to draw, or null when nothing should be.
	 *
	 * Auto is the tab list, which is the only place that knows and cannot be
	 * left set to the wrong thing after a run where you swapped class. It says
	 * nothing at all rather than guessing when it does not know — a wrong
	 * assignment sends somebody to a terminal that is not theirs, which is
	 * worse in a run than no assignment at all.
	 */
	private fun assignedClass(): DungeonClass? {
		if (!autoAssign.value) return CLASSES.getOrNull(classChoice.selectedIndex)

		val name = Minecraft.getInstance().player?.name?.string ?: return null
		return DungeonTeam.classOf(name)?.takeIf { it != DungeonClass.UNKNOWN }
	}

	private fun render(context: LevelRenderContext) {
		if (!module.enabled) return
		// Which quarter you are standing in is what says which set of numbers
		// these are: every section repeats them.
		val section = Floor7.p3Section ?: return

		val tasks = if (highlightAll.value) {
			Floor7Tasks.tasksIn(section)
		} else {
			// The class is only resolved for a plan that splits by one, so a
			// plan that gives everybody the same thing still draws when the tab
			// list has not been read yet.
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
				text = label(task),
				x = task.anchor.x,
				y = task.anchor.y + height.value,
				z = task.anchor.z,
				scale = scale.value.toFloat(),
				seeThrough = throughWalls.value,
				argb = (alpha shl 24) or 0xFFFFFF,
			)
		}
	}

	/**
	 * How solid a label [distance] blocks away should be, nought to 255.
	 *
	 * Straight-line between the two ends rather than anything cleverer: the
	 * point is only that a label gets out of the way as you arrive, and a
	 * curve would make the distance setting harder to reason about than the
	 * one sentence that describes it.
	 */
	private fun alphaAt(distance: Double): Int {
		if (!fadeWhenClose.value) return 0xFF
		val from = fadeDistance.value.coerceAtLeast(FADE_TO_NOTHING + 1.0)
		if (distance >= from) return 0xFF
		if (distance <= FADE_TO_NOTHING) return 0

		val fraction = (distance - FADE_TO_NOTHING) / (from - FADE_TO_NOTHING)
		return (fraction * 0xFF).roundToInt().coerceIn(0, 0xFF)
	}

	/** `[ 3 ]`, with the brackets quieter than the thing they hold. */
	private fun label(task: Floor7Tasks.Task): Component {
		val name = when (task.kind) {
			// A terminal's number is the whole of what a party calls it.
			Floor7Tasks.Kind.TERMINAL -> task.number.toString()
			// The other two are said by name as well as by number, and the
			// number is what a plan is written in terms of.
			Floor7Tasks.Kind.DEVICE -> "Device ${task.number}"
			Floor7Tasks.Kind.LEVER -> "Lever ${task.number}"
		}

		return Component.literal("[ ").withColor(bracketColor.rgb)
			.append(Component.literal(name).withColor(numberColor.rgb))
			.append(Component.literal(" ]").withColor(bracketColor.rgb))
			.withStyle(ChatFormatting.BOLD)
	}
}
