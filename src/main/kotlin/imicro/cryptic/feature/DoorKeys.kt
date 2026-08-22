package imicro.cryptic.feature

import imicro.cryptic.Cryptic
import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.render.CrypticRenderPipelines
import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand

/**
 * Finds the wither and blood keys, and says so.
 *
 * A key is an invisible armour stand carrying an item, which is easy to walk
 * straight past in a room full of mobs — and the run stops until somebody picks
 * it up. The highlight is ported from NoammAddons (CC0), and the pickup sound
 * is dtMap's (BSD 3-Clause, Copyright (c) 2026 rice.who); both licences are
 * recorded in `licenses/` and `META-INF/notices/`.
 */
object DoorKeys {
	/** dtMap's pling, resolved through Cryptic's own `sounds.json`. */
	private val KEY_SOUND: SoundEvent = SoundEvent.createVariableRangeEvent(Cryptic.id("key"))

	/** How Hypixel names the two stands, which is what marks them as keys. */
	private const val WITHER_KEY = "Wither Key"
	private const val BLOOD_KEY = "Blood Key"

	/** A key does not move, so looking for one four times a second is plenty. */
	private const val RESCAN_INTERVAL_TICKS = 5

	/** The vertical slice of an armour stand the carried key floats in. */
	private const val KEY_BOTTOM = 1.2
	private const val KEY_TOP = 2.0
	private const val KEY_HALF_WIDTH = 0.4

	private val soundSection = SectionModuleSetting("sound_section", "Pickup sound")

	@JvmField
	val pickupSound = ToggleModuleSetting(
		id = "pickup_sound",
		label = "Play a sound",
		defaultValue = true,
		description = "Plays a pling when anyone picks up a wither or blood key.",
	)

	@JvmField
	val volume = SliderModuleSetting(
		id = "volume",
		label = "Volume",
		defaultValue = 1.0,
		min = 0.1,
		max = 2.0,
		step = 0.05,
		visibleIf = { pickupSound.value },
	)

	private val highlightSection = SectionModuleSetting("highlight_section", "Highlight")

	@JvmField
	val mode = DropdownModuleSetting(
		id = "mode",
		label = "Style",
		options = listOf("Outline", "Fill", "Both"),
		defaultIndex = 2,
		description = "The outline is drawn solid; the colour's alpha sets how heavy the fill is.",
	)

	@JvmField
	val witherKey = ToggleModuleSetting(
		id = "wither_key",
		label = "Wither keys",
		defaultValue = true,
	)

	@JvmField
	val witherColor = ColorModuleSetting(
		id = "wither_color",
		label = "Wither key",
		defaultRgb = 0x202020,
		supportsAlpha = true,
		defaultAlpha = 110,
		visibleIf = { witherKey.value },
	)

	@JvmField
	val bloodKey = ToggleModuleSetting(
		id = "blood_key",
		label = "Blood keys",
		defaultValue = true,
	)

	@JvmField
	val bloodColor = ColorModuleSetting(
		id = "blood_color",
		label = "Blood key",
		defaultRgb = 0xFF0000,
		supportsAlpha = true,
		defaultAlpha = 110,
		visibleIf = { bloodKey.value },
	)

	@JvmField
	val lineWidth = SliderModuleSetting(
		id = "line_width",
		label = "Outline width",
		defaultValue = 2.0,
		min = 1.0,
		max = 6.0,
		step = 0.5,
		visibleIf = { mode.selectedIndex != 1 },
	)

	@JvmField
	val throughWalls = ToggleModuleSetting(
		id = "through_walls",
		label = "Through walls",
		defaultValue = true,
		description = "A key behind a pillar is the one you were going to miss.",
	)

	private val tracersSection = SectionModuleSetting("tracers_section", "Tracers")

	@JvmField
	val tracers = ToggleModuleSetting(
		id = "tracers",
		label = "Tracers",
		defaultValue = true,
		description = "Draws a line from your view to the key.",
	)

	@JvmField
	val tracerWidth = SliderModuleSetting(
		id = "tracer_width",
		label = "Tracer width",
		defaultValue = 2.0,
		min = 1.0,
		max = 6.0,
		step = 0.5,
		visibleIf = { tracers.value },
	)

	private val configurableSettings = listOf(
		pickupSound,
		volume,
		mode,
		witherKey,
		witherColor,
		bloodKey,
		bloodColor,
		lineWidth,
		throughWalls,
		tracers,
		tracerWidth,
	)

