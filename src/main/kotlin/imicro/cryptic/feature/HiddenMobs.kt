package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.SectionModuleSetting
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

	private val highlightSection = SectionModuleSetting("highlight_section", "Highlight")

	/**
	 * Boxing the two lives here rather than with the other highlights, because
	 * revealing a mob and marking it are the same job: an invisible Fel that has
	 * been un-hidden is still an enderman in a dark room, and a Shadow Assassin
	 * that has blinked back in is still behind you.
	 *
	 * The drawing is still [Highlight]'s — it owns the render pass and there is
	 * no sense in having two — so these are the settings it asks.
	 */
	@JvmField
	val highlightFels = ToggleModuleSetting(
		id = "highlight_fels",
		label = "Highlight Fels",
		description = "Boxes them as well as revealing them.",
	)

	@JvmField
	val felColor = ColorModuleSetting(
		id = "fel_color",
		label = "Fel",
		defaultRgb = 0xFF55FF,
		supportsAlpha = true,
		defaultAlpha = 0x80,
		visibleIf = { highlightFels.value },
	)

	@JvmField
	val highlightShadowAssassins = ToggleModuleSetting(
		id = "highlight_shadow_assassins",
		label = "Highlight Shadow Assassins",
		description = "Boxes one after it blinks, which is the moment it is about to be behind you.",
	)

	@JvmField
	val shadowAssassinColor = ColorModuleSetting(
		id = "shadow_assassin_color",
		label = "Shadow Assassin",
		defaultRgb = 0xFFFFFF,
		supportsAlpha = true,
		defaultAlpha = 0x80,
		visibleIf = { highlightShadowAssassins.value },
	)

	/** The name a Shadow Assassin's health tag carries, which is how it is known. */
	const val SHADOW_ASSASSIN_NAME = SHADOW_ASSASSIN

	private val configurableSettings = listOf(
		fels,
		shadowAssassins,
		highlightSection,
		highlightFels,
		felColor,
		highlightShadowAssassins,
		shadowAssassinColor,
	)

	private val reset = ButtonModuleSetting("reset", "Reset", action = {
		configurableSettings.forEach {
			when (it) {
				is ToggleModuleSetting -> it.reset()
				is ColorModuleSetting -> it.reset()
				else -> Unit
			}
		}
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
