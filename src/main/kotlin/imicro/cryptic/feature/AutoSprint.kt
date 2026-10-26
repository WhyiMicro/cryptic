package imicro.cryptic.feature

import imicro.cryptic.gui.ModuleRegistry

/**
 * Sprints for you, as Odin's Auto Sprint does: the sprint key reads as held where movement asks for
 * it (see `LocalPlayerMixin`), so the game's own rules apply (no sprinting while sneaking, hungry,
 * blind, using an item or walking backwards).
 */
object AutoSprint {
	@JvmStatic
	fun holdsSprint(): Boolean = ModuleRegistry.autoSprint.enabled
}
