package imicro.cryptic.feature

import imicro.cryptic.render.WorldRender
import imicro.cryptic.skyblock.SkyblockItem
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where an arrow or ender pearl would go: [BowTweaks]' bow trajectory and [Combat]'s pearl
 * trajectory. Stepped as Hypixel's 1.8 server moves them, and stopped at the first mob whose
 * hitbox the shot would clip. `/cryptic debug bow` records real shots against the prediction.
 */
object Trajectories {
	/** How a projectile flies on a 1.8 server, and how far to the side of the eyes it leaves. */
	private class Physics(
		val gravity: Double,
		val waterDrag: Double,
		val blocks: ClipContext.Block,
		val sideOffset: Double,
	)

	/**
	 * Arrows skip blocks without a collision box (grass, flowers); pearls stop on them. Hypixel fires
	 * arrows from straight below the eyes, without vanilla 1.8's 0.16 to the side (recorded shots
	 * fit within 0.05 without it and 0.15 off with it); pearls are thrown as vanilla throws them.
	 */
	private val ARROW = Physics(gravity = 0.05, waterDrag = 0.6, blocks = ClipContext.Block.COLLIDER, sideOffset = 0.0)
	private val PEARL = Physics(gravity = 0.03, waterDrag = 0.8, blocks = ClipContext.Block.OUTLINE, sideOffset = 0.16)

	private const val AIR_DRAG = 0.99
	private const val MAX_TICKS = 200

	/** 1.8 grows each entity's box by this much when a projectile checks it for a hit. */
	private const val HIT_MARGIN = 0.3

	private const val PEARL_SPEED = 1.5
	private const val FULL_ARROW_SPEED = 3.0

	/** Mobs further than this aren't checked; no shot gets that far before it falls. */
	private const val MOB_RANGE = 160.0

	/** The line starts this far along, so it begins ahead of the hand rather than behind the camera. */
	private const val LINE_START = 1.2
	private const val MARKER = 0.25

	/**
	 * A bow's volley: the arrow you aim, plus a pair [sideYaw] degrees either side of it. Record one
	 * with `/cryptic debug bow` to find another bow's spread.
	 */
	private class Volley(val name: String, val sideYaw: Float?) {
		val yawOffsets: List<Float> = if (sideYaw == null) listOf(0f) else listOf(-sideYaw, 0f, sideYaw)
	}

	private val SINGLE = Volley("single", null)
	private val VOLLEYS = mapOf(
		"TERMINATOR" to Volley("Terminator", 5.5f),
	)

	/**
	 * Where Hypixel puts a volley's side arrows: once the middle one has moved a tick, half a block
	 * below it. They fly at its speed by then, along your aim turned by the spread, and keep that
	 * velocity through their first tick. Fits recorded Terminator volleys within 0.05 blocks.
	 */
	private const val SIDE_ARROW_DROP = 0.5

	private class Shot(val yawOffset: Float, val path: List<Vec3>, val end: Vec3, val target: Entity?, val landed: Boolean)

	/** Where the shot leaves from and the rotation it leaves at. */
	private class Aim(val feet: Vec3, val yaw: Float, val pitch: Float, val sneaking: Boolean)

	private var initialized = false

	/** Mobs a shot could hit, found once a tick so drawing doesn't go through every entity every frame. */
	private var mobs: List<LivingEntity> = emptyList()

	private val bowEnabled: Boolean get() = BowTweaks.module.enabled && BowTweaks.trajectory.value
	private val pearlEnabled: Boolean get() = Combat.module.enabled && Combat.pearlTrajectory.value

	fun initialize() {
		if (initialized) return
		initialized = true
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
	}

	fun tick(client: Minecraft) {
		val level = client.level
		val player = client.player
		if (level == null || player == null) {
			mobs = emptyList()
			forgetRecording()
			return
		}
		mobs = if (bowEnabled || pearlEnabled || recordShots) findMobs(level, player) else emptyList()
		if (recordShots) record(client, level, player)
	}

