package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonTeam
import imicro.cryptic.dungeon.DungeonTeam.DungeonClass
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.render.CrypticRenderPipelines
import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player

/**
 * Hypixel's dungeon-class colours on teammates' glows and on the labels [ClassNames] draws. Only
 * the colour of known teammates is replaced (Odin's approach); Hypixel still decides who glows.
 * Teammates come from [DungeonTeam].
 */
object ClassColors {
	/** Blade Addons' label height. */
	private const val NAME_TAG_HEIGHT = 2.75

	private val colorsSection = SectionModuleSetting("colors_section", "Colors")

	@JvmField
	val disableGlow = ToggleModuleSetting(
		id = "disable_glow",
		label = "Disable player glow",
		defaultValue = false,
		description = "Stops every player glowing in a dungeon, you included. The name labels keep their class colours.",
	)

	@JvmField
	val applyToSelf = ToggleModuleSetting(
		id = "apply_to_self",
		label = "Apply to self",
		defaultValue = false,
		description = "Gives you your class's glow. Off, you do not glow at all.",
	)

	@JvmField
	val archerColor = ColorModuleSetting("archer_color", "Archer", 0xFFAA00)

	@JvmField
	val berserkColor = ColorModuleSetting("berserk_color", "Berserk", 0xAA0000)

	@JvmField
	val healerColor = ColorModuleSetting("healer_color", "Healer", 0xFF55FF)

	@JvmField
	val mageColor = ColorModuleSetting("mage_color", "Mage", 0x55FFFF)

	@JvmField
	val tankColor = ColorModuleSetting("tank_color", "Tank", 0x00AA00)

	@JvmField
	val unknownColor = ColorModuleSetting("unknown_color", "Unknown", 0xFFFFFF)

	private val namesSection = SectionModuleSetting("names_section", "Names")

	@JvmField
	val showNames = ToggleModuleSetting(
		id = "show_names",
		label = "Show names",
		defaultValue = true,
		description = "Replaces teammates' name tags with their name and class",
	)

	@JvmField
	val showLetter = ToggleModuleSetting(
		id = "show_letter",
		label = "Show class letter",
		defaultValue = true,
		visibleIf = { showNames.value },
	)

	@JvmField
	val letterSide = DropdownModuleSetting(
		id = "letter_side",
		label = "Letter side",
		options = listOf("Right", "Left"),
		defaultIndex = 0,
		description = "Which side of the name the class letter sits on.",
		visibleIf = { showNames.value && showLetter.value },
	)

	@JvmField
	val namesThroughWalls = ToggleModuleSetting(
		id = "through_walls",
		label = "Phase",
		defaultValue = true,
		visibleIf = { showNames.value },
	)

	@JvmField
	val nameScale = SliderModuleSetting(
		id = "scale",
		label = "Scale",
		defaultValue = 2.0,
		min = 0.5,
		max = 5.0,
		step = 0.1,
		visibleIf = { showNames.value },
	)

	@JvmField
	val nameHeight = SliderModuleSetting(
		id = "height",
		label = "Height",
		defaultValue = NAME_TAG_HEIGHT,
		min = 0.0,
		max = 5.0,
		step = 0.05,
		visibleIf = { showNames.value },
	)

	private val boxesSection = SectionModuleSetting("boxes_section", "Boxes")

	private const val STYLE_OUTLINE = 0
	private const val STYLE_FILL = 1

	@JvmField
	val boxes = ToggleModuleSetting(
		id = "boxes",
		label = "Box teammates",
		defaultValue = false,
		description = "A box around each teammate, in their class colour.",
	)

	@JvmField
	val boxStyle = DropdownModuleSetting(
		id = "box_style",
		label = "Style",
		options = listOf("Outline", "Fill", "Fill + Outline"),
		defaultIndex = STYLE_OUTLINE,
		visibleIf = { boxes.value },
	)

	@JvmField
	val boxPhase = ToggleModuleSetting(
		id = "box_phase",
		label = "Phase",
		defaultValue = true,
		description = "Shows the boxes through walls.",
		visibleIf = { boxes.value },
	)

	@JvmField
	val boxFillOpacity = SliderModuleSetting(
		id = "box_fill_opacity",
		label = "Fill opacity",
		defaultValue = 0.3,
		min = 0.05,
		max = 1.0,
		step = 0.05,
		visibleIf = { boxes.value && boxStyle.selectedIndex != STYLE_OUTLINE },
	)

	@JvmField
	val boxLineWidth = SliderModuleSetting(
		id = "box_line_width",
		label = "Thickness",
		defaultValue = 2.0,
		min = 1.0,
		max = 10.0,
		step = 0.5,
		visibleIf = { boxes.value && boxStyle.selectedIndex != STYLE_FILL },
	)

	private val boxSettings = listOf(boxes.withMore(boxStyle, boxPhase, boxFillOpacity, boxLineWidth))

