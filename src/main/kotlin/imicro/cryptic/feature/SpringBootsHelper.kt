package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.render.WorldRender
import imicro.cryptic.skyblock.SkyblockItem
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.EquipmentSlot
import java.util.Locale
import kotlin.math.abs

/**
 * Says how high a charged pair of Spring Boots is about to throw you.
 *
 * Ported from Odin's Spring Boots (BSD 3-Clause, Copyright (c) 2025 odtheking)
 * and NoammAddons' (CC0, Noamm9); the table of heights and the pitches it is
 * read from are theirs, and identical in both. The boots charge while you
 * crouch and jump when you let go, and the only feedback Hypixel gives is a
 * note that climbs in pitch — so the charge is counted off the notes: two low
 * ones to begin with, then one higher one for every step after, each step
 * being a row of the table.
 *
 * The box is the half that makes it usable. "33 blocks" is a number; a box
 * hanging in the air where your feet will reach is whether you clear the wall.
 */
object SpringBootsHelper {
	private const val BOOTS_ID = "SPRING_BOOTS"

	/** Indices into [hudMode]. */
	private const val MODE_PERCENT = 0

	/** Indices into [boxStyle]. */
	private const val STYLE_OUTLINE = 0
	private const val STYLE_FILL = 1

	/** The first two notes of a charge, which are the same low one twice. */
	private const val LOW_PITCH = 0.6984127f
	private const val MAX_LOW_NOTES = 2

	/** The notes after that, any of which is one more step. */
	private val HIGH_PITCHES = floatArrayOf(
		0.82539684f, 0.8888889f, 0.93650794f, 1.0476191f, 1.1746032f, 1.3174603f, 1.7777778f,
	)

	/** The firework Hypixel plays as the boots go off, at one of these two pitches. */
	private val LAUNCH_PITCHES = floatArrayOf(0.0952381f, 1.6984127f)

	/** A pitch is sent as a float and compared as one, so "equal" needs a little room. */
	private const val PITCH_SLACK = 0.0005f

	/** How many blocks each step of charge is worth. The last is the most the boots can do. */
	private val HEIGHTS = floatArrayOf(
		0.0f, 3.0f, 6.5f, 9.0f, 11.5f, 13.5f, 16.0f, 18.0f, 19.0f,
		20.5f, 22.5f, 25.0f, 26.5f, 28.0f, 29.0f, 30.0f, 31.0f, 33.0f,
		34.0f, 35.5f, 37.0f, 38.0f, 39.5f, 40.0f, 41.0f, 42.5f, 43.5f,
		44.0f, 45.0f, 46.0f, 47.0f, 48.0f, 49.0f, 50.0f, 51.0f, 52.0f,
		53.0f, 54.0f, 55.0f, 56.0f, 57.0f, 58.0f, 59.0f, 60.0f, 61.0f,
	)

	private const val EXAMPLE_HEIGHT = 33.0f
	private const val LABEL_COLOR = 0xFFFFFFFF.toInt()

	private val hudSection = SectionModuleSetting("hud_section", "HUD")

	@JvmField
	val showHud = ToggleModuleSetting(
		id = "show_hud",
		label = "Show on the HUD",
		defaultValue = true,
	)

	@JvmField
	val hudMode = DropdownModuleSetting(
		id = "hud_mode",
		label = "Show as",
		options = listOf("Percentage", "Blocks"),
		defaultIndex = MODE_PERCENT,
		description = "How charged the boots are out of a full charge, or how many blocks that charge is worth.",
		visibleIf = { showHud.value },
	)

	private val boxSection = SectionModuleSetting("box_section", "Height marker")

	@JvmField
	val showBox = ToggleModuleSetting(
		id = "show_box",
		label = "Mark the height",
		defaultValue = true,
		description = "Draws a box in the air above you, at the height the jump will reach.",
	)

	@JvmField
	val boxStyle = DropdownModuleSetting(
		id = "box_style",
		label = "Style",
		options = listOf("Outline", "Fill", "Fill + Outline"),
		defaultIndex = STYLE_OUTLINE,
		visibleIf = { showBox.value },
	)

	@JvmField
	val fillColor = ColorModuleSetting(
		id = "fill_color",
		label = "Fill",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
		defaultAlpha = 0x32,
		visibleIf = { showBox.value && boxStyle.selectedIndex != STYLE_OUTLINE },
	)

	@JvmField
	val outlineColor = ColorModuleSetting(
		id = "outline_color",
		label = "Outline",
		defaultRgb = 0x55FF55,
		visibleIf = { showBox.value && boxStyle.selectedIndex != STYLE_FILL },
	)

	@JvmField
	val lineWidth = SliderModuleSetting(
		id = "line_width",
		label = "Outline width",
		defaultValue = 2.0,
		min = 1.0,
		max = 6.0,
		step = 0.5,
		visibleIf = { showBox.value && boxStyle.selectedIndex != STYLE_FILL },
	)

	@JvmField
	val phase = ToggleModuleSetting(
		id = "phase",
		label = "Phase",
		defaultValue = true,
		description = "Draws the box through whatever is between you and it, which is usually a ceiling.",
		visibleIf = { showBox.value },
	)

	@JvmField
	val module = Module(
		id = "spring_boots_helper",
		name = "Spring Boots Helper",
		description = "Shows how high a charged jump will go",
		category = ModuleCategory.GENERAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(
			hudSection, showHud, hudMode,
			boxSection, showBox, boxStyle, fillColor, outlineColor, lineWidth, phase,
		),
	)

