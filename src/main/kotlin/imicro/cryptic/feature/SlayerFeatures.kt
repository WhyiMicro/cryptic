package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.TabModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.render.WorldRender
import imicro.cryptic.skyblock.SkyblockItem
import imicro.cryptic.skyblock.SkyblockLocation
import imicro.cryptic.slayer.SlayerBosses
import imicro.cryptic.slayer.SlayerType
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.core.particles.ParticleType
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Guardian
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import java.util.EnumMap
import java.util.Locale
import kotlin.math.ceil
import org.joml.Vector3f

/**
 * Slayer help, a tab per boss. Every boss in sight gets its stats; whose each is comes from the
 * `<player>'s SLAYER BOSS` stand over it ([SlayerBosses]), so kill times, highlights and alerts
 * are for your own boss (or a carried player's).
 *
 * Voidgloom: the phase comes off its name stand ("50 Hits" while shielded, health otherwise), and
 * the laser from the armour stand it rides; that lasts 8.2 seconds from when it mounts (Athen,
 * BSD-3). A beacon, skull or laser belongs to the Voidgloom nearest it; only yours and a carried
 * player's are marked.
 *
 * The Fire Veil overlay is IQ's (Apache 2.0): the wand's 3.5-block ring for the five seconds it
 * burns, and its flames hidden for nine seconds from the cast.
 */
object SlayerFeatures {
	private const val VOIDGLOOM_ISLAND = "The End"
	private const val INFERNO_ISLAND = "Crimson Isle"
	private const val TARANTULA_ISLAND = "Spider's Den"
	private val SVEN_ISLANDS = setOf("Hub", "The Park")

	/** The laser, in ticks of the stand the boss rides. */
	private const val LASER_TICKS = 164

	/** A Yang Glyph goes off this long after it lands. */
	private const val BEACON_MILLIS = 5_000L

	/** A mini-boss this close when it spawns is called. */
	private const val MINIBOSS_RANGE = 20.0

	/** Glyphs and skulls are their nearest Voidgloom's, up to this far from it. */
	private const val NEAR = 16.0

	/** A laser guardian is the beam of the Voidgloom nearest it, within this. */
	private const val LASER_REACH = 5.0

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

	/** A Voidgloom tier's hits shield. */
	private val MAX_HITS = listOf(15, 30, 60, 100)

	/** Each boss's health before mayor buffs, tier I first. */
	private val MAX_HEALTH = mapOf(
		SlayerType.REVENANT to listOf(500.0, 20_000.0, 400_000.0, 1_500_000.0, 10_000_000.0),
		SlayerType.TARANTULA to listOf(750.0, 30_000.0, 900_000.0, 2_400_000.0, 10_000_000.0),
		SlayerType.SVEN to listOf(2_000.0, 40_000.0, 750_000.0, 2_000_000.0),
		SlayerType.VOIDGLOOM to listOf(300_000.0, 12_000_000.0, 50_000_000.0, 210_000_000.0),
		SlayerType.INFERNO to listOf(2_500_000.0, 10_000_000.0, 45_000_000.0, 150_000_000.0),
		SlayerType.RIFTSTALKER to listOf(625.0, 1_100.0, 1_800.0, 2_400.0, 3_000.0),
	)

	/** The four minutes a Voidgloom has before it despawns, which dates a fight seen part way in. */
	private const val VOIDGLOOM_LIMIT_SECONDS = 240

	/** The Voidgloom's own smoke, flames and spell particles, as SkyHanni hides them. */
	private val VOIDGLOOM_PARTICLES = setOf(ParticleTypes.LARGE_SMOKE, ParticleTypes.FLAME, ParticleTypes.WITCH)

	/** The Demonlord's and its demons' fire, smoke and lava. */
	private val INFERNO_PARTICLES = setOf(
		ParticleTypes.FLAME, ParticleTypes.SOUL_FIRE_FLAME, ParticleTypes.SMOKE, ParticleTypes.LARGE_SMOKE,
		ParticleTypes.LAVA, ParticleTypes.DRIPPING_LAVA, ParticleTypes.FALLING_LAVA, ParticleTypes.LANDING_LAVA,
	)

	/** Particles this far from a boss's hitbox are its own. */
	private const val PARTICLE_REACH = 3.0

	/** Stands this close to your boss are about it (its attacks' names). */
	private const val STAND_REACH = 3.0

	/** A fire pillar this close to your boss (or you) is in your fight; it's called from this many seconds. */
	private const val PILLAR_REACH = 22.0
	private const val PILLAR_SECONDS = 5

	/** A Demonlord's or demon's attunement is written this close over it. */
	private const val ATTUNEMENT_REACH = 2.5

	/** A mini-boss is only called when it has just spawned. */
	private const val MINIBOSS_NEW_TICKS = 40

	private val hitsPattern = Regex("""(\d+) Hits""")

	/** Health as "266k/750k", current first, or as "45M❤". */
	private val healthPattern = Regex("""^([\d.,]+[kMB]?)(?:/([\d.,]+[kMB]?))?""")

	/** An Inferno Demonlord's fire pillar: "4s 8 hits". */
	private val pillarPattern = Regex("""^(\d+)s \d+ hits$""")

	/** A Tarantula's egg sac's timer, "5s 1/3", beside its "SHOOT ME!". */
	private val eggSacPattern = Regex("""^\d+s \d+/\d+$""")

