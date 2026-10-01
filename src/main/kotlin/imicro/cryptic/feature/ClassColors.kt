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
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player

/**
 * Applies Hypixel's dungeon-class colors to teammates, on the glow and on the
 * labels [ClassNames] draws over their heads.
 *
 * Hypixel already controls whether a player is glowing. Cryptic only replaces
 * the color returned for known dungeon teammates, mirroring Odin's approach.
 * Who is on the team comes from [DungeonTeam], which other modules share. The
 * name labels live in the same module because they are the same idea told a
 * second way, and nobody wants to find one switched on and the other off.
 */
object ClassColors {
	/** Blade Addons' own label height, kept so the labels sit where players expect. */
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

	private val configurableSettings = colorSettings + nameSettings

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
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(colorsSection) + colorSettings + namesSection + nameSettings + reset,
	)

	/**
	 * Called from Entity#getTeamColor for every entity in view, so the checks are
	 * ordered cheapest and most selective first. Reading an entity's name builds
	 * a string, which only happens once there is something to match it against.
	 * Returning null leaves vanilla behavior untouched.
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

	/** Shared palette entry point for every module which colors dungeon classes. */
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
	 * Whether a player glows, or null to leave it to the game.
	 *
	 * Only players, only in a dungeon, and only with this module on — anything
	 * else is not this module's to decide. "Disable player glow" turns it off
	 * for everyone. Otherwise your own glow follows "Apply to self" both ways:
	 * switched on, you glow in third person even without the server's glow
	 * flag; switched off, you do not glow at all, because Hypixel sets that flag
	 * on everyone in a dungeon and "off" would otherwise only have meant your
	 * glow kept its plain team colour. Teammates are left to the server.
	 */
	@JvmStatic
	fun glowOverride(entity: Entity): Boolean? {
		if (!DungeonTeam.inDungeons || !module.enabled || entity !is Player) return null
		if (disableGlow.value) return false
		if (entity !== Minecraft.getInstance().player) return null
		return applyToSelf.value
	}
}