	/**
	 * Living things a shot stops at: not you, not name-tag armour stands, and not other real players,
	 * whom SkyBlock's arrows pass through. Hypixel's player-shaped mobs and NPCs have version 2 UUIDs.
	 */
	private fun findMobs(level: ClientLevel, player: Player): List<LivingEntity> {
		val found = ArrayList<LivingEntity>()
		val range = MOB_RANGE * MOB_RANGE
		for (entity in level.entitiesForRendering()) {
			if (entity !is LivingEntity || entity === player || entity is ArmorStand) continue
			if (!entity.isAlive || entity.isDeadOrDying) continue
			if (entity is Player && entity.uuid.version() == 4) continue
			if (entity.distanceToSqr(player) > range) continue
			found += entity
		}
		return found
	}

	// ---- What's held -------------------------------------------------------------------------

	/** The arrow speed for what's held, or null when no arrow would fly: a shortbow always, a bow while drawn. */
	private fun arrowSpeed(player: Player, partialTick: Float): Double? {
		val stack = player.mainHandItem
		if (!stack.`is`(Items.BOW)) return null
		if (ArrowFix.isShortbowItem(stack)) return FULL_ARROW_SPEED
		if (!player.isUsingItem || player.useItem.isEmpty || !player.useItem.`is`(Items.BOW)) return null
		val power = drawPower(player.ticksUsingItem + partialTick)
		// Under a tenth of a draw, Minecraft doesn't fire at all.
		return if (power < 0.1) null else power * FULL_ARROW_SPEED
	}

	/** [net.minecraft.world.item.BowItem.getPowerForTime], smooth between ticks. */
	private fun drawPower(ticks: Float): Double {
		val pull = ticks / 20.0
		return ((pull * pull + pull * 2) / 3).coerceAtMost(1.0)
	}

	private fun volleyFor(stack: ItemStack): Volley = VOLLEYS[SkyblockItem.id(stack)] ?: SINGLE

	private fun aimOf(player: Player, partialTick: Float): Aim =
		Aim(player.getPosition(partialTick), player.getViewYRot(partialTick), player.getViewXRot(partialTick), player.isShiftKeyDown)

	// ---- The simulation ----------------------------------------------------------------------

	/**
	 * One shot, as a 1.8 server throws it: from 0.1 below the eyes, along the look. Each tick it moves,
	 * then slows (more in water) and falls. A tick's move stops at the first block, or the first mob
	 * whose box grown by [HIT_MARGIN] the move passes through. A [side] arrow starts as
	 * [SIDE_ARROW_DROP] describes.
	 */
	private fun simulate(
		level: ClientLevel,
		player: Player,
		aim: Aim,
		yawOffset: Float,
		speed: Double,
		physics: Physics,
		side: Boolean = false,
	): Shot {
		val aimYaw = Math.toRadians(aim.yaw.toDouble())
		val yaw = Math.toRadians((aim.yaw + yawOffset).toDouble())
		val pitch = Math.toRadians(aim.pitch.toDouble())
		var x = aim.feet.x - cos(aimYaw) * physics.sideOffset
		var y = aim.feet.y + (if (aim.sneaking) 1.54 else 1.62) - 0.1
		var z = aim.feet.z - sin(aimYaw) * physics.sideOffset
		var flySpeed = speed
		var freeTicks = 0
		if (side) {
			x += -sin(aimYaw) * cos(pitch) * speed
			y += -sin(pitch) * speed - SIDE_ARROW_DROP
			z += cos(aimYaw) * cos(pitch) * speed
			flySpeed = speed * AIR_DRAG
			freeTicks = 1
		}
		var mx = -sin(yaw) * cos(pitch) * flySpeed
		var my = -sin(pitch) * flySpeed
		var mz = cos(yaw) * cos(pitch) * flySpeed

		// Blocks first. Then only the mobs near that path are checked, a few instead of every one per tick.
		val path = ArrayList<Vec3>()
		path += Vec3(x, y, z)
		var landed = false
		for (tick in 0 until MAX_TICKS) {
			val from = Vec3(x, y, z)
			val to = Vec3(x + mx, y + my, z + mz)
			val hit = level.clip(ClipContext(from, to, physics.blocks, ClipContext.Fluid.NONE, player))
			if (hit.type != HitResult.Type.MISS) {
				path += hit.location
				landed = true
				break
			}
			path += to
			val drag = if (inWater(level, x, y, z)) physics.waterDrag else AIR_DRAG
			x = to.x
			y = to.y
			z = to.z
			if (freeTicks > 0) {
				freeTicks--
				continue
			}
			mx *= drag
			my = my * drag - physics.gravity
			mz *= drag
			if (y < level.minY - 16) break
		}

		val near = mobsNear(path)
		if (near.isNotEmpty()) {
			for (i in 0 until path.size - 1) {
				val from = path[i]
				val to = path[i + 1]
				var best: Entity? = null
				var bestAt: Vec3? = null
				var bestDistance = Double.MAX_VALUE
				for (mob in near) {
					val box = mob.boundingBox.inflate(HIT_MARGIN)
					val at = if (box.contains(from)) from else box.clip(from, to).orElse(null)
					if (at == null) continue
					val distance = from.distanceToSqr(at)
					if (distance < bestDistance) {
						bestDistance = distance
						best = mob
						bestAt = at
					}
				}
				if (best != null && bestAt != null) {
					val cut = ArrayList<Vec3>(path.subList(0, i + 1))
					cut += bestAt
					return Shot(yawOffset, cut, bestAt, best, true)
				}
			}
		}
		return Shot(yawOffset, path, path.last(), null, landed)
	}

