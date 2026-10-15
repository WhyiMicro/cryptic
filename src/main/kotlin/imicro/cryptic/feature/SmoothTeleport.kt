package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.Floor7
import imicro.cryptic.skyblock.ServerStats
import imicro.cryptic.skyblock.SkyblockItem
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.util.Mth
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntitySelector
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CarpetBlock
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.level.block.WebBlock
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Glides the camera, and your own model in third person, from where a teleport
 * started to where it lands, instead of snapping.
 *
 * Skyblocker's Smooth AOTE (LGPL-3.0), both of its modes, as Skyblocker has them.
 *
 * **Predictive** starts on the click. The landing spot is worked out the way
 * Hypixel walks a teleport — a block at a time along your view, stopping at a
 * wall, a low ceiling or the floor — and the camera sets off towards it at
 * once, timed to arrive about when the server's answer will, which is your
 * ping. A teleport clicked before the last one landed chains on from its end.
 * If the server never answers, the glide stands down until it does.
 *
 * **Responsive** waits for the server. When its teleport arrives with an
 * enabled item in hand, the camera sets off from wherever it is and closes the
 * same share of the remaining distance each frame as time has passed of the
 * time remaining, so it lands on time at an even pace.
 *
 * Only what is drawn moves. The player is wherever the server says, and
 * nothing is sent that would not have been.
 */
object SmoothTeleport {
	/** The teleport kinds, as the Etherwarp card names them: Skyblocker's five. */
	const val INSTANT = "Instant Transmission"
	const val ETHER = "Etherwarp"
	const val IMPACT = "Wither Impact"
	const val SINRECALL = "Sinrecall"
	const val WEIRD = "Weird Transmission"

	val KINDS = listOf(INSTANT, ETHER, IMPACT, SINRECALL, WEIRD)

	private val WITHER_BLADES = setOf("NECRON_BLADE", "ASTRAEA", "HYPERION", "SCYLLA", "VALKYRIE")

	/** The longest a predicted teleport is given, whatever the ping says. */
	private const val MAX_TELEPORT_TIME = 2_500L

	private val client: Minecraft get() = Minecraft.getInstance()

