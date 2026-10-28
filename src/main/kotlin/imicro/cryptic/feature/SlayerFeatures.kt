package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.render.WorldRender
import imicro.cryptic.skyblock.SkyblockItem
import imicro.cryptic.skyblock.SkyblockLocation
import imicro.cryptic.slayer.VoidgloomBosses
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import java.util.EnumMap
import java.util.Locale
import kotlin.math.ceil
import org.joml.Vector3f

/**
 * Voidgloom Seraph help: the boss's phase and how long is left of it, how long the fight has taken,
 * and its Yang Glyphs (beacons) and Nukekubi Fixations (skulls) marked out.
 *
 * Every boss in sight gets its stats; beacons and skulls are looked for around one: yours or a carried
 * player's when the Carry Manager knows it, otherwise the nearest. The phase comes off its name
 * stand ("50 Hits" while shielded, health otherwise), and the laser from the armour stand it rides;
 * that lasts 8.2 seconds from when it mounts (Athen, BSD-3).
 *
 * The Fire Veil overlay is IQ's (Apache 2.0): the wand's 3.5-block ring for the five seconds it
 * burns, and its flames hidden for nine seconds from the cast.
 */
object SlayerFeatures {
	private const val VOIDGLOOM_ISLAND = "The End"

	/** The laser, in ticks of the stand the boss rides. */
	private const val LASER_TICKS = 164

	/** A Yang Glyph goes off this long after it lands. */
	private const val BEACON_MILLIS = 5_000L

	/** A boss this close becomes the one followed, and stays it until this far. */
	private const val FOCUS_RANGE = 20.0
	private const val FOCUS_DROP = 40.0

	/** Glyphs and skulls further than this from the followed boss (or you) are somebody else's. */
	private const val NEAR = 16.0

	/** The Nukekubi skull's skin id, which a re-signed texture keeps. */
	private const val NUKEKUBI_SKIN = "eb07594e2df273921a77c101d0bfdfa1115abed5b9b2029eb496ceba9bdbb4b3"

	private const val LINE_WIDTH = 2f

	private const val AT_FEET = 0

	private const val BY_PHASE = 0

	private const val VEIL_CIRCLE = 0

	/** The Fire Veil Wand's ring, how long it burns, and how long its flames are hidden. */
	private const val VEIL_ID = "FIRE_VEIL_WAND"
	private const val VEIL_RADIUS = 3.5
	private const val VEIL_WALL_HEIGHT = 1.0
	private const val VEIL_MILLIS = 5_000L
	private const val VEIL_FLAME_MILLIS = 9_000L


	/** Flames this close to you while the veil burns are its own. */
	private const val VEIL_FLAME_REACH = 6.0

	/** A click and the use that follows it both fire; one cast is counted from both. */
	private const val VEIL_DEBOUNCE_MILLIS = 500L

	/** A kill's time stays where the boss died this long. */
	private const val KILL_LABEL_MILLIS = 3_000L

	/** One line of world text, in blocks at size one: the font's 10 px at [WorldRender]'s scale. */
	private const val LINE_HEIGHT = 0.25

	/** A tier's hits shield, and its health before mayor buffs, tier I first. */
	private val MAX_HITS = listOf(15, 30, 60, 100)
	private val MAX_HEALTH = listOf(300_000.0, 12_000_000.0, 50_000_000.0, 210_000_000.0)

	/** The boss's own smoke, flames and spell particles, as SkyHanni hides them. */
	private val BOSS_PARTICLES = setOf(ParticleTypes.LARGE_SMOKE, ParticleTypes.FLAME, ParticleTypes.WITCH)

	/** Particles this far from a boss's hitbox are its own. */
	private const val PARTICLE_REACH = 3.0

	private const val BOSS_NAME = "Voidgloom Seraph"

	private val hitsPattern = Regex("""(\d+) Hits""")

	/** Health as "266k/750k", current first, or as "45M❤". */
	private val healthPattern = Regex("""^([\d.,]+[kMB]?)(?:/([\d.,]+[kMB]?))?""")

	private val bossSection = SectionModuleSetting("boss_section", "Boss")

	@JvmField
	val bossLabel = ToggleModuleSetting(
		id = "boss_label",
		label = "Stats on boss",
		defaultValue = true,
		description = "The phase, the laser's time left, and hits or health, written on every boss. At its feet, you see them looking down in first person. The kill time stays there a moment after it dies.",
	)

	@JvmField
	val labelPlace = DropdownModuleSetting(
		id = "boss_label_place",
		label = "Where",
		options = listOf("At its feet", "Over its head"),
		defaultIndex = AT_FEET,
		visibleIf = { bossLabel.value },
	)

	@JvmField
	val labelSize = SliderModuleSetting(
		id = "boss_label_size",
		label = "Size",
		defaultValue = 1.5,
		min = 0.5,
		max = 4.0,
		step = 0.1,
		visibleIf = { bossLabel.value },
	)

