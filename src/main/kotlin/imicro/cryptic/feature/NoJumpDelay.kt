package imicro.cryptic.feature

import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory

/**
 * Lets the player jump again the moment they land.
 *
 * Modelled on mitfox's NoJumpDelay (1.8.9), which is a single line — zero the
 * cooldown every tick — and nothing of whose code survives the move: the field
 * it wrote to was `jumpTicks` on `EntityLivingBase`, and it is `noJumpDelay` on
 * `LivingEntity` now. The work is in [imicro.cryptic.mixin.LivingEntityMixin].
 *
 * Vanilla makes you wait ten ticks between jumps, which is half a second of
 * holding space doing nothing on every landing.
 */
object NoJumpDelay {
	@JvmField
	val module = Module(
		id = "no_jump_delay",
		name = "No Jump Delay",
		description = "Jump again the moment you land, instead of waiting ten ticks",
		category = ModuleCategory.GENERAL,
		hasDemoSettings = false,
		supportsKeybind = false,
	)
}