	private val colorSettings = listOf(
		disableGlow,
		applyToSelf,
		archerColor,
		berserkColor,
		healerColor,
		mageColor,
		tankColor,
		unknownColor,
	)

	private val nameSettings = listOf(
		showNames,
		showLetter,
		letterSide,
		namesThroughWalls,
		nameScale,
		nameHeight,
	)

	private val configurableSettings = colorSettings + nameSettings + boxSettings.flatMap { listOf(it) + it.more }

	private val reset = ButtonModuleSetting("reset", "Reset", action = {
		configurableSettings.forEach {
			when (it) {
				is ToggleModuleSetting -> it.reset()
				is SliderModuleSetting -> it.reset()
				is ColorModuleSetting -> it.reset()
				is DropdownModuleSetting -> it.reset()
				else -> Unit
			}
		}
	})

	@JvmField
	val module = Module(
		id = "class_colors",
		name = "Class Colors",
		description = "Colours teammates by class",
		category = ModuleCategory.DUNGEON,
		supportsKeybind = false,
		settings = listOf(colorsSection) + colorSettings + namesSection + nameSettings + boxesSection + boxSettings + reset,
	)

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		CrypticRenderPipelines.touch()
		LevelRenderEvents.COLLECT_SUBMITS.register(::renderBoxes)
	}

	/**
	 * A box round each teammate in their class colour, matching the glow and label. Not your own;
	 * it would sit around the camera.
	 */
	private fun renderBoxes(context: LevelRenderContext) {
		if (!module.enabled || !boxes.value || !DungeonTeam.inDungeons || DungeonTeam.classes.isEmpty()) return
		val client = Minecraft.getInstance()
		val level = client.level ?: return
		val self = client.player
		val partial = client.deltaTracker.getGameTimeDeltaPartialTick(false).toDouble()
		val outline = boxStyle.selectedIndex != STYLE_FILL
		val fill = boxStyle.selectedIndex != STYLE_OUTLINE
		val fillAlpha = (boxFillOpacity.value * 255).toInt().coerceIn(0, 255) shl 24

		for (player in level.players()) {
			if (player === self || player.isInvisible) continue
			val dungeonClass = DungeonTeam.classOf(player.name.string) ?: continue
			val rgb = getClassColor(dungeonClass) and 0xFFFFFF
			val box = player.type.dimensions.makeBoundingBox(
				Mth.lerp(partial, player.xOld, player.x),
				Mth.lerp(partial, player.yOld, player.y),
				Mth.lerp(partial, player.zOld, player.z),
			)
			WorldRender.drawBox(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				minX = box.minX, minY = box.minY, minZ = box.minZ,
				maxX = box.maxX, maxY = box.maxY, maxZ = box.maxZ,
				outlineArgb = 0xFF000000.toInt() or rgb,
				fillArgb = fillAlpha or rgb,
				outline = outline,
				fill = fill,
				phase = boxPhase.value,
				lineWidth = boxLineWidth.value.toFloat(),
			)
		}
	}

	/**
	 * Called from Entity#getTeamColor for every entity in view, so cheap checks go first and names
	 * are read last. Null leaves vanilla alone.
	 */
	@JvmStatic
	fun getGlowColor(entity: Entity): Int? {
		if (!DungeonTeam.inDungeons || !module.enabled || entity !is Player) return null
		if (DungeonTeam.classes.isEmpty() && !applyToSelf.value) return null

		val localPlayer = Minecraft.getInstance().player
		if (entity === localPlayer) {
			if (!applyToSelf.value) return null
			return getClassColor(DungeonTeam.classOf(entity.name.string) ?: DungeonClass.UNKNOWN)
		}

		return DungeonTeam.classOf(entity.name.string)?.let(::getClassColor)
	}

	@JvmStatic
	fun getClassColor(dungeonClass: DungeonClass): Int = when (dungeonClass) {
		DungeonClass.ARCHER -> archerColor.rgb
		DungeonClass.BERSERK -> berserkColor.rgb
		DungeonClass.HEALER -> healerColor.rgb
		DungeonClass.MAGE -> mageColor.rgb
		DungeonClass.TANK -> tankColor.rgb
		DungeonClass.UNKNOWN -> unknownColor.rgb
	}

	/**
	 * Whether a player glows, or null to leave it to the game. Players in a dungeon only, with the
	 * module on. "Disable player glow" turns it off for everyone; your own glow follows "Apply to
	 * self" both ways (Hypixel glows everyone in dungeons). Teammates are left to the server.
	 */
	@JvmStatic
	fun glowOverride(entity: Entity): Boolean? {
		if (!DungeonTeam.inDungeons || !module.enabled || entity !is Player) return null
		if (disableGlow.value) return false
		if (entity !== Minecraft.getInstance().player) return null
		return applyToSelf.value
	}
}