	@JvmField
	val labelThroughWalls = ToggleModuleSetting(
		id = "boss_label_through_walls",
		label = "Through walls",
		defaultValue = true,
		description = "Shows the stats through walls and the boss itself.",
		visibleIf = { bossLabel.value },
	)

	@JvmField
	val healthColour = DropdownModuleSetting(
		id = "health_colour",
		label = "Health colour",
		options = listOf("By phase", "By total health"),
		defaultIndex = BY_PHASE,
		description = "Whether health goes green to red over each phase, or over the whole fight.",
		visibleIf = { bossLabel.value },
	)

	@JvmField
	val hideParticles = ToggleModuleSetting(
		id = "hide_particles",
		label = "Hide boss particles",
		defaultValue = true,
		description = "Hides the smoke, flames and purple spell particles around Voidgloom bosses.",
	)

	@JvmField
	val laserColor = ColorModuleSetting("laser_color", "Laser colour", defaultRgb = 0xFF2020)

	@JvmField
	val laserFill = ColorModuleSetting("laser_fill", "Fill", defaultRgb = 0xFF2020, supportsAlpha = true, defaultAlpha = 0x10)

	@JvmField
	val customLaser = ToggleModuleSetting(
		id = "custom_laser",
		label = "Custom laser",
		defaultValue = true,
		description = "The laser phase drawn as four walls, in place of Minecraft's guardian beams and their bubbles: the " +
			"top and bottom beam of each direction, joined at both ends and filled between. Only on the End's island.",
	).withMore(laserColor, laserFill)

	@JvmField
	val killTime = ToggleModuleSetting(
		id = "kill_time",
		label = "Kill time in chat",
		defaultValue = true,
		description = "Says in chat how long your own boss, or a client's, took once it dies.",
	)

	@JvmField
	val phaseTimes = ToggleModuleSetting(
		id = "phase_times",
		label = "Time per phase",
		defaultValue = true,
		description = "Adds how long was spent in hits, damage and laser phases.",
		visibleIf = { killTime.value },
	)

	private val beaconSection = SectionModuleSetting("beacon_section", "Beacons")

	@JvmField
	val highlightBeacons = ToggleModuleSetting(
		id = "highlight_beacons",
		label = "Highlight beacons",
		defaultValue = true,
		description = "Boxes a thrown beacon in the air and where it lands, with the seconds until it goes off.",
	)

	@JvmField
	val beaconColor = ColorModuleSetting(
		id = "beacon_color",
		label = "Beacon",
		defaultRgb = 0xFF5555,
		supportsAlpha = true,
		defaultAlpha = 0x80,
		visibleIf = { highlightBeacons.value },
		inlineWith = highlightBeacons,
	)

	@JvmField
	val beaconTracer = ToggleModuleSetting(
		id = "beacon_tracer",
		label = "Beacon tracer",
		defaultValue = true,
		description = "A line from you to each beacon.",
	)

	@JvmField
	val beaconTracerColor = ColorModuleSetting(
		id = "beacon_tracer_color",
		label = "Beacon tracer",
		defaultRgb = 0xFF5555,
		supportsAlpha = true,
		visibleIf = { beaconTracer.value },
		inlineWith = beaconTracer,
	)

	@JvmField
	val beaconAlert = ToggleModuleSetting(
		id = "beacon_alert",
		label = "Beacon alert",
		defaultValue = true,
		description = "A title and a sound when the boss throws a beacon.",
	)

	private val veilSection = SectionModuleSetting("veil_section", "Fire Veil")

	@JvmField
	val fireVeil = ToggleModuleSetting(
		id = "fire_veil",
		label = "Fire Veil overlay",
		defaultValue = false,
		description = "Draws the Fire Veil Wand's reach around you while it burns.",
	)

	@JvmField
	val veilStyle = DropdownModuleSetting(
		id = "veil_style",
		label = "Style",
		options = listOf("Circle", "Wall"),
		defaultIndex = VEIL_CIRCLE,
		visibleIf = { fireVeil.value },
	)

	@JvmField
	val veilColor = ColorModuleSetting(
		id = "veil_color",
		label = "Colour",
		defaultRgb = 0xFFAA00,
		supportsAlpha = true,
		defaultAlpha = 0xC8,
		visibleIf = { fireVeil.value },
	)

	@JvmField
	val veilWidth = SliderModuleSetting(
		id = "veil_width",
		label = "Line width",
		defaultValue = 3.0,
		min = 1.0,
		max = 10.0,
		step = 0.5,
		visibleIf = { fireVeil.value },
	)

	@JvmField
	val veilThroughWalls = ToggleModuleSetting(
		id = "veil_through_walls",
		label = "Through walls",
		defaultValue = false,
		visibleIf = { fireVeil.value },
	)

	@JvmField
	val hideVeilFlames = ToggleModuleSetting(
		id = "hide_veil_flames",
		label = "Hide Fire Veil flames",
		defaultValue = true,
		description = "Hides the flame particles the wand puts around you.",
	)

	private val skullSection = SectionModuleSetting("skull_section", "Skulls")

