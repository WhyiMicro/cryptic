package imicro.cryptic.feature

import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.skyblock.SkyblockItem
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.InteractionResult
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/**
 * Finds what a Wishing Compass points at. Each use sends a trail of green sparks toward the nearest
 * place you haven't found in the zone you're in; two uses from different spots give two lines, and
 * where they cross is the place. It goes on the Crystal Hollows map and in the world like any other.
 *
 * The idea, the zones and what each zone's compass leads to are Skyblocker's (its Wishing Compass
 * solver); the line fitting and crossing are Cryptic's own.
 */
object WishingCompass {
	@JvmField
	val setting = ToggleModuleSetting(
		id = "wishing_compass",
		label = "Wishing Compass solver",
		defaultValue = true,
		description = "Use a Wishing Compass twice, at least 8 blocks apart in the same zone, and where its two trails cross is marked on the map and in the world. A use too soon or too close is stopped, so no compass is wasted.",
	)

	private const val ITEM_ID = "WISHING_COMPASS"
	private const val FAILED = "The Wishing Compass can't seem to locate anything!"

	/** Sparks read for each line, the most one may lag behind the last, and how far apart they lie. */
	private const val SPARKS_PER_LINE = 25
	private const val SPARK_GAP_MILLIS = 500L
	private const val SPARK_STEP = 0.9

	/** The second use must be this far from the first, for two lines that cross cleanly. */
	private const val MIN_SPACING = 8.0

	/** Two lines passing closer than this are taken to cross. */
	private const val CROSS_TOLERANCE = 5.0

	/** A found place this far outside the zone used in is something else. */
	private const val ZONE_SLACK = 100.0

	/** The temple's compass point is its middle; the door is here from it. */
	private val TEMPLE_DOOR = Vec3(-57.0, 36.0, -21.0)

	private const val STANDING_EYES = 1.62

	private const val TOAST_SOURCE = "Wishing Compass"

	/** Long enough to read the instructions. */
	private const val HINT_SECONDS = 12.0

	private enum class Zone(val box: AABB, val crystal: CrystalStatus.Crystal?) {
		NUCLEUS(AABB(462.0, 63.0, 461.0, 564.0, 181.0, 565.0), null),
		JUNGLE(AABB(201.0, 63.0, 201.0, 513.0, 189.0, 513.0), CrystalStatus.Crystal.AMETHYST),
		MITHRIL(AABB(512.0, 63.0, 201.0, 824.0, 189.0, 513.0), CrystalStatus.Crystal.JADE),
		GOBLIN(AABB(201.0, 63.0, 512.0, 513.0, 189.0, 824.0), CrystalStatus.Crystal.AMBER),
		PRECURSOR(AABB(512.0, 63.0, 512.0, 824.0, 189.0, 824.0), CrystalStatus.Crystal.SAPPHIRE),
		MAGMA(AABB(201.0, 30.0, 201.0, 824.0, 64.0, 824.0), CrystalStatus.Crystal.TOPAZ),
		;

		companion object {
			// The Nucleus first: its box sits inside the four quadrants'.
			fun of(at: Vec3): Zone = entries.firstOrNull { it.box.contains(at) } ?: NUCLEUS
		}
	}

	/** One use: where it was used from, and the sparks read so far. */
	private class Trail(val start: Vec3, val zone: Zone) {
		val sparks = ArrayList<Vec3>()
		var last: Vec3 = start
		var lastAt = System.currentTimeMillis()
		val done get() = sparks.size >= SPARKS_PER_LINE
	}