	private val attunementPattern = Regex("""\b(ASHEN|SPIRIT|CRYSTAL|AURIC|IMMUNE)\b""")
	private val ATTUNEMENT_COLOURS = mapOf(
		"ASHEN" to 0x555555, "SPIRIT" to 0xFFFFFF, "CRYSTAL" to 0x55FFFF, "AURIC" to 0xFFFF55, "IMMUNE" to 0xFF5555,
	)

	/** A Riftstalker that can be staked has this in its name. */
	private const val STEAK_MARK = "҉"

	private val STAKES = setOf("STEAK_STAKE", "SHARP_STEAK_STAKE")

	/** How close to your boss a hit still counts as on it, since the name stand and the body are apart. */
	private const val STAKE_REACH = 2.0

	// ---- General -------------------------------------------------------------------------------

	private val generalTab = TabModuleSetting("general_tab", "General")

	private val bossSection = SectionModuleSetting("boss_section", "Boss")

	@JvmField
	val bossLabel = ToggleModuleSetting(
		id = "boss_label",
		label = "Stats on boss",
		defaultValue = true,
		description = "Health on every slayer boss, shaded by what's left, and how long its fight has taken. A Voidgloom's shows its phase, the laser's time left and its hits. At its feet, you see them looking down in first person. The kill time stays there a moment after it dies.",
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
		description = "Whether a Voidgloom's health goes green to red over each phase, or over the whole fight.",
		visibleIf = { bossLabel.value },
	)

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
		description = "For a Voidgloom, adds how long was spent in hits, damage and laser phases.",
		visibleIf = { killTime.value },
	)

	@JvmField
	val highlightOwn = ToggleModuleSetting(
		id = "highlight_own",
		label = "Highlight your boss",
		defaultValue = false,
		description = "Makes your own boss glow, so it stands out from everyone else's.",
	)

	@JvmField
	val ownColor = ColorModuleSetting(
		id = "own_color",
		label = "Your boss",
		defaultRgb = 0x55FF55,
		visibleIf = { highlightOwn.value },
		inlineWith = highlightOwn,
	)

	@JvmField
	val lineToOwn = ToggleModuleSetting(
		id = "line_to_own",
		label = "Line to your boss",
		defaultValue = false,
		description = "A line from you to your own boss.",
	)

	@JvmField
	val lineColor = ColorModuleSetting(
		id = "line_color",
		label = "Line",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
		visibleIf = { lineToOwn.value },
		inlineWith = lineToOwn,
	)

	@JvmField
	val fadeOpacity = SliderModuleSetting(
		id = "fade_opacity",
		label = "Opacity",
		unit = "%",
		defaultValue = 25.0,
		min = 5.0,
		max = 90.0,
		step = 5.0,
	)

	@JvmField
	val fadePlayers = ToggleModuleSetting(
		id = "fade_players",
		label = "Players too",
		defaultValue = false,
		description = "Fades other players as well as mobs.",
	)

	@JvmField
	val fadeOthers = ToggleModuleSetting(
		id = "fade_others",
		label = "Fade others while fighting",
		defaultValue = false,
		description = "While your boss is up, every other mob (other players' bosses too) is drawn see-through, so yours stands out. Your mini-bosses stay solid.",
	).withMore(fadeOpacity, fadePlayers)

	private val alertSection = SectionModuleSetting("alert_section", "Alerts")

	@JvmField
	val cocoonAlert = ToggleModuleSetting(
		id = "cocoon_alert",
		label = "Cocoon alert",
		defaultValue = true,
		description = "A title and a sound when you cocoon your boss.",
	)

	@JvmField
	val highlightMinibosses = ToggleModuleSetting(
		id = "highlight_minibosses",
		label = "Highlight mini-bosses",
		defaultValue = false,
		description = "Makes slayer mini-bosses glow.",
	)

	@JvmField
	val minibossColor = ColorModuleSetting(
		id = "miniboss_color",
		label = "Mini-boss",
		defaultRgb = 0x5555FF,
		visibleIf = { highlightMinibosses.value },
		inlineWith = highlightMinibosses,
	)

	@JvmField
	val minibossAlert = ToggleModuleSetting(
		id = "miniboss_alert",
		label = "Mini-boss alert",
		defaultValue = false,
		description = "A title with its name when a slayer mini-boss spawns near you.",
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

	// ---- Revenant ------------------------------------------------------------------------------

	private val revenantTab = TabModuleSetting("revenant_tab", "Revenant")

	@JvmField
	val boomAlert = ToggleModuleSetting(
		id = "boom_alert",
		label = "Boom alert",
		defaultValue = true,
		description = "A title when your Atoned Horror is about to explode.",
	)

	// ---- Tarantula -----------------------------------------------------------------------------

	private val tarantulaTab = TabModuleSetting("tarantula_tab", "Tarantula")

	@JvmField
	val highlightEggSacs = ToggleModuleSetting(
		id = "highlight_egg_sacs",
		label = "Highlight egg sacs",
		defaultValue = true,
		description = "Makes the Conjoined Brood's egg sacs glow, so they're shot before they hatch.",
	)

	@JvmField
	val eggSacColor = ColorModuleSetting(
		id = "egg_sac_color",
		label = "Egg sac",
		defaultRgb = 0xFF55FF,
		visibleIf = { highlightEggSacs.value },
		inlineWith = highlightEggSacs,
	)

	@JvmField
	val muteSpiders = ToggleModuleSetting(
		id = "mute_spiders",
		label = "Mute spider sounds",
		defaultValue = false,
		description = "Silences spiders in the Spider's Den.",
	)

	// ---- Sven ----------------------------------------------------------------------------------

	private val svenTab = TabModuleSetting("sven_tab", "Sven")

	@JvmField
	val pupsAlert = ToggleModuleSetting(
		id = "pups_alert",
		label = "Pups alert",
		defaultValue = true,
		description = "A title when your Sven calls its pups.",
	)

	@JvmField
	val muteWolves = ToggleModuleSetting(
		id = "mute_wolves",
		label = "Mute wolf sounds",
		defaultValue = false,
		description = "Silences wolves in the Hub and the Park.",
	)

	// ---- Voidgloom -----------------------------------------------------------------------------

	private val voidgloomTab = TabModuleSetting("voidgloom_tab", "Voidgloom")

	private val voidgloomSection = SectionModuleSetting("voidgloom_section", "Boss")

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
	val hideOtherLasers = ToggleModuleSetting(
		id = "hide_other_lasers",
		label = "Hide others' lasers",
		defaultValue = false,
		description = "Hides the laser of every boss that isn't yours or a carried player's, custom or not.",
	)

	private val beaconSection = SectionModuleSetting("beacon_section", "Beacons")

	@JvmField
	val highlightBeacons = ToggleModuleSetting(
		id = "highlight_beacons",
		label = "Highlight beacons",
		defaultValue = true,
		description = "Boxes a beacon your boss (or a carried player's) throws, in the air and where it lands, with the seconds until it goes off.",
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

	private val skullSection = SectionModuleSetting("skull_section", "Skulls")

	@JvmField
	val highlightSkulls = ToggleModuleSetting(
		id = "highlight_skulls",
		label = "Highlight skulls",
		defaultValue = true,
		description = "Outlines the Nukekubi skulls floating around your boss, or a carried player's.",
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

	// ---- Inferno -------------------------------------------------------------------------------

	private val infernoTab = TabModuleSetting("inferno_tab", "Inferno")

	@JvmField
	val attunementColours = ToggleModuleSetting(
		id = "attunement_colours",
		label = "Attunement colours",
		defaultValue = true,
		description = "Makes the Demonlord and its demons glow in the colour of the dagger they need: grey Ashen, white Spirit, aqua Crystal, yellow Auric, and red while immune.",
	)

	@JvmField
	val hideInfernoParticles = ToggleModuleSetting(
		id = "hide_inferno_particles",
		label = "Hide boss particles",
		defaultValue = false,
		description = "Hides the fire, smoke and lava particles around Inferno Demonlords and their demons, and the fire on them.",
	)

	@JvmField
	val pillarAlert = ToggleModuleSetting(
		id = "pillar_alert",
		label = "Fire pillar alert",
		defaultValue = true,
		description = "A title counting down the last five seconds of a fire pillar near your boss, with a sound.",
	)

	@JvmField
	val muteBlazes = ToggleModuleSetting(
		id = "mute_blazes",
		label = "Mute blaze sounds",
		defaultValue = false,
		description = "Silences blazes on the Crimson Isle.",
	)

	// ---- Riftstalker ---------------------------------------------------------------------------

	private val riftstalkerTab = TabModuleSetting("riftstalker_tab", "Riftstalker")

	@JvmField
	val steakAlert = ToggleModuleSetting(
		id = "steak_alert",
		label = "Steak alert",
		defaultValue = true,
		description = "A title while your Bloodfiend can be finished with the Steak Stake.",
	)

	@JvmField
	val autoStake = ToggleModuleSetting(
		id = "auto_stake",
		label = "Auto stake",
		defaultValue = false,
		description = "Hitting your Bloodfiend while it can be staked switches to the Steak Stake in your hotbar first, so the hit kills it.",
		restriction = imicro.cryptic.gui.Restriction.CHEAT,
	)

	@JvmField
	val twinclawsAlert = ToggleModuleSetting(
		id = "twinclaws_alert",
		label = "Twinclaws alert",
		defaultValue = true,
		description = "A title when your Bloodfiend starts Twinclaws: time for Holy Ice.",
	)

	@JvmField
	val maniaAlert = ToggleModuleSetting(
		id = "mania_alert",
		label = "Mania alert",
		defaultValue = false,
		description = "A title when your Bloodfiend starts Mania.",
	)

	@JvmField
	val module = Module(
		id = "slayer_features",
		name = "Slayer Features",
		description = "Boss stats, alerts, lasers",
		category = ModuleCategory.GENERAL,
		settings = listOf(
			generalTab,
			bossSection,
			bossLabel.withMore(labelPlace, labelSize, labelThroughWalls, healthColour),
			killTime.withMore(phaseTimes),
			highlightOwn, ownColor,
			lineToOwn, lineColor,
			fadeOthers,
			alertSection,
			cocoonAlert,
			highlightMinibosses, minibossColor,
			minibossAlert,
			veilSection,
			fireVeil.withMore(veilStyle, veilColor, veilWidth, veilThroughWalls),
			hideVeilFlames,
			revenantTab,
			boomAlert,
			tarantulaTab,
			highlightEggSacs, eggSacColor,
			muteSpiders,
			svenTab,
			pupsAlert,
			muteWolves,
			voidgloomTab,
			voidgloomSection,
			hideParticles,
			customLaser,
			hideOtherLasers,
			beaconSection,
			highlightBeacons, beaconColor,
			beaconTracer, beaconTracerColor,
			beaconAlert,
			skullSection,
			highlightSkulls, skullColor,
			skullTracer, skullTracerColor,
			infernoTab,
			hideInfernoParticles,
			attunementColours,
			pillarAlert,
			muteBlazes,
			riftstalkerTab,
			steakAlert,
			autoStake,
			twinclawsAlert,
			maniaAlert,
		),
	)

	val needsSkyblock: Boolean get() = module.enabled

	private enum class Phase(val label: String) {
		HITS("§dHits"),
		LASER("§bLaser"),
		DAMAGE("§cDamage"),
	}

	/** One boss, and how its fight has gone so far. */
	private class Fight(val bossId: Int, val type: SlayerType, val tier: Int, var since: Long) {
		/** Kept from its stand, which a dying boss loses before the death event. */
		var owner: String? = null

		var phase: Phase? = null
		var phaseSince = since

		/** The despawn timer's seconds as last read, and whether a change of it has pinned [since]. */
		var timerSeconds: Int? = null
		var timerPinned = false
		val spent = EnumMap<Phase, Long>(Phase::class.java)

		var lastSeen = since

		/** Hits left on the shield, or null outside one. */
		var hits: Int? = null

		/** Health as last read off the name; the name shows hits instead during a shield. */
		var health = MAX_HEALTH[type]?.let { it.getOrElse(tier - 1) { _ -> it.last() } } ?: 1.0
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

	/** Kill times left where each boss died, until [KILL_LABEL_MILLIS] after. */
	private class KillLabel(val text: String, val where: Vec3, val at: Long)

	private val killLabels = ArrayList<KillLabel>()

	/** A boss whose name stand has been gone this long is forgotten. */
	private const val FORGET_MILLIS = 2_000L

	private val formattingPattern = Regex("§.")

	/** Thrown beacons in the air, by armour stand id, where each was last seen. */
	private val flying = HashMap<Int, Vec3>()

	/** Beacons that landed, and when. */
	private val placed = HashMap<BlockPos, Long>()

	private var skulls: List<ArmorStand> = emptyList()

	/** Glow colours by entity id, for the outline hook; replaced whole each tick. */
	private var glows: Map<Int, Int> = emptyMap()

	/** Every Voidgloom in sight, and whether it's yours or a client's, for the beam hook. */
	private class LaserBoss(val boss: Entity, val ours: Boolean)

	private var laserBosses: List<LaserBoss> = emptyList()

	/** While your boss is up: the entities left solid by Fade others. */
	private var fadeKeep: Set<Int>? = null

	/** Inferno bosses and demons, whose fire isn't drawn. */
	private var unlit: Set<Int> = emptySet()

	/** A boss's hitbox grown by [PARTICLE_REACH], and the particles hidden in it. */
	private class ParticleZone(val box: AABB, val types: Set<ParticleType<*>>)

	/**
	 * The zones for the particle hook. Read on the network thread, so the list is replaced whole each
	 * tick, never changed.
	 */
	@Volatile
	private var particleZones: List<ParticleZone> = emptyList()

	/** When the Fire Veil was last cast, and where you were last tick; both read by the particle hook. */
	@Volatile
	private var veilCastAt = 0L

	@Volatile
	private var playerAt: Vec3 = Vec3.ZERO

	/** Whether each skull texture seen is a Nukekubi, so each is decoded once. */
	private val skullTextures = HashMap<String, Boolean>()

	/** Alerts already given, so each is said once while it lasts. */
	private var steakShown = false
	private var twinclawsShown = false
	private var maniaShown = false
	private var boomShown = false
	private var pupsShown = false
	private val pillarsCalled = HashSet<Int>()
	private val minibossesSeen = HashSet<Int>()

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
	}

	private val active: Boolean
		get() = module.enabled && (SkyblockLocation.onSkyblock || DebugOverrides.slayerAnywhere)

	private fun onIsland(name: String): Boolean = SkyblockLocation.area == name || DebugOverrides.slayerAnywhere

	private val inEnd: Boolean get() = active && onIsland(VOIDGLOOM_ISLAND)

	fun tick(client: Minecraft) {
		val level = client.level
		val player = client.player
		tickVeil(player)
		if (!active || level == null || player == null) {
			clear()
			return
		}
		val now = System.currentTimeMillis()

		val seen = SlayerBosses.bosses
		for (entry in seen) {
			// Back-dated to when the client first saw the boss.
			val fight = fights.getOrPut(entry.boss.id) { Fight(entry.boss.id, entry.type, entry.tier, now - entry.boss.tickCount * 50L) }
			read(fight, entry, now)
		}
		fights.values.removeIf { now - it.lastSeen > FORGET_MILLIS || level.getEntity(it.bossId) == null }
		killLabels.removeIf { now - it.at > KILL_LABEL_MILLIS }

		val glow = HashMap<Int, Int>()
		val mine = SlayerBosses.mine()
		if (highlightOwn.value && mine != null) glow[mine.boss.id] = ARGB.opaque(ownColor.rgb)
		tickMinibosses(player, glow)
		tickAlerts(level, mine, player.position(), now)
		val zones = ArrayList<ParticleZone>()
		if (onIsland(INFERNO_ISLAND) && (attunementColours.value || hideInfernoParticles.value)) {
			val demons = attunedMobs(level)
			if (attunementColours.value) demons.forEach { (mob, colour) -> glow[mob.id] = ARGB.opaque(colour) }
			if (hideInfernoParticles.value) {
				val burning = seen.filter { it.type == SlayerType.INFERNO }.map { it.boss } + demons.map { it.first }
				unlit = burning.mapTo(HashSet()) { it.id }
				burning.mapTo(zones) { ParticleZone(it.boundingBox.inflate(PARTICLE_REACH), INFERNO_PARTICLES) }
			} else {
				unlit = emptySet()
			}
		} else {
			unlit = emptySet()
		}
		if (highlightEggSacs.value && onIsland(TARANTULA_ISLAND)) findEggSacs(level, player.position(), glow)

		val voidglooms = seen.filter { it.type == SlayerType.VOIDGLOOM }
		if (inEnd) {
			trackBeacons(level, voidglooms, now)
			skulls = if (highlightSkulls.value || skullTracer.value) {
				level.entitiesForRendering().filterIsInstance<ArmorStand>().filter { stand ->
					isNukekubi(stand) && belongsToOurs(stand.position(), voidglooms)
				}
			} else {
				emptyList()
			}
			if (highlightSkulls.value) skulls.forEach { glow[it.id] = ARGB.opaque(skullColor.rgb) }
			laserBosses = voidglooms.map { LaserBoss(it.boss, ours(it.owner)) }
			if (hideParticles.value) voidglooms.mapTo(zones) { ParticleZone(it.boss.boundingBox.inflate(PARTICLE_REACH), VOIDGLOOM_PARTICLES) }
		} else {
			flying.clear()
			placed.clear()
			skulls = emptyList()
			laserBosses = emptyList()
		}
		particleZones = zones
		fadeKeep = if (fadeOthers.value && mine != null) {
			HashSet<Int>().apply {
				add(mine.boss.id)
				SlayerBosses.minibosses.forEach { add(it.mob.id) }
			}
		} else {
			null
		}
		glows = glow
	}

	/** Whether [at] is nearest a Voidgloom that's yours or a client's, and near enough to it. */
	private fun belongsToOurs(at: Vec3, voidglooms: List<SlayerBosses.Seen>): Boolean {
		val nearest = voidglooms.minByOrNull { it.boss.position().distanceToSqr(at) } ?: return false
		return ours(nearest.owner) && nearest.boss.position().distanceTo(at) <= NEAR
	}

	private fun clear() {
		fights.clear()
		killLabels.clear()
		flying.clear()
		placed.clear()
		skulls = emptyList()
		glows = emptyMap()
		laserBosses = emptyList()
		fadeKeep = null
		unlit = emptySet()
		particleZones = emptyList()
		pillarsCalled.clear()
		minibossesSeen.clear()
		steakShown = false
		twinclawsShown = false
		maniaShown = false
		boomShown = false
		pupsShown = false
	}

	/**
	 * A hit about to go out. Runs before the game sends the held slot, so the swap reaches the server
	 * ahead of the hit.
	 */
	@JvmStatic
	fun beforeAttack(player: net.minecraft.world.entity.player.Player, target: Entity) {
		if (!active || !autoStake.value) return
		val mine = SlayerBosses.mine() ?: return
		if (mine.type != SlayerType.RIFTSTALKER || STEAK_MARK !in mine.name) return
		if (target.id != mine.boss.id && target.distanceToSqr(mine.boss) > STAKE_REACH * STAKE_REACH) return
		val inventory = player.inventory
		val slot = (0 until net.minecraft.world.entity.player.Inventory.getSelectionSize()).firstOrNull { SkyblockItem.id(inventory.getItem(it)) in STAKES } ?: return
		inventory.selectedSlot = slot
	}

	/** Yours, or a carried player's. */
	private fun ours(owner: String?): Boolean = SlayerBosses.isMe(owner) || (owner != null && CarryManager.isCarried(owner))

	// ---- Stats ---------------------------------------------------------------------------------

	/**
	 * Dates a Voidgloom's fight from the despawn timer. The moment its seconds change is a whole
	 * second, so the first change seen pins the start to the millisecond; until then, the middle of
	 * the second shown is the best guess.
	 */
	private fun syncStart(fight: Fight, seen: SlayerBosses.Seen, now: Long) {
		if (fight.timerPinned || fight.type != SlayerType.VOIDGLOOM) return
		val left = seen.timeLeft ?: return
		val changed = fight.timerSeconds?.let { it != left } == true
		fight.timerSeconds = left
		val elapsed = (VOIDGLOOM_LIMIT_SECONDS - left) * 1000L + if (changed) 0L else 500L
		val start = now - elapsed
		// Phase timing started with the fight; it moves with it until the first phase begins.
		if (fight.phaseSince == fight.since) fight.phaseSince = start
		fight.since = start
		if (changed) fight.timerPinned = true
	}

	/** What follows the boss's name and tier: "67 Hits", or health as "266k/750k" (or "45M❤"). */
	private fun tail(seen: SlayerBosses.Seen): String {
		val name = seen.type.names.sortedByDescending { it.length }.firstOrNull { it in seen.name } ?: return ""
		return seen.name.substringAfter(name).trim().removePrefix(roman(seen.tier)).trim()
	}

	private fun read(fight: Fight, seen: SlayerBosses.Seen, now: Long) {
		fight.lastSeen = now
		if (seen.owner != null) fight.owner = seen.owner
		syncStart(fight, seen, now)

		val tail = tail(seen)
		val voidgloom = fight.type == SlayerType.VOIDGLOOM
		val ride = if (voidgloom) seen.boss.vehicle else null
		fight.hits = if (voidgloom) hitsPattern.find(tail)?.groupValues?.get(1)?.toIntOrNull() else null
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
		if (!voidgloom) return
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
		if (text.isEmpty()) return null
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
	 * A Voidgloom's stats as two lines: the phase (out of three, or six for a tier IV, split by
	 * health as SkyHanni does) with the laser's time left, then hits or health shaded by what's left.
	 * Any other boss's: its health, then how long the fight has taken.
	 */
	private fun statLines(fight: Fight, now: Long): List<String> {
		if (fight.type != SlayerType.VOIDGLOOM) {
			val health = fight.healthText?.let { "${shade(fight.health, fight.maxHealth)}$it" }
			return listOfNotNull(health, "§7${seconds(now - fight.since)}")
		}
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

	// ---- Alerts --------------------------------------------------------------------------------

	private fun title(text: String, sound: Boolean = true) {
		val client = Minecraft.getInstance()
		client.gui.hud.setTimes(0, 20, 5)
		client.gui.hud.setTitle(Component.literal(text))
		if (sound) client.player?.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 2f)
	}

	/** The names of the stands near [boss], colour codes taken off. */
	private fun standsNear(level: ClientLevel, boss: Entity, reach: Double): List<String> =
		level.getEntitiesOfClass(ArmorStand::class.java, boss.boundingBox.inflate(reach)) { it.customName != null }
			.mapNotNull { it.customName?.string?.replace(formattingPattern, "") }

	/** Your boss's own attacks, each called once while it lasts. */
	private fun tickAlerts(level: ClientLevel, mine: SlayerBosses.Seen?, at: Vec3, now: Long) {
		val type = mine?.type
		val near = if (mine != null && (boomAlert.value || pupsAlert.value || twinclawsAlert.value || maniaAlert.value)) {
			standsNear(level, mine.boss, STAND_REACH)
		} else {
			emptyList()
		}

		fun once(enabled: Boolean, showing: Boolean, shown: Boolean, text: String): Boolean {
			if (enabled && showing && !shown) title(text)
			return enabled && showing
		}

		boomShown = once(boomAlert.value && type == SlayerType.REVENANT, near.any { "Boom!" in it }, boomShown, "§c§lBOOM!")
		pupsShown = once(pupsAlert.value && type == SlayerType.SVEN, near.any { "Calling the pups!" in it }, pupsShown, "§fPups!")
		twinclawsShown = once(twinclawsAlert.value && type == SlayerType.RIFTSTALKER, near.any { "TWINCLAWS" in it }, twinclawsShown, "§bTwinclaws! §fHoly Ice")
		maniaShown = once(maniaAlert.value && type == SlayerType.RIFTSTALKER, near.any { "MANIA" in it }, maniaShown, "§cMania!")
		val steak = steakAlert.value && mine != null && mine.type == SlayerType.RIFTSTALKER && STEAK_MARK in mine.name
		if (steak) title("§cSteak!", sound = !steakShown)
		steakShown = steak

		if (pillarAlert.value && onIsland(INFERNO_ISLAND)) callPillars(level, mine?.boss?.position() ?: at)
		else pillarsCalled.clear()
	}

	/** A pillar's last seconds as a title, each tick, with one sound for each pillar. */
	private fun callPillars(level: ClientLevel, centre: Vec3) {
		val live = HashSet<Int>()
		for (entity in level.entitiesForRendering()) {
			if (entity !is ArmorStand || entity.position().distanceTo(centre) > PILLAR_REACH) continue
			val text = entity.customName?.string?.replace(formattingPattern, "")?.trim() ?: continue
			val seconds = pillarPattern.matchEntire(text)?.groupValues?.get(1)?.toIntOrNull() ?: continue
			if (seconds > PILLAR_SECONDS) continue
			live += entity.id
			title("§5§l$text", sound = pillarsCalled.add(entity.id))
		}
		pillarsCalled.retainAll(live)
	}

	private fun tickMinibosses(player: net.minecraft.client.player.LocalPlayer, glow: HashMap<Int, Int>) {
		val minis = SlayerBosses.minibosses
		if (highlightMinibosses.value) minis.forEach { glow[it.mob.id] = ARGB.opaque(minibossColor.rgb) }
		if (minibossAlert.value) {
			for (mini in minis) {
				if (!minibossesSeen.add(mini.mob.id)) continue
				if (mini.mob.tickCount <= MINIBOSS_NEW_TICKS && mini.mob.distanceTo(player) <= MINIBOSS_RANGE) {
					title("§9${mini.name}")
				}
			}
		}
		minibossesSeen.retainAll(minis.mapTo(HashSet()) { it.mob.id })
	}

	/** The Demonlords and demons under an attunement stand, each with its attunement's colour. */
	private fun attunedMobs(level: ClientLevel): List<Pair<LivingEntity, Int>> {
		val found = ArrayList<Pair<LivingEntity, Int>>()
		for (entity in level.entitiesForRendering()) {
			if (entity !is ArmorStand) continue
			val text = entity.customName?.string?.replace(formattingPattern, "") ?: continue
			val colour = attunementPattern.find(text)?.groupValues?.get(1)?.let(ATTUNEMENT_COLOURS::get) ?: continue
			val mob = level.getEntitiesOfClass(LivingEntity::class.java, entity.boundingBox.inflate(ATTUNEMENT_REACH)) {
				it !is ArmorStand && it !is net.minecraft.world.entity.player.Player && it.isAlive
			}.minByOrNull { it.distanceToSqr(entity) } ?: continue
			found += mob to colour
		}
		return found
	}

	/** An egg sac is its timer's stand and the bare stands that make it up, under a "SHOOT ME!". */
	private fun findEggSacs(level: ClientLevel, at: Vec3, glow: HashMap<Int, Int>) {
		val stands = level.getEntitiesOfClass(ArmorStand::class.java, AABB.ofSize(at, 40.0, 40.0, 40.0)) { true }
		val shootMe = stands.filter { it.customName?.string?.replace(formattingPattern, "")?.trim().equals("SHOOT ME!", ignoreCase = true) }
		if (shootMe.isEmpty()) return
		val colour = ARGB.opaque(eggSacColor.rgb)
		for (timer in stands) {
			val text = timer.customName?.string?.replace(formattingPattern, "")?.trim() ?: continue
			if (!eggSacPattern.matches(text) || shootMe.none { it.distanceTo(timer) < 2.5 }) continue
			glow[timer.id] = colour
			for (part in stands) {
				if (part.customName == null && part.distanceTo(timer) < 1.2) glow[part.id] = colour
			}
		}
	}

	// ---- Voidgloom -----------------------------------------------------------------------------

	/**
	 * Whether a laser guardian is your boss's or a client's: the Voidgloom nearest it, where it is
	 * now. Asked as each beam is drawn, so a laser that has just started is never shown first.
	 */
	private fun isOurLaser(guardian: Guardian): Boolean {
		var nearest: LaserBoss? = null
		var best = Double.MAX_VALUE
		for (entry in laserBosses) {
			val distance = entry.boss.distanceToSqr(guardian)
			if (distance < best) {
				best = distance
				nearest = entry
			}
		}
		return nearest != null && nearest.ours && best <= LASER_REACH * LASER_REACH
	}

	/**
	 * Whether a particle is a Voidgloom's own, or the Fire Veil's, to be dropped. Network thread:
	 * reads only the volatile fields.
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
			zones.isNotEmpty() && zones.any { type in it.types && it.box.contains(packet.x, packet.y, packet.z) }
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

	/** The glow colour for [entity]. Asked per entity per frame, so it's one map lookup. */
	@JvmStatic
	fun outlineColorFor(entity: Entity): Int {
		val map = glows
		if (map.isEmpty() || !module.enabled) return EntityRenderState.NO_OUTLINE
		return map[entity.id] ?: EntityRenderState.NO_OUTLINE
	}

	/**
	 * How see-through to draw [entity] while your boss is up, as an alpha, or -1 to draw it as
	 * usual. Asked per living entity per frame.
	 */
	@JvmStatic
	fun fadeAlpha(entity: Entity?): Int {
		val keep = fadeKeep ?: return -1
		if (entity !is LivingEntity || entity is ArmorStand || entity.id in keep || !module.enabled) return -1
		if (entity is net.minecraft.world.entity.player.Player && (!fadePlayers.value || entity === Minecraft.getInstance().player)) return -1
		return (fadeOpacity.value / 100.0 * 255).toInt().coerceIn(0, 255)
	}

	/** Inferno bosses and demons go without their fire while their particles are hidden. */
	@JvmStatic
	fun hidesFire(entity: Entity): Boolean {
		val ids = unlit
		return ids.isNotEmpty() && entity.id in ids && module.enabled
	}

	/** Wolves, spiders and blazes silenced on their slayer's island. */
	@JvmStatic
	fun mutes(instance: SoundInstance): Boolean {
		if (!module.enabled || !SkyblockLocation.onSkyblock) return false
		val path = instance.identifier.path
		return when {
			path.startsWith("entity.wolf.") -> muteWolves.value && SkyblockLocation.area in SVEN_ISLANDS
			path.startsWith("entity.spider.") -> muteSpiders.value && SkyblockLocation.area == TARANTULA_ISLAND
			path.startsWith("entity.blaze.") -> muteBlazes.value && SkyblockLocation.area == INFERNO_ISLAND
			else -> false
		}
	}

	private fun trackBeacons(level: ClientLevel, voidglooms: List<SlayerBosses.Seen>, now: Long) {
		if ((!highlightBeacons.value && !beaconTracer.value && !beaconAlert.value) || voidglooms.none { ours(it.owner) }) {
			flying.clear()
			placed.clear()
			return
		}

		val inAir = level.entitiesForRendering().filterIsInstance<ArmorStand>().filter { stand ->
			stand.getItemBySlot(EquipmentSlot.HEAD).`is`(Items.BEACON) && belongsToOurs(stand.position(), voidglooms)
		}
		for (stand in inAir) {
			if (stand.id !in flying && beaconAlert.value) title("§cBeacon!")
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

	private fun findLanded(level: ClientLevel, where: Vec3, now: Long) {
		val middle = BlockPos.containing(where)
		for (pos in BlockPos.betweenClosed(middle.offset(-2, -2, -2), middle.offset(2, 3, 2))) {
			if (level.getBlockState(pos).`is`(Blocks.BEACON)) placed.putIfAbsent(pos.immutable(), now)
		}
	}

	private fun isNukekubi(stand: ArmorStand): Boolean {
		val head = stand.getItemBySlot(EquipmentSlot.HEAD)
		if (!head.`is`(Items.PLAYER_HEAD)) return false
		val texture = SkyblockItem.skullTexture(head) ?: return false
		return skullTextures.getOrPut(texture) {
			runCatching { String(java.util.Base64.getDecoder().decode(texture)) }.getOrDefault("").contains(NUKEKUBI_SKIN)
		}
	}

	// ---- Kills ---------------------------------------------------------------------------------

	/**
	 * A boss died, from the entity-event mixin. Every kill gets its time at its feet; only yours or
	 * a client's goes in chat.
	 */
	@JvmStatic
	fun onEntityDied(entityId: Int) {
		val fight = fights.remove(entityId) ?: return
		if (!module.enabled) return
		val now = System.currentTimeMillis()
		fight.finish(now)
		val where = Minecraft.getInstance().level?.getEntity(entityId)?.position() ?: return
		if (bossLabel.value) killLabels += KillLabel("§aKilled in: §f${killSeconds(now - fight.since)}", where, now)
		if (ours(fight.owner)) announce(fight, now)
	}

	/** Hypixel's line for cocooning your boss. Client thread, from the chat packet. */
	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !module.enabled || !cocoonAlert.value) return
		val text = message.string.replace(formattingPattern, "").trim()
		if (text == "YOU COCOONED YOUR SLAYER BOSS") title("§6§lCocooned!")
	}

	private fun announce(fight: Fight, now: Long) {
		if (!killTime.value) return
		val whose = fight.owner?.takeIf { !SlayerBosses.isMe(it) }?.let { " §8(§7$it§8)" } ?: ""
		val splits = if (phaseTimes.value && fight.spent.isNotEmpty()) {
			"\n§8 ┗ " + Phase.entries.filter { it in fight.spent }
				.joinToString(" §8· ") { "${it.label} §f${seconds(fight.spent.getValue(it))}" }
		} else {
			""
		}
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(
			Component.literal("§8[Cryptic] §5${fight.type.title} ${roman(fight.tier)}$whose §7killed in: §f${killSeconds(now - fight.since)}$splits"),
		)
	}

	// ---- Rendering -----------------------------------------------------------------------------

	/** Head-sized box around an armour stand's helmet. */
	private fun headBox(stand: ArmorStand, partialTick: Float): DoubleArray {
		val scale = if (stand.isSmall) 0.5 else 1.0
		val x = Mth.lerp(partialTick.toDouble(), stand.xo, stand.x)
		val y = Mth.lerp(partialTick.toDouble(), stand.yo, stand.y) + 1.73 * scale
		val z = Mth.lerp(partialTick.toDouble(), stand.zo, stand.z)
		val half = 0.32 * scale
		return doubleArrayOf(x - half, y - half, z - half, x + half, y + half, z + half)
	}

	/**
	 * A laser-phase beam not to draw: an invisible guardian's on the End's island, when the custom
	 * laser replaces it or it isn't your boss's or a client's.
	 */
	@JvmStatic
	fun hidesBeam(guardian: Guardian): Boolean =
		inEnd && guardian.isInvisible && (customLaser.value || hidesOthers(guardian))

	private fun hidesOthers(guardian: Guardian): Boolean = hideOtherLasers.value && !isOurLaser(guardian)

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
			val guardian = entity as? Guardian ?: return@mapNotNull null
			if (!hidesBeam(guardian) || hidesOthers(guardian) || !guardian.hasActiveAttackTarget()) return@mapNotNull null
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
		val partialTick = client.deltaTracker.getGameTimeDeltaPartialTick(false)
		// The veil works on every island.
		renderVeil(context, partialTick, System.currentTimeMillis())
		if (!active) return
		if (inEnd) renderLasers(context, partialTick)
		val level = client.level ?: return
		val poseStack = context.poseStack()
		val collector = context.submitNodeCollector()
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

		if (lineToOwn.value) {
			SlayerBosses.mine()?.boss?.let { boss ->
				val x = Mth.lerp(partialTick.toDouble(), boss.xo, boss.x)
				val y = Mth.lerp(partialTick.toDouble(), boss.yo, boss.y) + boss.bbHeight / 2
				val z = Mth.lerp(partialTick.toDouble(), boss.zo, boss.z)
				tracer(x, y, z, lineColor.argb)
			}
		}

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
			lines(statLines(fight, now), Vec3(x + toward.x, y + height, z + toward.z), onFloor = atFeet)
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
