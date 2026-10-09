package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.animal.sheep.Sheep
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Draws the mage beam as a line instead of a trail of sparks.
 *
 * Ported from Odin's Mage Beam and NoammAddons' (BSD 3-Clause, Copyright (c)
 * 2025 odtheking; CC0, Noamm9). The ability itself is invisible: what Hypixel
 * sends is a run of firework particles along the path and a sheep at your feet
 * to make the noise. Both are read here — the particles become the two ends of
 * a line, and the sheep can be dropped before it is ever added to the world.
 *
 * Particles arrive several to a tick and in the order they were fired, so a
 * point continuing the direction of the last one belongs to the same beam, and
 * anything else starts a new one.
 */
object MageBeam {
	@JvmField
	val hideBeam = ToggleModuleSetting(
		id = "hide_beam",
		label = "Hide the whole beam",
		defaultValue = false,
		description = "Draws no line and hides the sparks too, so the beam is not shown at all. The sheep settings still apply.",
	)

	@JvmField
	val duration = SliderModuleSetting(
		id = "duration",
		visibleIf = { !hideBeam.value },
		label = "Duration",
		defaultValue = 40.0,
		min = 5.0,
		max = 100.0,
		step = 1.0,
		description = "How long a beam stays on screen, in ticks.",
	)

	@JvmField
	val lineWidth = SliderModuleSetting(
		id = "line_width",
		visibleIf = { !hideBeam.value },
		label = "Thickness",
		defaultValue = 2.0,
		min = 1.0,
		max = 10.0,
		step = 0.5,
	)

	@JvmField
	val phase = ToggleModuleSetting(
		id = "phase",
		visibleIf = { !hideBeam.value },
		label = "Phase",
		defaultValue = true,
		description = "Draws the beam through whatever is in front of it.",
	)

	@JvmField
	val fade = ToggleModuleSetting(
		id = "fade",
		visibleIf = { !hideBeam.value },
		label = "Fade out",
		defaultValue = true,
		description = "Thins the beam away over its duration instead of dropping it.",
	)

	@JvmField
	val hideSheep = ToggleModuleSetting(
		id = "hide_sheep",
		label = "Hide the sheep",
		defaultValue = true,
		description = "Stops drawing the sheep the ability spawns, yours and your teammates'.",
	)

	@JvmField
	val sheepHitbox = ToggleModuleSetting(
		id = "sheep_hitbox",
		label = "Show sheep hitbox",
		defaultValue = false,
		description = "Outlines where each of the ability's sheep stands, hidden or not.",
	)

	@JvmField
	val sheepHitboxStyle = DropdownModuleSetting(
		id = "sheep_hitbox_style",
		label = "Hitbox style",
		options = listOf("Outline", "Fill", "Fill + outline"),
		defaultIndex = HITBOX_OUTLINE,
		visibleIf = { sheepHitbox.value },
	)

	@JvmField
	val sheepHitboxColor = ColorModuleSetting(
		id = "sheep_hitbox_color",
		label = "Hitbox outline",
		defaultRgb = 0xFFFFFF,
		supportsAlpha = true,
		visibleIf = { sheepHitbox.value && sheepHitboxStyle.selectedIndex != HITBOX_FILL },
	)

	@JvmField
	val sheepHitboxFill = ColorModuleSetting(
		id = "sheep_hitbox_fill",
		label = "Hitbox fill",
		defaultRgb = 0xFFFFFF,
		supportsAlpha = true,
		defaultAlpha = 0x40,
		visibleIf = { sheepHitbox.value && sheepHitboxStyle.selectedIndex != HITBOX_OUTLINE },
	)

	@JvmField
	val hideParticles = ToggleModuleSetting(
		id = "hide_particles",
		visibleIf = { !hideBeam.value },
		label = "Hide the sparks",
		defaultValue = true,
		description = "Leaves only the line, instead of drawing it over the particles.",
	)

	private val markerSection = SectionModuleSetting("marker_section", "End marker", visibleIf = { !hideBeam.value })

