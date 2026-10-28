package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.monster.EnderMan
import net.minecraft.world.entity.monster.Giant

/**
 * Un-hides the dungeon mobs Hypixel spawns invisible (Fels behind a block, blinked Shadow
 * Assassins). The entities are already in the world; only their invisible flag is cleared. In
 * NoammAddons this sits behind their cheat flag, since it shows what the server chose not to draw.
 */
object HiddenMobs {
	/** Hypixel names its Fels Dinnerbone, which also turns them upside down. */
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

	private val configurableSettings = listOf(
		fels,
		shadowAssassins,
		stealthyMobs,
	)

	@JvmField
	val module = Module(
		id = "hidden_mobs",
		restriction = imicro.cryptic.gui.Restriction.SEMI_LEGIT,
		name = "Hidden Mobs",
		description = "Reveals invisible mobs",
		category = ModuleCategory.DUNGEON,
		supportsKeybind = false,
		settings = configurableSettings,
	)

	/**
	 * Clears the flag as the entity is drawn. Hypixel resends metadata (and invisibility) on every
	 * change, so deciding at draw time means a packet mid-frame can't flash the mob away.
	 */
	@JvmStatic
	fun reveal(entity: Entity) {
		// Asked per entity per frame, so the dungeon check goes first.
		if (!module.enabled || !DungeonLocation.inDungeon || !entity.isInvisible) return
		if (!fels.value && !shadowAssassins.value && !stealthyMobs.value) return
		if (reveals(entity)) entity.isInvisible = false
	}

	private fun reveals(entity: Entity): Boolean {
		val name = entity.displayName.string.trim()

		if (fels.value && entity is EnderMan) {
			return name == FEL_NAME
		}

		// Only giants wearing something are mobs (the blood room's and the Watcher's); bare ones
		// are scenery.
		if (stealthyMobs.value && entity is Giant) {
			return !entity.getItemBySlot(EquipmentSlot.FEET).isEmpty
		}

		if (entity is AbstractClientPlayer) {
			if (shadowAssassins.value && name.contains(SHADOW_ASSASSIN)) return true
			return stealthyMobs.value && name in WATCHER_MOBS
		}

		return false
	}

	/**
	 * The Watcher's roster, which arrives as invisible player entities. NoammAddons downloads this
	 * list; it's twenty-three names, so Cryptic keeps a copy.
	 */
	private val WATCHER_MOBS = setOf(
		"Revoker", "Psycho", "Reaper", "Cannibal", "Mute", "Ooze", "Putrid", "Freak",
		"Leech", "Tear", "Parasite", "Flamer", "Skull", "Mr. Dead", "Vader", "Frost",
		"Walker", "Wandering Soul", "Bonzo", "Scarf", "Livid", "Spirit Bear", "Giant",
	)
}
