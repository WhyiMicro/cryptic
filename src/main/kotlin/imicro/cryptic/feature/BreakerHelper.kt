package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.map.DungeonFloor
import imicro.cryptic.dungeon.map.DungeonRoom
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.mixin.MultiPlayerGameModeAccessor
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.IronBarsBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import kotlin.jvm.optionals.getOrNull

/**
 * The Dungeon Breaker: secrets it must not eat, and breaking without waiting.
 *
 * Everything is decided where the game starts or continues breaking a block ([allowsBreak], from
 * the `MultiPlayerGameMode` mixin). A protected block is refused there, so a held sweep across a
 * chest skips it and continues.
 *
 * **Instant mine** gives the Breaker a huge mining speed against blocks it can break
 * ([breakerProgress]) through the game's own formula: nearly everything breaks in one hit via the
 * instant-break path, the hardest blocks take a few ticks, and unbreakable blocks like bedrock are
 * left to Hypixel's own pace. The break delay is cleared each tick, so a sweep takes a block a
 * tick. **Remove on hit** instead removes the block from the screen when hit.
 *
 * Hypixel uses the item the server thinks is held, so the Breaker's slot is sent before its first
 * break, and a hit in the tick it was taken out is replayed next tick.
 */
object BreakerHelper {
	private const val DUNGEON_BREAKER = "DUNGEONBREAKER"

	private const val METHOD_INSTANT_MINE = 0
	private const val METHOD_REMOVE_ON_HIT = 1

	/** Secrets, puzzle machinery, and blocks that should never go by accident. */
	private val protectedBlocks = setOf(
		Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.LEVER, Blocks.STONE_BUTTON,
		Blocks.PLAYER_HEAD, Blocks.PLAYER_WALL_HEAD, Blocks.SKELETON_SKULL, Blocks.SKELETON_WALL_SKULL,
		Blocks.WITHER_SKELETON_SKULL, Blocks.WITHER_SKELETON_WALL_SKULL,
		Blocks.BARRIER, Blocks.BEDROCK, Blocks.COMMAND_BLOCK, Blocks.TNT,
		Blocks.END_PORTAL, Blocks.END_PORTAL_FRAME, Blocks.END_GATEWAY, Blocks.NETHER_PORTAL,
		Blocks.PISTON, Blocks.STICKY_PISTON, Blocks.PISTON_HEAD, Blocks.MOVING_PISTON,
	)

	/** The levers of Goldor's four terminal sections, which are meant to be hit. */
	private val terminalLevers = setOf(
		BlockPos(94, 124, 113), BlockPos(106, 124, 113),
		BlockPos(23, 132, 138), BlockPos(27, 124, 127),
		BlockPos(2, 122, 55), BlockPos(14, 122, 55),
		BlockPos(84, 121, 34), BlockPos(86, 128, 46),
	)

	@JvmField
	val protectSecrets = ToggleModuleSetting(
		id = "protect_secrets",
		label = "Protect secrets",
		defaultValue = true,
		description = "Refuses to mine chests, levers and skulls while holding the Dungeon Breaker. Levers in boss are never mined either way.",
	)

	@JvmField
	val zeroPing = ToggleModuleSetting(
		id = "zero_ping",
		label = "Zero ping",
		defaultValue = false,
		description = "Breaks blocks the moment you hit them, a block a tick while held, and puts back any the server refuses.",
	)

	@JvmField
	val method = DropdownModuleSetting(
		id = "zero_ping_method",
		label = "Method",
		options = listOf("Instant mine", "Remove on hit"),
		defaultIndex = METHOD_INSTANT_MINE,
		description = "Instant mine: the Breaker mines in one hit, and a break the server refuses comes back. " +
			"Remove on hit: the block is taken off your screen when you hit it, and the server's word is waited for.",
		visibleIf = { zeroPing.value },
	)

	@JvmField
	val module = Module(
		id = "breaker_helper",
		restriction = imicro.cryptic.gui.Restriction.SEMI_LEGIT,
		name = "Breaker Helper",
		description = "No more lost Breaker clicks",
		category = ModuleCategory.DUNGEON,
		supportsKeybind = false,
		settings = listOf(protectSecrets, zeroPing, method),
	)

	private var ticks = 0L

	/** The tick the Breaker was first seen in hand, or -1. */
	private var heldSince = -1L

