package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.map.DungeonFloor
import imicro.cryptic.dungeon.map.DungeonRoom
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.mixin.KeyMappingAccessor
import imicro.cryptic.mixin.MultiPlayerGameModeAccessor
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.BushBlock
import net.minecraft.world.level.block.CauldronBlock
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.FlowerBlock
import net.minecraft.world.level.block.HopperBlock
import net.minecraft.world.level.block.IronBarsBlock
import net.minecraft.world.level.block.RedstoneTorchBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import kotlin.jvm.optionals.getOrNull

/**
 * The Dungeon Breaker: secrets it must not eat, and breaking without waiting.
 *
 * **Protect secrets** is from NoammAddons (CC0): chests, levers and skulls are
 * refused while the Breaker is in hand, because one mistimed click on them can
 * leave a room unfinishable. Holding the button and sweeping across one pauses
 * on it and carries on mining past it, rather than stopping dead.
 *
 * **Zero ping** has two methods. **Instant mine**, the default, is Lumen's
 * (AGPL-3.0, Lumen contributors); **Remove on hit** is NoammAddons', which
 * takes the block off the screen by hand when it is hit.
 *
 * Instant mine tells the game the Breaker
 * mines at a speed that makes every block it can break an instant break. The
 * game then breaks the block the way it predicts any instant break: on screen
 * at once, with the server's answer still checked against it, so a break
 * Hypixel refuses puts the block back instead of leaving a ghost. The game's
 * pause between instant breaks while the button is held is cleared, so a sweep
 * takes a block a tick, and the click right after swapping to the Breaker
 * waits one tick for the swap to reach the server first.
 */
object BreakerHelper {
	/** Hypixel's id for the pickaxe this is about. */
	private const val DUNGEON_BREAKER = "DUNGEONBREAKER"