	private fun mobsNear(path: List<Vec3>): List<LivingEntity> {
		if (mobs.isEmpty()) return emptyList()
		var minX = Double.MAX_VALUE
		var minY = Double.MAX_VALUE
		var minZ = Double.MAX_VALUE
		var maxX = -Double.MAX_VALUE
		var maxY = -Double.MAX_VALUE
		var maxZ = -Double.MAX_VALUE
		for (point in path) {
			minX = minOf(minX, point.x)
			minY = minOf(minY, point.y)
			minZ = minOf(minZ, point.z)
			maxX = maxOf(maxX, point.x)
			maxY = maxOf(maxY, point.y)
			maxZ = maxOf(maxZ, point.z)
		}
		val bounds = AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(HIT_MARGIN)
		return mobs.filter { !it.isRemoved && it.boundingBox.intersects(bounds) }
	}

	private val waterPos = BlockPos.MutableBlockPos()

	private fun inWater(level: ClientLevel, x: Double, y: Double, z: Double): Boolean =
		level.getFluidState(waterPos.set(Mth.floor(x), Mth.floor(y), Mth.floor(z))).`is`(FluidTags.WATER)

	private fun arrowShots(level: ClientLevel, player: Player, aim: Aim, volley: Volley, speed: Double): List<Shot> =
		volley.yawOffsets.map { simulate(level, player, aim, it, speed, ARROW, side = it != 0f) }

	// ---- Drawing -----------------------------------------------------------------------------

	private fun render(context: LevelRenderContext) {
		if (!bowEnabled && !pearlEnabled) return
		val client = Minecraft.getInstance()
		val player = client.player ?: return
		val level = client.level ?: return
		val partialTick = client.deltaTracker.getGameTimeDeltaPartialTick(false)
		val stack = player.mainHandItem

		if (bowEnabled) {
			val speed = arrowSpeed(player, partialTick)
			if (speed != null) {
				val aim = aimOf(player, partialTick)
				for (shot in arrowShots(level, player, aim, volleyFor(stack), speed)) {
					draw(context, shot, BowTweaks.trajectoryColor.argb, BowTweaks.trajectoryHitColor.argb, partialTick, BowTweaks.trajectoryLine.value)
				}
			}
		}
		if (pearlEnabled && stack.`is`(Items.ENDER_PEARL)) {
			val shot = simulate(level, player, aimOf(player, partialTick), 0f, PEARL_SPEED, PEARL)
			draw(context, shot, Combat.pearlColor.argb, Combat.pearlHitColor.argb, partialTick)
		}
	}