	/** A hit made in the Breaker's first tick, replayed on the next. */
	private var deferredHit = false

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		ClientTickEvents.START_CLIENT_TICK.register(::tick)
	}

	private fun tick(client: Minecraft) {
		ticks++
		if (!breakerInHand()) {
			heldSince = -1L
			deferredHit = false
			return
		}
		if (heldSince < 0L) heldSince = ticks
		if (!instantMine()) return

		// Clears the break delay and miss cooldown, so a sweep takes a block a tick.
		client.missTime = 0
		(client.gameMode as? MultiPlayerGameModeAccessor)?.`cryptic$setDestroyDelay`(0)

		if (deferredHit && settled()) {
			deferredHit = false
			val hit = (client.hitResult as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK } ?: return
			if (client.level?.getBlockState(hit.blockPos)?.isAir != false) return
			client.gameMode?.startDestroyBlock(hit.blockPos, hit.direction)
			client.player?.swing(InteractionHand.MAIN_HAND)
		}
	}

	/**
	 * Whether the game may start ([starting]) or continue breaking [pos]. False makes the click or
	 * held button do nothing to that block.
	 */
	@JvmStatic
	fun allowsBreak(pos: BlockPos, direction: Direction, starting: Boolean): Boolean {
		if (!breakerInHand()) return true
		val client = Minecraft.getInstance()
		val level = client.level ?: return true
		val state = level.getBlockState(pos)
		// Boss levers (terminals, lights) are pulled, never mined.
		if (state.`is`(Blocks.LEVER) && imicro.cryptic.dungeon.DungeonRun.inBoss) return false
		if (protectSecrets.value && isProtected(pos, state)) return false

		when {
			instantMine() -> {
				if (heldSince < 0L) heldSince = ticks
				if (!settled()) {
					// Taken out this tick: the server may not know yet, so the click is replayed
					// next tick.
					if (starting) deferredHit = true
					return false
				}
				if (!breaks(level, pos, state)) return false
				if (starting) (client.gameMode as? MultiPlayerGameModeAccessor)?.`cryptic$ensureHasSentCarriedItem`()
			}
			removeOnHit() && starting && breaks(level, pos, state) -> removeFromScreen(pos, state)
		}
		return true
	}

	/**
	 * One tick of mining progress for the local [player] on [state] at [pos] with Instant mine and
	 * the Breaker, or null for the game's answer. The game's formula with the Breaker's speed;
	 * unbreakable blocks (hardness -1) stay unbroken.
	 */
	@JvmStatic
	fun breakerProgress(player: Player, level: BlockGetter, pos: BlockPos, state: BlockState): Float? {
		if (player !== Minecraft.getInstance().player || !instantMine() || !breakerInHand()) return null
		if (!breaks(level, pos, state)) return null
		val hardness = state.getDestroySpeed(level, pos)
		if (hardness < 0f) return null
		if (hardness == 0f) return 1f
		val perTick = if (player.hasCorrectToolForDrops(state)) TOOL_DIVISOR else NO_TOOL_DIVISOR
		return BREAKER_SPEED / hardness / perTick
	}

	/**
	 * Instant mine's speed: one hit through anything up to obsidian with the right tool, as Lumen's
	 * Instant mine felt.
	 */
	private const val BREAKER_SPEED = 1500f

	/** The game's divisors, with and without the right tool. */
	private const val TOOL_DIVISOR = 30f
	private const val NO_TOOL_DIVISOR = 100f

	private fun active(): Boolean = module.enabled && DungeonLocation.inDungeon

	private fun instantMine(): Boolean = zeroPing.value && method.selectedIndex == METHOD_INSTANT_MINE

	private fun removeOnHit(): Boolean = zeroPing.value && method.selectedIndex == METHOD_REMOVE_ON_HIT

	private fun breakerInHand(): Boolean {
		if (!active()) return false
		val stack = Minecraft.getInstance().player?.mainHandItem ?: return false
		val id = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getString("id").getOrNull()
		return id == DUNGEON_BREAKER
	}

	private fun settled(): Boolean = heldSince in 0 until ticks

	/** A secret, or a block never to hit by accident. */
	private fun isProtected(@Suppress("UNUSED_PARAMETER") pos: BlockPos, state: BlockState): Boolean = state.block in protectedBlocks

	/**
	 * Whether the Breaker can take the block at [pos]. Hypixel forbids fairy rooms and puzzles
	 * (except iron bars, and the Water Board and Tic Tac Toe rooms), anything holding contents, and
	 * bodiless decoration.
	 */
	private val PLANTS = setOf(Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN, Blocks.DEAD_BUSH)

	private fun breaks(level: BlockGetter, pos: BlockPos, state: BlockState): Boolean {
		if (state.isAir) return false
		if (pos in terminalLevers || state.`is`(Blocks.COAL_BLOCK)) return true

		val room = DungeonFloor.roomAt(DungeonFloor.tileOf(pos.x.toDouble(), pos.z.toDouble()))
		when (room?.displayName()) {
			"Water Board", "Tic Tac Toe" -> return true
		}
		when (room?.type) {
			DungeonRoom.Type.FAIRY -> return false
			DungeonRoom.Type.PUZZLE -> return state.block is IronBarsBlock
			else -> Unit
		}

		if (state.block in protectedBlocks || state.hasBlockEntity()) return false
		if (state.`is`(BlockTags.TRAPDOORS) || state.`is`(BlockTags.CAULDRONS)) return false
		// Grass and flowers break like anything else; torches, buttons and the like don't.
		if (state.block in PLANTS || state.`is`(BlockTags.FLOWERS)) return true
		val decoration = state.getCollisionShape(level, pos).isEmpty && !state.`is`(BlockTags.CLIMBABLE)
		return !decoration
	}

	/**
	 * Remove on hit: takes the block off your screen at once. The break still goes to the server; a
	 * refused break only returns if the server resends the block.
	 */
	private fun removeFromScreen(pos: BlockPos, state: BlockState) {
		val level = Minecraft.getInstance().level ?: return
		level.removeBlock(pos, false)
		val sound = state.soundType
		level.playLocalSound(
			pos.x + 0.5, pos.y + 0.5, pos.z + 0.5,
			sound.breakSound, SoundSource.BLOCKS,
			(sound.volume + 1.0f) / 2.0f, sound.pitch * 0.8f, false,
		)
	}
}
