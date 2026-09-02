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
 * Stops drawing the other players standing on top of you.
 *
 * Ported from Odin (BSD 3-Clause, Copyright (c) 2025 odtheking). A crowded
 * room is mostly other people's armour, and in a fight the one thing you need
 * to see is what is behind them. Hiding them is a purely local decision —
 * everyone is still there, still being hit, still able to hit back.
 *
 * Only real players go. Hypixel's mob NPCs are player entities too, told apart
 * by the version of their uuid: a real account's is version four, and anything
 * the server invented is version two.
 */
object HidePlayers {
	/** What a real account's uuid is versioned as. */
	private const val REAL_PLAYER_UUID_VERSION = 4

	@JvmField
	val onlyInDungeons = ToggleModuleSetting(
		id = "only_in_dungeons",
		label = "Only in dungeons",
		defaultValue = true,
		description = "Leaves everyone visible outside a dungeon, where a hub full of nobody is disorienting.",
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
		description = "Stops drawing the players crowding you",
		category = ModuleCategory.VISUAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(onlyInDungeons, hideAll, distance),
	)

	/**
	 * Whether [entity] should be left undrawn. Asked once per player per frame,
	 * so the tests that rule most entities out come first.
	 */
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