	private var lowNotes = 0
	private var highNotes = 0

	/** How many blocks the charge so far is worth, and zero while there is none. */
	private val height: Float
		get() {
			if (DebugOverrides.sampleHudValues) return EXAMPLE_HEIGHT
			return HEIGHTS[(lowNotes + highNotes).coerceIn(HEIGHTS.indices)]
		}

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		Hud.register(SpringElement())
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
	}

	private fun reset() {
		lowNotes = 0
		highNotes = 0
	}

	private fun wearingBoots(client: Minecraft): Boolean {
		val player = client.player ?: return false
		return SkyblockItem.id(player.getItemBySlot(EquipmentSlot.FEET)) == BOOTS_ID
	}

	private fun near(pitch: Float, target: Float): Boolean = abs(pitch - target) < PITCH_SLACK

	/**
	 * A sound from the server, which is the only place the charge is announced.
	 *
	 * A packet handler is run twice — as it arrives, on the network thread, and
	 * again on the client's — and a note counted on both would be two steps of
	 * charge for one. Only the client's pass is counted, which is also the one
	 * that is allowed to look at the player.
	 */
	@JvmStatic
	fun onSound(packet: ClientboundSoundPacket) {
		if (!module.enabled) return
		val client = Minecraft.getInstance()
		if (!client.isSameThread) return
		val player = client.player ?: return

		val sound = packet.sound.value().location()
		val pitch = packet.pitch

		when (sound) {
			SoundEvents.NOTE_BLOCK_PLING.value().location() -> {
				if (!player.isCrouching || !player.onGround() || !wearingBoots(client)) return
				when {
					near(pitch, LOW_PITCH) -> lowNotes = (lowNotes + 1).coerceAtMost(MAX_LOW_NOTES)
					HIGH_PITCHES.any { near(pitch, it) } -> highNotes++
				}
			}
			// The boots going off, which spends the charge.
			SoundEvents.FIREWORK_ROCKET_LAUNCH.location() ->
				if (LAUNCH_PITCHES.any { near(pitch, it) }) reset()
		}
	}

	/** A charge is lost the moment you stand up, leave the ground or take the boots off. */
	fun tick(client: Minecraft) {
		if (lowNotes == 0 && highNotes == 0) return
		val player = client.player
		if (player == null || !player.isCrouching || !player.onGround() || !wearingBoots(client)) reset()
	}

	private fun render(context: LevelRenderContext) {
		if (!module.enabled || !showBox.value) return
		val blocks = height
		if (blocks <= 0f) return

		val client = Minecraft.getInstance()
		val player = client.player ?: return
		// Where the player is being drawn this frame rather than where they were
		// last tick, so the box does not shiver as they shuffle about.
		val at = player.getPosition(client.deltaTracker.getGameTimeDeltaPartialTick(false))
		val style = boxStyle.selectedIndex

		WorldRender.drawBox(
			poseStack = context.poseStack(),
			collector = context.submitNodeCollector(),
			minX = at.x - 0.5,
			minY = at.y + blocks,
			minZ = at.z - 0.5,
			maxX = at.x + 0.5,
			maxY = at.y + blocks + 1.0,
			maxZ = at.z + 0.5,
			outlineArgb = outlineColor.argb,
			fillArgb = fillColor.argb,
			outline = style != STYLE_FILL,
			fill = style != STYLE_OUTLINE,
			phase = phase.value,
			lineWidth = lineWidth.value.toFloat(),
		)
	}

	/** Odin's bands: red for a hop, aqua for most of the way to the ceiling. */
	private fun colorFor(blocks: Float): Int = when {
		blocks <= 13.5f -> 0xFFFF5555.toInt()
		blocks <= 22.5f -> 0xFFFFFF55.toInt()
		blocks <= 33.0f -> 0xFFFFAA00.toInt()
		blocks <= 43.5f -> 0xFF55FF55.toInt()
		else -> 0xFF55FFFF.toInt()
	}

	private fun label(): String = if (hudMode.selectedIndex == MODE_PERCENT) "Charge: " else "Height: "

	private fun value(blocks: Float): String =
		if (hudMode.selectedIndex == MODE_PERCENT) {
			String.format(Locale.ROOT, "%.0f%%", blocks / HEIGHTS.last() * 100f)
		} else {
			String.format(Locale.ROOT, "%.1f", blocks)
		}

	private class SpringElement : HudElement("spring_boots", "Spring Boots", 0.46, 0.60) {
		private val font get() = Minecraft.getInstance().font

		// Against the widest it gets in either mode, so the frame holds still.
		override val width: Int get() = font.width(label() + if (hudMode.selectedIndex == MODE_PERCENT) "100%" else "61.0")
		override val height: Int get() = font.lineHeight

		override fun isVisible(): Boolean = module.enabled && showHud.value && SpringBootsHelper.height > 0f

		override fun showInEditor(): Boolean = module.enabled && showHud.value

		override fun render(context: GuiGraphicsExtractor) = draw(context, SpringBootsHelper.height)

		override fun renderExample(context: GuiGraphicsExtractor) = draw(context, EXAMPLE_HEIGHT)

		private fun draw(context: GuiGraphicsExtractor, blocks: Float) {
			val name = label()
			val amount = value(blocks)
			val x = (width - font.width(name + amount)) / 2
			context.text(font, name, x, 0, LABEL_COLOR)
			context.text(font, amount, x + font.width(name), 0, colorFor(blocks))
		}
	}
}
