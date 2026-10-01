package imicro.cryptic.feature

import imicro.cryptic.dungeon.Floor7
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
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Boxes the terminals in Goldor's tower that still have to be done.
 *
 * Ported from NoammAddons (CC0, Copyright (c) Noamm9). A terminal is a chest
 * that has to be right-clicked, and the part of it that answers a right click
 * is an invisible armour stand rather than the block anybody can see — so a
 * terminal missed at range is usually a terminal that was aimed at and not hit.
 * What is drawn here is that armour stand's own hitbox, which is the thing the
 * click has to land on.
 *
 * Hypixel names the stand "Inactive Terminal" until the terminal is finished
 * and "Terminal Active" afterwards, so the name alone says which are left.
 */
object TerminalEsp {
	/** Indices into [mode]. */
	private const val MODE_OUTLINE = 0
	private const val MODE_FILL = 1

	/** How far a stand may sit from a recorded terminal and still be one. */
	private const val MATCH_RANGE_SQUARED = 1.5

	/** How far past the outermost terminal the world is asked for entities. */
	private const val SEARCH_MARGIN = 2.0

	/** NoammAddons' flash timings, in milliseconds. */
	private const val FLASH_RISE_MS = 50L
	private const val FLASH_HOLD_MS = 250L
	private const val FLASH_FADE_MS = 200L

	/** The name Hypixel gives a terminal nobody has finished yet. */
	private const val UNFINISHED_NAME = "Inactive Terminal"

	/** Strips leftover section-sign codes, which Hypixel colours the name with. */
	private val formattingPattern = Regex("§.")

	/**
	 * Where the terminals stand in each quarter of the phase.
	 *
	 * The positions are NoammAddons'. They are needed because the tower holds
	 * other armour stands with the same name in other quarters, and a stand two
	 * hundred blocks away being boxed through a wall is worse than not boxing
	 * anything.
	 */
	private val TERMINAL_POSITIONS = listOf(
		listOf(Vec3(110.0, 113.0, 73.0), Vec3(110.0, 119.0, 79.0), Vec3(90.0, 112.0, 92.0), Vec3(90.0, 122.0, 101.0)),
		listOf(
			Vec3(68.0, 109.0, 122.0), Vec3(59.0, 119.0, 123.0), Vec3(47.0, 109.0, 122.0),
			Vec3(39.0, 108.0, 142.0), Vec3(40.0, 124.0, 123.0),
		),
		listOf(Vec3(-2.0, 109.0, 112.0), Vec3(-2.0, 119.0, 93.0), Vec3(18.0, 123.0, 93.0), Vec3(-2.0, 109.0, 77.0)),
		listOf(Vec3(41.0, 109.0, 30.0), Vec3(44.0, 121.0, 30.0), Vec3(67.0, 109.0, 30.0), Vec3(72.0, 114.0, 47.0)),
	)

	@JvmField
	val mode = DropdownModuleSetting(
		id = "mode",
		label = "Mode",
		options = listOf("Outline", "Fill", "Fill + Outline"),
		defaultIndex = 2,
	)

	@JvmField
	val fillColor = ColorModuleSetting(
		id = "fill_color",
		label = "Fill",
		defaultRgb = 0xFFAA00,
		supportsAlpha = true,
		defaultAlpha = 0x50,
		visibleIf = { mode.selectedIndex != MODE_OUTLINE },
	)

	@JvmField
	val outlineColor = ColorModuleSetting(
		id = "outline_color",
		label = "Outline",
		defaultRgb = 0xFFAA00,
		supportsAlpha = true,
		defaultAlpha = 0xFF,
		visibleIf = { mode.selectedIndex != MODE_FILL },
	)

	private val drawingSection = SectionModuleSetting(id = "drawing_section", label = "Drawing")

	@JvmField
	val lineWidth = SliderModuleSetting(
		id = "line_width",
		label = "Outline width",
		defaultValue = 2.0,
		min = 1.0,
		max = 10.0,
		step = 0.5,
		visibleIf = { mode.selectedIndex != MODE_FILL },
	)

	@JvmField
	val throughWalls = ToggleModuleSetting(
		id = "through_walls",
		label = "Phase",
		defaultValue = false,
		description = "Draws the terminals through the tower's own walls, including the ones behind you.",
	)

	private val flashSection = SectionModuleSetting(id = "flash_section", label = "Flash")

	@JvmField
	val flashOnClick = ToggleModuleSetting(
		id = "flash_on_click",
		label = "Flash on click",
		defaultValue = true,
		description = "Flashes a terminal's box when you click it, so a click that landed is one you can see.",
	)

