package imicro.cryptic.feature

import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import net.minecraft.world.item.ItemStack

/** Shortbow and arrow tweaks in one card: [ArrowFix], [ArrowHitSound] and [ArrowHitboxes]. */
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
			hitSound.withMore(*ArrowHitSound.settings.toTypedArray()),
			hitboxes.withMore(*ArrowHitboxes.settings.toTypedArray()),
		),
	)

	@JvmStatic
	fun hidesSwing(stack: ItemStack): Boolean = module.enabled && noShortbowSwing.value && ArrowFix.isShortbowItem(stack)
}
