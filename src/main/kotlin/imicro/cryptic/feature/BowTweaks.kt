package imicro.cryptic.feature

import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import net.minecraft.world.item.ItemStack

/** Shortbow and arrow tweaks in one card: [ArrowFix], [ArrowHitSound], [ArrowHitboxes] and the bow's [Trajectories]. */
object BowTweaks {
	@JvmField
	val arrowFix = ToggleModuleSetting(
		id = "arrow_fix",
		label = "Arrow fix",
		description = "Shortbows never play the draw-back animation.",
	)

	@JvmField
	val noShortbowSwing = ToggleModuleSetting(
		id = "shortbow_no_swing",
		label = "No swing on shortbows",
		description = "Disables the swing animation while holding a shortbow, such as a Terminator.",
	)

	@JvmField
	val hitSound = ToggleModuleSetting(
		id = "hit_sound",
		label = "Arrow hit sound",
		description = "Replaces the arrow hit sound with one you choose.",
	)

	@JvmField
	val hitboxes = ToggleModuleSetting(
		id = "arrow_hitboxes",
		label = "Arrow hitboxes",
		description = "Outlines arrows in flight, whose hitbox is much bigger than their model.",
	)

	@JvmField
	val trajectoryLine = ToggleModuleSetting(
		id = "trajectory_line",
		label = "Show path",
		defaultValue = true,
		description = "Draws the arrow's path. Off, only where it lands and the mob it hits are marked.",
	)

	@JvmField
	val trajectoryColor = ColorModuleSetting("trajectory_color", "Line", defaultRgb = 0xFFFFFF)

	@JvmField
	val trajectoryHitColor = ColorModuleSetting("trajectory_hit_color", "Mob it hits", defaultRgb = 0xFF5555)

	@JvmField
	val trajectory = ToggleModuleSetting(
		id = "trajectory",
		label = "Bow trajectory",
		defaultValue = true,
		description = "Where your arrows would go: while you draw a bow, and whenever you hold a shortbow. Stops at the first mob it would hit.",
	)

	@JvmField
	val module = Module(
		id = "bow_tweaks",
		name = "Bow Tweaks",
		description = "Shortbow and arrow tweaks",
		category = ModuleCategory.GENERAL,
		supportsKeybind = false,
		settings = listOf(
			SectionModuleSetting("shortbow_section", "Shortbows"),
			arrowFix,
			noShortbowSwing,
			SectionModuleSetting("arrow_section", "Arrows"),
			trajectory.withMore(trajectoryLine, trajectoryColor, trajectoryHitColor),
			hitSound.withMore(*ArrowHitSound.settings.toTypedArray()),
			hitboxes.withMore(*ArrowHitboxes.settings.toTypedArray()),
		),
	)

	@JvmStatic
	fun hidesSwing(stack: ItemStack): Boolean = module.enabled && noShortbowSwing.value && ArrowFix.isShortbowItem(stack)
}
