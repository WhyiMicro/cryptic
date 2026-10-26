package imicro.cryptic.feature

import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory

/**
 * Lets you jump again the moment you land, instead of vanilla's ten-tick wait. The idea of mitfox's
 * NoJumpDelay; the work is in [imicro.cryptic.mixin.LivingEntityMixin].
 */
object NoJumpDelay {
	@JvmField
	val module = Module(
		id = "no_jump_delay",
		restriction = imicro.cryptic.gui.Restriction.SEMI_LEGIT,
		name = "No Jump Delay",
		description = "Jump again as you land",
		category = ModuleCategory.GENERAL,
		supportsKeybind = false,
	)
}
