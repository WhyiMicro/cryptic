package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.render.BoxColors
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.monster.EnderMan
import net.minecraft.world.entity.monster.Giant

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

	@JvmField
	val stealthyMobs = ToggleModuleSetting(
		id = "stealthy_mobs",
		label = "Stealthy mobs",
		defaultValue = true,
		description = "The Watcher's mobs and the giants.",
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
		label = "Fel fill",
		defaultRgb = 0xFF55FF,
		supportsAlpha = true,
		defaultAlpha = 0x80,
		visibleIf = { highlightFels.value },
	)

	@JvmField
	val felOutlineColor = ColorModuleSetting(
		id = "fel_outline_color",
		label = "Fel outline",
		defaultRgb = 0xFF55FF,
		supportsAlpha = true,
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
		label = "Assassin fill",
		defaultRgb = 0xFFFFFF,
		supportsAlpha = true,
		defaultAlpha = 0x80,
		visibleIf = { highlightShadowAssassins.value },
	)

	@JvmField
	val shadowAssassinOutlineColor = ColorModuleSetting(
		id = "shadow_assassin_outline_color",
		label = "Assassin outline",
		defaultRgb = 0xFFFFFF,
		supportsAlpha = true,
		visibleIf = { highlightShadowAssassins.value },
	)

	/** The name a Shadow Assassin's health tag carries, which is how it is known. */
	const val SHADOW_ASSASSIN_NAME = SHADOW_ASSASSIN

	/** Paired so the Highlight module can draw a box with both halves. */
	val felColors = BoxColors(felColor, felOutlineColor)
	val shadowAssassinColors = BoxColors(shadowAssassinColor, shadowAssassinOutlineColor)

	private val configurableSettings = listOf(
		fels,
		shadowAssassins,
		stealthyMobs,
		highlightSection,
		highlightFels,
		felColor,
		felOutlineColor,
		highlightShadowAssassins,
		shadowAssassinColor,
		shadowAssassinOutlineColor,
	)

	@JvmField
	val module = Module(
		id = "hidden_mobs",
		name = "Hidden Mobs",
		description = "Shows the dungeon mobs that spawn invisible",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = configurableSettings,
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
		// Asked of every entity every frame, so the dungeon check goes first:
		// outside one, nothing else here needs asking.
		if (!module.enabled || !DungeonLocation.inDungeon || !entity.isInvisible) return
		if (!fels.value && !shadowAssassins.value && !stealthyMobs.value) return
		if (reveals(entity)) entity.isInvisible = false
	}

	private fun reveals(entity: Entity): Boolean {
		val name = entity.displayName?.string?.trim()

		if (fels.value && entity is EnderMan) {
			return name == FEL_NAME
		}

		// A giant is only a mob when it is wearing something: the ones the
		// blood room and the Watcher send are invisible and booted, while the
		// bare ones are scenery Hypixel means to stay hidden.
		if (stealthyMobs.value && entity is Giant) {
			return !entity.getItemBySlot(EquipmentSlot.FEET).isEmpty
		}

		if (entity is AbstractClientPlayer) {
			if (shadowAssassins.value && name?.contains(SHADOW_ASSASSIN) == true) return true
			return stealthyMobs.value && name in WATCHER_MOBS
		}

		return false
	}

	/**
	 * The Watcher's roster, which arrives as invisible player entities.
	 *
	 * NoammAddons downloads this list; it is twenty-three names that change
	 * when Hypixel adds a mob, so Cryptic keeps its own copy rather than a
	 * download and a cache for the sake of half a kilobyte.
	 */
	private val WATCHER_MOBS = setOf(
		"Revoker", "Psycho", "Reaper", "Cannibal", "Mute", "Ooze", "Putrid", "Freak",
		"Leech", "Tear", "Parasite", "Flamer", "Skull", "Mr. Dead", "Vader", "Frost",
		"Walker", "Wandering Soul", "Bonzo", "Scarf", "Livid", "Spirit Bear", "Giant",
	)
}