	private fun draw(context: LevelRenderContext, shot: Shot, lineArgb: Int, hitArgb: Int, partialTick: Float, showLine: Boolean = true) {
		val poseStack = context.poseStack()
		val collector = context.submitNodeCollector()
		val target = shot.target
		val lineRgb = lineArgb and 0xFFFFFF
		if (showLine) WorldRender.drawLine(poseStack, collector, trimStart(shot.path), (0xFF shl 24) or lineRgb, 2f, phase = false)

		if (target != null) {
			// Moved to where the mob is drawn this frame, so the outline doesn't trail it.
			val box = target.boundingBox.move(
				Mth.lerp(partialTick.toDouble(), target.xOld, target.x) - target.x,
				Mth.lerp(partialTick.toDouble(), target.yOld, target.y) - target.y,
				Mth.lerp(partialTick.toDouble(), target.zOld, target.z) - target.z,
			)
			val hitRgb = hitArgb and 0xFFFFFF
			WorldRender.drawBox(
				poseStack, collector, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
				outlineArgb = (0xFF shl 24) or hitRgb, fillArgb = (0x40 shl 24) or hitRgb, outline = true, fill = true, phase = true, lineWidth = 2f,
			)
		} else if (shot.landed) {
			val at = shot.end
			val half = MARKER / 2
			WorldRender.drawBox(
				poseStack, collector, at.x - half, at.y - half, at.z - half, at.x + half, at.y + half, at.z + half,
				outlineArgb = (0xFF shl 24) or lineRgb, fillArgb = (0x50 shl 24) or lineRgb, outline = true, fill = true, phase = true, lineWidth = 2f,
			)
		}
	}

	/** The path without its first [LINE_START] blocks. */
	private fun trimStart(path: List<Vec3>): List<Vec3> {
		var left = LINE_START
		for (i in 1 until path.size) {
			val step = path[i].distanceTo(path[i - 1])
			if (step >= left) {
				val start = path[i - 1].lerp(path[i], left / step)
				return listOf(start) + path.subList(i, path.size)
			}
			left -= step
		}
		return emptyList()
	}

	// ---- Shot recorder (/cryptic debug bow) --------------------------------------------------

	private val shotLogger = org.slf4j.LoggerFactory.getLogger("cryptic/bow")

	/** Logs each volley you fire: the prediction, then where Hypixel says each arrow went. */
	var recordShots = false
		set(value) {
			field = value
			forgetRecording()
		}

	private class Tracked(val arrow: AbstractArrow, val spawn: Vec3, val launch: Vec3, val ageWhenSeen: Int) {
		/** Each position Hypixel sent, by tick; between them the client moves arrows by itself. */
		val serverPoints = ArrayList<Pair<Int, Vec3>>()
		val clientPoints = ArrayList<Vec3>()
		var ticks = 0
		var stillTicks = 0
		var done = false
	}

	private class Recording(
		val startTick: Int,
		val item: String,
		val volley: Volley,
		val speed: Double,
		val looks: List<Aim>,
		val predicted: List<Shot>,
	) {
		val arrows = ArrayList<Tracked>()

		/** Arrows that appeared exactly where another did, with its motion: Hypixel sends each side arrow twice. */
		var copies = 0
	}

	private val looks = ArrayDeque<Aim>()
	private val seenArrows = HashSet<Int>()
	private var primed = false
	private var recordLevel: ClientLevel? = null
	private var clock = 0
	private var recording: Recording? = null

	/** The last draw's arrow speed, kept a few ticks past the release that fired it. */
	private var lastDrawSpeed = FULL_ARROW_SPEED
	private var sinceDraw = Int.MAX_VALUE

	private const val LOOK_HISTORY = 6
	private const val RECORD_TICKS = 120

	/** How near your eyes a new arrow must appear to count as yours. */
	private const val OWN_ARROW_RANGE = 6.0

	private fun forgetRecording() {
		looks.clear()
		seenArrows.clear()
		primed = false
		recordLevel = null
		recording = null
		sinceDraw = Int.MAX_VALUE
	}

