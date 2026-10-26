package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.Floor7
import imicro.cryptic.skyblock.ServerStats
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.item.ShovelItem
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3

/**
 * Glides the camera (and your model in third person) from where a teleport started to where it
 * lands, instead of snapping. Only what's drawn moves; nothing extra is sent.
 *
 * - **Predictive** sets off on the click towards the predicted landing and arrives about a ping
 * later, when the server's answer is due. A click before the last teleport landed adds a leg from
 * the end of the last one. With no answer in time, gliding stops until the server teleports you.
 * - **Responsive** waits for the server, then closes in from where the camera was over the
 * Smoothing time. Only a teleport that follows a click with a teleport item glides, so a lag-back
 * or a warp while holding one still snaps.
 *
 * Either way, a glide that would put the camera inside a block is lifted onto it, and etherwarps
 * have their own smoothing time. Inspired by Skyblocker's Smooth AOTE; the click arming, the lift
 * and the separate etherwarp time are ideas from NEU's.
 */
object SmoothTeleport {
	/** The teleport kinds, as the Etherwarp card names them. */
	const val INSTANT = "Instant Transmission"
	const val ETHER = "Etherwarp"
	const val IMPACT = "Wither Impact"
	const val SINRECALL = "Sinrecall"
	const val WEIRD = "Weird Transmission"

	val KINDS = listOf(INSTANT, ETHER, IMPACT, SINRECALL, WEIRD)

	private fun nameOf(kind: EtherwarpZeroPing.Kind): String = when (kind) {
		EtherwarpZeroPing.Kind.ETHERWARP -> ETHER
		EtherwarpZeroPing.Kind.TRANSMISSION -> INSTANT
		EtherwarpZeroPing.Kind.IMPACT -> IMPACT
		EtherwarpZeroPing.Kind.SINRECALL -> SINRECALL
		EtherwarpZeroPing.Kind.WEIRD -> WEIRD
	}

	/** The longest a glide is given, whatever the ping says. */
	private const val MAX_GLIDE_MS = 2_500L

	/** An NPC this close along the view takes the click instead of the item. */
	private const val NPC_REACH = 4.0

	/** A server teleport this long after a teleport click is that click's; later ones snap. */
	private const val ARM_MILLIS = 1_000L

	/** How far above a block's top a lifted camera sits. */
	private const val LIFT_MARGIN = 0.05

	/** How far under a ceiling a pushed-down camera sits; clear of the view's near plane. */
	private const val DROP_MARGIN = 0.15

	private const val STANDING_EYES = 1.62
	private const val SNEAKING_EYES = 1.27

	private val client: Minecraft get() = Minecraft.getInstance()

