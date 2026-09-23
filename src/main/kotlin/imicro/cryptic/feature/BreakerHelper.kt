package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.map.DungeonRoom
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import kotlin.jvm.optionals.getOrNull

/**
 * Stops the Dungeon Breaker from eating the thing you were trying to click.
 *
 * Ported from NoammAddons (CC0). The Breaker mines almost anything in a
 * dungeon instantly, which is the point of it and also the problem: a secret
 * chest, a lever or a wither-essence skull is one mistimed left click away from
 * being gone, and gone means the room cannot be finished. Those blocks are
 * simply refused while the Breaker is in hand.
 */
object BreakerHelper {
	/** Hypixel's id for the pickaxe this is about. */
	private const val DUNGEON_BREAKER = "DUNGEONBREAKER"

	/**
	 * Blocks worth more than the second they take to mine.
	 *
	 * Secrets and the machinery of puzzles, plus the handful of blocks that
	 * should never be broken by accident anywhere.
	 */
	private val protectedBlocks = setOf(
		Blocks.CHEST,
		Blocks.TRAPPED_CHEST,
		Blocks.LEVER,
		Blocks.STONE_BUTTON,
		Blocks.PLAYER_HEAD,
		Blocks.PLAYER_WALL_HEAD,
		Blocks.SKELETON_SKULL,
		Blocks.SKELETON_WALL_SKULL,
		Blocks.WITHER_SKELETON_SKULL,
		Blocks.WITHER_SKELETON_WALL_SKULL,
		Blocks.BARRIER,
		Blocks.BEDROCK,
		Blocks.COMMAND_BLOCK,
		Blocks.TNT,
		Blocks.END_PORTAL,
		Blocks.END_PORTAL_FRAME,
		Blocks.PISTON,
		Blocks.STICKY_PISTON,
		Blocks.PISTON_HEAD,
		Blocks.MOVING_PISTON,
	)

	@JvmField
	val protectSecrets = ToggleModuleSetting(
		id = "protect_secrets",
		label = "Protect secrets",
		defaultValue = true,
		description = "Refuses to mine chests, levers and skulls while holding the Dungeon Breaker.",
	)

	@JvmField
	val zeroPing = ToggleModuleSetting(
		id = "zero_ping",
		label = "Zero ping",
		defaultValue = false,
		description = "Breaks blocks on screen without waiting for the server.",
	)

	private val configurableSettings = listOf(protectSecrets, zeroPing)

	private val reset = ButtonModuleSetting("reset", "Reset", action = {
		configurableSettings.forEach { if (it is ToggleModuleSetting) it.reset() }
	})

	@JvmField
	val module = Module(
		id = "breaker_helper",
		name = "Breaker Helper",
		description = "Stops the Breaker eating your clicks",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = configurableSettings + reset,
	)

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		// Refusing the attack here means the client never sends the break, so
		// there is nothing for the server to undo and nothing to flicker.
		AttackBlockCallback.EVENT.register { player, level, hand, pos, _ ->
			if (!module.enabled || !DungeonLocation.inDungeon) return@register InteractionResult.PASS

			val held = player.getItemInHand(hand)
			val id = held.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
				.copyTag().getString("id").getOrNull()
			if (id != DUNGEON_BREAKER) return@register InteractionResult.PASS

			val state = level.getBlockState(pos)
			if (state.block in protectedBlocks) {
				return@register if (protectSecrets.value) InteractionResult.FAIL else InteractionResult.PASS
			}

			if (zeroPing.value) clearAtOnce(level, pos)
			InteractionResult.PASS
		}
	}

	/**
	 * Takes a mined block off your screen without waiting for the server.
	 *
	 * The break itself still goes to the server exactly as it would have: this
	 * only stops the client rendering a block that is, as far as the run is
	 * concerned, already gone. Nothing becomes breakable that was not, and the
	 * server remains the one deciding — at 150ms that is a third of a second
	 * per block of standing still, and a room is a lot of blocks.
	 *
	 * Ported from NoammAddons (CC0). Puzzle and fairy rooms are left alone:
	 * their blocks are part of a solution, and guessing wrong about one of
	 * those on screen is worse than waiting.
	 */
	private fun clearAtOnce(level: Level, pos: BlockPos) {
		val roomType = DungeonMap.currentRoom()?.type
		if (roomType == DungeonRoom.Type.PUZZLE || roomType == DungeonRoom.Type.FAIRY) return

		val state = level.getBlockState(pos)
		if (state.isAir || state.block == Blocks.OBSIDIAN) return

		level.removeBlock(pos, false)
		val sound = state.soundType
		level.playLocalSound(
			pos.x + 0.5,
			pos.y + 0.5,
			pos.z + 0.5,
			sound.breakSound,
			SoundSource.BLOCKS,
			(sound.volume + 1.0f) / 2.0f,
			sound.pitch * 0.8f,
			false,
		)
	}
}