	private fun record(client: Minecraft, level: ClientLevel, player: Player) {
		clock++
		if (level !== recordLevel) {
			forgetRecording()
			recordLevel = level
		}
		looks.addLast(aimOf(player, 1f))
		while (looks.size > LOOK_HISTORY) looks.removeFirst()

		val held = player.mainHandItem
		if (player.isUsingItem && held.`is`(Items.BOW) && !ArrowFix.isShortbowItem(held)) {
			lastDrawSpeed = drawPower(player.ticksUsingItem.toFloat()) * FULL_ARROW_SPEED
			sinceDraw = 0
		} else if (sinceDraw != Int.MAX_VALUE) {
			sinceDraw++
		}

		val eye = player.eyePosition
		for (entity in level.entitiesForRendering()) {
			val arrow = entity as? AbstractArrow ?: continue
			if (!seenArrows.add(arrow.id) || !primed) continue
			val spawn = arrow.positionCodec.base
			if (spawn.distanceTo(eye) > OWN_ARROW_RANGE) continue
			val joined = startOrJoin(level, player, held) ?: continue
			if (joined.arrows.any { it.spawn == spawn && it.arrow.deltaMovement == arrow.deltaMovement }) {
				joined.copies++
				continue
			}
			joined.arrows += Tracked(arrow, spawn, launchMotion(arrow), arrow.tickCount)
		}
		// Arrows already flying when recording starts aren't yours to compare.
		primed = true
		if (seenArrows.size > 4096) seenArrows.clear()

		val current = recording ?: return
		for (tracked in current.arrows) {
			if (tracked.done) continue
			val arrow = tracked.arrow
			val base = arrow.positionCodec.base
			val lastServer = tracked.serverPoints.lastOrNull()?.second
			if (lastServer == null || lastServer != base) tracked.serverPoints += tracked.ticks to base
			val position = arrow.position()
			val previous = tracked.clientPoints.lastOrNull()
			tracked.stillTicks = if (previous != null && previous.distanceToSqr(position) < 1e-4) tracked.stillTicks + 1 else 0
			tracked.clientPoints += position
			tracked.ticks++
			if (arrow.isRemoved || tracked.stillTicks >= 3 || tracked.ticks >= RECORD_TICKS) tracked.done = true
		}
		if (current.arrows.all { it.done }) {
			report(client, current)
			recording = null
		}
	}

	/** The recording a new arrow belongs to: the open one if it began a tick or two ago, else a new one. */
	private fun startOrJoin(level: ClientLevel, player: Player, held: ItemStack): Recording? {
		recording?.let { if (clock - it.startTick <= 2) return it }
		// Firing is answered a tick or more later; the look a tick back is the one it was fired at.
		val history = looks.toList()
		val aim = if (history.size >= 2) history[history.size - 2] else history.lastOrNull() ?: return null
		val speed = when {
			ArrowFix.isShortbowItem(held) -> FULL_ARROW_SPEED
			sinceDraw <= 5 -> lastDrawSpeed
			else -> FULL_ARROW_SPEED
		}
		val volley = if (held.`is`(Items.BOW)) volleyFor(held) else SINGLE
		val predicted = arrowShots(level, player, aim, volley, speed)
		val item = if (held.isEmpty) "nothing" else "${held.hoverName.string} (${SkyblockItem.id(held).ifEmpty { "no id" }})"
		recording?.let { report(Minecraft.getInstance(), it) }
		return Recording(clock, item, volley, speed, history, predicted).also { recording = it }
	}

	/**
	 * The arrow's motion as fired. The client has usually ticked it once by the time it's seen, so
	 * that tick's drag and gravity are taken back off.
	 */
	private fun launchMotion(arrow: AbstractArrow): Vec3 {
		val motion = arrow.deltaMovement
		if (arrow.tickCount == 0) return motion
		return Vec3(motion.x / AIR_DRAG, (motion.y + ARROW.gravity) / AIR_DRAG, motion.z / AIR_DRAG)
	}

	/**
	 * Which way an arrow went: its launch motion, or where it moved when Hypixel sent it without one
	 * (as it does for a volley's side arrows that are still waiting to be launched).
	 */
	private fun heading(tracked: Tracked): Vec3 {
		if (tracked.launch.lengthSqr() > 0.01) return tracked.launch
		val last = tracked.serverPoints.lastOrNull()?.second ?: return tracked.launch
		return last.subtract(tracked.spawn)
	}

	private fun yawOf(motion: Vec3): Double = Math.toDegrees(atan2(-motion.x, motion.z))

	private fun pitchOf(motion: Vec3): Double = -Math.toDegrees(atan2(motion.y, motion.horizontalDistance()))

	private fun Vec3.short(): String = "%.2f %.2f %.2f".format(x, y, z)

	/** How far a point is from a path, by its nearest segment. */
	private fun distanceToPath(point: Vec3, path: List<Vec3>): Double {
		if (path.size == 1) return point.distanceTo(path[0])
		var best = Double.MAX_VALUE
		for (i in 0 until path.size - 1) {
			val a = path[i]
			val ab = path[i + 1].subtract(a)
			val lengthSqr = ab.lengthSqr()
			val t = if (lengthSqr == 0.0) 0.0 else (point.subtract(a).dot(ab) / lengthSqr).coerceIn(0.0, 1.0)
			best = minOf(best, point.distanceTo(a.add(ab.scale(t))))
		}
		return best
	}

