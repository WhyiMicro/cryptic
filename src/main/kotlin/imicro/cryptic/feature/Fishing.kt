package imicro.cryptic.feature

import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.render.WorldRender
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.item.FishingRodItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fishing helpers around hotspots, the patches of water that grant a perk to whoever fishes in them:
 * a filled circle on the water with a rim, in its perk's colour, a message when
 * you come across one with buttons to share it, and an alert when the one you're fishing goes.
 *
 * How hotspots are found, sized and drawn is SkyOcean's (MIT): a hotspot is an armour stand named
 * "HOTSPOT" with its perk on a stand below; Hypixel marks its edge with particles (pink dust, or
 * smoke on the Crimson Isle), and the distance those land from the stand, to the half block, is its
 * radius. Hotspot and perk stands as Feesh (Apache-2.0) reads them.
 */
object Fishing {
	private val hotspotSection = SectionModuleSetting("hotspot_section", "Hotspots")

	@JvmField
	val useCustomColor = ToggleModuleSetting(
		"use_custom_color", "Use custom color", false,
		"One colour for every hotspot instead of the colour of its perk (gold for trophy fish, blue for double hook...).",
	)

	@JvmField
	val customColor = ColorModuleSetting("custom_color", "Custom color", defaultRgb = 0xFF55FF, visibleIf = { useCustomColor.value })

	@JvmField
	val highlight = ToggleModuleSetting(
		"highlight", "Highlight hotspots", true,
		"Fills each hotspot on the water, with a rim at its edge. Its size is the one Hypixel marks with particles.",
	).withMore(useCustomColor, customColor)

	@JvmField
	val hideParticles = ToggleModuleSetting(
		"hide_particles", "Hide hotspot particles", true,
		"Hides the particles Hypixel draws around a hotspot's edge, which the highlight replaces.",
	)

	@JvmField
	val insideColor = ColorModuleSetting("inside_color", "Bobber inside", defaultRgb = 0x55FF55)

	@JvmField
	val bobberInside = ToggleModuleSetting(
		"bobber_inside", "Show when the bobber is in", true,
		"Outlines your bobber in this colour while it's in a hotspot.",
	).withMore(insideColor)

	@JvmField
	val foundMessage = ToggleModuleSetting(
		"found_message", "Message when found", true,
		"Says in chat when you come across a hotspot, with buttons to share where it is with your party or everyone.",
	)

	@JvmField
	val goneAlert = ToggleModuleSetting("gone_alert", "Alert when gone", true, "A title and a sound when the hotspot you're fishing in disappears.")

	@JvmField
	val radarTracer = ToggleModuleSetting("radar_tracer", "Line to it", true, "A line from the crosshair to where the radar points.")

	@JvmField
	val radarGuess = ToggleModuleSetting(
		"radar_guess", "Hotspot radar guess", true,
		"Using a Hotspot Radar sends a trail of flames toward the nearest hotspot. The trail is followed to its end, " +
			"and that spot is marked with its distance.",
	).withMore(radarTracer)

	private val castSection = SectionModuleSetting("cast_section", "Casting")

	@JvmField
	val trajectoryColor = ColorModuleSetting("trajectory_color", "Line", defaultRgb = 0x55FFFF)

	@JvmField
	val landingColor = ColorModuleSetting("landing_color", "Landing spot", defaultRgb = 0xFFFF55)

	@JvmField
	val trajectory = ToggleModuleSetting(
		"trajectory", "Cast trajectory", true,
		"While you hold a fishing rod, shows the path your bobber would take and where it would land. Gone once you've cast.",
	).withMore(trajectoryColor, landingColor)

	@JvmField
	val rodLine = ToggleModuleSetting(
		"rod_line", "Line from rod tip", true,
		"In first person your fishing line starts at the rod's tip, wherever Animations moves or scales the rod.",
	)

	@JvmField
	val module = Module(
		id = "fishing",
		name = "Fishing",
		description = "QoL fishing features",
		category = ModuleCategory.GENERAL,
		supportsKeybind = false,
		settings = listOf(hotspotSection, highlight, hideParticles, bobberInside, foundMessage, goneAlert, radarGuess, castSection, trajectory, rodLine),
	)

