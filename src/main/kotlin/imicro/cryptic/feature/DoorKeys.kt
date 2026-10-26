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
import imicro.cryptic.gui.withMore
import imicro.cryptic.render.BoxColors
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
 * Finds the wither and blood keys (invisible armour stands carrying the item) and highlights them,
 * with a sound on pickup. The highlight is from NoammAddons; the sound is dtMap's.
 */
object DoorKeys {
	/** dtMap's pling, through Cryptic's `sounds.json`. */
	private val KEY_SOUND: SoundEvent = SoundEvent.createVariableRangeEvent(Cryptic.id("key"))

	private const val WITHER_KEY = "Wither Key"
	private const val BLOOD_KEY = "Blood Key"

	private const val RESCAN_INTERVAL_TICKS = 5

	/** The slice of the armour stand the key floats in. */
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

	private const val OUTLINE = 0
	private const val FILL = 1

	private val highlightSection = SectionModuleSetting("highlight_section", "Highlight")

	@JvmField
	val mode = DropdownModuleSetting(
		id = "mode",
		label = "Style",
		options = listOf("Outline", "Fill", "Fill + Outline"),
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
		label = "Wither fill",
		defaultRgb = 0x202020,
		supportsAlpha = true,
		defaultAlpha = 110,
		visibleIf = { witherKey.value && mode.selectedIndex != OUTLINE },
	)

	@JvmField
	val witherOutlineColor = ColorModuleSetting(
		id = "wither_outline_color",
		label = "Wither outline",
		defaultRgb = 0x202020,
		supportsAlpha = true,
		visibleIf = { witherKey.value && mode.selectedIndex != FILL },
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
		label = "Blood fill",
		defaultRgb = 0xFF0000,
		supportsAlpha = true,
		defaultAlpha = 110,
		visibleIf = { bloodKey.value && mode.selectedIndex != OUTLINE },
	)

	@JvmField
	val bloodOutlineColor = ColorModuleSetting(
		id = "blood_outline_color",
		label = "Blood outline",
		defaultRgb = 0xFF0000,
		supportsAlpha = true,
		visibleIf = { bloodKey.value && mode.selectedIndex != FILL },
	)

	@JvmField
	val lineWidth = SliderModuleSetting(
		id = "line_width",
		label = "Outline width",
		defaultValue = 2.0,
		min = 1.0,
		max = 6.0,
		step = 0.5,
		visibleIf = { mode.selectedIndex != FILL },
	)

	@JvmField
	val throughWalls = ToggleModuleSetting(
		id = "through_walls",
		label = "Phase",
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
		witherOutlineColor,
		bloodKey,
		bloodColor,
		bloodOutlineColor,
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
		description = "Highlights the door keys",
		category = ModuleCategory.DUNGEON,
		supportsKeybind = false,
		settings = listOf(soundSection, pickupSound, volume) +
			listOf(
				highlightSection, mode,
				witherKey.withMore(witherColor, witherOutlineColor),
				bloodKey.withMore(bloodColor, bloodOutlineColor),
				lineWidth, throughWalls,
			) +
			listOf(tracersSection, tracers, tracerWidth, reset),
	)

	/** Hypixel announces a pickup by name or anonymously. */
	private val witherClaimed = Regex("""^(?:\[[A-Za-z+]+] )?[A-Za-z0-9_]+ has obtained Wither Key!$""")
	private val bloodClaimed = Regex("""^(?:\[[A-Za-z+]+] )?[A-Za-z0-9_]+ has obtained Blood Key!$""")
	private const val WITHER_PICKED_UP = "A Wither Key was picked up!"
	private const val BLOOD_PICKED_UP = "A Blood Key was picked up!"

	private var initialized = false
	private var ticksUntilRescan = 0

	private val witherColors = BoxColors(witherColor, witherOutlineColor)
	private val bloodColors = BoxColors(bloodColor, bloodOutlineColor)

	private var found: List<Pair<Entity, BoxColors>> = emptyList()

	fun initialize() {
		if (initialized) return
		initialized = true

		// Pipelines are registered at startup, not on the first highlight.
		CrypticRenderPipelines.touch()
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		ClientReceiveMessageEvents.GAME.register { message, _ -> onMessage(message.string) }
	}

	/**
	 * True while there's a reason to look for keys. The armour-stand override widens what counts as
	 * a key, not where to look; pair it with `/cryptic debug floor` outside a dungeon, or every
	 * minion and damage number gets a tracer.
	 */
	private val active: Boolean
		get() = module.enabled && DungeonLocation.inDungeon && !DungeonRun.inBoss

	fun tick(client: Minecraft) {
		if (!active) {
			if (found.isNotEmpty()) found = emptyList()
			return
		}

		val level = client.level ?: return
		if (ticksUntilRescan-- > 0) {
			// Keys vanish when taken; worth noticing before the next scan.
			if (found.any { !it.first.isAlive }) found = found.filter { it.first.isAlive }
			return
		}
		ticksUntilRescan = RESCAN_INTERVAL_TICKS

		var keys: MutableList<Pair<Entity, BoxColors>>? = null
		for (entity in level.entitiesForRendering()) {
			if (entity !is ArmorStand) continue
			val color = colorFor(entity) ?: continue
			(keys ?: mutableListOf<Pair<Entity, BoxColors>>().also { keys = it }).add(entity to color)
		}

		found = keys ?: emptyList()
	}

	/** Which key this stand carries, or null for neither or one switched off. */
	private fun colorFor(stand: ArmorStand): BoxColors? {
		if (DebugOverrides.highlightEveryArmorStand) return witherColors

		return when (stand.customName?.string) {
			WITHER_KEY -> witherColors.takeIf { witherKey.value }
			BLOOD_KEY -> bloodColors.takeIf { bloodKey.value }
			else -> null
		}
	}

	private fun render(context: LevelRenderContext) {
		if (!active || found.isEmpty()) return

		val outline = mode.selectedIndex != FILL
		val fill = mode.selectedIndex != OUTLINE
		val phase = throughWalls.value

		found.forEach { (entity, color) ->
			if (!entity.isAlive) return@forEach

			WorldRender.drawBox(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				minX = entity.x - KEY_HALF_WIDTH,
				minY = entity.y + KEY_BOTTOM,
				minZ = entity.z - KEY_HALF_WIDTH,
				maxX = entity.x + KEY_HALF_WIDTH,
				maxY = entity.y + KEY_TOP,
				maxZ = entity.z + KEY_HALF_WIDTH,
				outlineArgb = color.outline.argb,
				fillArgb = color.fill.argb,
				outline = outline,
				fill = fill,
				phase = phase,
				lineWidth = lineWidth.value.toFloat(),
			)

			if (tracers.value) {
				WorldRender.drawTracer(
					poseStack = context.poseStack(),
					collector = context.submitNodeCollector(),
					x = entity.x,
					y = entity.y + (KEY_BOTTOM + KEY_TOP) / 2,
					z = entity.z,
					// The tracer follows the outline, the half meant to be seen from across a room.
					argb = color.outline.rgb or 0xFF000000.toInt(),
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

	/** Plays the pling (also for `/cryptic debug keys`). */
	fun play() {
		val client = Minecraft.getInstance()
		client.player?.playSound(KEY_SOUND, volume.value.toFloat(), 1.0f)
	}
}
