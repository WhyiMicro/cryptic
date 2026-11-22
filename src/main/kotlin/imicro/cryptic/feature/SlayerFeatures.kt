package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.MultiSelectModuleSetting
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
 * Each boss's stage and what it's doing come off its name stand and the stands around it: a
 * Voidgloom's hits shield and laser (the laser lasts 8.2 seconds from when it mounts its stand;
 * Athen, BSD-3), an Inferno's attunement and fire pillars, Sven's pups, an Atoned Horror's TNT, a
 * Bloodfiend's Twinclaws, Mania and steak. Voidgloom and Inferno stages split their health evenly,
 * a Tarantula V is a Broodfather and then a Conjoined Brood, and a Bloodfiend can be staked from 20%
 * health (SkyHanni and Skyblocker, as facts only). A beacon, skull or laser belongs to the
 * Voidgloom nearest it; only yours and a carried player's are marked.
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

	/** A Tarantula V's second boss, which spawns when the Broodfather dies, and its health. */
	private const val CONJOINED = "Conjoined Brood"
	private const val CONJOINED_HEALTH = 20_000_000.0

	/** A Broodfather V's death this recent dates the Conjoined Brood's fight from it. */
	private const val BROOD_JOIN_MILLIS = 30_000L

	/** A Bloodfiend can be staked from this share of its health. */
	private const val STEAK_SHARE = 0.2

	/** Mania's circles, in ticks of the stand a Bloodfiend rides, shown once they've lasted two seconds. */
	private const val MANIA_TICKS = 520
	private const val MANIA_SHOWN_AFTER = 40

	/** Hypixel's line when a Broodfather III+ can't be hurt until its hatchlings die. */
	private const val HATCHLINGS_LINE = "You need to kill the Broodfather's hatchlings before it can be damaged again!"

	/** A stuck Broodfather moved this far (squared, in blocks) is free again. */
	private const val UNSTUCK_DISTANCE = 0.01

	private val ATTUNEMENT_CODES = mapOf("ASHEN" to "§8", "SPIRIT" to "§f", "CRYSTAL" to "§b", "AURIC" to "§e", "IMMUNE" to "§c")

	private val ROMAN = listOf("I", "II", "III", "IV", "V")

	/** A Bloodfiend's dripping lava, purple spell and portal particles; the hearts it draws from you. */
	private val RIFT_PARTICLES = setOf(
		ParticleTypes.DRIPPING_LAVA, ParticleTypes.FALLING_LAVA, ParticleTypes.LANDING_LAVA,
		ParticleTypes.WITCH, ParticleTypes.PORTAL, ParticleTypes.REVERSE_PORTAL, ParticleTypes.HEART,
	)
	private val RIFT_HEARTS = setOf(ParticleTypes.HEART)
	private val RIFT_SMOKE = setOf(ParticleTypes.LARGE_SMOKE, ParticleTypes.SMOKE)
	private const val RIFT_SMOKE_REACH = 16.0

	/** Particles this close to your Bloodfiend are counted for `/cryptic debug rift`. */
	private const val PARTICLE_WATCH_REACH = 6.0

	@Volatile
	private var particleWatch: AABB? = null
	private val particleCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()


	/** A Bloodfiend's skill and its countdown, on the stand with the despawn timer. */
	private val skillPattern = Regex("""\b(TWINCLAWS|ICHOR|MANIA|STONEWRATH|KILLER SPRING)\b(?:\s+(\d+(?:\.\d+)?s))?""")
	private val SKILL_COLOURS = mapOf(
		"TWINCLAWS" to "§6", "ICHOR" to "§3", "MANIA" to "§c", "STONEWRATH" to "§7", "KILLER SPRING" to "§d",
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
	const val STEAK_MARK = "҉"

	// ---- General -------------------------------------------------------------------------------

	private val generalTab = TabModuleSetting("general_tab", "General")

	private val bossSection = SectionModuleSetting("boss_section", "Boss")

	@JvmField
	val bossLabel = ToggleModuleSetting(
		id = "boss_label",
		label = "Stats on boss",
		defaultValue = true,
		description = "Every slayer boss's stage, what it's doing (hits shield, laser, pups, attunement, fire pillar, Twinclaws, steak and more), its health shaded by what's left, and how long its fight has taken. At its feet, you see them looking down in first person. The kill time stays there a moment after it dies.",
	)

	@JvmField
	val hudBar = ToggleModuleSetting(
		id = "boss_hud_bar",
		label = "Health bar",
		defaultValue = true,
		visibleIf = { bossHud.value },
	)

	@JvmField
	val hudCarried = ToggleModuleSetting(
		id = "boss_hud_carried",
		label = "Carried players' bosses",
		defaultValue = true,
		description = "With no boss of your own up, shows the nearest carried player's.",
		visibleIf = { bossHud.value },
	)

	@JvmField
	val bossHud = ToggleModuleSetting(
		id = "boss_hud",
		label = "Boss HUD",
		defaultValue = false,
		description = "Your boss on the HUD: its stage, health, what it's doing, the fight's time and the time left before it despawns. Place it with /cryptic hud.",
	)

	@JvmField
	val labelWhose = MultiSelectModuleSetting(
		id = "boss_label_whose",
		label = "Whose bosses",
		options = listOf(WHOSE_YOURS, WHOSE_CARRIED, WHOSE_OTHERS),
		defaultSelected = setOf(WHOSE_YOURS, WHOSE_CARRIED),
		description = "Which bosses get their stats and kill time: your own, carried players', everyone else's.",
		visibleIf = { bossLabel.value },
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
		description = "Whether a staged boss's health (Voidgloom, Inferno) goes green to red over each stage, or over the whole fight.",
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
		description = "Marks your own boss, so it stands out from everyone else's.",
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
	val highlightStyle = DropdownModuleSetting(
		id = "highlight_style",
		label = "Style",
		options = listOf("Glow", "Outline", "Fill", "Fill + Outline"),
		defaultIndex = STYLE_GLOW,
		description = "Glow is the game's own outline, always through walls; the rest are a box around it.",
		visibleIf = { highlightOwn.value },
	)

	@JvmField
	val ownFill = ColorModuleSetting(
		id = "own_fill",
		label = "Fill",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
		defaultAlpha = 0x40,
		visibleIf = { highlightOwn.value && boxFills() },
	)

	@JvmField
	val highlightWidth = SliderModuleSetting(
		id = "highlight_width",
		label = "Line width",
		defaultValue = 2.0,
		min = 1.0,
		max = 10.0,
		step = 0.5,
		visibleIf = { highlightOwn.value && boxOutlines() },
	)

	@JvmField
	val highlightThroughWalls = ToggleModuleSetting(
		id = "highlight_through_walls",
		label = "Through walls",
		defaultValue = true,
		visibleIf = { highlightOwn.value && highlightStyle.selectedIndex != STYLE_GLOW },
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

	private val progressSection = SectionModuleSetting("progress_section", "Progress")

	@JvmField
	val tubaEvery = SliderModuleSetting(
		id = "tuba_every",
		label = "Every",
		unit = "s",
		defaultValue = 30.0,
		min = 20.0,
		max = 60.0,
		step = 1.0,
		description = "How often to blow it. The Weirder Tuba's buff lasts 30 seconds (the Weird Tuba's 20) and its cooldown is 20, so 30 saves the most mana.",
		visibleIf = { autoTuba.value },
	)

	@JvmField
	val autoTuba = ToggleModuleSetting(
		id = "auto_tuba",
		label = "Auto tuba",
		defaultValue = false,
		description = "When your Bloodfiend spawns, uses the Weird or Weirder Tuba in your hotbar and switches back, then again every so often while it's up.",
		restriction = imicro.cryptic.gui.Restriction.CHEAT,
	)

	@JvmField
	val xpOverlay = ToggleModuleSetting(
		id = "xp_overlay",
		label = "Slayer XP overlay",
		defaultValue = false,
		description = "Your slayer level, XP to the next one, bosses to go and XP an hour, on the HUD. Filled in from the first quest you finish; place it with /cryptic hud.",
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

	private val riftAutoSection = SectionModuleSetting("rift_auto_section", "Automation")

	@JvmField
	val steakHighlight = ToggleModuleSetting(
		id = "steak_highlight",
		label = "Stakeable colour",
		defaultValue = true,
		description = "With Highlight your boss on, your Bloodfiend's highlight changes colour once it's under 20% health and can be staked.",
	)

	@JvmField
	val steakColor = ColorModuleSetting(
		id = "steak_color",
		label = "Stakeable",
		defaultRgb = 0xFF0058,
		visibleIf = { steakHighlight.value },
		inlineWith = steakHighlight,
	)

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
		description = "Hitting your Bloodfiend, or a thrall, splatter or fledgling, while it can be staked (20% health) switches to the Steak Stake in your hotbar for the hit, then back. The first hit after a stake kill uses it too, for the Sharp Steak Stake's Bloodcrave.",
		restriction = imicro.cryptic.gui.Restriction.CHEAT,
	)

	@JvmField
	val stakeDoubleHit = ToggleModuleSetting(
		id = "stake_double_hit",
		label = "Double hit without Bloodcrave",
		defaultValue = true,
		description = "With no Bloodcrave stored from a stake kill, the stake stays out for a second hit once the mob can be hit again, so the kill is the stake's and stores Bloodcrave for the next.",
		visibleIf = { autoStake.value },
	)

	@JvmField
	val stakeSwapBack = SliderModuleSetting(
		id = "stake_swap_back",
		label = "Swap back after",
		unit = "ms",
		defaultValue = 300.0,
		min = 100.0,
		max = 1000.0,
		step = 50.0,
		description = "How long the stake stays in your hand after it comes out, before going back to what you held.",
		visibleIf = { autoStake.value },
	)

	@JvmField
	val holyIceAt = SliderModuleSetting(
		id = "holy_ice_at",
		label = "Use at",
		unit = "s",
		defaultValue = 0.6,
		min = 0.0,
		max = 1.5,
		step = 0.1,
		description = "Uses the ice when the Twinclaws countdown over your boss reaches this. The ice protects for 1.5 seconds, so it covers the claws landing at zero.",
		visibleIf = { autoHolyIce.value },
	)

	@JvmField
	val autoHolyIce = ToggleModuleSetting(
		id = "auto_holy_ice",
		label = "Auto Holy Ice",
		defaultValue = false,
		description = "When your Bloodfiend starts Twinclaws, uses the Holy Ice in your hotbar and switches back.",
		restriction = imicro.cryptic.gui.Restriction.CHEAT,
	)

	@JvmField
	val healThreshold = SliderModuleSetting(
		id = "heal_threshold",
		label = "Below",
		unit = "%",
		defaultValue = 40.0,
		min = 10.0,
		max = 90.0,
		step = 5.0,
		description = "Heals when your health drops below this share of its maximum.",
		visibleIf = { autoHeal.value },
	)

	@JvmField
	val autoHeal = ToggleModuleSetting(
		id = "auto_heal",
		label = "Auto heal",
		defaultValue = false,
		description = "While your Bloodfiend is up and your health is low, uses the Healing Melon in your hotbar and switches back.",
		restriction = imicro.cryptic.gui.Restriction.CHEAT,
	)

	@JvmField
	val maniaDelay = SliderModuleSetting(
		id = "mania_helper_delay",
		label = "Delay",
		unit = "ms",
		defaultValue = 300.0,
		min = 0.0,
		max = 1500.0,
		step = 50.0,
		description = "How long after the prompt shows to answer it. Tier III gives 2.5 seconds, tiers IV and V two.",
		visibleIf = { maniaHelper.value },
	)

	@JvmField
	val impelPrompts = DropdownModuleSetting(
		id = "impel_prompts",
		label = "Impel prompts",
		options = listOf("Show", "Hide jump and sneak", "Hide all"),
		defaultIndex = 1,
		description = "Hides the Impel titles the helper answers for you (jump and sneak), or every Impel title.",
		visibleIf = { maniaHelper.value },
	)

	@JvmField
	val maniaHelper = ToggleModuleSetting(
		id = "mania_helper",
		label = "Mania helper",
		defaultValue = false,
		description = "Jumps or sneaks when your Bloodfiend's Impel prompt says to.",
		restriction = imicro.cryptic.gui.Restriction.CHEAT,
	)

	@JvmField
	val autoKillerSpring = ToggleModuleSetting(
		id = "auto_killer_spring",
		label = "Auto Killer Spring",
		defaultValue = false,
		description = "While you look at the Killer Spring, clicks it the way it asks (left or right), so it's compacted in time.",
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
	val hideRiftParticles = ToggleModuleSetting(
		id = "hide_rift_particles",
		label = "Hide boss particles",
		defaultValue = false,
		description = "Hides the dripping lava, purple spell and portal particles around Bloodfiends, the hearts between you and yours, and the smoke puffs around its arena.",
	)

	@JvmField
	val impelDisplay = ToggleModuleSetting(
		id = "impel_display",
		label = "Impel display",
		defaultValue = true,
		description = "Replaces Hypixel's Impel title with a bigger flashing one, arrows for CLICK UP and CLICK DOWN, and the time left under it. Place it with /cryptic hud.",
	)

	@JvmField
	val twinclawsPath = ToggleModuleSetting(
		id = "twinclaws_path",
		label = "Twinclaws path",
		defaultValue = false,
		description = "While your Bloodfiend counts down Twinclaws, a line at your chest height along the claws' path, from it through you; when the count ends the line stays where the claws will go.",
	)

	@JvmField
	val twinclawsColor = ColorModuleSetting(
		id = "twinclaws_color",
		label = "Twinclaws path",
		defaultRgb = 0xFFAA00,
		supportsAlpha = true,
		visibleIf = { twinclawsPath.value },
		inlineWith = twinclawsPath,
	)

	@JvmField
	val stonewrathMark = ToggleModuleSetting(
		id = "stonewrath_mark",
		label = "Stonewrath lock-on",
		defaultValue = false,
		description = "A box at your feet where your Bloodfiend locked on for Stonewrath (three quick, rising notes), until it lands.",
	)

	@JvmField
	val stonewrathColor = ColorModuleSetting(
		id = "stonewrath_color",
		label = "Stonewrath box",
		defaultRgb = 0xFF2020,
		supportsAlpha = true,
		defaultAlpha = 0x60,
		visibleIf = { stonewrathMark.value },
		inlineWith = stonewrathMark,
	)

	@JvmField
	val ichorLine = ToggleModuleSetting(
		id = "ichor_line",
		label = "Line to Ichor",
		defaultValue = false,
		description = "A line from your tier V Bloodfiend to the Blood Ichor it spawned (after its ICHOR countdown), to aim the Twinclaws at it.",
	)

	@JvmField
	val ichorColor = ColorModuleSetting(
		id = "ichor_color",
		label = "Ichor line",
		defaultRgb = 0xFF2020,
		supportsAlpha = true,
		visibleIf = { ichorLine.value },
		inlineWith = ichorLine,
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
			bossLabel.withMore(labelWhose, labelPlace, labelSize, labelThroughWalls, healthColour),
			bossHud.withMore(hudBar, hudCarried),
			killTime.withMore(phaseTimes),
			highlightOwn.withMore(highlightStyle, ownFill, highlightWidth, highlightThroughWalls), ownColor,
			lineToOwn, lineColor,
			fadeOthers,
			alertSection,
			cocoonAlert,
			highlightMinibosses, minibossColor,
			minibossAlert,
			progressSection,
			xpOverlay,
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
			steakHighlight, steakColor,
			autoStake.withMore(stakeSwapBack, stakeDoubleHit),
			twinclawsAlert,
			maniaAlert,
			hideRiftParticles,
			impelDisplay,
			ichorLine, ichorColor,
			twinclawsPath, twinclawsColor,
			stonewrathMark, stonewrathColor,
			riftAutoSection,
			autoHolyIce.withMore(holyIceAt),
			autoHeal.withMore(healThreshold),
			maniaHelper.withMore(maniaDelay, impelPrompts),
			autoKillerSpring,
			autoTuba.withMore(tubaEvery),
		),
	)

	val needsSkyblock: Boolean get() = module.enabled

	private enum class Phase(val label: String) {
		HITS("§dHits"),
		LASER("§bLaser"),
		DAMAGE("§cDamage"),
	}

	/** One boss, and how its fight has gone so far. */
	private class Fight(val bossId: Int, val type: SlayerType, val tier: Int, val form: String, var since: Long) {
		/** Kept from its stand, which a dying boss loses before the death event. */
		var owner: String? = null

		/** A Broodfather V: the first of the tier's two bosses. */
		val firstBrood get() = type == SlayerType.TARANTULA && tier >= 5 && form != CONJOINED

		/** A Conjoined Brood dated from its Broodfather, or found to have none. */
		var joined = false

		/** What the boss is doing, as shown under its stage. */
		var state: List<String> = emptyList()

		/** Where a Broodfather stood when Hypixel said its hatchlings must die first, or NaN. */
		var stuckX = Double.NaN
		var stuckZ = Double.NaN

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
		var health = if (form == CONJOINED) CONJOINED_HEALTH else MAX_HEALTH[type]?.let { it.getOrElse(tier - 1) { _ -> it.last() } } ?: 1.0
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

	/** Each owner's last Broodfather V to die: when its fight began, and when it died. */
	private val broodDeaths = HashMap<String, Pair<Long, Long>>()

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
		imicro.cryptic.hud.Hud.register(imicro.cryptic.slayer.SlayerXp.Element(), module, xpOverlay)
		imicro.cryptic.hud.Hud.register(imicro.cryptic.slayer.BossHud(), module, bossHud)
		imicro.cryptic.hud.Hud.register(imicro.cryptic.slayer.ImpelDisplay(), module, impelDisplay)
	}

	private val active: Boolean
		get() = module.enabled && (SkyblockLocation.onSkyblock || DebugOverrides.slayerAnywhere)

	private fun onIsland(name: String): Boolean = SkyblockLocation.area == name || DebugOverrides.slayerAnywhere

	private val inEnd: Boolean get() = active && onIsland(VOIDGLOOM_ISLAND)

	fun tick(client: Minecraft) {
		val level = client.level
		val player = client.player
		if (!active || level == null || player == null) {
			clear()
			return
		}
		val now = System.currentTimeMillis()

		val seen = SlayerBosses.bosses
		for (entry in seen) {
			// Back-dated to when the client first saw the boss.
			val fight = fights.getOrPut(entry.boss.id) { Fight(entry.boss.id, entry.type, entry.tier, form(entry), now - entry.boss.tickCount * 50L) }
			read(fight, entry, level, now)
		}
		fights.values.removeIf { now - it.lastSeen > FORGET_MILLIS || level.getEntity(it.bossId) == null }
		killLabels.removeIf { now - it.at > KILL_LABEL_MILLIS }
		broodDeaths.values.removeIf { now - it.second > BROOD_JOIN_MILLIS }

		val glow = HashMap<Int, Int>()
		val mine = SlayerBosses.mine()
		if (highlightOwn.value && mine != null && highlightStyle.selectedIndex == STYLE_GLOW) glow[mine.boss.id] = ARGB.opaque(highlightRgb(mine))
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
		val bloodfiend = mine?.takeIf { it.type == SlayerType.RIFTSTALKER }
		if (hideRiftParticles.value) {
			seen.filter { it.type == SlayerType.RIFTSTALKER }.mapTo(zones) { ParticleZone(it.boss.boundingBox.inflate(PARTICLE_REACH), RIFT_PARTICLES) }
			// The hearts fly from you to your boss, so they're hidden all along the way.
			bloodfiend?.let { zones += ParticleZone(it.boss.boundingBox.minmax(player.boundingBox).inflate(PARTICLE_REACH), RIFT_HEARTS) }
			// Its smoke puffs land all over the arena, as far out as the Mania circle.
			bloodfiend?.let { zones += ParticleZone(it.boss.boundingBox.inflate(RIFT_SMOKE_REACH), RIFT_SMOKE) }
		}
		particleWatch = bloodfiend?.boss?.boundingBox?.inflate(PARTICLE_WATCH_REACH)
		imicro.cryptic.slayer.RiftIndicators.tick(level, player, bloodfiend, now)
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
		imicro.cryptic.slayer.SlayerAutomation.tick(client, player, mine, now)
	}

	/** Whether [at] is nearest a Voidgloom that's yours or a client's, and near enough to it. */
	private fun belongsToOurs(at: Vec3, voidglooms: List<SlayerBosses.Seen>): Boolean {
		val nearest = voidglooms.minByOrNull { it.boss.position().distanceToSqr(at) } ?: return false
		return ours(nearest.owner) && nearest.boss.position().distanceTo(at) <= NEAR
	}

	private fun clear() {
		imicro.cryptic.slayer.SlayerAutomation.clear()
		imicro.cryptic.slayer.RiftIndicators.clear()
		fights.clear()
		killLabels.clear()
		broodDeaths.clear()
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
		imicro.cryptic.slayer.SlayerAutomation.beforeAttack(player, target)
	}

	private const val WHOSE_YOURS = "Yours"
	private const val WHOSE_CARRIED = "Carried players"
	private const val WHOSE_OTHERS = "Others"

	/** Whether Stats on boss is shown for a boss of [owner]'s; one not yet read counts as someone else's. */
	private fun labelled(owner: String?): Boolean = labelWhose.isSelected(
		when {
			SlayerBosses.isMe(owner) -> WHOSE_YOURS
			owner != null && CarryManager.isCarried(owner) -> WHOSE_CARRIED
			else -> WHOSE_OTHERS
		},
	)

	private const val STYLE_GLOW = 0
	private const val STYLE_OUTLINE = 1
	private const val STYLE_FILL = 2

	private fun boxOutlines() = highlightStyle.selectedIndex == STYLE_OUTLINE || highlightStyle.selectedIndex == STYLE_FILL + 1
	private fun boxFills() = highlightStyle.selectedIndex >= STYLE_FILL

	/** Your boss's glow or outline colour: the stakeable colour for a Bloodfiend that can be staked. */
	private fun highlightRgb(mine: SlayerBosses.Seen): Int = if (stakeColoured(mine)) steakColor.rgb else ownColor.rgb

	/** A Bloodfiend at 20% or under, or marked as stakeable, with Stakeable colour on. */
	private fun stakeColoured(mine: SlayerBosses.Seen): Boolean {
		if (!steakHighlight.value || mine.type != SlayerType.RIFTSTALKER) return false
		if (STEAK_MARK in mine.name) return true
		val fight = fights[mine.boss.id] ?: return false
		return fight.healthText != null && fight.health <= fight.maxHealth * STEAK_SHARE
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

	/** Which of the type's names the boss goes by ("Atoned Horror" for a Revenant V). */
	private fun form(seen: SlayerBosses.Seen): String =
		seen.type.names.sortedByDescending { it.length }.firstOrNull { it in seen.name } ?: seen.type.title

	/** What follows the boss's name and tier: "67 Hits", or health as "266k/750k" (or "45M❤"). */
	private fun tail(seen: SlayerBosses.Seen): String {
		val name = seen.type.names.sortedByDescending { it.length }.firstOrNull { it in seen.name } ?: return ""
		return seen.name.substringAfter(name).trim().removePrefix(roman(seen.tier)).trim()
	}

	private fun read(fight: Fight, seen: SlayerBosses.Seen, level: ClientLevel, now: Long) {
		fight.lastSeen = now
		if (seen.owner != null) fight.owner = seen.owner
		syncStart(fight, seen, now)
		joinBrood(fight)

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
		fight.state = readState(fight, seen, level)
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

	/**
	 * A Conjoined Brood's fight is its Broodfather's too, so it's dated from when the Broodfather
	 * spawned: one of the same owner's still up, or one that just died.
	 */
	private fun joinBrood(fight: Fight) {
		if (fight.joined || fight.form != CONJOINED) return
		val owner = fight.owner ?: return
		fight.joined = true
		val alive = fights.values.filter { it.firstBrood && it.owner == owner }.minOfOrNull { it.since }
		val start = alive ?: broodDeaths.remove(owner)?.first ?: return
		fight.since = minOf(fight.since, start)
	}

	/** What the boss is doing, each a coloured phrase, from its name and the stands around it. */
	private fun readState(fight: Fight, seen: SlayerBosses.Seen, level: ClientLevel): List<String> {
		val out = ArrayList<String>(2)
		fight.laserLeft?.let { left -> out += if (left <= 0) "§bLaser §fsoon" else "§bLaser §f${seconds(left)}" }
		when (fight.type) {
			SlayerType.REVENANT -> standsNear(level, seen.boss, STAND_REACH).firstOrNull { "Boom!" in it }?.let { out += "§c§l${it.trim()}" }
			SlayerType.SVEN -> if (standsNear(level, seen.boss, STAND_REACH).any { "Calling the pups!" in it }) out += "§fPups! §7Immune"
			SlayerType.TARANTULA -> {
				if (!fight.stuckX.isNaN()) {
					val dx = seen.boss.x - fight.stuckX
					val dz = seen.boss.z - fight.stuckZ
					if (dx * dx + dz * dz > UNSTUCK_DISTANCE) {
						fight.stuckX = Double.NaN
						fight.stuckZ = Double.NaN
					} else {
						out += "§eKill the hatchlings"
					}
				}
			}
			SlayerType.INFERNO -> {
				attunement(level, seen.boss)?.let { out += it }
				pillarNear(level, seen.boss)?.let { out += "§5Pillar §f$it" }
			}
			SlayerType.RIFTSTALKER -> {
				if (STEAK_MARK in seen.name) {
					out += "§c§lSteak!"
				} else if (fight.healthText != null) {
					val left = fight.health - fight.maxHealth * STEAK_SHARE
					if (left > 0) out += "§7Steak in §c${ceil(left).toLong()}"
				}
				// Skills share the despawn timer's stand: "03:45 TWINCLAWS 1.2s ICHOR 1.4s".
				val skills = standsNear(level, seen.boss, STAND_REACH)
					.flatMap { text -> skillPattern.findAll(text).map { it.groupValues[1] to it.groupValues[2] } }
					.distinctBy { it.first }
				val circles = seen.boss.vehicle?.tickCount
				val maniaCircles = circles != null && circles > MANIA_SHOWN_AFTER
				for ((skill, time) in skills) {
					if (skill == "MANIA" && maniaCircles) continue
					out += (SKILL_COLOURS[skill] ?: "§f") + skill.lowercase().replaceFirstChar { it.uppercase() } +
						(if (time.isNotEmpty()) " §f$time" else "")
				}
				if (maniaCircles) out += "§cMania §f${seconds((MANIA_TICKS - circles).coerceAtLeast(0) * 50L)}"
			}
			SlayerType.VOIDGLOOM -> {}
		}
		return out
	}

	/** The attunement over an Inferno boss, coloured, with its hits ("ASHEN ♨8"). */
	private fun attunement(level: ClientLevel, boss: Entity): String? {
		val stand = level.getEntitiesOfClass(ArmorStand::class.java, boss.boundingBox.inflate(ATTUNEMENT_REACH)) { it.customName != null }
			.filter { attunementPattern.containsMatchIn(it.customName!!.string.replace(formattingPattern, "")) }
			.minByOrNull { it.distanceToSqr(boss) } ?: return null
		val text = stand.customName!!.string.replace(formattingPattern, "").trim()
		val word = attunementPattern.find(text)?.groupValues?.get(1) ?: return null
		return (ATTUNEMENT_CODES[word] ?: "§f") + text.substring(text.indexOf(word))
	}

	/** The fire pillar nearest to going off around an Inferno boss ("4s 8 hits"). */
	private fun pillarNear(level: ClientLevel, boss: Entity): String? =
		level.getEntitiesOfClass(ArmorStand::class.java, boss.boundingBox.inflate(PILLAR_REACH)) { it.customName != null }
			.mapNotNull { stand ->
				val text = stand.customName!!.string.replace(formattingPattern, "").trim()
				pillarPattern.matchEntire(text)?.groupValues?.get(1)?.toIntOrNull()?.let { it to text }
			}
			.minByOrNull { it.first }?.second

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

	/** Health in Hypixel's short form: "750k", "2.4M". */
	private fun shortHealth(value: Double): String {
		fun trim(number: Double, unit: String) = String.format(Locale.ROOT, "%.1f", number).removeSuffix(".0") + unit
		return when {
			value >= 1e9 -> trim(value / 1e9, "B")
			value >= 1e6 -> trim(value / 1e6, "M")
			value >= 1e4 -> trim(value / 1e3, "k")
			else -> String.format(Locale.ROOT, "%,d", value.toLong())
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

	/** How many even health stages the boss has, as SkyHanni splits them; 0 for none. */
	private fun healthStages(fight: Fight): Int = when (fight.type) {
		SlayerType.VOIDGLOOM -> if (fight.tier >= 4) 6 else 3
		SlayerType.INFERNO -> if (fight.tier >= 3) 3 else 2
		else -> 0
	}

	/** One boss as the label and the HUD show it. Every text is coloured. */
	class BossView(
		val title: String,
		/** "2/3", or null for a boss without stages. */
		val stage: String?,
		/** Hits or health, shaded by what's left; null until the name has been read. */
		val main: String?,
		/** The boss's full health, or the shield's hits, as "/750k"; empty when unknown. */
		val maximum: String,
		/** What's left of the health (or the shield), 0 to 1, for the bar; its colour code. */
		val fraction: Double,
		val shade: String,
		val state: List<String>,
		val elapsedMillis: Long,
		val timeLeft: Int?,
		/** The player whose boss it is, when not you. */
		val owner: String?,
	)

	private fun view(fight: Fight, now: Long): BossView {
		val title = "${fight.type.colour}${fight.form} ${roman(fight.tier)}"
		val hits = fight.hits
		val steps = healthStages(fight)
		var stage: String? = null
		var colour = shade(fight.health, fight.maxHealth)
		if (fight.type == SlayerType.TARANTULA && fight.tier >= 5) {
			stage = if (fight.form == CONJOINED) "§e2/2" else "§e1/2"
		} else if (steps > 0) {
			val step = fight.maxHealth / steps
			val index = (steps - ceil(fight.health / step).toInt() + 1).coerceIn(1, steps)
			stage = "§e$index/$steps"
			if (healthColour.selectedIndex == BY_PHASE) colour = shade(fight.health - step * (steps - index), step)
		}
		val main: String?
		val maximum: String
		val fraction: Double
		if (hits != null) {
			val full = MAX_HITS.getOrElse(fight.tier - 1) { 100 }.toDouble()
			colour = shade(hits.toDouble(), full)
			main = "$colour$hits Hits"
			maximum = ""
			fraction = hits / full
		} else {
			main = fight.healthText?.let { "$colour$it" }
			maximum = if (fight.healthText != null) "/${shortHealth(fight.maxHealth)}" else ""
			fraction = if (fight.maxHealth > 0) fight.health / fight.maxHealth else 1.0
		}
		return BossView(
			title = title,
			stage = stage,
			main = main,
			maximum = maximum,
			fraction = fraction.coerceIn(0.0, 1.0),
			shade = colour,
			state = fight.state,
			elapsedMillis = now - fight.since,
			timeLeft = SlayerBosses.byId(fight.bossId)?.timeLeft,
			owner = fight.owner?.takeIf { !SlayerBosses.isMe(it) },
		)
	}

	/**
	 * The label's lines: the stage and what the boss is doing, then its hits or health, then how
	 * long the fight has taken.
	 */
	private fun statLines(fight: Fight, now: Long): List<String> {
		val view = view(fight, now)
		val top = listOfNotNull(view.stage) + view.state
		return listOfNotNull(
			top.takeIf { it.isNotEmpty() }?.joinToString(" §8· "),
			view.main,
			"§7${seconds(view.elapsedMillis)}",
		)
	}

	/**
	 * The boss for the HUD: your own, or else (when allowed) the nearest carried player's. Null
	 * while neither is up.
	 */
	fun hudView(): BossView? {
		if (!active || !bossHud.value) return null
		val now = System.currentTimeMillis()
		SlayerBosses.mine()?.let { mine -> fights[mine.boss.id]?.let { return view(it, now) } }
		if (!hudCarried.value) return null
		val player = Minecraft.getInstance().player ?: return null
		val carried = SlayerBosses.bosses
			.filter { it.owner != null && CarryManager.isCarried(it.owner) }
			.minByOrNull { it.boss.distanceToSqr(player) } ?: return null
		return fights[carried.boss.id]?.let { view(it, now) }
	}

	val hudOn: Boolean get() = module.enabled && bossHud.value

	/** For the HUD editor, when no boss is up. */
	fun exampleView(): BossView = BossView(
		title = "${SlayerType.VOIDGLOOM.colour}Voidgloom Seraph IV",
		stage = "§e2/6",
		main = "§e142M",
		maximum = "/210M",
		fraction = 142.0 / 210,
		shade = "§e",
		state = listOf("§bLaser §f3.4s"),
		elapsedMillis = 41_300,
		timeLeft = 199,
		owner = null,
	)

	/** "1:23" for a time left in seconds. */
	fun clock(seconds: Int): String = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)

	/** "41.3s", or "1:23.4" from a minute. */
	fun duration(millis: Long): String =
		if (millis < 60_000) seconds(millis)
		else String.format(Locale.ROOT, "%d:%04.1f", millis / 60_000, (millis % 60_000) / 1000.0)

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

	/** Whether a particle is a boss's own, to be dropped. Network thread: reads only the volatile fields. */
	@JvmStatic
	fun hidesParticle(packet: ClientboundLevelParticlesPacket): Boolean {
		return try {
			val type = packet.particle.type
			// Counted on the network thread only, where each packet arrives once.
			val watch = particleWatch
			if (watch != null && !Minecraft.getInstance().isSameThread && watch.contains(packet.x, packet.y, packet.z)) {
				particleCounts.merge(net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE.getKey(type)?.path ?: "?", 1, Int::plus)
			}
			val zones = particleZones
			zones.isNotEmpty() && zones.any { type in it.types && it.box.contains(packet.x, packet.y, packet.z) }
		} catch (_: RuntimeException) {
			false
		}
	}

	/** The particle types seen around your Bloodfiend since the last call, most first, for `/cryptic debug rift`. */
	fun particleReport(): String {
		val counts = particleCounts.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" }
		particleCounts.clear()
		return counts.ifEmpty { "none seen" }
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
		imicro.cryptic.slayer.SlayerAutomation.onEntityDied(entityId)
		val fight = fights.remove(entityId) ?: return
		if (!module.enabled) return
		val now = System.currentTimeMillis()
		fight.finish(now)
		// Half a Tarantula V: the Conjoined Brood that follows carries the fight on.
		if (fight.firstBrood) {
			fight.owner?.let { broodDeaths[it] = fight.since to now }
			return
		}
		val where = Minecraft.getInstance().level?.getEntity(entityId)?.position() ?: return
		if (bossLabel.value && labelled(fight.owner)) killLabels += KillLabel("§aKilled in: §f${killSeconds(now - fight.since)}", where, now)
		if (ours(fight.owner)) announce(fight, now)
	}

	/** Hypixel's line for cocooning your boss. Client thread, from the chat packet. */
	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		imicro.cryptic.slayer.SlayerXp.onSystemChat(message, overlay)
		if (overlay || !module.enabled) return
		val text = message.string.replace(formattingPattern, "").trim()
		if (text == "YOU COCOONED YOUR SLAYER BOSS" && cocoonAlert.value) title("§6§lCocooned!")
		if (text == HATCHLINGS_LINE) {
			// Said when you hit it, so it's about your own Broodfather; it stays stuck until it moves.
			val mine = SlayerBosses.mine() ?: return
			val fight = fights[mine.boss.id] ?: return
			fight.stuckX = mine.boss.x
			fight.stuckZ = mine.boss.z
		}
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
			Component.literal("§8[Cryptic] §5${fight.form} ${roman(fight.tier)}$whose §7killed in: §f${killSeconds(now - fight.since)}$splits"),
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

		if (highlightOwn.value && highlightStyle.selectedIndex != STYLE_GLOW) {
			SlayerBosses.mine()?.let { mine ->
				val boss = mine.boss
				val dx = Mth.lerp(partialTick.toDouble(), boss.xo, boss.x) - boss.x
				val dy = Mth.lerp(partialTick.toDouble(), boss.yo, boss.y) - boss.y
				val dz = Mth.lerp(partialTick.toDouble(), boss.zo, boss.z) - boss.z
				val b = boss.boundingBox
				val staking = stakeColoured(mine)
				WorldRender.drawBox(
					poseStack = poseStack, collector = collector,
					minX = b.minX + dx, minY = b.minY + dy, minZ = b.minZ + dz,
					maxX = b.maxX + dx, maxY = b.maxY + dy, maxZ = b.maxZ + dz,
					outlineArgb = ARGB.opaque(highlightRgb(mine)),
					fillArgb = if (staking) (ownFill.argb and 0xFF000000.toInt()) or steakColor.rgb else ownFill.argb,
					outline = boxOutlines(), fill = boxFills(),
					phase = highlightThroughWalls.value, lineWidth = highlightWidth.value.toFloat(),
				)
			}
		}

		imicro.cryptic.slayer.RiftIndicators.render(poseStack, collector, partialTick, now)

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
			if (!labelled(fight.owner)) continue
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

	private fun seconds(millis: Long): String = String.format(Locale.ROOT, "%.1fs", millis / 1000.0)

	/** A kill's time to the millisecond, as "42.315s". */
	private fun killSeconds(millis: Long): String = String.format(Locale.ROOT, "%.3fs", millis / 1000.0)

	private fun roman(tier: Int) = ROMAN.getOrElse(tier - 1) { tier.toString() }
}
