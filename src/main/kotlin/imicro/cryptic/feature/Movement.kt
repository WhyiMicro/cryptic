package imicro.cryptic.feature

import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.Restriction
import imicro.cryptic.gui.ToggleModuleSetting

/**
 * Auto Sprint and No Jump Delay in one card.
 *
 * Auto Sprint works as Odin's does: the sprint key reads as held where movement asks for it (see
 * `LocalPlayerMixin`), so the game's own rules apply (no sprinting while sneaking, hungry, blind,
 * using an item or walking backwards). No Jump Delay is mitfox's NoJumpDelay idea; it clears
 * vanilla's ten-tick wait in `LivingEntityMixin`.
 */
object Movement {
	@JvmField
	val autoSprint = ToggleModuleSetting(
		id = "auto_sprint",
		label = "Auto sprint",
		defaultValue = true,
		description = "Holds sprint for you.",
	)

	@JvmField
	val noJumpDelay = ToggleModuleSetting(
		id = "no_jump_delay",
		label = "No jump delay",
		description = "Jump again the moment you land, instead of waiting half a second.",
		restriction = Restriction.SEMI_LEGIT,
	)

	@JvmField
	val module = Module(
		id = "movement",
		name = "Movement",
		description = "Auto sprint, no jump delay",
		category = ModuleCategory.GENERAL,
		supportsKeybind = false,
		settings = listOf(autoSprint, noJumpDelay),
	)

	@JvmStatic
	fun holdsSprint(): Boolean = module.enabled && autoSprint.value

	@JvmStatic
	fun skipsJumpDelay(): Boolean = module.enabled && noJumpDelay.value
}