	/** The rim's height above the water, as SkyOcean draws it. */
	private const val RIM_HEIGHT = 0.1

	/** The colour of the dust Hypixel rings hotspots with, away from the Crimson Isle. */
	private const val HOTSPOT_DUST = 0xFF69B4

	private class Hotspot(val stand: ArmorStand, val perk: Component?, val surface: Double, val color: Int) {
		val centre: Vec3 get() = Vec3(stand.x, surface, stand.z)
	}

	private val hotspots = ArrayList<Hotspot>()
	private var scanTicks = 0

	/** What the network thread needs: where each hotspot is, to match its particles to it. */
	private class Spot(val id: UUID, val x: Double, val z: Double)

	@Volatile
	private var spots: List<Spot> = emptyList()

	/** How often each radius (to the half block) has been measured, by stand; the most common wins. */
	private val radiusCounts = ConcurrentHashMap<UUID, ConcurrentHashMap<Double, Int>>()

	/** The last two hotspots announced, so moving between two close ones doesn't repeat itself. */
	private val announced = ArrayDeque<UUID>()

	/** The hotspot your bobber was last in, and the world it was in. */
	private var fishedIn: Hotspot? = null
	private var fishedLevel: ClientLevel? = null

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
	}

	val needsSkyblock: Boolean get() = module.enabled

	/** The radius measured for a hotspot, or null before any of its particles were seen. */
	private fun radiusOf(id: UUID): Double? = radiusCounts[id]?.maxByOrNull { it.value }?.key

	private fun holdsRod(): Boolean {
		val inventory = Minecraft.getInstance().player?.inventory ?: return false
		return (0 until 9).any { inventory.getItem(it).item is FishingRodItem }
	}

	/** Your bobber, once it's in water or lava. */
	private fun bobber(): FishingHook? {
		val client = Minecraft.getInstance()
		val hook = client.player?.fishing ?: return null
		val level = client.level ?: return null
		val wet = hook.isInWater || hook.isInLava || !level.getFluidState(hook.blockPosition()).isEmpty
		return hook.takeIf { wet }
	}

	fun tick(client: Minecraft) {
		if (!module.enabled || !SkyblockLocation.onSkyblock) {
			hotspots.clear()
			spots = emptyList()
			holdsCastableRod = false
			return
		}
		val level = client.level ?: return
		val player = client.player ?: return
		holdsCastableRod = trajectory.value && isFishingRod(player.mainHandItem)
		recordCast(level, player)
		if (level !== fishedLevel) {
			// A new world: the old hotspot left with it, not by going.
			fishedIn = null
			fishedLevel = level
			announced.clear()
			radiusCounts.clear()
			clearRadar()
		}
		if (imicro.cryptic.skyblock.SkyblockItem.id(player.mainHandItem) == "HOTSPOT_RADAR") radarHeldAt = System.currentTimeMillis()
		radarGuessAt?.let { found ->
			val at = radarAt ?: return@let
			val flat = Vec3(at.x - player.x, 0.0, at.z - player.z).length()
			if (flat < RADAR_ARRIVED || System.currentTimeMillis() - found > RADAR_MILLIS) clearRadar()
		}
		if (--scanTicks <= 0) {
			scanTicks = 10
			scan(level, player.position())
			checkGone(player.position())
			announce(player.position())
		}
		bobber()?.let { hook -> hotspots.firstOrNull { inside(it, hook.position()) }?.let { fishedIn = it } }
	}

	/** The largest radius a hotspot has here, squared, so far-off particles aren't counted. */
	private fun maxRadiusSquared(): Double = when (SkyblockLocation.area) {
		"Crimson Isle", "Torrhus Canyon" -> 25.0
		"Jerry's Workshop", "Lotus Atoll" -> 16.0
		else -> 9.0
	} + 0.5

	private fun isHotspotParticle(packet: ClientboundLevelParticlesPacket): Boolean {
		if (SkyblockLocation.area == "Crimson Isle") {
			return packet.particle.type == ParticleTypes.SMOKE && (packet.count == 5 || packet.count == 2)
		}
		val dust = packet.particle as? DustParticleOptions ?: return false
		val color = dust.color
		val rgb = ((color.x() * 255).roundToInt() shl 16) or ((color.y() * 255).roundToInt() shl 8) or (color.z() * 255).roundToInt()
		return rgb == HOTSPOT_DUST && packet.count == 0 && packet.xDist == 1f && packet.maxSpeed == 1f
	}

	/**
	 * A particle, on the network thread (the first of the two calls; the second, on the client
	 * thread, is ignored). Measures the hotspot it marks; true when it's to be hidden.
	 */
	@JvmStatic
	fun onParticle(packet: ClientboundLevelParticlesPacket): Boolean {
		if (Minecraft.getInstance().isSameThread) {
			radarParticle(packet)
			return false
		}
		if (!module.enabled) return false
		val current = spots
		if (current.isEmpty() || !isHotspotParticle(packet)) return false
		val limit = maxRadiusSquared()
		val (spot, distanceSquared) = current.map { it to (packet.x - it.x) * (packet.x - it.x) + (packet.z - it.z) * (packet.z - it.z) }
			.filter { it.second <= limit }
			.minByOrNull { it.second } ?: return false
		// To the half block; counted, so a stray particle (a pet's, say) doesn't make it flicker.
		val radius = (sqrt(distanceSquared) * 2).roundToInt() / 2.0
		radiusCounts.getOrPut(spot.id) { ConcurrentHashMap() }.merge(radius, 1, Int::plus)
		return hideParticles.value && highlight.value
	}

	private fun scan(level: ClientLevel, from: Vec3) {
		hotspots.clear()
		val stands = level.entitiesForRendering().filterIsInstance<ArmorStand>().filter { it.position().distanceToSqr(from) < 64.0 * 64.0 }
		for (stand in stands) {
			if (stand.customName?.string != "HOTSPOT") continue
			// The perk sits on its own stand just under the title.
			val perk = stands.firstOrNull { other ->
				other !== stand && other.x == stand.x && other.z == stand.z && other.y < stand.y && stand.y - other.y <= 1.0
			}?.customName
			hotspots += Hotspot(stand, perk, surfaceUnder(level, stand), colorOf(perk))
		}
		spots = hotspots.map { Spot(it.stand.uuid, it.stand.x, it.stand.z) }
	}

	/** A perk's colour, as SkyOcean colours them. */
	private fun colorOf(perk: Component?): Int {
		val text = perk?.string?.lowercase() ?: return 0xFF55FF
		return when {
			"sea creature chance" in text -> 0x00AAAA
			"fishing speed" in text -> 0x55FFFF
			"double hook" in text -> 0x5555FF
			"treasure chance" in text || "trophy chance" in text -> 0xFFAA00
			"shard" in text -> 0xFFFF55
			else -> 0xFF55FF
		}
	}

	/** The water (or lava) surface under a hotspot's stand. */
	private fun surfaceUnder(level: ClientLevel, stand: ArmorStand): Double {
		val start = BlockPos.containing(stand.x, stand.y, stand.z)
		for (down in 0..6) {
			val pos = start.below(down)
			val fluid = level.getFluidState(pos)
			if (!fluid.isEmpty) return pos.y + fluid.getHeight(level, pos).toDouble() + 0.1
		}
		return stand.y - 1.5
	}

	private fun inside(hotspot: Hotspot, at: Vec3): Boolean {
		val radius = radiusOf(hotspot.stand.uuid) ?: return false
		val dx = at.x - hotspot.stand.x
		val dz = at.z - hotspot.stand.z
		return dx * dx + dz * dz <= radius * radius && kotlin.math.abs(at.y - hotspot.surface) < 3.0
	}

	private fun checkGone(at: Vec3) {
		val last = fishedIn ?: return
		if (!last.stand.isRemoved) return
		fishedIn = null
		// Walking away unloads it too; only one gone close by has really gone.
		if (!goneAlert.value || last.stand.position().distanceToSqr(at) > 30.0 * 30.0) return
		val client = Minecraft.getInstance()
		client.gui.hud.setTimes(0, 40, 10)
		client.gui.hud.setTitle(Component.literal("§dHotspot §cis gone"))
		client.gui.hud.setSubtitle(last.perk ?: Component.empty())
		SoundChoices.play(0, 1f, 1f)
	}

	private fun announce(at: Vec3) {
		if (!foundMessage.value || !holdsRod()) return
		val near = hotspots.filter { it.stand.position().distanceToSqr(at) < 10.0 * 10.0 }
			.minByOrNull { it.stand.position().distanceToSqr(at) } ?: return
		val id = near.stand.uuid
		if (id in announced) return
		announced.addFirst(id)
		while (announced.size > 2) announced.removeLast()

		val perk = near.perk?.string?.trim().orEmpty()
		val where = "x: ${near.stand.blockX}, y: ${near.stand.blockY}, z: ${near.stand.blockZ}"
		val zone = SkyblockLocation.zone()?.let { " at $it" }.orEmpty()
		val share = "$where | ${if (perk.isEmpty()) "" else "$perk "}Hotspot$zone"
		val message = Component.literal("§8[Cryptic] §7You found ")
			.append(near.perk?.copy()?.append(" ") ?: Component.empty())
			.append(Component.literal("§dHotspot§7. "))
			.append(button("§9[Party]", "pc $share", "Share it in party chat"))
			.append(Component.literal(" "))
			.append(button("§e[All]", "ac $share", "Share it with everyone"))
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(message)
	}

	private fun button(label: String, command: String, hover: String): Component =
		Component.literal(label).withStyle {
			it.withClickEvent(ClickEvent.RunCommand(command)).withHoverEvent(HoverEvent.ShowText(Component.literal(hover)))
		}

	// ---- Hotspot radar -------------------------------------------------------------------------

	/** When the radar was last in your hand; its trail is only read just after. */
	private var radarHeldAt = 0L
	private val radarTrail = imicro.cryptic.skyblock.ParticleTrail()

	/** Where the radar's last trail leads, and when it was found. */
	private var radarAt: Vec3? = null
	private var radarGuessAt: Long? = null

	private const val RADAR_HELD_MILLIS = 1_500L
	private const val RADAR_MILLIS = 60_000L
	private const val RADAR_ARRIVED = 10.0
	private const val RADAR_MAX_DISTANCE = 400.0
	private const val RADAR_COLOR = 0xFFFF55FF.toInt()

	/** The radar's flames, one at a time along the trail. Client thread. */
	private fun radarParticle(packet: ClientboundLevelParticlesPacket) {
		if (!module.enabled || !radarGuess.value || !SkyblockLocation.onSkyblock) return
		if (packet.particle.type != ParticleTypes.FLAME || packet.count != 1 || packet.maxSpeed != 0f) return
		val now = System.currentTimeMillis()
		if (now - radarHeldAt > RADAR_HELD_MILLIS) return
		val player = Minecraft.getInstance().player ?: return
		val end = radarTrail.offer(Vec3(packet.x, packet.y, packet.z), player.eyePosition, now) ?: return
		if (end.distanceTo(player.position()) > RADAR_MAX_DISTANCE) return
		radarAt = end
		radarGuessAt = now
	}

	private fun clearRadar() {
		radarAt = null
		radarGuessAt = null
		radarTrail.clear()
	}

	private fun renderRadar(context: LevelRenderContext) {
		val at = radarAt ?: return
		val client = Minecraft.getInstance()
		val player = client.player ?: return
		val poseStack = context.poseStack()
		val collector = context.submitNodeCollector()
		WorldRender.drawBox(
			poseStack, collector, at.x - 0.15, at.y, at.z - 0.15, at.x + 0.15, at.y + 40.0, at.z + 0.15,
			outlineArgb = 0, fillArgb = 0x60000000 or (RADAR_COLOR and 0xFFFFFF), outline = false, fill = true, phase = true, lineWidth = 1f,
		)
		if (radarTracer.value) WorldRender.drawTracer(poseStack, collector, at.x, at.y, at.z, RADAR_COLOR, 2f, phase = true)
		val distance = player.eyePosition.distanceTo(at)
		WorldRender.drawText(
			poseStack = poseStack,
			collector = collector,
			orientation = client.gameRenderer.mainCamera().rotation(),
			text = Component.literal("§d§lHotspot §e${distance.toInt()}m"),
			x = at.x, y = at.y + 1.5, z = at.z,
			scale = (distance / 12.0).coerceIn(1.0, 6.0).toFloat(),
			seeThrough = true,
			centered = true,
		)
	}

	// ---- Cast trajectory -----------------------------------------------------------------------

	/** Held in the main hand, checked each tick: a rod Hypixel counts as one, not a whip or Grappling Hook. */
	private var holdsCastableRod = false

	private val COLOR_CODE = Regex("§.")

	/**
	 * A rod you fish with. Flaming Flay, Soul Whip, Zombie Commander Whip and the Grappling Hook are
	 * fishing rods to Minecraft too; Hypixel's type line ("LEGENDARY FISHING ROD") tells them apart.
	 */
	private fun isFishingRod(stack: ItemStack): Boolean {
		if (stack.item !is FishingRodItem) return false
		val lore = stack.get(DataComponents.LORE)?.lines() ?: return false
		val type = lore.lastOrNull { it.string.isNotBlank() }?.string ?: return false
		return "FISHING ROD" in COLOR_CODE.replace(type, "")
	}

	private const val HOOK_SIZE = 0.25
	private const val CAST_MAX_TICKS = 200
	private const val CAST_MAX_WET_TICKS = 60
	private const val LINE_START = 1.2

	private class Cast(val path: List<Vec3>, val landing: Vec3)

	/** What a block-stopped hook keeps of its speed: 1.8 picks 0-20% at random, so the middle. */
	private const val STOPPED_SPEED = 0.1

	/**
	 * Where a cast would end up, stepped as Hypixel's 1.8 server moves the hook: thrown from just below
	 * the eyes at 1.5 blocks a tick, 0.04 gravity and 0.92 drag. Each tick a single point (the hook's
	 * bottom) is traced first: if it would pass into a block, the hook stays put and loses most of its
	 * speed. Otherwise its 0.25 box moves with collisions, so a box that grazes a wall the point missed
	 * slides along it at speed. Which of the two happens is down to where the tick falls, which is why
	 * one cast slides and the next doesn't. In water or lava it floats and drifts on before settling.
	 * Checked against recorded casts (`/cryptic debug cast`).
	 */
	private fun predictCast(level: ClientLevel, player: Player, partialTick: Float): Cast? {
		val yaw = Math.toRadians(player.getViewYRot(partialTick).toDouble())
		val pitch = Math.toRadians(player.getViewXRot(partialTick).toDouble())
		val feet = player.getPosition(partialTick)
		var x = feet.x - cos(yaw) * 0.16
		var y = feet.y + (if (player.isShiftKeyDown) 1.54 else 1.62) - 0.1
		var z = feet.z - sin(yaw) * 0.16
		var mx = -sin(yaw) * cos(pitch) * 1.5
		var my = -sin(pitch) * 1.5
		var mz = cos(yaw) * cos(pitch) * 1.5
		val path = ArrayList<Vec3>()
		path += Vec3(x, y, z)
		var wetTicks = 0
		var stillTicks = 0
		var stopped = false
		val half = HOOK_SIZE / 2
		for (tick in 0 until CAST_MAX_TICKS) {
			if (stopped) {
				stopped = false
				mx *= STOPPED_SPEED
				my *= STOPPED_SPEED
				mz *= STOPPED_SPEED
			}
			val from = Vec3(x, y, z)
			val hit = level.clip(ClipContext(from, Vec3(x + mx, y + my, z + mz), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
			if (hit.type != HitResult.Type.MISS) {
				// Stays put this tick, without gravity or drag.
				stopped = true
				if (++stillTicks >= 4) break
				continue
			}

			// The box's move, as 1.8 does it: up or down first, then x, then z.
			var box = AABB(x - half, y, z - half, x + half, y + HOOK_SIZE, z + half)
			val shapes = level.getBlockCollisions(player, box.expandTowards(mx, my, mz)).toList()
			val dy = Shapes.collide(Direction.Axis.Y, box, shapes, my)
			box = box.move(0.0, dy, 0.0)
			val dx = Shapes.collide(Direction.Axis.X, box, shapes, mx)
			box = box.move(dx, 0.0, 0.0)
			val dz = Shapes.collide(Direction.Axis.Z, box, shapes, mz)
			box = box.move(0.0, 0.0, dz)
			val onGround = dy != my && my < 0
			val againstWall = dx != mx || dz != mz
			if (dy != my) my = 0.0
			if (dx != mx) mx = 0.0
			if (dz != mz) mz = 0.0
			val moved = abs(dx) + abs(dy) + abs(dz)
			x = (box.minX + box.maxX) / 2
			y = box.minY
			z = (box.minZ + box.maxZ) / 2
			path += Vec3(x, y, z)

			val wet = wetFraction(level, x, y, z)
			my += 0.04 * (wet * 2 - 1)
			var drag = if (onGround || againstWall) 0.5 else 0.92
			if (wet > 0) {
				drag *= 0.9
				my *= 0.8
				wetTicks++
			}
			mx *= drag
			my *= drag
			mz *= drag
			// A few ticks first, to bob back up from where it plunged in.
			if (wetTicks >= 10 && (wetTicks >= CAST_MAX_WET_TICKS || mx * mx + mz * mz < 1e-4)) break
			stillTicks = if (wet == 0.0 && moved < 0.005) stillTicks + 1 else 0
			if (stillTicks >= 4) break
			if (y < level.minY) return null
		}
		return Cast(path, Vec3(x, y, z))
	}

	/** The path without its first [LINE_START] blocks, so the line starts ahead of the rod, not behind it. */
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

	/** How much of the hook is in water or lava, in fifths of its height, as 1.8 measures it. */
	private fun wetFraction(level: ClientLevel, x: Double, y: Double, z: Double): Double {
		val half = HOOK_SIZE / 2
		if (!fluidIn(level, x - half, y, z - half, x + half, y + HOOK_SIZE, z + half)) return 0.0
		var wet = 0
		for (slice in 0 until 5) {
			val bottom = y + HOOK_SIZE * slice / 5
			if (fluidIn(level, x - half, bottom, z - half, x + half, bottom + HOOK_SIZE / 5, z + half)) wet++
		}
		return wet / 5.0
	}

	private val fluidPos = BlockPos.MutableBlockPos()

	/** Whether any fluid in the box reaches its bottom. 1.8 fills a source block to its top. */
	private fun fluidIn(level: ClientLevel, minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double): Boolean {
		for (bx in floor(minX).toInt()..floor(maxX).toInt()) {
			for (by in floor(minY).toInt()..floor(maxY).toInt()) {
				for (bz in floor(minZ).toInt()..floor(maxZ).toInt()) {
					val fluid = level.getFluidState(fluidPos.set(bx, by, bz))
					if (!fluid.isEmpty && by + fluid.amount / 8.0 >= minY) return true
				}
			}
		}
		return false
	}

	// ---- Cast recorder (/cryptic debug cast) ----------------------------------------------------

	private val castLogger = org.slf4j.LoggerFactory.getLogger("cryptic/cast")

	/** Logs each cast's prediction and then where Hypixel says the hook is, tick by tick. */
	var recordCasts = false
	private var lastPrediction: Cast? = null
	private var recordedHook: FishingHook? = null
	private var recordedTicks = 0

	private fun Vec3.short(): String = "%.2f %.2f %.2f".format(x, y, z)

	private fun recordCast(level: ClientLevel, player: Player) {
		if (!recordCasts) return
		val hook = player.fishing
		if (hook == null) {
			if (recordedHook != null) castLogger.info("hook gone after $recordedTicks ticks")
			recordedHook = null
			// The last prediction before the hook appears is the one to compare it with.
			lastPrediction = if (holdsCastableRod) predictCast(level, player, 1f) else null
			return
		}
		if (hook !== recordedHook) {
			recordedHook = hook
			recordedTicks = 0
			val cast = lastPrediction
			castLogger.info(
				"cast: feet ${player.position().short()} yaw %.1f pitch %.1f sneaking ${player.isShiftKeyDown}".format(player.yRot, player.xRot),
			)
			castLogger.info("predicted landing ${cast?.landing?.short()} path ${cast?.path?.joinToString(" | ") { it.short() }}")
			castLogger.info("spawned at ${hook.positionCodec.base.short()} motion ${hook.deltaMovement.short()}")
		}
		if (recordedTicks++ < 120) {
			castLogger.info("tick $recordedTicks server ${hook.positionCodec.base.short()} client ${hook.position().short()} motion ${hook.deltaMovement.short()}")
		}
	}

	private fun renderTrajectory(context: LevelRenderContext) {
		val client = Minecraft.getInstance()
		val player = client.player ?: return
		val level = client.level ?: return
		if (player.fishing != null) return
		val cast = predictCast(level, player, client.deltaTracker.getGameTimeDeltaPartialTick(false)) ?: return
		val poseStack = context.poseStack()
		val collector = context.submitNodeCollector()
		WorldRender.drawLine(poseStack, collector, trimStart(cast.path),(0xFF shl 24) or (trajectoryColor.argb and 0xFFFFFF), 2f, phase = false)
		val rgb = landingColor.argb and 0xFFFFFF
		val half = HOOK_SIZE / 2
		val at = cast.landing
		WorldRender.drawBox(
			poseStack, collector, at.x - half, at.y, at.z - half, at.x + half, at.y + HOOK_SIZE, at.z + half,
			outlineArgb = (0xFF shl 24) or rgb, fillArgb = (0x50 shl 24) or rgb, outline = true, fill = true, phase = true, lineWidth = 2f,
		)
	}

	private fun render(context: LevelRenderContext) {
		if (module.enabled && trajectory.value && holdsCastableRod) renderTrajectory(context)
		if (module.enabled && radarGuess.value) renderRadar(context)
		if (!module.enabled || !highlight.value || hotspots.isEmpty()) return
		val client = Minecraft.getInstance()
		val hook = bobber()
		for (hotspot in hotspots) {
			if (hotspot.stand.isRemoved) continue
			// Drawn once Hypixel's particles have said how big it is.
			val radius = radiusOf(hotspot.stand.uuid) ?: continue
			val rgb = (if (useCustomColor.value) customColor.argb else hotspot.color) and 0xFFFFFF
			WorldRender.drawFilledCylinder(
				context.poseStack(), context.submitNodeCollector(), hotspot.centre, radius, RIM_HEIGHT,
				wallArgb = (0xFF shl 24) or rgb, floorArgb = (0x80 shl 24) or rgb, phase = false,
			)
			if (bobberInside.value && hook != null && inside(hotspot, hook.position())) {
				val box = hook.boundingBox
				val green = insideColor.argb and 0xFFFFFF
				WorldRender.drawBox(
					poseStack = context.poseStack(),
					collector = context.submitNodeCollector(),
					minX = box.minX, minY = box.minY, minZ = box.minZ,
					maxX = box.maxX, maxY = box.maxY, maxZ = box.maxZ,
					outlineArgb = (0xFF shl 24) or green,
					fillArgb = (0x50 shl 24) or green,
					outline = true,
					fill = true,
					phase = true,
					lineWidth = 2f,
				)
			}
		}
	}

	fun describe(): List<String> = hotspots.map {
		val counts = radiusCounts[it.stand.uuid]?.entries?.sortedByDescending { e -> e.value }?.joinToString { (r, n) -> "${r}x$n" } ?: "none yet"
		"Hotspot ${it.perk?.string ?: "?"} at ${it.stand.blockPosition().toShortString()}: radius ${radiusOf(it.stand.uuid) ?: "unknown"} (measured $counts)"
	}.ifEmpty { listOf("No hotspots in range.") }
}