	private fun describeEnd(shot: Shot): String = when {
		shot.target != null -> "hits ${shot.target.name.string.replace(Regex("§."), "")} at ${shot.end.short()}"
		shot.landed -> "lands at ${shot.end.short()}"
		else -> "flies on past ${shot.end.short()}"
	}

	private fun report(client: Minecraft, recorded: Recording) {
		val fired = recorded.looks.let { if (it.size >= 2) it[it.size - 2] else it.last() }
		shotLogger.info(
			"volley: {} as {} ({} arrow(s) predicted), speed {}, {} arrow(s) seen and {} copies left out",
			recorded.item, recorded.volley.name, recorded.volley.yawOffsets.size, "%.3f".format(recorded.speed), recorded.arrows.size,
			recorded.copies,
		)
		recorded.looks.forEachIndexed { index, aim ->
			val age = recorded.looks.size - 1 - index
			shotLogger.info(
				"look t-{}: feet {} yaw {} pitch {} sneaking {}",
				age, aim.feet.short(), "%.2f".format(aim.yaw), "%.2f".format(aim.pitch), aim.sneaking,
			)
		}
		recorded.predicted.forEachIndexed { index, shot ->
			shotLogger.info(
				"predicted {} (yaw {}): {}, path {}",
				index + 1, "%+.1f".format(shot.yawOffset), describeEnd(shot), shot.path.joinToString(" | ") { it.short() },
			)
		}

		val lines = ArrayList<String>()
		lines += "§8[Cryptic] §fVolley: §e${recorded.arrows.size}§f arrow(s), §e${recorded.predicted.size}§f predicted (${recorded.volley.name})"
		recorded.arrows.sortedBy { yawOf(heading(it)) }.forEachIndexed { index, tracked ->
			val launchYaw = yawOf(heading(tracked))
			val yawOffset = Mth.wrapDegrees(launchYaw - fired.yaw)
			val pitchOffset = pitchOf(tracked.launch) - fired.pitch
			val speed = tracked.launch.length()
			val match = recorded.predicted.minByOrNull { kotlin.math.abs(Mth.wrapDegrees(it.yawOffset - yawOffset)) }
			val samples = tracked.serverPoints.map { it.second }
			val deviations = if (match != null) samples.map { distanceToPath(it, match.path) } else emptyList()
			val worst = deviations.maxOrNull() ?: 0.0
			val mean = if (deviations.isEmpty()) 0.0 else deviations.sum() / deviations.size
			val end = tracked.serverPoints.lastOrNull()?.second ?: tracked.spawn
			val endGap = if (match != null) end.distanceTo(match.end) else Double.NaN
			val spawnGap = match?.path?.firstOrNull()?.let { tracked.spawn.distanceTo(it) } ?: Double.NaN

			shotLogger.info(
				"arrow {}: spawn {} launch {} (yaw {}, offset {}, pitch offset {}, speed {}), ticked {} on the client when seen",
				index + 1, tracked.spawn.short(), tracked.launch.short(), "%.2f".format(launchYaw), "%+.2f".format(yawOffset),
				"%+.2f".format(pitchOffset), "%.3f".format(speed), tracked.ageWhenSeen,
			)
			shotLogger.info(
				"arrow {}: matched predicted yaw {}, spawn {} apart, path off by {} at most and {} on average over {} server positions, ends {} ({} from the prediction's end)",
				index + 1, match?.yawOffset?.let { "%+.1f".format(it) } ?: "none", "%.2f".format(spawnGap), "%.2f".format(worst),
				"%.2f".format(mean), samples.size, end.short(), "%.2f".format(endGap),
			)
			shotLogger.info("arrow {} server: {}", index + 1, tracked.serverPoints.joinToString(" | ") { "t${it.first} ${it.second.short()}" })
			shotLogger.info("arrow {} client: {}", index + 1, tracked.clientPoints.joinToString(" | ") { it.short() })

			lines += "§7 ${index + 1}: §fyaw §e%+.1f°§f, speed §e%.2f§f, off by §e%.2f§f (avg %.2f), end §e%.2f§f away".format(
				yawOffset, speed, worst, mean, endGap,
			)
		}
		lines += "§7Full paths are in logs/latest.log under cryptic/bow."
		lines.forEach { client.gui.hud.chat.addClientSystemMessage(Component.literal(it)) }
	}
}
