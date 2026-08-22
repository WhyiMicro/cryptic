package imicro.cryptic.feature

import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory

/** Makes the client lightmap fully illuminated without changing video settings. */
object Fullbright {
	@JvmField
	val module = Module(
		id = "fullbright",
		name = "Fullbright",
		description = "Makes dark areas fully visible",
		category = ModuleCategory.VISUAL,
		hasDemoSettings = false,
		supportsKeybind = false,
	)

	/**
	 * True while the extracted lightmap already holds Cryptic's values. A fully
	 * lit lightmap never changes, so the texture only has to be rebuilt on the
	 * frames where the module is switched on or off.
	 */
	@JvmField
	var lightmapApplied = false
}