	@JvmField
	val highlightSkulls = ToggleModuleSetting(
		id = "highlight_skulls",
		label = "Highlight skulls",
		defaultValue = true,
		description = "Outlines the Nukekubi skulls floating around the boss.",
	)

	@JvmField
	val skullColor = ColorModuleSetting(
		id = "skull_color",
		label = "Skull",
		defaultRgb = 0x55FF55,
		visibleIf = { highlightSkulls.value },
		inlineWith = highlightSkulls,
	)

	@JvmField
	val skullTracer = ToggleModuleSetting(
		id = "skull_tracer",
		label = "Skull tracer",
		defaultValue = false,
		description = "A line from you to each skull.",
	)

	@JvmField
	val skullTracerColor = ColorModuleSetting(
		id = "skull_tracer_color",
		label = "Skull tracer",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
		visibleIf = { skullTracer.value },
		inlineWith = skullTracer,
	)

	@JvmField
	val module = Module(
		id = "slayer_features",
		name = "Slayer Features",
		description = "Voidgloom stats, Fire Veil",
		category = ModuleCategory.GENERAL,
		settings = listOf(
			bossSection,
			bossLabel.withMore(labelPlace, labelSize, labelThroughWalls, healthColour),
			hideParticles,
			customLaser,
			killTime.withMore(phaseTimes),
			beaconSection,
			highlightBeacons, beaconColor,
			beaconTracer, beaconTracerColor,
			beaconAlert,
			skullSection,
			highlightSkulls, skullColor,
			skullTracer, skullTracerColor,
			veilSection,
			fireVeil.withMore(veilStyle, veilColor, veilWidth, veilThroughWalls),
			hideVeilFlames,
		),
	)

	val needsSkyblock: Boolean get() = module.enabled

	private enum class Phase(val label: String) {
		HITS("§dHits"),
		LASER("§bLaser"),
		DAMAGE("§cDamage"),
	}

	/** One boss, and how its fight has gone so far. */
	private class Fight(val bossId: Int, val tier: Int, var since: Long) {
		var phase: Phase? = null
		var phaseSince = since

		/** The despawn timer's seconds as last read, and whether a change of it has pinned [since]. */
		var timerSeconds: Int? = null
		var timerPinned = false
		val spent = EnumMap<Phase, Long>(Phase::class.java)

		/** When its name stand was last seen; a dying boss loses it before the death event. */
		var lastSeen = since

		/** Hits left on the shield, or null outside one. */
		var hits: Int? = null

		/** Health as last read off the name; the name shows hits instead during a shield. */
		var health = MAX_HEALTH.getOrElse(tier - 1) { MAX_HEALTH.last() }
		var maxHealth = health
		var healthText: String? = null

		/** Milliseconds left of the laser, or null outside it. */
		var laserLeft: Long? = null


		fun enter(next: Phase, now: Long) {
			if (next == phase) return
			phase?.let { spent.merge(it, now - phaseSince, Long::plus) }
			phase = next
			phaseSince = now
		}

		fun finish(now: Long) {
			phase?.let { spent.merge(it, now - phaseSince, Long::plus) }
			phase = null
		}
	}

	/** Every boss in sight, by entity id. */
	private val fights = HashMap<Int, Fight>()

	/** The boss beacons and skulls are looked for around. */
	private var focusId = -1

	/** Kill times left where each boss died, until [KILL_LABEL_MILLIS] after. */
	private class KillLabel(val text: String, val where: Vec3, val at: Long)

	private val killLabels = ArrayList<KillLabel>()

	/**
	 * A kill whose owner is unknown, waiting for Hypixel to say your own boss died, and when that
	 * last said so, for a line that comes first.
	 */
	private class Kill(val fight: Fight, val where: Vec3, val at: Long)

	private val unclaimed = ArrayList<Kill>()
	private var slainSaidAt = 0L

	/** How far apart a death and Hypixel's "boss slain" line can be and still go together. */
	private const val CLAIM_MILLIS = 2_000L

	/** A boss whose name stand has been gone this long is forgotten. */
	private const val FORGET_MILLIS = 2_000L

	private val formattingPattern = Regex("§.")

	/** Thrown beacons in the air, by armour stand id, where each was last seen. */
	private val flying = HashMap<Int, Vec3>()

	/** Beacons that landed, and when. */
	private val placed = HashMap<BlockPos, Long>()

	private var skulls: List<ArmorStand> = emptyList()
	private var skullIds: Set<Int> = emptySet()

	/**
	 * Every boss's hitbox, grown by [PARTICLE_REACH], for the particle hook. Read on the network
	 * thread, so the list is replaced whole each tick, never changed.
	 */
	@Volatile
	private var particleZones: List<AABB> = emptyList()

	/** When the Fire Veil was last cast, and where you were last tick; both read by the particle hook. */
	@Volatile
	private var veilCastAt = 0L

	@Volatile
	private var playerAt: Vec3 = Vec3.ZERO