	val predictive: Boolean
		get() = Etherwarp.module.enabled && Etherwarp.smoothTeleport.value && Etherwarp.smoothPredictive.value

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		UseItemCallback.EVENT.register { _, level, _ ->
			if (level.isClientSide) {
				noteClick()
				onClick()
			}
			InteractionResult.PASS
		}
		// A shovel on grass or dirt makes a path and the item is never used, so that click is
		// caught on the block instead.
		UseBlockCallback.EVENT.register { player, level, hand, hit ->
			if (level.isClientSide && player.getItemInHand(hand).item is ShovelItem &&
				level.getBlockState(hit.blockPos).block in PATH_BLOCKS
			) {
				noteClick()
				onClick()
			}
			InteractionResult.PASS
		}
	}

	private val PATH_BLOCKS = setOf(Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.PODZOL)

	/** Whether the camera may glide here, in this perspective. */
	private fun allowed(): Boolean {
		if (!Etherwarp.module.enabled || !Etherwarp.smoothTeleport.value) return false
		if (Etherwarp.smoothOutsideDungeons.value && DungeonLocation.inDungeon) return false
		return client.options.cameraType == CameraType.FIRST_PERSON || Etherwarp.smoothThirdPerson.value
	}

	/** False where Hypixel refuses teleports, so none is predicted. */
	private fun teleportsWorkHere(): Boolean {
		if (SkyblockLocation.area == "Mineshaft" || EtherwarpZeroPing.noTeleportZone() != null) return false
		if (!DungeonLocation.inDungeon) return true
		// Maxor's fight.
		if (DungeonLocation.floor == 7 && DungeonRun.inBoss && Floor7.phase == 1) return false
		return DungeonMap.currentRoom()?.data?.name !in NO_TELEPORT_ROOMS
	}

	private val NO_TELEPORT_ROOMS = setOf("Boulder", "Teleport Maze", "Old Trap", "New Trap")

	/** The last click with a ticked teleport item, and its kind, for Responsive. */
	private var clickedAt = 0L
	private var clickedKind: EtherwarpZeroPing.Kind? = null

	private fun noteClick() {
		val (kind, _) = heldTeleport() ?: return
		clickedAt = System.currentTimeMillis()
		clickedKind = kind
	}

	/** The Smoothing time for [kind]: etherwarps have their own. */
	private fun smoothMillis(kind: EtherwarpZeroPing.Kind?): Double =
		if (kind == EtherwarpZeroPing.Kind.ETHERWARP) Etherwarp.smoothTimeEther.value else Etherwarp.smoothTime.value

	private fun eyeHeight(player: Player): Double = if (player.isShiftKeyDown) SNEAKING_EYES else STANDING_EYES

	/** The held item's teleport and range, if it's one of the ticked kinds. */
	private fun heldTeleport(): Pair<EtherwarpZeroPing.Kind, Double>? {
		val player = client.player ?: return null
		val found = EtherwarpZeroPing.classify(player.mainHandItem, client.options.keyShift.isDown) ?: return null
		return found.takeIf { Etherwarp.smoothKinds.isSelected(nameOf(it.first)) }
	}

	// ---- Fake zpew conflicts ------------------------------------------------
	//
	// Fake zpew moves the camera to the landing at once; a glide moves it over time. Both can't own
	// the same teleport, so whichever switch was just turned on into a clash is turned back off,
	// with a message.

	/** The three pairs: a Fake zpew switch and the Smooth teleport kind it clashes with. */
	private val CONFLICTS: List<Pair<imicro.cryptic.gui.ToggleModuleSetting, String>> by lazy {
		listOf(
			Etherwarp.fakeZpew to ETHER,
			Etherwarp.fakeZpewTransmission to INSTANT,
			Etherwarp.fakeZpewImpact to IMPACT,
		)
	}

	/** Each switch's state last tick, to tell which was just turned on. Null before the first look. */
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
				// Both already on (an old saved setup), so the Smooth teleport kind gives way.
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

	private fun warn(turnedOff: String, kept: String) {
		Toasts.show("Teleport Features", "§f$turnedOff §7was turned back off: it can't be on with §f$kept§7.", color = Toasts.Colors.TELEPORT)
	}

	fun tick() {
		resolveConflicts()
		// A predicted glide lasts a ping, so the number is kept fresh while one could start.
		if (predictive && SkyblockLocation.onSkyblock) {
			ServerStats.wantFreshPing()
			ping = ServerStats.ping.toLong().coerceAtLeast(0L)
		}
	}

	// ---- Predictive -----------------------------------------------------------
	//
	// A chain of legs. Each click adds a leg: the camera sets off from wherever it's drawn now and
	// moves by that teleport's step, timed to the ping. The next leg's step is worked out from
	// where the server will have you after the steps so far.

	/**
	 * The leg under way: the server-side eyes it starts from, its step, where the camera set off
	 * from, and when.
	 */
	private class Leg(val eyes: Vec3, val step: Vec3, val cameraFrom: Vec3, val startedAt: Long, val duration: Long)

	private var leg: Leg? = null

	/** Teleports clicked that the server hasn't answered yet. */
	private var inFlight = 0
	private var lastAnswerAt = 0L

	/** Set when a teleport went unanswered: no gliding until the server teleports you. */
	private var standingDown = false

	private var ping = 0L

	private fun onClick() {
		val player = client.player ?: return
		if (client.level == null || !predictive || standingDown) return
		if (!allowed() || !teleportsWorkHere()) return
		val (kind, range) = heldTeleport() ?: return
		if (EtherwarpZeroPing.outOfMana()) return

		val now = System.currentTimeMillis()
		val previous = leg
		val chained = inFlight > 0 && previous != null
		val eyes: Vec3
		val cameraFrom: Vec3
		if (chained) {
			eyes = previous.eyes.add(previous.step)
			cameraFrom = predictivePosition(now) ?: client.gameRenderer.mainCamera().position()
		} else {
			eyes = player.position().add(0.0, eyeHeight(player), 0.0)
			cameraFrom = client.gameRenderer.mainCamera().position()
			lastAnswerAt = now
		}

		val look = player.calculateViewVector(player.xRot, player.yRot)
		if (npcTakesClick(player, eyes, look)) {
			leg = null
			return
		}

		val landing = landingEyes(kind, range, eyes, look, eyeHeight(player))
		if (landing == null) {
			leg = null
			return
		}
		leg = Leg(eyes, landing.subtract(eyes), cameraFrom, now, ping)
		inFlight++
		imicro.cryptic.debug.TeleportLog.glide(cameraFrom, cameraFrom.add(landing.subtract(eyes)), ping)
	}

	/** Where your eyes end up after the teleport, or null if it goes nowhere. */
	private fun landingEyes(kind: EtherwarpZeroPing.Kind, range: Double, eyes: Vec3, look: Vec3, eyeHeight: Double): Vec3? {
		val feet = eyes.subtract(0.0, eyeHeight, 0.0)
		val landed = if (kind == EtherwarpZeroPing.Kind.ETHERWARP) {
			val target = EtherwarpHelper.target(feet, look, range)
			if (!target.valid) return null
			EtherwarpHelper.landing(target.pos ?: return null)
		} else {
			InstantTransmissionHelper.predict(range, feet, look) ?: return null
		}
		return landed.add(0.0, eyeHeight, 0.0)
	}

	/** Whether the click lands on an NPC (one with a "CLICK" tag), which takes it. */
	private fun npcTakesClick(player: Player, eyes: Vec3, look: Vec3): Boolean {
		val level = player.level()
		val reach = eyes.add(look.scale(NPC_REACH))
		val wall = level.clip(ClipContext(eyes, reach, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).location
		val search = player.boundingBox.expandTowards(look.scale(NPC_REACH)).inflate(1.0)
		val hit = ProjectileUtil.getEntityHitResult(player, eyes, reach, search, { !it.isSpectator && it !== player }, eyes.distanceToSqr(wall))
			?: return false
		return level.getEntitiesOfClass(ArmorStand::class.java, hit.entity.boundingBox.inflate(0.0, 2.0, 0.0)) { !it.isPassenger }
			.any { it.name.string == "CLICK" }
	}

	/** A server teleport, applied: one fewer in flight, and the chain may end. */
	private fun onAnswer() {
		inFlight = maxOf(0, inFlight - 1)
		lastAnswerAt = System.currentTimeMillis()
		standingDown = false
		if (inFlight > 0) return
		val current = leg ?: return
		// A little of the glide left: let it finish instead of jumping to the end.
		val left = current.startedAt + current.duration - lastAnswerAt
		if (left > 0 && left <= Etherwarp.smoothTime.value) return
		leg = null
	}

	/** Where the predicted glide has the camera now, or null when not gliding. */
	private fun predictivePosition(now: Long): Vec3? {
		val current = leg ?: return null
		if (standingDown || client.player == null) return null
		val grace = Etherwarp.smoothTime.value.toLong()

		// No answer in time: that click didn't teleport. Stand down until one does.
		val patience = minOf(maxOf(ping, current.duration) + grace * inFlight, MAX_GLIDE_MS)
		if (now - lastAnswerAt > patience) {
			standingDown = true
			leg = null
			inFlight = 0
			return null
		}

		val duration = minOf(current.duration, MAX_GLIDE_MS)
		val elapsed = now - current.startedAt
		if (inFlight == 0 && elapsed >= duration + grace) {
			leg = null
			return null
		}
		val t = if (duration <= 0) 1.0 else (elapsed.toDouble() / duration).coerceIn(0.0, 1.0)
		return current.cameraFrom.add(current.step.scale(t))
	}

	/** Your model during a predicted glide: on the server-side path, a share of the step along. */
	private fun predictivePlayer(): Vec3? {
		val current = leg ?: return null
		val player = client.player ?: return null
		val camera = predictivePosition(System.currentTimeMillis()) ?: return null
		val travelled = camera.subtract(current.cameraFrom)
		return current.eyes.subtract(0.0, eyeHeight(player), 0.0).add(travelled)
	}

	// ---- Responsive -----------------------------------------------------------

	/**
	 * A catch-up after a server teleport: each frame closes the share of the remaining distance
	 * that the time passed is of the time left, so it arrives on time at an even pace even while
	 * you move.
	 */
	private class CatchUp(var at: Vec3, val startedAt: Long, val duration: Double) {
		var done = 0.0
	}

	private var catchUp: CatchUp? = null

	/** Where the camera really was last frame, for the model's offset in third person. */
	private var lastReal: Vec3? = null

	private fun startCatchUp() {
		val now = System.currentTimeMillis()
		// Ping-long answers still count, however slow the server.
		val window = maxOf(ARM_MILLIS, ping + ARM_MILLIS / 2)
		if (clickedAt == 0L || now - clickedAt > window) return
		val duration = smoothMillis(clickedKind)
		if (duration <= 0.0 || !allowed()) return
		val camera = client.gameRenderer.mainCamera().position()
		val from = responsivePosition(camera) ?: camera
		catchUp = CatchUp(from, now, duration)
		// One click, one glide.
		clickedAt = 0L
	}

	private fun responsivePosition(real: Vec3): Vec3? {
		val current = catchUp ?: return null
		lastReal = real
		val progress = (System.currentTimeMillis() - current.startedAt) / current.duration.coerceAtLeast(1.0)
		if (progress >= 1.0) {
			catchUp = null
			return null
		}
		val share = (progress - current.done) / (1.0 - current.done)
		val next = current.at.add(real.subtract(current.at).scale(share))
		current.at = next
		current.done = progress
		return next
	}

	private fun responsivePlayer(partialTick: Float): Vec3? {
		val player = client.player ?: return null
		val real = lastReal ?: return null
		val camera = responsivePosition(real) ?: return null
		return player.getPosition(partialTick).add(camera.subtract(real))
	}

	// ---- Hooks ------------------------------------------------------------------

	/** The server's position packet arriving, before it's applied. */
	@JvmStatic
	fun beforeServerTeleport() {
		if (!client.isSameThread) return
		if (Etherwarp.module.enabled && Etherwarp.smoothTeleport.value && !Etherwarp.smoothPredictive.value) startCatchUp()
	}

	/** The server's position packet, applied. */
	@JvmStatic
	fun afterServerTeleport() {
		if (!client.isSameThread) return
		onAnswer()
	}

	/**
	 * The last positions handed out. Kept in fields so they always escape: the hooks pick between
	 * these and the real position, and Java 25's JIT miscompiles that pick when one side is a fresh
	 * allocation it could have optimised away (it crashed the game with a null position).
	 */
	private var handedCamera: Vec3? = null
	private var handedPlayer: Vec3? = null

	/** The camera's position while gliding, or null to leave it alone. */
	@JvmStatic
	fun cameraPosition(original: Vec3): Vec3? {
		if (!Etherwarp.module.enabled || !Etherwarp.smoothTeleport.value) return null
		val glided = if (Etherwarp.smoothPredictive.value) predictivePosition(System.currentTimeMillis()) else responsivePosition(original)
		handedCamera = glided?.let(::outOfBlocks)
		return handedCamera
	}

	/**
	 * Moves a glide point that's inside a block out of it, so its inside never shows. The straight
	 * line up a ledge or stairs cuts through the corner and is lifted onto the top, as NEU does; a
	 * ceiling (open below, solid above) pushes the camera down under it instead, or a low ceiling
	 * would throw the view up through the roof.
	 */
	private fun outOfBlocks(point: Vec3): Vec3 {
		val level = client.level ?: return point
		val pos = net.minecraft.core.BlockPos.containing(point)
		val shape = level.getBlockState(pos).getCollisionShape(level, pos)
		if (shape.isEmpty) return point
		val top = pos.y + shape.max(net.minecraft.core.Direction.Axis.Y)
		val bottom = pos.y + shape.min(net.minecraft.core.Direction.Axis.Y)
		if (point.y < bottom || point.y >= top) return point
		fun open(at: net.minecraft.core.BlockPos) = level.getBlockState(at).getCollisionShape(level, at).isEmpty
		val openAbove = top < pos.y + 1.0 || open(pos.above())
		val openBelow = bottom > pos.y || open(pos.below())
		val drop = when {
			openBelow && !openAbove -> true
			openAbove && !openBelow -> false
			// Open on both sides or neither: the nearer face.
			else -> point.y - bottom < top - point.y
		}
		return if (drop) Vec3(point.x, bottom - DROP_MARGIN, point.z) else Vec3(point.x, top + LIFT_MARGIN, point.z)
	}

	/** Your model's position in third person, riding along with the camera. */
	@JvmStatic
	fun playerPosition(partialTick: Float): Vec3? {
		if (!Etherwarp.module.enabled || !Etherwarp.smoothTeleport.value) return null
		if (client.options.cameraType.isFirstPerson) return null
		handedPlayer = if (Etherwarp.smoothPredictive.value) predictivePlayer() else responsivePlayer(partialTick)
		return handedPlayer
	}

	fun forget() {
		leg = null
		inFlight = 0
		standingDown = false
		catchUp = null
		clickedAt = 0L
	}
}