	private val reset = ButtonModuleSetting("reset", "Reset", action = {
		configurableSettings.forEach {
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
		id = "door_keys",
		name = "Door Keys",
		description = "Highlights dungeon keys with customizations",
		category = ModuleCategory.DUNGEON,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(soundSection, pickupSound, volume) +
			listOf(highlightSection, mode, witherKey, witherColor, bloodKey, bloodColor, lineWidth, throughWalls) +
			listOf(tracersSection, tracers, tracerWidth, reset),
	)

	/** Hypixel announces a pickup either by name or anonymously. */
	private val witherClaimed = Regex("""^(?:\[[A-Za-z+]+] )?[A-Za-z0-9_]+ has obtained Wither Key!$""")
	private val bloodClaimed = Regex("""^(?:\[[A-Za-z+]+] )?[A-Za-z0-9_]+ has obtained Blood Key!$""")
	private const val WITHER_PICKED_UP = "A Wither Key was picked up!"
	private const val BLOOD_PICKED_UP = "A Blood Key was picked up!"

	private var initialized = false
	private var ticksUntilRescan = 0

	/** The keys on the floor right now, and which colour each is drawn in. */
	private var found: List<Pair<Entity, ColorModuleSetting>> = emptyList()

	fun initialize() {
		if (initialized) return
		initialized = true

		// Pipelines are gathered while the game starts, so they are registered
		// now rather than on the first frame that draws a highlight.
		CrypticRenderPipelines.touch()
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(::render)
		ClientReceiveMessageEvents.GAME.register { message, _ -> onMessage(message.string) }
	}

	/** True while there is any reason to be looking for keys. */
	private val active: Boolean
		get() = module.enabled &&
			(DebugOverrides.highlightEveryArmorStand || (DungeonLocation.inDungeon && !DungeonRun.inBoss))

	fun tick(client: Minecraft) {
		if (!active) {
			if (found.isNotEmpty()) found = emptyList()
			return
		}

		val level = client.level ?: return
		if (ticksUntilRescan-- > 0) {
			// Keys vanish the instant somebody takes them, which is worth
			// noticing before the next scan comes round.
			if (found.any { !it.first.isAlive }) found = found.filter { it.first.isAlive }
			return
		}
		ticksUntilRescan = RESCAN_INTERVAL_TICKS

		var keys: MutableList<Pair<Entity, ColorModuleSetting>>? = null
		for (entity in level.entitiesForRendering()) {
			if (entity !is ArmorStand) continue
			val color = colorFor(entity) ?: continue
			(keys ?: mutableListOf<Pair<Entity, ColorModuleSetting>>().also { keys = it }).add(entity to color)
		}

		found = keys ?: emptyList()
	}

	/**
	 * Which of the two keys this stand is carrying, or null when it is carrying
	 * neither or the player asked not to be shown that one.
	 */
	private fun colorFor(stand: ArmorStand): ColorModuleSetting? {
		if (DebugOverrides.highlightEveryArmorStand) return witherColor

		return when (stand.customName?.string) {
			WITHER_KEY -> witherColor.takeIf { witherKey.value }
			BLOOD_KEY -> bloodColor.takeIf { bloodKey.value }
			else -> null
		}
	}

	private fun render(context: LevelRenderContext) {
		if (!active || found.isEmpty()) return

		val outline = mode.selectedIndex != 1
		val fill = mode.selectedIndex != 0
		val phase = throughWalls.value

		found.forEach { (entity, color) ->
			if (!entity.isAlive) return@forEach

			WorldRender.drawBox(
				poseStack = context.poseStack(),
				consumers = context.bufferSource(),
				minX = entity.x - KEY_HALF_WIDTH,
				minY = entity.y + KEY_BOTTOM,
				minZ = entity.z - KEY_HALF_WIDTH,
				maxX = entity.x + KEY_HALF_WIDTH,
				maxY = entity.y + KEY_TOP,
				maxZ = entity.z + KEY_HALF_WIDTH,
				// One colour drives both halves: the outline wants to be seen,
				// so it ignores the alpha the fill is tuned with.
				outlineArgb = color.rgb or 0xFF000000.toInt(),
				fillArgb = color.argb,
				outline = outline,
				fill = fill,
				phase = phase,
				lineWidth = lineWidth.value.toFloat(),
			)

			if (tracers.value) {
				WorldRender.drawTracer(
					poseStack = context.poseStack(),
					consumers = context.bufferSource(),
					x = entity.x,
					y = entity.y + (KEY_BOTTOM + KEY_TOP) / 2,
					z = entity.z,
					argb = color.rgb or 0xFF000000.toInt(),
					lineWidth = tracerWidth.value.toFloat(),
					phase = phase,
				)
			}
		}
	}

	private fun onMessage(line: String) {
		if (!module.enabled || !pickupSound.value) return
		if (!DungeonLocation.inDungeon || DungeonRun.inBoss) return

		val pickedUp = line == WITHER_PICKED_UP || line == BLOOD_PICKED_UP ||
			witherClaimed.matches(line) || bloodClaimed.matches(line)
		if (pickedUp) play()
	}

	/** Plays the pling, which `/cryptic debug keys` also uses to preview it. */
	fun play() {
		val client = Minecraft.getInstance()
		client.player?.playSound(KEY_SOUND, volume.value.toFloat(), 1.0f)
	}
}