	private const val METHOD_INSTANT_MINE = 0
	private const val METHOD_REMOVE_ON_HIT = 1

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
		Blocks.END_GATEWAY,
		Blocks.NETHER_PORTAL,
		Blocks.PISTON,
		Blocks.STICKY_PISTON,
		Blocks.PISTON_HEAD,
		Blocks.MOVING_PISTON,
	)

	/** Kinds of block the Breaker cannot break, so it is not asked to. Lumen's list. */
	private val unbreakableKinds: List<Class<out Block>> = listOf(
		RedstoneTorchBlock::class.java,
		BushBlock::class.java,
		CauldronBlock::class.java,
		HopperBlock::class.java,
		BaseEntityBlock::class.java,
		CropBlock::class.java,
		FlowerBlock::class.java,
		TrapDoorBlock::class.java,
	)

	/** The levers of Goldor's terminals sections, which are meant to be hit. */
	private val p3Levers = setOf(
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
		description = "Refuses to mine chests, levers and skulls while holding the Dungeon Breaker. The Floor 7 terminal and lights levers can still be hit.",
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
		description = "Instant mine (Lumen's): the Breaker mines in one hit, and a break the server refuses comes back. " +
			"Remove on hit (NoammAddons'): the block is taken off your screen when you hit it, and the server's word is waited for.",
		visibleIf = { zeroPing.value },
	)

	private val configurableSettings = listOf(protectSecrets, zeroPing, method)

	@JvmField
	val module = Module(
		id = "breaker_helper",
		name = "Breaker Helper",
		description = "Stops the Breaker eating your clicks",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = configurableSettings,
	)

	/** Whether the Breaker is the held item, as of the last check. */
	private var holdingBreaker = false

	/** The held item's id last tick, to see a swap happen. */
	private var lastHeldId: String? = null

	/** True for the tick after swapping to the Breaker, when the attack waits. */
	private var justSwapped = false

	/** A held attack stopped on a secret, to be pressed again once off it. */
	private var pausedOnSecret = false

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		// Refusing the attack here means the client never sends the break, so
		// there is nothing for the server to undo and nothing to flicker.
		AttackBlockCallback.EVENT.register { player, level, hand, pos, _ ->
			if (!active() || !protectSecrets.value) return@register InteractionResult.PASS
			if (heldId(player.getItemInHand(hand)) != DUNGEON_BREAKER) return@register InteractionResult.PASS
			if (isProtected(pos, level.getBlockState(pos))) InteractionResult.FAIL else InteractionResult.PASS
		}

		// Remove on hit: after the protection above, so a refused hit never
		// gets this far.
		AttackBlockCallback.EVENT.register { player, level, hand, pos, _ ->
			if (!active() || !removeOnHit()) return@register InteractionResult.PASS
			if (heldId(player.getItemInHand(hand)) != DUNGEON_BREAKER) return@register InteractionResult.PASS
			val state = level.getBlockState(pos)
			if (protectSecrets.value && isProtected(pos, state)) return@register InteractionResult.PASS
			if (canInstantMine(pos, state)) removeAtOnce(level, pos, state)
			InteractionResult.PASS
		}

		ClientTickEvents.START_CLIENT_TICK.register(::tick)
	}

	private fun active(): Boolean = module.enabled && DungeonLocation.inDungeon

	private fun instantMine(): Boolean = zeroPing.value && method.selectedIndex == METHOD_INSTANT_MINE

	private fun removeOnHit(): Boolean = zeroPing.value && method.selectedIndex == METHOD_REMOVE_ON_HIT

	private fun instantMineActive(): Boolean = active() && instantMine()

	private fun heldId(stack: ItemStack): String? =
		stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getString("id").getOrNull()

	private fun holdsBreaker(): Boolean {
		val player = Minecraft.getInstance().player ?: return false
		return heldId(player.mainHandItem) == DUNGEON_BREAKER
	}

	private fun tick(client: Minecraft) {
		justSwapped = false
		val id = client.player?.mainHandItem?.let(::heldId)
		if (id == lastHeldId) return

		val wasBreaker = lastHeldId == DUNGEON_BREAKER
		lastHeldId = id
		holdingBreaker = active() && id == DUNGEON_BREAKER
		if (wasBreaker && id != DUNGEON_BREAKER) {
			// Swapped away mid-sweep: the paused click belongs to the new item.
			if (pausedOnSecret) queueAttackClick()
			pausedOnSecret = false
		} else if (id == DUNGEON_BREAKER && instantMineActive()) {
			justSwapped = true
			clearBreakCooldowns()
		}
	}

	// ---- Called from the mixins -------------------------------------------

	/**
	 * A click of the attack button, before it does anything. True refuses it:
	 * the crosshair is on a secret.
	 */
	@JvmStatic
	fun onStartAttack(): Boolean {
		holdingBreaker = active() && holdsBreaker()
		if (!holdingBreaker) return false
		if (instantMine()) {
			clearBreakCooldowns()
			syncCarriedItem()
		}
		val hit = blockHit() ?: return false
		val level = Minecraft.getInstance().level ?: return false
		return protectSecrets.value && isProtected(hit.blockPos, level.getBlockState(hit.blockPos))
	}

	@JvmStatic
	fun onAttackFinished() {
		if (holdingBreaker && instantMineActive()) clearBreakCooldowns()
	}

	/** The start of the game's input handling, each tick. */
	@JvmStatic
	fun onHandleKeybinds() {
		holdingBreaker = active() && holdsBreaker()
		if (holdingBreaker && instantMine()) clearBreakCooldowns()
	}

	/** About to start breaking a block: the server has to know the Breaker is held first. */
	@JvmStatic
	fun onStartDestroyBlock() {
		if (instantMineActive() && holdsBreaker()) syncCarriedItem()
	}

	/**
	 * Whether the held attack button keeps mining this tick, given that the
	 * game thinks it should be [down].
	 *
	 * On a secret it stops, and is pressed again once the crosshair is off it,
	 * so a sweep carries on past the chest without taking it. With Zero ping
	 * on, it also stops on a block the Breaker cannot break.
	 */
	@JvmStatic
	fun continueAttack(down: Boolean): Boolean {
		if (!down || !holdingBreaker || !active()) {
			pausedOnSecret = false
			return down
		}
		val hit = blockHit()
		val level = Minecraft.getInstance().level
		if (hit != null && level != null) {
			val state = level.getBlockState(hit.blockPos)
			if (protectSecrets.value && isProtected(hit.blockPos, state)) {
				pausedOnSecret = true
				return false
			}
			if (instantMine() && !canInstantMine(hit.blockPos, state)) return false
		}
		if (pausedOnSecret) {
			queueAttackClick()
			pausedOnSecret = false
			return false
		}
		return true
	}

	/** True while the attack button should read as not pressed: the tick after a swap. */
	@JvmStatic
	fun waitsAfterSwap(): Boolean = justSwapped

	/**
	 * Whether the local player's mining speed against [state] should be the
	 * Breaker's instant one: Zero ping on, the Breaker held, and the block in
	 * the crosshair one it can break.
	 */
	@JvmStatic
	fun instantSpeed(state: BlockState): Boolean {
		if (!instantMineActive() || !holdsBreaker()) return false
		val hit = blockHit() ?: return false
		val level = Minecraft.getInstance().level ?: return false
		val target = level.getBlockState(hit.blockPos)
		if (target != state) return false
		return canInstantMine(hit.blockPos, target)
	}

	// ---- Rules --------------------------------------------------------------

	private fun blockHit(): BlockHitResult? =
		(Minecraft.getInstance().hitResult as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK }

	/**
	 * A secret, or a block that should never be hit by accident. The levers
	 * Floor 7 wants hit — Goldor's terminal sections' and the lights device's —
	 * are not, which is Lumen's exception.
	 */
	private fun isProtected(pos: BlockPos, state: BlockState): Boolean {
		val block = state.block
		if (block !in protectedBlocks) return false
		if (block != Blocks.LEVER) return true
		if (DungeonLocation.floor != 7 || DungeonLocation.inDungeon.not()) return true
		if (pos in p3Levers) return false
		val lightsDevice = pos.x in 58..62 && pos.y in 133..136 && pos.z == 142
		return !lightsDevice
	}

	/**
	 * Whether the Breaker breaks [pos] in one hit, after Lumen: never in a
	 * fairy room, only iron bars in a puzzle room except Water Board and Tic
	 * Tac Toe, and nothing protected, obsidian, a button, a block entity or a
	 * plant anywhere. The F7 levers and coal blocks always.
	 */
	private fun canInstantMine(pos: BlockPos, state: BlockState): Boolean {
		if (state.isAir) return false
		if (state.`is`(Blocks.COAL_BLOCK) || pos in p3Levers) return true

		val room = DungeonFloor.roomAt(DungeonFloor.tileOf(pos.x.toDouble(), pos.z.toDouble()))
		val name = room?.displayName()
		if (name == "Water Board" || name == "Tic Tac Toe") return true
		when (room?.type) {
			DungeonRoom.Type.PUZZLE -> return state.block is IronBarsBlock
			DungeonRoom.Type.FAIRY -> return false
			else -> Unit
		}

		val block = state.block
		if (block in protectedBlocks || block == Blocks.OBSIDIAN) return false
		if (state.`is`(BlockTags.BUTTONS) || state.`is`(BlockTags.COPPER_CHESTS)) return false
		return unbreakableKinds.none { it.isInstance(block) }
	}

	/**
	 * Remove on hit: takes a mined block off your screen without waiting for
	 * the server. NoammAddons' method (CC0).
	 *
	 * The break still goes to the server exactly as it would have; this only
	 * stops the client drawing a block that, as far as the run is concerned,
	 * is already gone. It is outside the game's own prediction, so a break the
	 * server refuses only comes back if the server sends the block again.
	 */
	private fun removeAtOnce(level: Level, pos: BlockPos, state: BlockState) {
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

	private fun queueAttackClick() {
		val key = (Minecraft.getInstance().options.keyAttack as KeyMappingAccessor).`cryptic$key`()
		KeyMapping.click(key)
	}

	/** The game's pauses between breaks and between missed swings, both cleared. */
	private fun clearBreakCooldowns() {
		val client = Minecraft.getInstance()
		client.missTime = 0
		(client.gameMode as? MultiPlayerGameModeAccessor)?.`cryptic$setDestroyDelay`(0)
	}

	private fun syncCarriedItem() {
		(Minecraft.getInstance().gameMode as? MultiPlayerGameModeAccessor)?.`cryptic$ensureHasSentCarriedItem`()
	}
}
