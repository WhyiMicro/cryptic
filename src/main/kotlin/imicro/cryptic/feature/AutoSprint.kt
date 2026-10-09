package imicro.cryptic.feature

import imicro.cryptic.gui.ModuleRegistry

/**
 * Sprints for you, the way Odin's Auto Sprint does.
 *
 * Rather than setting the sprint flag once a tick from outside, the sprint key
 * reads as held at the one place the player's movement asks about it (see
 * `LocalPlayerMixin`). The game's own rules then decide, as they would for a
 * held key: no sprint while sneaking, hungry, blind, using an item or walking
 * backwards. The sprint also starts in the same tick as the movement, not one
 * tick after it.
 */
object AutoSprint {
	@JvmStatic
	fun holdsSprint(): Boolean = ModuleRegistry.autoSprint.enabled
}
