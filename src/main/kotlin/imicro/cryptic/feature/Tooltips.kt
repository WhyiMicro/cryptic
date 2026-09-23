package imicro.cryptic.feature

import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory

/**
 * Explains a setting when the cursor rests on it.
 *
 * Every setting in Cryptic already carries a sentence or two saying what it
 * does and, where it matters, why it is the way it is. None of it was on
 * screen: the menu drew the labels and kept the explanations to itself, which
 * left the tuning of a module to guesswork and a good deal of it undiscovered.
 *
 * A module rather than a hidden preference because it is a preference — some
 * people want the menu quiet once they know it — and the menu is the only place
 * it applies.
 */
object Tooltips {
	@JvmField
	val module = Module(
		id = "tooltips",
		name = "Tooltips",
		description = "Explains settings on hover",
		category = ModuleCategory.MISC,
		enabled = true,
		hasDemoSettings = false,
		supportsKeybind = false,
	)

	/** True while the menu should explain whatever the cursor is over. */
	val showing: Boolean get() = module.enabled
}