	@JvmField
	val endMarker = ToggleModuleSetting(
		id = "end_marker",
		visibleIf = { !hideBeam.value },
		label = "Mark the end",
		defaultValue = true,
		description = "A small cross where the beam stopped.",
	)

	@JvmField
	val markerOnHit = ToggleModuleSetting(
		id = "marker_on_hit",
		label = "Only on a hit",
		defaultValue = true,
		description = "Marks only a beam that stopped on a mob, rather than one that ran into a wall.",
		visibleIf = { !hideBeam.value && endMarker.value },
	)

	@JvmField
	val markerSize = SliderModuleSetting(
		id = "marker_size",
		label = "Marker size",
		defaultValue = 0.25,
		min = 0.05,
		max = 1.0,
		step = 0.05,
		visibleIf = { !hideBeam.value && endMarker.value },
	)

	@JvmField
	val markerColor = ColorModuleSetting(
		id = "marker_color",
		label = "Marker",
		defaultRgb = 0xFFFFFF,
		supportsAlpha = true,
		visibleIf = { !hideBeam.value && endMarker.value },
		inlineWith = endMarker,
	)

	private val colorSection = SectionModuleSetting("color_section", "Color", visibleIf = { !hideBeam.value })

	@JvmField
	val color = ColorModuleSetting(
		id = "color",
		visibleIf = { !hideBeam.value },
		label = "Beam",
		defaultRgb = 0xAA0000,
		supportsAlpha = true,
	)

	@JvmField
	val gradient = ToggleModuleSetting(
		id = "gradient",
		visibleIf = { !hideBeam.value },
		label = "Gradient",
		description = "Runs the beam from one color to another along its length.",
	)

	@JvmField
	val farColor = ColorModuleSetting(
		id = "far_color",
		label = "Far end",
		defaultRgb = 0xFF55FF,
		supportsAlpha = true,
		visibleIf = { !hideBeam.value && gradient.value },
		inlineWith = gradient,
	)

	private val configurable = listOf(
		hideBeam,
		duration,
		lineWidth,
		phase,
		fade,
		hideSheep,
		sheepHitbox,
		sheepHitboxStyle,
		sheepHitboxColor,
		sheepHitboxFill,
		hideParticles,
		markerSection,
		endMarker,
		markerOnHit,
		markerSize,
		markerColor,
		colorSection,
		color,
		gradient,
		farColor,
	)

	private val reset = ButtonModuleSetting("reset", "Reset", action = {
		configurable.forEach {
			when (it) {
				is ToggleModuleSetting -> it.reset()
				is SliderModuleSetting -> it.reset()
				is ColorModuleSetting -> it.reset()
				is DropdownModuleSetting -> it.reset()
				else -> Unit
			}
		}
	})

	@JvmField
	val module = Module(
		id = "mage_beam",
		name = "Mage Beam",
		description = "Draws the mage beam as a line",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = configurable + reset,
	)

	/**
	 * One cast: the points it has been seen at, and when they arrived.
	 *
	 * The two ends are worked out from where the player is rather than from the
	 * order the points arrived in, because a beam fired towards you and one
	 * fired away from you send their particles in opposite orders.
	 */
	private class Beam(first: Vec3, val bornAt: Int) {
		val points = CopyOnWriteArrayList<Vec3>().apply { add(first) }
		var lastTick = bornAt
		var near: Vec3 = first
		var far: Vec3 = first

		/** Whether the far end stopped on something alive rather than on a wall. */
		var hitMob = false

		fun measure(from: Vec3) {
			var nearest = points.first()
			var furthest = nearest
			var least = nearest.distanceToSqr(from)
			var most = least

			points.forEach { point ->
				val distance = point.distanceToSqr(from)
				if (distance < least) {
					least = distance
					nearest = point
				}
				if (distance > most) {
					most = distance
					furthest = point
				}
			}

			near = nearest
			far = furthest
		}

		/** True while a new point carries on in the direction this beam runs, close behind its last one. */
		fun continues(point: Vec3): Boolean {
			val last = points.last()
			if (last.distanceToSqr(point) > MAX_GAP * MAX_GAP) return false
			if (points.size <= 1) return true
			return last.subtract(points.first()).normalize()
				.dot(point.subtract(last).normalize()) > STRAIGHT
		}
	}

