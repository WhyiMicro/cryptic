package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player

/**
 * Stops drawing other players near you (still there and still hittable). Ported from Odin. Only
 * real players: Hypixel's NPCs are player entities with version-two uuids; real accounts are
 * version four.
 */
object HidePlayers {
	private const val REAL_PLAYER_UUID_VERSION = 4

	@JvmField
	val onlyInDungeons = ToggleModuleSetting(
		id = "only_in_dungeons",
		label = "Only in dungeons",
		defaultValue = true,
		description = "Only hides players inside a dungeon.",
	)

	@JvmField
	val hideAll = ToggleModuleSetting(
		id = "hide_all",
		label = "Hide all",
		description = "Hides every player at any distance, rather than only the ones crowding you.",
	)

	@JvmField
	val distance = SliderModuleSetting(
		id = "distance",
		label = "Distance",
		defaultValue = 3.0,
		min = 0.0,
		max = 32.0,
		step = 0.5,
		description = "How close a player has to be before they are hidden, in blocks.",
		visibleIf = { !hideAll.value },
	)

	@JvmField
	val module = Module(
		id = "hide_players",
		name = "Hide Players",
		description = "Hides players crowding you",
		category = ModuleCategory.VISUAL,
		supportsKeybind = false,
		settings = listOf(onlyInDungeons, hideAll, distance),
	)

	/** Asked per player per frame, so the cheapest tests come first. */
	@JvmStatic
	fun hides(entity: Entity): Boolean {
		if (!module.enabled || entity !is Player) return false
		if (entity.uuid.version() != REAL_PLAYER_UUID_VERSION) return false

		val self = Minecraft.getInstance().player ?: return false
		if (entity === self) return false
		if (onlyInDungeons.value && !DungeonLocation.inDungeon) return false
		if (hideAll.value) return true

		val range = distance.value
		return entity.distanceToSqr(self) <= range * range
	}
}