	@JvmField
	val flashColor = ColorModuleSetting(
		id = "flash_color",
		label = "Flash",
		defaultRgb = 0xFF0000,
		visibleIf = { flashOnClick.value },
	)

	@JvmField
	val module = Module(
		id = "terminal_esp",
		name = "Terminal ESP",
		description = "Boxes the terminals left in your section",
		category = ModuleCategory.FLOOR_7,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(
			mode, fillColor, outlineColor, drawingSection, lineWidth, throughWalls,
			flashSection, flashOnClick, flashColor,
		),
	)

	/**
	 * The boxes to draw, rebuilt once a tick.
	 *
	 * The scan is a tick's work rather than a frame's because a terminal only
	 * changes when somebody finishes it, and the render pass runs many times in
	 * between.
	 */
	private var boxes: List<Pair<Int, AABB>> = emptyList()

	/** When each stand was last clicked, by entity id. */
	private val clickedAt = HashMap<Int, Long>()

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		CrypticRenderPipelines.touch()
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
	}

	fun tick(client: Minecraft) {
		if (!module.enabled) {
			boxes = emptyList()
			return
		}

		val level = client.level
		val section = Floor7.p3Section
		if (level == null || section == null) {
			boxes = emptyList()
			return
		}

		val positions = TERMINAL_POSITIONS[section - 1]
		// One box around every terminal in the quarter, so the world is asked
		// for entities once rather than once per terminal.
		val search = AABB(
			positions.minOf { it.x } - SEARCH_MARGIN,
			positions.minOf { it.y } - SEARCH_MARGIN,
			positions.minOf { it.z } - SEARCH_MARGIN,
			positions.maxOf { it.x } + SEARCH_MARGIN,
			positions.maxOf { it.y } + SEARCH_MARGIN,
			positions.maxOf { it.z } + SEARCH_MARGIN,
		)

		boxes = level.getEntitiesOfClass(ArmorStand::class.java, search) { stand ->
			stand.customName?.string?.replace(formattingPattern, "") == UNFINISHED_NAME &&
				positions.any { stand.distanceToSqr(it) <= MATCH_RANGE_SQUARED }
		}.map { it.id to it.boundingBox }
		if (clickedAt.isNotEmpty()) clickedAt.entries.removeIf { System.currentTimeMillis() - it.value > FLASH_HOLD_MS + FLASH_FADE_MS }
	}

	/**
	 * An entity you clicked or hit. Only the stands being boxed are remembered,
	 * so this costs nothing anywhere else. NoammAddons' Flash On Click.
	 */
	@JvmStatic
	fun onClicked(entity: Entity) {
		if (!module.enabled || !flashOnClick.value || entity !is ArmorStand) return
		if (boxes.any { it.first == entity.id }) clickedAt[entity.id] = System.currentTimeMillis()
	}

	/**
	 * How far a box is through its flash, 0 to 1: up almost at once, held while
	 * the click is fresh, then faded back to its own colour.
	 */
	private fun flashOf(id: Int): Float {
		val at = clickedAt[id] ?: return 0f
		val elapsed = System.currentTimeMillis() - at
		return when {
			elapsed < FLASH_RISE_MS -> elapsed.toFloat() / FLASH_RISE_MS
			elapsed < FLASH_HOLD_MS -> 1f
			else -> (1f - (elapsed - FLASH_HOLD_MS).toFloat() / FLASH_FADE_MS).coerceAtLeast(0f)
		}
	}

	/** [base] moved towards the flash colour by [amount], keeping its own alpha. */
	private fun flashed(base: Int, amount: Float): Int {
		if (amount <= 0f) return base
		val flash = flashColor.argb
		fun channel(shift: Int): Int {
			val from = base shr shift and 0xFF
			val to = flash shr shift and 0xFF
			return (from + (to - from) * amount).toInt().coerceIn(0, 255)
		}
		return (base and 0xFF000000.toInt()) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
	}

	private fun render(context: LevelRenderContext) {
		if (!module.enabled || boxes.isEmpty()) return

		val fill = mode.selectedIndex != MODE_OUTLINE
		val outline = mode.selectedIndex != MODE_FILL

		for ((id, box) in boxes) {
			val flash = if (flashOnClick.value) flashOf(id) else 0f
			WorldRender.drawBox(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				minX = box.minX,
				minY = box.minY,
				minZ = box.minZ,
				maxX = box.maxX,
				maxY = box.maxY,
				maxZ = box.maxZ,
				outlineArgb = flashed(outlineColor.argb, flash),
				fillArgb = flashed(fillColor.argb, flash),
				outline = outline,
				fill = fill,
				phase = throughWalls.value,
				lineWidth = lineWidth.value.toFloat(),
			)
		}
	}
}