	private val beams = CopyOnWriteArrayList<Beam>()
	private var ticks = 0

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> forget() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> forget() }
	}

	private fun forget() {
		beams.clear()
		recentBoxes = emptyList()
		ticks = 0
	}


	fun tick(client: Minecraft) {
		if (!module.enabled) {
			if (beams.isNotEmpty()) beams.clear()
			return
		}

		ticks++
		val life = duration.value.toInt()
		beams.removeAll { ticks - it.bornAt > life }

		// Before the beams are measured, so a beam fired this tick is tested
		// against a world that still has in it whatever the beam just killed.
		snapshotMobs(client)

		val player = client.player ?: return
		val at = player.position()
		beams.forEach {
			it.measure(at)
			if (!it.hitMob) it.hitMob = endsOnMob(it)
		}
	}

	/**
	 * One of the ability's particles. Returns true when the game should not
	 * draw it, which is what leaves only the line behind.
	 */
	@JvmStatic
	fun onParticle(packet: ClientboundLevelParticlesPacket): Boolean {
		if (!module.enabled || !DungeonLocation.inDungeon) return false
		if (packet.particle.type != ParticleTypes.FIREWORK) return false
		// Nothing to draw, so nothing to record: the sparks just go.
		if (hideBeam.value) return true

		val point = Vec3(packet.x, packet.y, packet.z)
		// Hypixel sends each beam twice; a point that is already on one is the
		// second copy of it.
		if (beams.none { point in it.points }) {
			// Any beam still arriving, not only the newest. Other fireworks come
			// in between a beam's sparks — the dragons' in the last part of Floor 7
			// — and with only the newest tried, every spark of the beam started a
			// beam of its own, too short to draw.
			val recent = beams.lastOrNull { ticks - it.lastTick <= 1 && it.continues(point) }
			if (recent != null) {
				recent.points.add(point)
				recent.lastTick = ticks
			} else {
				beams.add(Beam(point, ticks))
			}
		}

		// Nothing else happens here, and that is deliberate. This runs on the
		// network thread, where anything that throws is answered by Minecraft
		// dropping the connection, so it only records the point. Whether the
		// beam hit anything is worked out on the game thread's tick, which tests
		// every beam against the last second of mob positions anyway.
		return hideParticles.value
	}

	/**
	 * The sheep the ability spawns to make its noise, which nobody looks at.
	 *
	 * Left in the world and only not drawn, rather than dropped as it spawns,
	 * so its hitbox can still be shown. Every sheep in a dungeon, as Devonian's
	 * Hide Sheeps has it: there is no other sheep in one, and the old test —
	 * one spawned within three blocks of your feet — let a teammate's through,
	 * and your own whenever you were moving as you cast.
	 */
	@JvmStatic
	fun hidesSheep(entity: Entity): Boolean =
		module.enabled && hideSheep.value && entity is Sheep && DungeonLocation.inDungeon

	private fun render(context: LevelRenderContext) {
		if (!module.enabled) return
		if (sheepHitbox.value && DungeonLocation.inDungeon) drawSheepHitboxes(context)
		if (beams.isEmpty() || hideBeam.value) return

		val life = duration.value.toInt().coerceAtLeast(1)
		val width = lineWidth.value.toFloat()

		beams.forEach { beam ->
			if (beam.points.size < MIN_POINTS) return@forEach
			if (beam.near == beam.far) return@forEach

			val age = (ticks - beam.bornAt).coerceIn(0, life)
			val strength = if (fade.value) 1f - age.toFloat() / life else 1f
			if (strength <= 0f) return@forEach

			val from = faded(color.argb, strength)
			val to = faded(if (gradient.value) farColor.argb else color.argb, strength)

			WorldRender.drawGradientLine(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				from = beam.near,
				to = beam.far,
				argbFrom = from,
				argbTo = to,
				lineWidth = width,
				phase = phase.value,
			)

			if (!endMarker.value) return@forEach
			if (markerOnHit.value && !beam.hitMob) return@forEach
			drawMarker(context, beam, faded(markerColor.argb, strength))
		}
	}

	/** An outline where each sheep stands, drawn between ticks where it is moving. */
	private fun drawSheepHitboxes(context: LevelRenderContext) {
		val client = Minecraft.getInstance()
		val level = client.level ?: return
		val partialTick = client.deltaTracker.getGameTimeDeltaPartialTick(false).toDouble()

		for (entity in level.entitiesForRendering()) {
			if (entity !is Sheep) continue
			val box = entity.boundingBox.move(
				Mth.lerp(partialTick, entity.xOld, entity.x) - entity.x,
				Mth.lerp(partialTick, entity.yOld, entity.y) - entity.y,
				Mth.lerp(partialTick, entity.zOld, entity.z) - entity.z,
			)
			WorldRender.drawBox(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				minX = box.minX,
				minY = box.minY,
				minZ = box.minZ,
				maxX = box.maxX,
				maxY = box.maxY,
				maxZ = box.maxZ,
				outlineArgb = sheepHitboxColor.argb,
				fillArgb = sheepHitboxFill.argb,
				outline = sheepHitboxStyle.selectedIndex != HITBOX_FILL,
				fill = sheepHitboxStyle.selectedIndex != HITBOX_OUTLINE,
				phase = phase.value,
				lineWidth = lineWidth.value.toFloat(),
			)
		}
	}

	/**
	 * Whether the beam stopped on something alive rather than on a wall.
	 *
	 * The last stretch of the beam is tested, not its last spark. A beam stops
	 * at the surface of what it hit, and a point on a surface is as easily a
	 * hair outside the box as a hair inside it — which is why testing the point
	 * alone answered yes only sometimes, on hits that all looked the same. A
	 * short segment back along the beam crosses the box whichever side of the
	 * surface the spark landed on.
	 *
	 * Armour stands are skipped, because a dungeon is full of them holding name
	 * tags and none of them is what was aimed at. Once true it stays true,
	 * since a mob killed by the beam stops existing a moment later.
	 */
	private fun endsOnMob(beam: Beam): Boolean {
		val direction = beam.far.subtract(beam.near)
		val length = direction.length()
		if (length < START_SKIP) return false
		val along = direction.scale(1.0 / length)

		// The whole beam past the first block or so, not just the far end. A
		// beam that passes through a mob and carries on to the wall behind it
		// has hit that mob, and testing only the last stretch calls that a miss
		// — which is most of what "sometimes" meant. The first stretch is left
		// out because it starts inside you, where anything standing next to you
		// would count for nothing you did.
		val start = beam.near.add(along.scale(START_SKIP))
		val end = beam.far

		// Every snapshot, not just the newest. A mob this beam killed is not in
		// the world by the time anything gets to look for it, and a check that
		// only ever sees the living was never really testing whether the beam
		// hit something — only whether what it hit survived.
		return recentBoxes.any { snapshot -> snapshot.any { crosses(it, start, end) } }
	}

	/**
	 * Where everything alive was, over the last few ticks.
	 *
	 * Kept so a beam can be tested against the world as it was when the beam
	 * was fired rather than as it is once the beam has finished with it. Boxes
	 * rather than entities, because an entity that has been removed stops
	 * reporting where it used to be.
	 *
	 * Replaced whole every tick rather than added to and trimmed, and never
	 * changed once built. This was a queue edited in place, and a queue edited
	 * on the game thread while the network thread was reading it handed the
	 * reader a slot that had just been emptied — an exception inside a packet
	 * handler, which Minecraft answers by dropping the connection. That was the
	 * kick in the blood room, where more beams are fired than anywhere else. A
	 * list that is only ever swapped for a new one cannot be caught half-built.
	 */
	@Volatile
	private var recentBoxes: List<List<AABB>> = emptyList()

	private fun snapshotMobs(client: Minecraft) {
		val level = client.level ?: return
		val player = client.player

		val boxes = level.entitiesForRendering()
			.filter { it is LivingEntity && it !is ArmorStand && it !is Sheep && it !== player }
			.map { it.boundingBox.inflate(HIT_SLACK) }

		recentBoxes = (recentBoxes + listOf(boxes)).takeLast(MEMORY_TICKS)
	}

	/**
	 * A cross at the end of a beam, drawn square-on to it.
	 *
	 * The two strokes are laid out across the beam's own direction rather than
	 * across the screen, so the mark sits on the end of the line from wherever
	 * it is looked at instead of flattening into it.
	 */
	private fun drawMarker(context: LevelRenderContext, beam: Beam, argb: Int) {
		val direction = beam.far.subtract(beam.near)
		if (direction.lengthSqr() < 1e-6) return
		val forward = direction.normalize()

		// Any vector not running along the beam will do to build the other two
		// from; the upright one does, except for a beam fired straight up.
		val reference = if (kotlin.math.abs(forward.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
		val right = forward.cross(reference).normalize()
		val up = forward.cross(right).normalize()

		val size = markerSize.value
		val first = right.add(up).normalize().scale(size)
		val second = right.subtract(up).normalize().scale(size)
		val width = lineWidth.value.toFloat()

		listOf(first, second).forEach { arm ->
			WorldRender.drawGradientLine(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				from = beam.far.subtract(arm),
				to = beam.far.add(arm),
				argbFrom = argb,
				argbTo = argb,
				lineWidth = width,
				phase = phase.value,
			)
		}
	}

	private const val HITBOX_OUTLINE = 0
	private const val HITBOX_FILL = 1

	/** How far off the end of a beam a mob still counts as the thing it hit. */
	private const val HIT_SLACK = 1.0

	/** How much of the beam nearest you is not counted, being where you stand. */
	private const val START_SKIP = 1.5

	/**
	 * Whether a line meets a box, counting a line that begins or ends inside it.
	 *
	 * `AABB.clip` answers where a ray *enters* a box, so it says no for a line
	 * that starts already inside one - which is the case whenever a beam stops
	 * within the thing it hit rather than on its surface.
	 */
	private fun crosses(box: AABB, from: Vec3, to: Vec3): Boolean =
		box.contains(from) || box.contains(to) || box.clip(from, to).isPresent

	/**
	 * What the beams look like from in here, for `/cryptic debug beam`.
	 *
	 * Whether the mark is drawn comes down to a geometry question nothing on
	 * screen reports the answer to, so the answer is printed: how long each
	 * beam is, whether it was judged a hit, and how many remembered mobs were
	 * anywhere near it.
	 */
	fun describe(): List<String> {
		if (beams.isEmpty()) return listOf("Mage beam: nothing on screen.")

		val lines = mutableListOf(
			"Mage beam: ${beams.size} on screen, " +
				"${recentBoxes.size} ticks remembered, " +
				"${recentBoxes.lastOrNull()?.size ?: 0} mobs this tick",
		)

		beams.forEach { beam ->
			val length = beam.near.distanceTo(beam.far)
			val near = recentBoxes.sumOf { snapshot ->
				snapshot.count { it.distanceToSqr(beam.far) < NEAR_REPORT }
			}
			lines += "  %.1f blocks, %d points, hit=%s, %d remembered boxes near the end"
				.format(length, beam.points.size, beam.hitMob, near)
		}

		return lines
	}

	/** How near the end of a beam a remembered box is worth reporting, squared. */
	private const val NEAR_REPORT = 25.0

	/** How many ticks of the world a beam may be tested against. */
	private const val MEMORY_TICKS = 20

	/** The same colour at part of its alpha, for a beam on its way out. */
	private fun faded(argb: Int, strength: Float): Int {
		val alpha = (ARGB.alpha(argb) * strength).toInt().coerceIn(0, 255)
		return ARGB.color(alpha, argb)
	}

	/** How straight a point has to carry on to belong to the same beam. */
	private const val STRAIGHT = 0.99

	/** How far apart two sparks of one beam can be. */
	private const val MAX_GAP = 5.0

	/** Fewer points than this is a stray spark rather than a cast. */
	private const val MIN_POINTS = 3
}