	/** The first use's line, once read, while the second is awaited or read. */
	private var first: Trail? = null
	private var firstLine: Pair<Vec3, Vec3>? = null
	private var second: Trail? = null

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		UseItemCallback.EVENT.register { _, level, _ -> if (level.isClientSide) onUse() else InteractionResult.PASS }
		UseBlockCallback.EVENT.register { _, level, _, _ -> if (level.isClientSide) onUse() else InteractionResult.PASS }
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
	}

	private val active: Boolean
		get() = NucleusQol.module.enabled && setting.value && SkyblockLocation.inCrystalHollows

	private fun reset() {
		first = null
		firstLine = null
		second = null
	}

	/** The trail being read right now, if any. */
	private val reading: Trail?
		get() = second?.takeIf { !it.done } ?: first?.takeIf { !it.done && firstLine == null }

	/** A right click; FAIL keeps the compass from being used when it would be wasted. */
	private fun onUse(): InteractionResult {
		val player = Minecraft.getInstance().player ?: return InteractionResult.PASS
		if (!active || SkyblockItem.id(player.mainHandItem) != ITEM_ID) return InteractionResult.PASS
		// Standing eye height, so sneaking doesn't move the line's start.
		val eyes = player.position().add(0.0, STANDING_EYES, 0.0)
		val zone = Zone.of(eyes)
		val now = System.currentTimeMillis()

		reading?.let { trail ->
			if (now - trail.lastAt < SPARK_GAP_MILLIS) {
				say("§cWait for the trail to finish.")
				return InteractionResult.FAIL
			}
			// The last use's sparks never came; start over with this one.
			say("§7The last use wasn't read; starting again.")
			reset()
		}

		val line = firstLine
		if (line == null) {
			if (zone == Zone.NUCLEUS) {
				say("§cUse it outside the Nucleus; there it can point anywhere.")
				return InteractionResult.FAIL
			}
			first = Trail(eyes, zone)
			return InteractionResult.PASS
		}

		val from = first ?: return InteractionResult.PASS
		if (eyes.distanceTo(from.start) < MIN_SPACING) {
			say("§cMove at least ${MIN_SPACING.toInt()} blocks from where you used it first.")
			return InteractionResult.FAIL
		}
		if (zone != from.zone) {
			say("§7You've changed zone, so this counts as a first use.")
			reset()
			first = Trail(eyes, zone)
			return InteractionResult.PASS
		}
		second = Trail(eyes, zone)
		return InteractionResult.PASS
	}

	/** A particle from the server. Client thread only. */
	@JvmStatic
	fun onParticle(packet: ClientboundLevelParticlesPacket) {
		if (!Minecraft.getInstance().isSameThread) return
		if (packet.particle.type != ParticleTypes.HAPPY_VILLAGER) return
		val trail = reading ?: return
		if (!active) return
		val at = Vec3(packet.x, packet.y, packet.z)
		// Only the next spark along this trail, not others' trails or stray sparks.
		if (at.distanceTo(trail.last) > SPARK_STEP) return
		trail.last = at
		trail.lastAt = System.currentTimeMillis()
		trail.sparks += at
		if (!trail.done) return

		val line = fit(trail) ?: run {
			say("§cThat trail couldn't be read; use it again.")
			reset()
			return
		}
		if (trail === first) {
			firstLine = line
			say("§aFirst reading taken. §7Move ${MIN_SPACING.toInt()}+ blocks and use it again.")
			// One line only gives a direction; a second, from elsewhere, crosses it at the spot.
			Toasts.show(
				TOAST_SOURCE,
				"§7Move §f${MIN_SPACING.toInt()} blocks §7away and use another Wishing Compass to triangulate the location.",
				life = HINT_SECONDS,
				color = Toasts.Colors.MINING,
			)
		} else {
			solve(line)
		}
	}

	@JvmStatic
	fun onSystemChat(content: Component, overlay: Boolean) {
		if (overlay || !active) return
		if (content.string.replace(Regex("§."), "").trim() == FAILED) reset()
	}

	/**
	 * The trail's line: through its sparks' middle, along the direction they spread most (the
	 * largest axis of their spread), pointing away from where it was used.
	 */
	private fun fit(trail: Trail): Pair<Vec3, Vec3>? {
		val points = trail.sparks
		val mean = points.fold(Vec3.ZERO) { sum, p -> sum.add(p) }.scale(1.0 / points.size)
		val c = Array(3) { DoubleArray(3) }
		for (p in points) {
			val d = doubleArrayOf(p.x - mean.x, p.y - mean.y, p.z - mean.z)
			for (i in 0..2) for (j in 0..2) c[i][j] += d[i] * d[j]
		}
		// Power iteration from the rough direction converges on the largest axis.
		var v = mean.subtract(trail.start).normalize()
		if (v.lengthSqr() < 1e-9) return null
		repeat(50) {
			val next = Vec3(
				c[0][0] * v.x + c[0][1] * v.y + c[0][2] * v.z,
				c[1][0] * v.x + c[1][1] * v.y + c[1][2] * v.z,
				c[2][0] * v.x + c[2][1] * v.y + c[2][2] * v.z,
			)
			if (next.lengthSqr() < 1e-12) return null
			v = next.normalize()
		}
		if (v.dot(mean.subtract(trail.start)) < 0) v = v.scale(-1.0)
		return mean to v
	}

	/** Where the two lines cross, ahead of both, and what's likely there. */
	private fun solve(line: Pair<Vec3, Vec3>) {
		val one = firstLine
		val from = first
		reset()
		if (one == null || from == null) return
		val (p1, d1) = one
		val (p2, d2) = line
		val w = p1.subtract(p2)
		val a = d1.dot(d1)
		val b = d1.dot(d2)
		val c = d2.dot(d2)
		val d = d1.dot(w)
		val e = d2.dot(w)
		val denominator = a * c - b * b
		if (abs(denominator) < 1e-6) {
			say("§cThe two trails were parallel; use it from somewhere more to the side.")
			return
		}
		val t = (b * e - c * d) / denominator
		val s = (a * e - b * d) / denominator
		val c1 = p1.add(d1.scale(t))
		val c2 = p2.add(d2.scale(s))
		if (t < 0 || s < 0 || c1.distanceTo(c2) > CROSS_TOLERANCE) {
			say("§cThe two trails don't meet; try again from two other spots.")
			return
		}
		var target = c1.add(c2).scale(0.5)
		val zone = from.zone
		var place = placeFor(zone)
		if (place != HollowsMap.Place.COMPASS_TARGET && !zone.box.inflate(ZONE_SLACK, 0.0, ZONE_SLACK).contains(target)) {
			place = HollowsMap.Place.COMPASS_TARGET
		}
		if (place == HollowsMap.Place.JUNGLE_TEMPLE) target = target.add(TEMPLE_DOOR)
		val pos = BlockPos.containing(target)
		HollowsMap.offer(place, pos, HollowsMap.Source.COMPASS)
		say("§aFound §f${place.title} §7at §f${pos.x} ${pos.y} ${pos.z}")
	}

	/** What a compass used in [zone] leads to, by what's still to find there. */
	private fun placeFor(zone: Zone): HollowsMap.Place {
		val crystal = zone.crystal ?: return HollowsMap.Place.COMPASS_TARGET
		val state = CrystalStatus.stateOf(crystal)
		if (state != null && state != CrystalStatus.State.NOT_FOUND) return HollowsMap.Place.COMPASS_TARGET
		return when (zone) {
			Zone.JUNGLE -> if (hasJungleKey()) HollowsMap.Place.JUNGLE_TEMPLE else HollowsMap.Place.ODAWA
			Zone.MITHRIL -> HollowsMap.Place.MINES_OF_DIVAN
			Zone.GOBLIN -> if (hasKingsScent()) HollowsMap.Place.GOBLIN_QUEENS_DEN else HollowsMap.Place.KING_YOLKAR
			Zone.PRECURSOR -> HollowsMap.Place.LOST_PRECURSOR_CITY
			Zone.MAGMA -> HollowsMap.Place.KHAZAD_DUM
			Zone.NUCLEUS -> HollowsMap.Place.COMPASS_TARGET
		}
	}

	private fun hasJungleKey(): Boolean {
		val inventory = Minecraft.getInstance().player?.inventory ?: return false
		return (0 until inventory.containerSize).any { SkyblockItem.id(inventory.getItem(it)) == "JUNGLE_KEY" }
	}

	private fun hasKingsScent(): Boolean {
		val players = Minecraft.getInstance().connection?.onlinePlayers ?: return false
		return players.any { it.tabListDisplayName?.string?.contains("King's Scent") == true }
	}

	private fun say(text: String) {
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §7Wishing Compass: $text"))
	}

	/** Where the solver is, for the debug readout. */
	fun describe(): String = when {
		second != null -> "reading the second trail (${second?.sparks?.size} sparks)"
		firstLine != null -> "first line read; waiting for the second use"
		first != null -> "reading the first trail (${first?.sparks?.size} sparks)"
		else -> "idle"
	}
}