	/** True while the predictive mode is the one in use. */
	val predictive: Boolean
		get() = Etherwarp.module.enabled && Etherwarp.smoothTeleport.value && Etherwarp.smoothPredictive.value

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		UseItemCallback.EVENT.register { player, level, hand ->
			if (level.isClientSide) calculateTeleportUse()
			InteractionResult.PASS
		}
		// A shovel turning grass or dirt into a path takes the click, so the
		// item is never used: its teleport is caught on the block instead.
		UseBlockCallback.EVENT.register { player, level, hand, hit ->
			if (level.isClientSide) {
				val stack = player.getItemInHand(hand)
				val block = level.getBlockState(hit.blockPos).block
				if (isShovel(stack.item) && block in SHOVEL_BLOCKS) calculateTeleportUse()
			}
			InteractionResult.PASS
		}
	}

	// ---- Fake zpew conflicts ------------------------------------------------
	//
	// Fake zpew puts the camera on the landing spot the moment you click; a
	// glide moves it there over time. The two cannot both have the camera for
	// the same teleport, so neither is allowed to win quietly: whichever switch
	// was just turned on into a clash is turned back off, and you are told.

	/** The three pairs: a Fake zpew switch, and the Smooth teleport kind it clashes with. */
	private val CONFLICTS: List<Pair<imicro.cryptic.gui.ToggleModuleSetting, String>> by lazy {
		listOf(
			Etherwarp.fakeZpew to ETHER,
			Etherwarp.fakeZpewTransmission to INSTANT,
			Etherwarp.fakeZpewImpact to IMPACT,
		)
	}

	/** What each switch was last tick, to tell which one was just turned on. Null before the first look. */
	private var lastFake: List<Boolean>? = null
	private var lastKinds: List<Boolean>? = null
	private var lastSmooth = false

	private fun resolveConflicts() {
		val smoothOn = Etherwarp.smoothTeleport.value
		val fake = CONFLICTS.map { it.first.value }
		val kinds = CONFLICTS.map { Etherwarp.smoothKinds.isSelected(it.second) }
		val previousFake = lastFake
		val previousKinds = lastKinds

		CONFLICTS.forEachIndexed { index, (fakeSetting, kind) ->
			if (!(fake[index] && smoothOn && kinds[index])) return@forEachIndexed
			val zpewName = "Fake zpew (${fakeSetting.label})"
			val smoothName = "Smooth teleport ($kind)"
			when {
				previousFake != null && !previousFake[index] -> {
					fakeSetting.value = false
					warn(zpewName, smoothName)
				}
				previousKinds != null && !previousKinds[index] -> {
					Etherwarp.smoothKinds.toggle(Etherwarp.smoothKinds.options.indexOf(kind))
					warn(smoothName, zpewName)
				}
				previousFake != null && !lastSmooth -> {
					Etherwarp.smoothTeleport.value = false
					warn("Smooth teleport", zpewName)
				}
				// Both already on — a saved setup from before the two were kept
				// apart — so the newer of the two gives way.
				else -> {
					Etherwarp.smoothKinds.toggle(Etherwarp.smoothKinds.options.indexOf(kind))
					warn(smoothName, zpewName)
				}
			}
		}

		lastFake = CONFLICTS.map { it.first.value }
		lastKinds = CONFLICTS.map { Etherwarp.smoothKinds.isSelected(it.second) }
		lastSmooth = Etherwarp.smoothTeleport.value
	}

	/** Says that [turnedOff] was switched back off because it cannot be on with [kept]. */
	private fun warn(turnedOff: String, kept: String) {
		Toasts.show("Etherwarp", "§f$turnedOff §7was turned back off: it can't be on with §f$kept§7.", color = Toasts.Colors.TELEPORT)
		if (!Toasts.module.enabled) {
			client.gui.hud.chat.addClientSystemMessage(net.minecraft.network.chat.Component.literal("§8[Cryptic] §c$turnedOff was turned back off: it can't be on with $kept."))
		}
	}

	/** Keeps the ping measured while the predictive mode needs it to time a glide. */
	fun tick() {
		resolveConflicts()
		if (predictive && SkyblockLocation.onSkyblock) lastPing = ServerStats.ping.toLong().coerceAtLeast(0L)
	}

	// ---- Which items -------------------------------------------------------

	/**
	 * How far the held item teleports, or -1 when it does not or its kind is not
	 * ticked: Skyblocker's table. An Aspect only etherwarps when merged and
	 * sneaking, and a Transmission Tuner adds its blocks to either.
	 */
	private fun itemDistance(): Int {
		val stack = client.player?.mainHandItem ?: return -1
		val data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
		fun on(kind: String) = Etherwarp.smoothKinds.isSelected(kind)
		return when (val id = SkyblockItem.id(stack)) {
			"ASPECT_OF_THE_LEECH_1" -> if (on(WEIRD)) 3 else -1
			"ASPECT_OF_THE_LEECH_2" -> if (on(WEIRD)) 4 else -1
			"ASPECT_OF_THE_END", "ASPECT_OF_THE_VOID" ->
				if (client.options.keyShift.isDown && data.getIntOr("ethermerge", 0) == 1) {
					if (on(ETHER)) tuned(data, 57) else -1
				} else {
					if (on(INSTANT)) tuned(data, 8) else -1
				}
			"ETHERWARP_CONDUIT" -> if (on(ETHER)) tuned(data, 57) else -1
			"SINSEEKER_SCYTHE" -> if (on(SINRECALL)) tuned(data, 4) else -1
			else -> if (id in WITHER_BLADES && on(IMPACT)) 10 else -1
		}
	}

	private fun tuned(data: CompoundTag, base: Int): Int = base + data.getIntOr("tuned_transmission", 0)

	private fun isShovel(item: net.minecraft.world.item.Item): Boolean =
		item == Items.WOODEN_SHOVEL || item == Items.STONE_SHOVEL || item == Items.IRON_SHOVEL ||
			item == Items.GOLDEN_SHOVEL || item == Items.DIAMOND_SHOVEL

	private val SHOVEL_BLOCKS = setOf(Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.PODZOL)

	/** Whether the camera may glide at all, in this place and perspective. */
	private fun allowed(): Boolean {
		if (!Etherwarp.module.enabled || !Etherwarp.smoothTeleport.value) return false
		if (Etherwarp.smoothOutsideDungeons.value && DungeonLocation.inDungeon) return false
		if (client.options.cameraType != CameraType.FIRST_PERSON && !Etherwarp.smoothThirdPerson.value) return false
		return true
	}

	/**
	 * Where teleports are refused, so none is predicted: Skyblocker's list as
	 * Cryptic can tell it — Glacite mineshafts, and in a dungeon the boulder
	 * room, the teleport maze, the trap rooms and Maxor's fight.
	 */
	private fun allowedLocation(): Boolean {
		if (SkyblockLocation.area == "Mineshaft") return false
		if (!DungeonLocation.inDungeon) return true
		if (DungeonLocation.floor == 7 && DungeonRun.inBoss && Floor7.phase == 1) return false
		val room = DungeonMap.currentRoom()?.data?.name ?: return true
		return room != "Boulder" && room != "Teleport Maze" && room != "Old Trap" && room != "New Trap"
	}

	private fun eyeHeight(player: Player): Double = if (player.isShiftKeyDown) 1.27 else 1.62

	// ---- Predictive --------------------------------------------------------

	private var startTime = 0L
	private var startPos: Vec3? = null
	private var cameraStartPos: Vec3? = null
	private var teleportVector: Vec3? = null
	private var lastPing = 0L
	private var currentTeleportPing = 0L
	private var teleportsAhead = 0
	private var lastTeleportTime = 0L
	private var teleportDisabled = false

	/** A right click with an item, which may be a teleport to start gliding towards. */
	private fun calculateTeleportUse() {
		val player = client.player ?: return
		if (client.level == null || !predictive || teleportDisabled) return
		if (!allowed() || !allowedLocation()) return

		val distance = itemDistance()
		if (distance == -1) return
		if (EtherwarpZeroPing.outOfMana()) return

		// The start of a chain, or the next link from the end of the last one.
		if (teleportsAhead == 0 || startPos == null || teleportVector == null) {
			startPos = player.position().add(0.0, eyeHeight(player), 0.0)
			cameraStartPos = client.gameRenderer.mainCamera().position()
			lastTeleportTime = System.currentTimeMillis()
		} else {
			startPos = startPos!!.add(teleportVector!!)
			cameraStartPos = interpolatedPredictive()
		}
		currentTeleportPing = lastPing
		startTime = System.currentTimeMillis()

		val start = startPos ?: return
		val look = player.calculateViewVector(player.xRot, player.yRot)

		// Talking to an NPC takes the click, and nobody goes anywhere.
		if (isTargetingNpc(player, 4.0, start, look)) {
			startPos = null
			teleportVector = null
			return
		}

		var vector = raycast(distance, look, start, false)
		if (vector == null) {
			startPos = null
			return
		}

		// Hypixel lands you on the middle of a block, at its own eye height.
		val end = start.add(vector)
		val offset = Vec3(
			end.x - roundToCenter(end.x),
			end.y - (ceil(end.y) + eyeHeight(player) - 1),
			end.z - roundToCenter(end.z),
		)
		vector = vector.subtract(offset)
		teleportVector = vector
		teleportsAhead += 1
	}

	/** The server's teleport, applied: one fewer in flight, and the chain may end. */
	private fun predictiveTeleported() {
		teleportsAhead = maxOf(0, teleportsAhead - 1)
		lastTeleportTime = System.currentTimeMillis()
		teleportDisabled = false
		if (teleportsAhead == 0) {
			// A little of the glide left: let it finish rather than jump to the end.
			val timeLeft = currentTeleportPing - (System.currentTimeMillis() - startTime)
			if (timeLeft > 0 && timeLeft <= Etherwarp.smoothTime.value) return
			startPos = null
			teleportVector = null
		}
	}

	/** Where the predictive glide has the camera now, or null when it is not gliding. */
	private fun interpolatedPredictive(): Vec3? {
		val vector = teleportVector ?: return null
		val cameraStart = cameraStartPos ?: return null
		if (startPos == null || teleportDisabled || client.player == null) return null

		val now = System.currentTimeMillis()
		val gap = now - startTime
		// No answer in time: this was no teleport. Stop until one really happens.
		val patience = minOf(maxOf(lastPing, currentTeleportPing) + Etherwarp.smoothTime.value.toLong() * teleportsAhead, MAX_TELEPORT_TIME)
		if (now - lastTeleportTime > patience) {
			teleportDisabled = true
			startPos = null
			teleportVector = null
			teleportsAhead = 0
			return null
		}
		val estimated = minOf(currentTeleportPing, MAX_TELEPORT_TIME)
		val percentage = if (estimated <= 0) 1.0 else (gap.toDouble() / estimated).coerceIn(0.0, 1.0)

		if (teleportsAhead == 0 && gap >= estimated + Etherwarp.smoothTime.value) {
			startPos = null
			teleportVector = null
			return null
		}
		return cameraStart.add(vector.scale(percentage))
	}

	private fun interpolatedPredictivePlayer(): Vec3? {
		val start = startPos ?: return null
		val cameraStart = cameraStartPos ?: return null
		val player = client.player ?: return null
		val difference = start.subtract(0.0, eyeHeight(player), 0.0).subtract(cameraStart)
		return interpolatedPredictive()?.add(difference)
	}

	/** Whether the click would land on an NPC, which takes it instead of the item. */
	private fun isTargetingNpc(player: Player, maxDistance: Double, start: Vec3, look: Vec3): Boolean {
		val level = player.level()
		val end = start.add(look.scale(maxDistance))
		val blockDistance = level.clip(ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
			.location.distanceTo(start)
		val box = player.boundingBox.expandTowards(look.scale(maxDistance)).inflate(1.0)
		val hit = ProjectileUtil.getEntityHitResult(player, start, end, box, { !it.isSpectator && it !== player }, Mth.square(blockDistance))
			?: return false
		return level.getEntitiesOfClass(ArmorStand::class.java, hit.entity.boundingBox.inflate(0.0, 2.0, 0.0), EntitySelector.ENTITY_NOT_BEING_RIDDEN)
			.any { it.name.string == "CLICK" }
	}

	private fun roundToCenter(value: Double): Double = Math.round(value - 0.5).toDouble() + 0.5

	/**
	 * Walks a teleport a block at a time, the way Hypixel does, and returns how
	 * far it goes: Skyblocker's raycast, line for line.
	 */
	private fun raycast(distance: Int, direction: Vec3, start: Vec3, isEtherwarp: Boolean): Vec3? {
		if (client.level == null) return null
		val xDiagonal = if (direction.x > 0) BlockPos(1, 0, 0) else BlockPos(-1, 0, 0)
		val zDiagonal = if (direction.z > 0) BlockPos(0, 0, 1) else BlockPos(0, 0, -1)
		var closeFloorY = Int.MAX_VALUE

		var offset = 0.0
		while (offset <= distance) {
			val pos = start.add(direction.scale(offset))
			val check = BlockPos.containing(pos)

			if (!canTeleportThrough(check)) {
				if (!isEtherwarp && offset == 0.0) return null
				if (isEtherwarp) return direction.scale(offset - 1).add(direction)
				return direction.scale(offset - 1)
			}

			if (!canTeleportThrough(check.above()) && !isEtherwarp) {
				if (offset == 0.0) {
					// Too low a start to judge the ceiling from: carry on.
					val justAhead = start.add(direction.scale(0.2))
					if (justAhead.y - floor(justAhead.y) <= 0.495) {
						offset++
						continue
					}
					return null
				}
				return direction.scale(offset - 1)
			}

			// Diagonal walls, which Hypixel lets you through from some directions only.
			if (offset != 0.0 && direction.x < 0 && isBlockFloor(check.east()) &&
				isBlockFloor(BlockPos.containing(pos.subtract(direction)).offset(zDiagonal))
			) {
				return direction.scale(offset - 1)
			}
			if (offset != 0.0 && direction.z < 0 && direction.x < 0 && isBlockFloor(check.south()) &&
				isBlockFloor(BlockPos.containing(pos.subtract(direction)).offset(xDiagonal))
			) {
				return direction.scale(offset - 1)
			}

			// Close above a floor: the teleport ends when it would drop below it.
			if ((isBlockFloor(check.below()) ||
					(isBlockFloor(check.below().offset(xDiagonal)) && isBlockFloor(check.below().offset(zDiagonal)))) &&
				pos.y - floor(pos.y) < 0.31
			) {
				closeFloorY = check.y - 1
			}
			if (closeFloorY == check.y) return direction.scale(offset - 1)

			offset++
		}

		// The full distance, a block lower when there is nothing to stand on —
		// which Hypixel has taken to doing.
		if (!isBlockFloor(BlockPos.containing(start.add(direction.scale(distance.toDouble())).subtract(0.0, 1.0, 0.0)))) {
			return direction.scale(distance.toDouble()).subtract(0.0, 1.0, 0.0)
		}
		return direction.scale(distance.toDouble())
	}

	/** Air, nothing to collide with, carpets, pots, cobwebs and thin snow. */
	private fun canTeleportThrough(pos: BlockPos): Boolean {
		val level = client.level ?: return false
		val state = level.getBlockState(pos)
		if (state.isAir) return true
		val block = state.block
		return state.getCollisionShape(level, pos).isEmpty ||
			block is CarpetBlock || block is FlowerPotBlock || block is WebBlock ||
			(block == Blocks.SNOW && state.getValue(BlockStateProperties.LAYERS) <= 3)
	}

	/** A block full height, or mud, which counts as a floor. */
	private fun isBlockFloor(pos: BlockPos): Boolean {
		val level = client.level ?: return false
		val state = level.getBlockState(pos)
		val shape = state.getCollisionShape(level, pos)
		if (shape.isEmpty) return false
		return shape.bounds().maxY >= 1 || state.block == Blocks.MUD
	}

	// ---- Responsive --------------------------------------------------------

	private var lastPosition: Vec3? = null
	private var lastProgress = 0.0
	private var responsiveStart = 0L
	private var responsiveCameraStart: Vec3? = null

	/** The server's teleport arriving, before it is applied: start a responsive glide. */
	private fun responsiveGoingToTeleport() {
		if (Etherwarp.smoothTime.value <= 0.0 || !allowed()) return
		if (itemDistance() == -1) return
		val camera = client.gameRenderer.mainCamera().position()
		lastPosition = interpolatedResponsive(camera) ?: camera
		lastProgress = 0.0
		responsiveStart = System.currentTimeMillis()
	}

	private fun interpolatedResponsive(original: Vec3): Vec3? {
		val last = lastPosition ?: return null
		responsiveCameraStart = original
		val progress = (System.currentTimeMillis() - responsiveStart) / Etherwarp.smoothTime.value.coerceAtLeast(1.0)
		if (progress >= 1.0) {
			lastPosition = null
			lastProgress = 0.0
			return null
		}
		val relative = (progress - lastProgress) / (1.0 - lastProgress)
		val next = last.add(original.subtract(last).scale(relative))
		lastPosition = next
		lastProgress = progress
		return next
	}

	private fun interpolatedResponsivePlayer(partialTick: Float): Vec3? {
		val player = client.player ?: return null
		val cameraStart = responsiveCameraStart ?: return null
		val difference = player.getPosition(partialTick).subtract(cameraStart)
		return interpolatedResponsive(cameraStart)?.add(difference)
	}

	// ---- Hooks ---------------------------------------------------------------

	/** The server's teleport arriving, on the client thread, before it is applied. */
	@JvmStatic
	fun beforeServerTeleport() {
		if (!client.isSameThread) return
		if (Etherwarp.module.enabled && Etherwarp.smoothTeleport.value && !Etherwarp.smoothPredictive.value) {
			responsiveGoingToTeleport()
		}
	}

	/** The server's teleport, applied. */
	@JvmStatic
	fun afterServerTeleport() {
		if (!client.isSameThread) return
		predictiveTeleported()
	}

	/** Where the camera is, given where it really is: what the camera hook hands back. */
	@JvmStatic
	fun cameraPosition(original: Vec3): Vec3? {
		if (!Etherwarp.module.enabled || !Etherwarp.smoothTeleport.value) return null
		return if (Etherwarp.smoothPredictive.value) interpolatedPredictive() else interpolatedResponsive(original)
	}

	/** Where your own model is drawn in third person, so it rides along with the camera. */
	@JvmStatic
	fun playerPosition(partialTick: Float): Vec3? {
		if (!Etherwarp.module.enabled || !Etherwarp.smoothTeleport.value) return null
		if (client.options.cameraType.isFirstPerson) return null
		return if (Etherwarp.smoothPredictive.value) interpolatedPredictivePlayer() else interpolatedResponsivePlayer(partialTick)
	}

	fun forget() {
		startPos = null
		teleportVector = null
		teleportsAhead = 0
		teleportDisabled = false
		lastPosition = null
		lastProgress = 0.0
	}
}
