package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.monster.EnderMan

/**
 * Un-hides the dungeon mobs Hypixel spawns invisible.
 *
 * Ported from NoammAddons, where it sits behind their cheat flag rather than
 * in the ordinary build — worth knowing, since it does show you something the
 * server chose not to draw. What it does not do is invent anything: the
 * entities are already in the client's world, standing where they stand, with
 * only their invisible flag set.
 *
 * A Fel wedged behind a single block is otherwise found by walking into it,
 * and a Shadow Assassin that has blinked out is found by being hit.
 */
object HiddenMobs {
	/** The name Hypixel gives its Fels, which is also what turns them upside down. */
	private const val FEL_NAME = "Dinnerbone"

	private const val SHADOW_ASSASSIN = "Shadow Assassin"

	@JvmField
	val fels = ToggleModuleSetting(
		id = "fels",
		label = "Fels",
		defaultValue = true,
		description = "The endermen on the later floors, which spawn invisible.",
	)

	@JvmField
	val shadowAssassins = ToggleModuleSetting(
		id = "shadow_assassins",
		label = "Shadow Assassins",
		defaultValue = true,
		description = "Reveals one again after it blinks out of sight.",
	)

	private val configurableSettings = listOf(fels, shadowAssassins)

	private val reset = ButtonModuleSetting("reset", "Reset", action = {
		configurableSettings.forEach { if (it is ToggleModuleSetting) it.reset() }
	})

	@JvmField
	val module = Module(
		id = "hidden_mobs",
		name = "Hidden Mobs",
		description = "Shows the dungeon mobs that spawn invisible",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = configurableSettings + reset,
	)

	/**
	 * Clears the flag as the entity is about to be drawn.
	 *
	 * Asked from the render mixin rather than once a tick, because Hypixel
	 * re-sends a mob's metadata whenever anything about it changes and every
	 * one of those packets puts the invisibility back. Deciding at the moment
	 * of drawing means a packet landing mid-frame cannot flash the mob away
	 * again, and it costs an instance check only on entities already on screen.
	 */
	@JvmStatic
	fun reveal(entity: Entity) {
		if (!module.enabled || !entity.isInvisible) return
		if (!fels.value && !shadowAssassins.value) return
		if (!DungeonLocation.inDungeon) return
		if (reveals(entity)) entity.isInvisible = false
	}

	private fun reveals(entity: Entity): Boolean {
		if (fels.value && entity is EnderMan) {
			return entity.displayName?.string?.trim() == FEL_NAME
		}

		if (shadowAssassins.value && entity is AbstractClientPlayer) {
			return entity.displayName?.string?.contains(SHADOW_ASSASSIN) == true
		}

		return false
	}
}