	/** Whether each skull texture seen is a Nukekubi, so each is decoded once. */
	private val skullTextures = HashMap<String, Boolean>()

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
	}

	private val active: Boolean
		get() = module.enabled &&
			((SkyblockLocation.onSkyblock && SkyblockLocation.area == VOIDGLOOM_ISLAND) || DebugOverrides.slayerAnywhere)

	fun tick(client: Minecraft) {
		val level = client.level
		val player = client.player
		tickVeil(player)
		if (!active || level == null || player == null) {
			clear()
			return
		}
		val now = System.currentTimeMillis()

		val seen = VoidgloomBosses.visible(level)
		for (entry in seen) {
			// Back-dated to when the client first saw the boss.
			val fight = fights.getOrPut(entry.boss.id) { Fight(entry.boss.id, entry.tier, now - entry.boss.tickCount * 50L) }
			read(fight, entry, now)
		}
		fights.values.removeIf { now - it.lastSeen > FORGET_MILLIS || level.getEntity(it.bossId) == null }
		focusId = pickFocus(seen, player.gameProfile.name, player.position())?.boss?.id ?: -1
		unclaimed.removeIf { now - it.at > CLAIM_MILLIS }
		killLabels.removeIf { now - it.at > KILL_LABEL_MILLIS }

		val centre = level.getEntity(focusId)?.position() ?: player.position()
		trackBeacons(level, centre, now)
		skulls = if (highlightSkulls.value || skullTracer.value) {
			level.entitiesForRendering().filterIsInstance<ArmorStand>().filter { stand ->
				stand.position().distanceTo(centre) <= NEAR && isNukekubi(stand)
			}
		} else {
			emptyList()
		}
		skullIds = skulls.mapTo(HashSet()) { it.id }
		particleZones = if (hideParticles.value) seen.map { it.boss.boundingBox.inflate(PARTICLE_REACH) } else emptyList()
	}

	private fun clear() {
		fights.clear()
		focusId = -1
		killLabels.clear()
		unclaimed.clear()
		flying.clear()
		placed.clear()
		skulls = emptyList()
		skullIds = emptySet()
		particleZones = emptyList()
	}

	/**
	 * Whether a particle is a boss's own, or the Fire Veil's, to be dropped. Network thread: reads
	 * only the volatile fields.
	 */
	@JvmStatic
	fun hidesParticle(packet: ClientboundLevelParticlesPacket): Boolean {
		return try {
			val type = packet.particle.type
			if (type == ParticleTypes.FLAME && veilFlamesHidden() &&
				playerAt.distanceToSqr(packet.x, packet.y, packet.z) <= VEIL_FLAME_REACH * VEIL_FLAME_REACH
			) {
				return true
			}
			val zones = particleZones
			zones.isNotEmpty() && type in BOSS_PARTICLES && zones.any { it.contains(packet.x, packet.y, packet.z) }
		} catch (_: RuntimeException) {
			false
		}
	}

	private fun veilFlamesHidden(): Boolean =
		module.enabled && hideVeilFlames.value && veilCastAt > 0 && System.currentTimeMillis() - veilCastAt <= VEIL_FLAME_MILLIS

	/** A right click with an item, from the game-mode mixin (a block click and the use after it both come). */
	@JvmStatic
	fun onItemUsed(stack: ItemStack) {
		if (!module.enabled || (!fireVeil.value && !hideVeilFlames.value)) return
		if (stack.isEmpty) return
		val isVeil = SkyblockItem.id(stack) == VEIL_ID ||
			stack.hoverName.string.replace(formattingPattern, "").contains("Fire Veil", ignoreCase = true)
		if (!isVeil) return
		val now = System.currentTimeMillis()
		if (now - veilCastAt < VEIL_DEBOUNCE_MILLIS) return
		veilCastAt = now
	}

	private fun tickVeil(player: net.minecraft.client.player.LocalPlayer?) {
		if (player == null || !module.enabled) {
			veilCastAt = 0L
			return
		}
		playerAt = player.position()
	}

	/** The skulls' outline colour. Asked per entity per frame, so the set lookup comes first. */
	@JvmStatic
	fun outlineColorFor(entity: Entity): Int {
		if (entity.id !in skullIds || !highlightSkulls.value || !module.enabled) return EntityRenderState.NO_OUTLINE
		return ARGB.opaque(skullColor.rgb)
	}

	private fun ours(name: String): Boolean =
		name.equals(Minecraft.getInstance().player?.gameProfile?.name, ignoreCase = true) || VoidgloomBosses.carried(name)

	/**
	 * Yours or a carried player's boss first, then the one already followed, then the nearest that
	 * isn't known to be somebody else's.
	 */
	private fun pickFocus(seen: List<VoidgloomBosses.Seen>, me: String, at: Vec3): VoidgloomBosses.Seen? {
		fun ownedBy(entry: VoidgloomBosses.Seen) = VoidgloomBosses.ownerOf(entry.boss.id)

		seen.filter { entry -> ownedBy(entry)?.let(::ours) == true }
			.minByOrNull { it.boss.position().distanceToSqr(at) }
			?.let { return it }

		val free = seen.filter { entry -> ownedBy(entry)?.let(::ours) != false }
		free.firstOrNull { it.boss.id == focusId && it.boss.position().distanceTo(at) <= FOCUS_DROP }?.let { return it }
		return free.filter { it.boss.position().distanceTo(at) <= FOCUS_RANGE }.minByOrNull { it.boss.position().distanceToSqr(at) }
	}

	/** "03:56" over the boss: how long is left of the four minutes it has before it despawns. */
	private val timerPattern = Regex("""^(\d{1,2}):(\d{2})$""")
	private val colourCodes = Regex("§.")
	private const val BOSS_LIMIT_SECONDS = 240

	/** The despawn timer's stand, a little over the boss's name stand. */
	private fun timerLeft(seen: VoidgloomBosses.Seen): Int? {
		val stand = seen.nameStand
		val level = stand.level()
		return level.getEntitiesOfClass(ArmorStand::class.java, stand.boundingBox.inflate(0.6, 1.5, 0.6)) { other ->
			other !== stand && other.y >= stand.y
		}.firstNotNullOfOrNull { other ->
			timerPattern.matchEntire(other.customName?.string?.replace(colourCodes, "")?.trim() ?: return@firstNotNullOfOrNull null)
				?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
		}
	}

	/**
	 * Dates the fight from the despawn timer. The moment its seconds change is a whole second, so
	 * the first change seen pins the start to the millisecond; until then, the middle of the second
	 * shown is the best guess.
	 */
	private fun syncStart(fight: Fight, seen: VoidgloomBosses.Seen, now: Long) {
		if (fight.timerPinned) return
		val left = timerLeft(seen) ?: return
		val changed = fight.timerSeconds?.let { it != left } == true
		fight.timerSeconds = left
		val elapsed = (BOSS_LIMIT_SECONDS - left) * 1000L + if (changed) 0L else 500L
		val start = now - elapsed
		// Phase timing started with the fight; it moves with it until the first phase begins.
		if (fight.phaseSince == fight.since) fight.phaseSince = start
		fight.since = start
		if (changed) fight.timerPinned = true
	}

	private fun read(fight: Fight, seen: VoidgloomBosses.Seen, now: Long) {
		fight.lastSeen = now
		syncStart(fight, seen, now)

		val ride = seen.boss.vehicle
		// What follows the tier: "67 Hits", or health as "266k/750k" (or "45M❤").
		val tail = seen.name.substringAfter(BOSS_NAME).trim().substringAfter(' ').trim()
		fight.hits = hitsPattern.find(tail)?.groupValues?.get(1)?.toIntOrNull()
		fight.laserLeft = ride?.let { (LASER_TICKS - it.tickCount).coerceAtLeast(0) * 50L }
		if (fight.hits == null) {
			healthPattern.find(tail)?.let { match ->
				val current = match.groupValues[1]
				val health = parseHealth(current) ?: return@let
				fight.healthText = current
				fight.health = health
				val stated = match.groupValues[2].takeIf { it.isNotEmpty() }?.let(::parseHealth)
				// Buffed bosses start above the usual figure; without a stated maximum, the first
				// reading is their full health.
				fight.maxHealth = stated ?: maxOf(fight.maxHealth, health)
			}
		}
		// The laser comes in a hits phase, so it's checked first.
		fight.enter(
			when {
				ride != null -> Phase.LASER
				fight.hits != null -> Phase.HITS
				else -> Phase.DAMAGE
			},
			now,
		)
	}

	/** "45M" as 45 million; Hypixel writes k, M and B. */
	private fun parseHealth(text: String): Double? {
		val number = text.trimEnd('k', 'M', 'B').replace(",", "").toDoubleOrNull() ?: return null
		return number * when (text.last()) {
			'k' -> 1e3
			'M' -> 1e6
			'B' -> 1e9
			else -> 1.0
		}
	}

	/** Green, then yellow, then red as [left] runs down from [full]. */
	private fun shade(left: Double, full: Double): String {
		val fraction = if (full > 0) left / full else 1.0
		return when {
			fraction > 2.0 / 3 -> "§a"
			fraction > 1.0 / 3 -> "§e"
			else -> "§c"
		}
	}

	/**
	 * The stats as two lines: the phase (out of three, or six for a tier IV, split by health as
	 * SkyHanni does) with the laser's time left, then hits or health shaded by what's left.
	 */
	private fun statLines(fight: Fight): List<String> {
		val steps = if (fight.tier >= 4) 6 else 3
		val step = fight.maxHealth / steps
		val index = (steps - ceil(fight.health / step).toInt() + 1).coerceIn(1, steps)
		// Lag can keep the boss on its stand past the 8.2 seconds.
		val laser = fight.laserLeft?.let { if (it <= 0) " §fsoon" else " §f${seconds(it)}" } ?: ""
		val second = fight.hits?.let { hits ->
			val full = MAX_HITS.getOrElse(fight.tier - 1) { 100 }.toDouble()
			"${shade(hits.toDouble(), full)}$hits Hits"
		} ?: fight.healthText?.let {
			val colour = if (healthColour.selectedIndex == BY_PHASE) {
				shade(fight.health - step * (steps - index), step)
			} else {
				shade(fight.health, fight.maxHealth)
			}
			"$colour$it"
		}
		return listOfNotNull("§e$index/$steps$laser", second)
	}

	private fun trackBeacons(level: net.minecraft.client.multiplayer.ClientLevel, centre: Vec3, now: Long) {
		if (!highlightBeacons.value && !beaconTracer.value && !beaconAlert.value) {
			flying.clear()
			placed.clear()
			return
		}

		val inAir = level.entitiesForRendering().filterIsInstance<ArmorStand>().filter { stand ->
			stand.getItemBySlot(EquipmentSlot.HEAD).`is`(Items.BEACON) && stand.position().distanceTo(centre) <= NEAR
		}
		for (stand in inAir) {
			if (stand.id !in flying) onThrown()
			flying[stand.id] = stand.position()
		}
		// Look where each one is, and where each that just vanished was, for the block it became.
		val ids = inAir.mapTo(HashSet()) { it.id }
		for (where in flying.values) findLanded(level, where, now)
		flying.keys.retainAll(ids)

		placed.entries.removeIf { (pos, landed) ->
			!level.getBlockState(pos).`is`(Blocks.BEACON) || now - landed > BEACON_MILLIS * 3
		}
	}

	private fun findLanded(level: net.minecraft.client.multiplayer.ClientLevel, where: Vec3, now: Long) {
		val middle = BlockPos.containing(where)
		for (pos in BlockPos.betweenClosed(middle.offset(-2, -2, -2), middle.offset(2, 3, 2))) {
			if (level.getBlockState(pos).`is`(Blocks.BEACON)) placed.putIfAbsent(pos.immutable(), now)
		}
	}

	private fun onThrown() {
		if (!beaconAlert.value) return
		val client = Minecraft.getInstance()
		client.gui.hud.setTimes(0, 20, 5)
		client.gui.hud.setTitle(Component.literal("§cBeacon!"))
		client.player?.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 2f)
	}

	private fun isNukekubi(stand: ArmorStand): Boolean {
		val head = stand.getItemBySlot(EquipmentSlot.HEAD)
		if (!head.`is`(Items.PLAYER_HEAD)) return false
		val texture = SkyblockItem.skullTexture(head) ?: return false
		return skullTextures.getOrPut(texture) {
			runCatching { String(java.util.Base64.getDecoder().decode(texture)) }.getOrDefault("").contains(NUKEKUBI_SKIN)
		}
	}

	/**
	 * A boss died. From the entity-event mixin, after the Carry Manager, which settles the owner of
	 * a boss it was still watching. Every kill gets its time at its feet; only yours or a client's
	 * goes in chat. Hypixel names nobody's boss, so one with no known owner waits for its "boss
	 * slain" line, which only comes for your own.
	 */
	@JvmStatic
	fun onEntityDied(entityId: Int) {
		val fight = fights.remove(entityId) ?: return
		if (!module.enabled) return
		val now = System.currentTimeMillis()
		fight.finish(now)
		val where = Minecraft.getInstance().level?.getEntity(entityId)?.position() ?: return
		if (bossLabel.value) killLabels += KillLabel("§aKilled in: §f${killSeconds(now - fight.since)}", where, now)

		val owner = VoidgloomBosses.ownerOf(entityId)
		when {
			owner != null && ours(owner) -> announce(fight, owner, now)
			now - slainSaidAt <= CLAIM_MILLIS -> {
				slainSaidAt = 0L
				announce(fight, null, now)
			}
			else -> unclaimed += Kill(fight, where, now)
		}
	}

	/** Hypixel's line for your own boss dying. Client thread, from the chat packet. */
	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !module.enabled) return
		val text = message.string.replace(formattingPattern, "")
		if ("SLAYER BOSS SLAIN!" !in text && "SLAYER QUEST COMPLETE!" !in text) return
		val now = System.currentTimeMillis()
		val player = Minecraft.getInstance().player
		// Of several bosses dying at once, yours is the one beside you.
		val kill = unclaimed.filter { now - it.at <= CLAIM_MILLIS }
			.minByOrNull { player?.position()?.distanceToSqr(it.where) ?: 0.0 }
		if (kill == null) {
			slainSaidAt = now
			return
		}
		unclaimed.clear()
		announce(kill.fight, null, kill.at)
	}

	private fun announce(fight: Fight, owner: String?, now: Long) {
		if (!killTime.value) return
		val whose = owner?.takeIf { VoidgloomBosses.carried(it) }?.let { " §8(§7$it§8)" } ?: ""
		val splits = if (phaseTimes.value && fight.spent.isNotEmpty()) {
			"\n§8 ┗ " + Phase.entries.filter { it in fight.spent }
				.joinToString(" §8· ") { "${it.label} §f${seconds(fight.spent.getValue(it))}" }
		} else {
			""
		}
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(
			Component.literal("§8[Cryptic] §5Voidgloom Seraph ${roman(fight.tier)}$whose §7killed in: §f${killSeconds(now - fight.since)}$splits"),
		)
	}

	/** Head-sized box around an armour stand's helmet. */
	private fun headBox(stand: ArmorStand, partialTick: Float): DoubleArray {
		val scale = if (stand.isSmall) 0.5 else 1.0
		val x = Mth.lerp(partialTick.toDouble(), stand.xo, stand.x)
		val y = Mth.lerp(partialTick.toDouble(), stand.yo, stand.y) + 1.73 * scale
		val z = Mth.lerp(partialTick.toDouble(), stand.zo, stand.z)
		val half = 0.32 * scale
		return doubleArrayOf(x - half, y - half, z - half, x + half, y + half, z + half)
	}

	/** A laser-phase beam to replace: an invisible guardian's, on the boss's island. */
	@JvmStatic
	fun hidesBeam(guardian: net.minecraft.world.entity.monster.Guardian): Boolean =
		active && customLaser.value && guardian.isInvisible

	private const val LASER_WIDTH = 6f

	/** One hidden beam, from the guardian's eye to the middle of what it aims at. */
	private class Beam(val from: Vec3, val to: Vec3)

	/**
	 * The hidden beams as walls. Each direction has three beams one above another, aimed at stands
	 * sharing an x and z; the top and bottom ones are kept, joined at both ends and filled between.
	 */
	private fun renderLasers(context: LevelRenderContext, partialTick: Float) {
		if (!customLaser.value) return
		val level = Minecraft.getInstance().level ?: return
		val beams = level.entitiesForRendering().mapNotNull { entity ->
			val guardian = entity as? net.minecraft.world.entity.monster.Guardian ?: return@mapNotNull null
			if (!hidesBeam(guardian) || !guardian.hasActiveAttackTarget()) return@mapNotNull null
			val target = guardian.activeAttackTarget ?: return@mapNotNull null
			Beam(guardian.getEyePosition(partialTick), target.getPosition(partialTick).add(0.0, target.bbHeight * 0.5, 0.0))
		}
		if (beams.isEmpty()) return
		val poseStack = context.poseStack()
		val collector = context.submitNodeCollector()
		val line = 0xFF000000.toInt() or (laserColor.argb and 0xFFFFFF)
		fun draw(vararg points: Vec3) = WorldRender.drawLine(poseStack, collector, points.toList(), line, LASER_WIDTH, phase = false)
		val directions = beams.groupBy { Math.round(it.to.x * 4) to Math.round(it.to.z * 4) }
		for (wall in directions.values) {
			val bottom = wall.minBy { it.from.y }
			val top = wall.maxBy { it.from.y }
			if (bottom === top) {
				draw(bottom.from, bottom.to)
				continue
			}
			draw(bottom.from, bottom.to, top.to, top.from, bottom.from)
			if (laserFill.argb ushr 24 != 0) {
				WorldRender.drawPanel(poseStack, collector, bottom.from, bottom.to, top.to, top.from, laserFill.argb, phase = false)
			}
		}
	}

	private fun render(context: LevelRenderContext) {
		val client = Minecraft.getInstance()
		// The veil works on every island; the rest is the End's.
		renderVeil(context, client.deltaTracker.getGameTimeDeltaPartialTick(false), System.currentTimeMillis())
		if (!active) return
		renderLasers(context, client.deltaTracker.getGameTimeDeltaPartialTick(false))
		val level = client.level ?: return
		val poseStack = context.poseStack()
		val collector = context.submitNodeCollector()
		val partialTick = client.deltaTracker.getGameTimeDeltaPartialTick(false)
		val now = System.currentTimeMillis()

		fun box(b: DoubleArray, argb: Int) = WorldRender.drawBox(
			poseStack = poseStack, collector = collector,
			minX = b[0], minY = b[1], minZ = b[2], maxX = b[3], maxY = b[4], maxZ = b[5],
			outlineArgb = argb or 0xFF000000.toInt(), fillArgb = argb,
			outline = true, fill = true, phase = true, lineWidth = LINE_WIDTH,
		)

		fun tracer(x: Double, y: Double, z: Double, argb: Int) = WorldRender.drawTracer(
			poseStack = poseStack, collector = collector, x = x, y = y, z = z,
			argb = argb, lineWidth = LINE_WIDTH, phase = true,
		)

		fun label(text: String, x: Double, y: Double, z: Double, scale: Float, seeThrough: Boolean = true) = WorldRender.drawText(
			poseStack = poseStack, collector = collector,
			orientation = client.gameRenderer.mainCamera().rotation(),
			text = Component.literal(text), x = x, y = y, z = z,
			scale = scale, seeThrough = seeThrough, centered = true,
		)


		for (id in flying.keys) {
			val stand = level.getEntity(id) as? ArmorStand ?: continue
			val b = headBox(stand, partialTick)
			if (highlightBeacons.value) box(b, beaconColor.argb)
			if (beaconTracer.value) tracer((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2, beaconTracerColor.argb)
		}
		for ((pos, landed) in placed) {
			if (highlightBeacons.value) {
				WorldRender.drawBlock(
					poseStack = poseStack, collector = collector, pos = pos,
					outlineArgb = beaconColor.argb or 0xFF000000.toInt(), fillArgb = beaconColor.argb,
					outline = true, fill = true, phase = true, lineWidth = LINE_WIDTH, fullBlock = true,
				)
				val left = (BEACON_MILLIS - (now - landed)).coerceAtLeast(0)
				label("§c${seconds(left)}", pos.x + 0.5, pos.y + 1.6, pos.z + 0.5, 1.5f)
			}
			if (beaconTracer.value) tracer(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, beaconTracerColor.argb)
		}

		if (skullTracer.value) {
			for (skull in skulls) {
				val b = headBox(skull, partialTick)
				tracer((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2, skullTracerColor.argb)
			}
		}

		if (!bossLabel.value) return
		val size = labelSize.value
		// Lines stack along the camera's up, so they stay apart when looking straight down at them.
		val up = Vector3f(0f, 1f, 0f).rotate(client.gameRenderer.mainCamera().rotation())
		val phase = labelThroughWalls.value
		// Depth-tested at the feet, lifted by as much of the block as stands upright, so the floor
		// doesn't hide the bottom line.
		fun lines(texts: List<String>, at: Vec3, onFloor: Boolean) {
			val step = LINE_HEIGHT * size
			val top = (texts.size - 1) * step / 2
			val lift = if (onFloor && !phase) (texts.size * step / 2 + 0.05) * up.y else 0.0
			texts.forEachIndexed { row, text ->
				val offset = top - row * step
				label(text, at.x + up.x * offset, at.y + lift + up.y * offset, at.z + up.z * offset, size.toFloat(), seeThrough = phase)
			}
		}

		for (kill in killLabels) lines(listOf(kill.text), kill.where.add(0.0, 0.1, 0.0), onFloor = true)

		for (fight in fights.values) {
			val boss = level.getEntity(fight.bossId) ?: continue
			if (!boss.isAlive) continue
			val x = Mth.lerp(partialTick.toDouble(), boss.xo, boss.x)
			val y = Mth.lerp(partialTick.toDouble(), boss.yo, boss.y)
			val z = Mth.lerp(partialTick.toDouble(), boss.zo, boss.z)
			val atFeet = labelPlace.selectedIndex == AT_FEET
			val height = if (atFeet) 0.1 else boss.bbHeight + 1.4
			// Depth-tested, they're moved out of the boss toward you, or its own legs hide them.
			val camera = client.gameRenderer.mainCamera().position()
			val toward = Vec3(camera.x - x, 0.0, camera.z - z).let {
				if (phase || it.lengthSqr() <= 1e-4) Vec3.ZERO else it.normalize().scale(boss.bbWidth * 0.5 + 0.25)
			}
			lines(statLines(fight), Vec3(x + toward.x, y + height, z + toward.z), onFloor = atFeet)
		}
	}

	/** The veil's ring (or low wall) around you while it burns. */
	private fun renderVeil(context: LevelRenderContext, partialTick: Float, now: Long) {
		if (!module.enabled || !fireVeil.value || veilCastAt == 0L || now - veilCastAt > VEIL_MILLIS) return
		val player = Minecraft.getInstance().player ?: return
		val centre = Vec3(
			Mth.lerp(partialTick.toDouble(), player.xo, player.x),
			Mth.lerp(partialTick.toDouble(), player.yo, player.y) + 0.02,
			Mth.lerp(partialTick.toDouble(), player.zo, player.z),
		)
		if (veilStyle.selectedIndex == VEIL_CIRCLE) {
			WorldRender.drawCircle(
				context.poseStack(), context.submitNodeCollector(), centre, VEIL_RADIUS,
				veilColor.argb, veilWidth.value.toFloat(), veilThroughWalls.value, segments = 60,
			)
		} else {
			WorldRender.drawCylinder(
				context.poseStack(), context.submitNodeCollector(), centre, VEIL_RADIUS, VEIL_WALL_HEIGHT,
				veilColor.argb, veilWidth.value.toFloat(), veilThroughWalls.value, segments = 60,
			)
		}
	}

	private fun seconds(millis: Long): String = String.format(Locale.ROOT, "%.1fs", millis / 1000.0)

	/** A kill's time to the millisecond, as "42.315s". */
	private fun killSeconds(millis: Long): String = String.format(Locale.ROOT, "%.3fs", millis / 1000.0)

	private fun roman(tier: Int) = listOf("I", "II", "III", "IV", "V").getOrElse(tier - 1) { tier.toString() }
}
