package imicro.cryptic.feature

import imicro.cryptic.device.ArrowAlign
import imicro.cryptic.device.LightsOn
import imicro.cryptic.device.SharpShooter
import imicro.cryptic.device.SimonSays
import imicro.cryptic.dungeon.Floor7
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.render.CrypticRenderPipelines
import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.Items

/**
 * The four Floor 7 devices that stand between the terminals: the arrows, the
 * lights, Simon Says and Sharp Shooter.
 *
 * One module rather than four because they are one job — the things in
 * Goldor's tower that are not terminals — and because a party splitting up
 * still wants all four on at once. Each device is its own section, and each
 * has a switch of its own so the ones you do not run can be left off.
 *
 * The solving lives in [imicro.cryptic.device]; this is the part that draws,
 * and that decides whether a click is allowed out. Sources are named on each of
 * those files: the arrows and Sharp Shooter are NoammAddons', Simon Says is
 * Odin's, and the lights are Skyblocker's device.
 */
object DeviceSolver {
	/** Indices into [arrowColorStyle]. */
	private const val COLOR_DYNAMIC = 0

	/** Indices into [simonStyle]. */
	private const val STYLE_OUTLINE = 0
	private const val STYLE_FILL = 1

	/** Minecraft's own chat colours, which the defaults are built from. */
	private const val MINECRAFT_GREEN = 0x55FF55
	private const val MINECRAFT_GOLD = 0xFFAA00
	private const val MINECRAFT_RED = 0xFF5555

	/** Vanilla's own title timing, so the announcement looks like any other. */
	private const val TITLE_FADE_TICKS = 5
	private const val TITLE_STAY_TICKS = 40


	// ---- Arrow align -----------------------------------------------------

	private val arrowSection = SectionModuleSetting(id = "arrow_section", label = "Arrow Align")

	@JvmField
	val arrowAlign = ToggleModuleSetting(
		id = "arrow_align",
		label = "Arrow align",
		defaultValue = true,
		description = "Writes on each frame how many clicks it still needs to point the right way.",
	)

	@JvmField
	val arrowColorStyle = DropdownModuleSetting(
		id = "arrow_color_style",
		label = "Colour style",
		options = listOf("Dynamic", "Custom"),
		defaultIndex = COLOR_DYNAMIC,
		description = "Colours each count by how much is left.",
		visibleIf = { arrowAlign.value },
	)

	@JvmField
	val arrowTextColor = ColorModuleSetting(
		id = "arrow_text_color",
		label = "Count",
		defaultRgb = 0xFFFFFF,
		visibleIf = { arrowAlign.value && arrowColorStyle.selectedIndex != COLOR_DYNAMIC },
	)

	@JvmField
	val arrowBlockWrongClicks = ToggleModuleSetting(
		id = "arrow_block_wrong_clicks",
		label = "Block wrong clicks",
		defaultValue = false,
		description = "Ignores a click on a frame already correct.",
		visibleIf = { arrowAlign.value },
	)

	@JvmField
	val arrowInvertSneak = ToggleModuleSetting(
		id = "arrow_invert_sneak",
		label = "Invert sneak",
		defaultValue = false,
		description = "Turns it round: clicks are only blocked while you are sneaking.",
		visibleIf = { arrowAlign.value && arrowBlockWrongClicks.value },
	)

	// ---- Lights on -------------------------------------------------------

	private val lightsSection = SectionModuleSetting(id = "lights_section", label = "Lights On")

	@JvmField
	val lightsOn = ToggleModuleSetting(
		id = "lights_on",
		label = "Lights on",
		defaultValue = true,
		description = "Marks the levers of the lights device that are still switched off.",
	)

	@JvmField
	val lightsFill = ColorModuleSetting(
		id = "lights_fill",
		label = "Lever fill",
		defaultRgb = 0xFF0000,
		supportsAlpha = true,
		defaultAlpha = 0xC0,
		visibleIf = { lightsOn.value },
	)

	@JvmField
	val lightsOutline = ColorModuleSetting(
		id = "lights_outline",
		label = "Lever outline",
		defaultRgb = 0xFF0000,
		supportsAlpha = true,
		defaultAlpha = 0,
		visibleIf = { lightsOn.value },
	)

	// ---- Simon says ------------------------------------------------------

	private val simonSection = SectionModuleSetting(id = "simon_section", label = "Simon Says")

	@JvmField
	val simonSays = ToggleModuleSetting(
		id = "simon_says",
		label = "Simon says",
		defaultValue = true,
		description = "Boxes the buttons in the order the device asked for them.",
	)

	@JvmField
	val simonStyle = DropdownModuleSetting(
		id = "simon_style",
		label = "Style",
		options = listOf("Outline", "Fill", "Fill + Outline"),
		defaultIndex = 2,
		visibleIf = { simonSays.value },
	)

	@JvmField
	val simonFirstColor = ColorModuleSetting(
		id = "simon_first_color",
		label = "First button",
		defaultRgb = MINECRAFT_GREEN,
		supportsAlpha = true,
		defaultAlpha = 0x80,
		visibleIf = { simonSays.value },
	)

	@JvmField
	val simonSecondColor = ColorModuleSetting(
		id = "simon_second_color",
		label = "Second button",
		defaultRgb = MINECRAFT_GOLD,
		supportsAlpha = true,
		defaultAlpha = 0x80,
		visibleIf = { simonSays.value },
	)

	@JvmField
	val simonThirdColor = ColorModuleSetting(
		id = "simon_third_color",
		label = "Later buttons",
		defaultRgb = MINECRAFT_RED,
		supportsAlpha = true,
		defaultAlpha = 0x80,
		visibleIf = { simonSays.value },
	)

	@JvmField
	val simonBlockWrong = ToggleModuleSetting(
		id = "simon_block_wrong",
		label = "Block wrong clicks",
		defaultValue = false,
		description = "Swallows a press on any button but the next one in the sequence. Sneak to disable.",
		visibleIf = { simonSays.value },
	)

	@JvmField
	val simonBlockWrongStart = ToggleModuleSetting(
		id = "simon_block_wrong_start",
		label = "Block wrong on start",
		defaultValue = false,
		description = "Presses allowed while the sequence plays.",
		visibleIf = { simonSays.value },
	)

	@JvmField
	val simonMaxStartClicks = SliderModuleSetting(
		id = "simon_max_start_clicks",
		label = "Max start clicks",
		defaultValue = 4.0,
		min = 1.0,
		max = 10.0,
		step = 1.0,
		description = "Presses of the start button allowed before the rest are swallowed.",
		visibleIf = { simonSays.value && simonBlockWrongStart.value },
	)

	@JvmField
	val simonAnnounceProgress = ToggleModuleSetting(
		id = "simon_announce_progress",
		label = "Send progress",
		defaultValue = false,
		description = "Says \"SS 3/4\" in party chat as you finish each round of the sequence.",
		visibleIf = { simonSays.value },
	)

	@JvmField
	val simonLagGuard = ToggleModuleSetting(
		id = "simon_lag_guard",
		label = "Block clicks on lag",
		defaultValue = true,
		description = "Holds back a button press while the server has sent nothing for longer than the threshold. " +
			"Presses that reach the server together fail the device. Sneak to override.",
		visibleIf = { simonSays.value },
	)

	@JvmField
	val simonLagMillis = SliderModuleSetting(
		id = "simon_lag_millis",
		label = "Lag threshold (ms)",
		defaultValue = 300.0,
		min = 100.0,
		max = 1000.0,
		step = 25.0,
		visibleIf = { simonSays.value && simonLagGuard.value },
	)

	@JvmField
	val simonBreakAlert = ToggleModuleSetting(
		id = "simon_break_alert",
		label = "Send \"SS broke!\"",
		defaultValue = false,
		description = "Says \"SS broke!\" in party chat when the device throws an attempt away, whoever was doing it.",
		visibleIf = { simonSays.value },
	)

	// ---- Sharp shooter ---------------------------------------------------

	private val sharpSection = SectionModuleSetting(id = "sharp_section", label = "Sharp Shooter (i4)")

	@JvmField
	val sharpShooter = ToggleModuleSetting(
		id = "sharp_shooter",
		label = "Sharp shooter",
		defaultValue = true,
		description = "Marks the lit block on the s4 device.",
	)

	@JvmField
	val i4Style = DropdownModuleSetting(
		id = "i4_style",
		label = "Style",
		options = listOf("Outline", "Fill", "Fill + Outline"),
		defaultIndex = 2,
		visibleIf = { sharpShooter.value },
	)

	@JvmField
	val i4TargetColor = ColorModuleSetting(
		id = "i4_target_color",
		label = "Target",
		defaultRgb = MINECRAFT_GREEN,
		supportsAlpha = true,
		defaultAlpha = 0x80,
		visibleIf = { sharpShooter.value },
	)

	@JvmField
	val i4ShowPrediction = ToggleModuleSetting(
		id = "i4_show_prediction",
		label = "Show prediction",
		defaultValue = true,
		description = "Guesses which block lights up next so the shot can be lined up before it does.",
		visibleIf = { sharpShooter.value },
	)

	@JvmField
	val i4PredictionColor = ColorModuleSetting(
		id = "i4_prediction_color",
		label = "Prediction",
		defaultRgb = 0xFFFF55,
		supportsAlpha = true,
		defaultAlpha = 0x80,
		visibleIf = { sharpShooter.value && i4ShowPrediction.value },
	)

	@JvmField
	val i4DoneColor = ColorModuleSetting(
		id = "i4_done_color",
		label = "Shot already",
		defaultRgb = MINECRAFT_RED,
		supportsAlpha = true,
		defaultAlpha = 0x60,
		visibleIf = { sharpShooter.value },
	)

	@JvmField
	val i4Announce = ToggleModuleSetting(
		id = "i4_announce",
		label = "Announce finish",
		defaultValue = true,
		description = "A title when the device is done, with your count.",
		visibleIf = { sharpShooter.value },
	)

	// ---- Drawing ---------------------------------------------------------

	private val drawingSection = SectionModuleSetting(
		id = "drawing_section",
		label = "Drawing",
		visibleIf = { anyDevice() },
	)

	private fun anyDevice(): Boolean =
		arrowAlign.value || lightsOn.value || simonSays.value || sharpShooter.value

	/** The lights are always outlined; the other two only in a style that has one. */
	private fun anyOutline(): Boolean =
		lightsOn.value ||
			(simonSays.value && simonStyle.selectedIndex != STYLE_FILL) ||
			(sharpShooter.value && i4Style.selectedIndex != STYLE_FILL)

	@JvmField
	val lineWidth = SliderModuleSetting(
		id = "line_width",
		label = "Outline width",
		defaultValue = 3.0,
		min = 1.0,
		max = 10.0,
		step = 0.5,
		visibleIf = { anyOutline() },
	)

	@JvmField
	val throughWalls = ToggleModuleSetting(
		id = "through_walls",
		label = "Phase",
		defaultValue = true,
		description = "Draws the devices through walls.",
		visibleIf = { anyDevice() },
	)

	@JvmField
	val module = Module(
		id = "device_solver",
		name = "Device Solver",
		description = "Solves Goldor's three devices",
		category = ModuleCategory.FLOOR_7,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(
			arrowSection, arrowAlign, arrowColorStyle, arrowTextColor,
			arrowBlockWrongClicks, arrowInvertSneak,
			lightsSection, lightsOn, lightsFill, lightsOutline,
			simonSection, simonSays, simonStyle,
			simonFirstColor, simonSecondColor, simonThirdColor,
			simonBlockWrong, simonBlockWrongStart, simonMaxStartClicks, simonLagGuard, simonLagMillis, simonAnnounceProgress, simonBreakAlert,
			sharpSection, sharpShooter, i4Style,
			i4TargetColor, i4ShowPrediction, i4PredictionColor, i4DoneColor, i4Announce,
			drawingSection, lineWidth, throughWalls,
		),
	)

	val arrowAlignEnabled: Boolean get() = module.enabled && arrowAlign.value
	val lightsOnEnabled: Boolean get() = module.enabled && lightsOn.value
	val simonSaysEnabled: Boolean get() = module.enabled && simonSays.value

	val simonBreakAlertEnabled: Boolean get() = simonSaysEnabled && simonBreakAlert.value
	val sharpShooterEnabled: Boolean get() = module.enabled && sharpShooter.value

	/** True while any of the four wants to know where in the tower the player is. */
	val needsPhaseTracking: Boolean
		get() = module.enabled &&
			(arrowAlign.value || lightsOn.value || simonSays.value || sharpShooter.value)

	/** What [once] remembers of the last use, so a second hand cannot re-run it. */
	private var lastUseTick = Long.MIN_VALUE
	private var lastUseTarget: Any? = null
	private var lastUseBlocked = false

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		CrypticRenderPipelines.touch()
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		// Chat is heard in [onSystemChat], from the packet: Goldor's greeting is
		// boss dialogue, which other mods hide, and a hidden line never reaches
		// Fabric's chat event.
		// Hypixel moves the party to a new server between floors, so nothing
		// recorded from the last tower may survive into the next one.
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> forget() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> forget() }
	}

	/** A chat packet, on the client thread, before any mod has hidden it. */
	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay) return
		SimonSays.onChatMessage(message.string)
		SharpShooter.onChatMessage(message.string)
	}

	private fun forget() {
		ArrowAlign.reset()
		SimonSays.forget()
		SharpShooter.forget()
	}

	/** Always called: the arrow scan lets go of what it was holding when off. */
	fun tick(client: Minecraft) {
		ArrowAlign.tick(client)
		SharpShooter.tick(client)
	}

	/**
	 * The device reporting itself finished, which is the only certain end to it
	 * — the count of blocks shot is the client's own tally and can be a block
	 * out either way, so it is shown as what it is.
	 */
	fun announceSharpShooterDone(shot: Int, total: Int) {
		if (!i4Announce.value) return
		val client = Minecraft.getInstance()
		client.gui.hud.setTimes(TITLE_FADE_TICKS, TITLE_STAY_TICKS, TITLE_FADE_TICKS)
		client.gui.hud.setTitle(Component.literal("§aDevice complete"))
		if (shot < total) client.gui.hud.setSubtitle(Component.literal("§eCounted $shot/$total"))
	}

	/**
	 * A right click on a block, answered with whether it should be swallowed.
	 *
	 * Only Simon Says has anything to say about blocks; the arrows are frames,
	 * which arrive through [blocksEntityUse] instead.
	 */
	@JvmStatic
	fun blocksBlockUse(pos: BlockPos): Boolean = once(pos) { SimonSays.blocksClick(pos) }

	/**
	 * A right click on an entity, answered the same way, and the one place a
	 * frame turning is recorded — a click that goes out has to move the count
	 * on screen with it, or the next click is worked out against a frame that
	 * has already turned.
	 */
	@JvmStatic
	fun blocksEntityUse(entity: Entity): Boolean {
		if (entity !is ItemFrame || entity.item.item != Items.ARROW) return false
		return once(entity.id) {
			val pos = entity.blockPosition()
			if (ArrowAlign.blocksClick(pos)) {
				true
			} else {
				ArrowAlign.onClick(pos)
				false
			}
		}
	}

	/**
	 * Answers a use of [target] once per tick, replaying the answer after that.
	 *
	 * The game tries a use with each hand in turn, and only stops trying once
	 * one of them succeeds. That is two calls for one press of the button, and
	 * both of the answers here have a memory behind them — a frame turning, a
	 * press of the start button being counted — so a hand that fell through
	 * would count a click the player only made once.
	 */
	private fun once(target: Any, decide: () -> Boolean): Boolean {
		val tick = Minecraft.getInstance().level?.gameTime ?: 0L
		if (tick == lastUseTick && target == lastUseTarget) return lastUseBlocked

		lastUseTick = tick
		lastUseTarget = target
		lastUseBlocked = decide()
		return lastUseBlocked
	}

	private fun render(context: LevelRenderContext) {
		if (!module.enabled) return
		renderArrows(context)
		renderLights(context)
		renderSimonSays(context)
		renderSharpShooter(context)
	}

	/**
	 * The lit block, the guess at the next one, and the ones already shot.
	 *
	 * Only while stood on the platform: from anywhere else in s4 these are
	 * decoration on a far wall, and marking them there is clutter over a
	 * section that has four terminals in it.
	 */
	private fun renderSharpShooter(context: LevelRenderContext) {
		if (!sharpShooter.value || Floor7.p3Section != 4 || !SharpShooter.onPlatform()) return

		val fill = i4Style.selectedIndex != STYLE_OUTLINE
		val outline = i4Style.selectedIndex != STYLE_FILL

		fun mark(pos: BlockPos, color: ColorModuleSetting) {
			WorldRender.drawBlock(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				pos = pos,
				outlineArgb = color.argb,
				fillArgb = color.argb,
				outline = outline,
				fill = fill,
				phase = throughWalls.value,
				lineWidth = lineWidth.value.toFloat(),
				fullBlock = true,
			)
		}

		SharpShooter.done.forEach { mark(it, i4DoneColor) }
		// The guess goes under the target, so a guess that turned out right is
		// drawn as the target rather than as two boxes fighting over one block.
		SharpShooter.prediction?.takeIf { it != SharpShooter.target }?.let { mark(it, i4PredictionColor) }
		SharpShooter.target?.let { mark(it, i4TargetColor) }
	}

	private fun renderArrows(context: LevelRenderContext) {
		if (!arrowAlign.value || !Floor7.inGoldor) return
		val counts = ArrowAlign.clicksRemaining
		if (counts.isEmpty()) return

		val orientation = context.levelState().cameraRenderState.orientation
		for ((index, count) in counts) {
			val (x, y, z) = ArrowAlign.labelAnchor(index)
			WorldRender.drawText(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				orientation = orientation,
				text = Component.literal(count.toString()).withColor(arrowCountColor(count)),
				x = x,
				y = y,
				z = z,
				scale = 1f,
				seeThrough = throughWalls.value,
			)
		}
	}

	/**
	 * Green while a frame is nearly there, amber past halfway and red for the
	 * ones that will take the longest — so the frames worth starting on can be
	 * picked out without reading a single number.
	 */
	private fun arrowCountColor(count: Int): Int = when {
		arrowColorStyle.selectedIndex != COLOR_DYNAMIC -> arrowTextColor.rgb
		count < 3 -> MINECRAFT_GREEN
		count < 5 -> MINECRAFT_GOLD
		else -> MINECRAFT_RED
	}

	/**
	 * Gated on the boss room rather than on a phase, which is what Skyblocker
	 * does and what makes the device work when it is done early: the levers are
	 * two phases below the fight when a party goes down to flip them, so asking
	 * which phase is being fought would answer the wrong question.
	 */
	private fun renderLights(context: LevelRenderContext) {
		if (!lightsOn.value || !Floor7.inBossRoom) return
		val fill = lightsFill.alpha > 0
		val outline = lightsOutline.alpha > 0
		if (!fill && !outline) return

		for (lever in LightsOn.unlit()) {
			WorldRender.drawBlock(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				pos = lever,
				outlineArgb = lightsOutline.argb,
				fillArgb = lightsFill.argb,
				outline = outline,
				fill = fill,
				// Never through the wall, whatever **Through walls** says. The
				// six levers sit on one flat face a couple of blocks apart, so
				// a highlight that carried through would show the far side of
				// the wall marked as well and there would be no telling which
				// of the two you are looking at.
				phase = false,
				lineWidth = lineWidth.value.toFloat(),
				fullBlock = false,
			)
		}
	}

	/**
	 * The boxes trace the face of each button rather than the whole block, so
	 * three of them stacked next to each other stay told apart.
	 */
	private fun renderSimonSays(context: LevelRenderContext) {
		if (!simonSays.value || !Floor7.inGoldor) return
		val remaining = SimonSays.remaining
		if (remaining.isEmpty()) return

		val fill = simonStyle.selectedIndex != STYLE_OUTLINE
		val outline = simonStyle.selectedIndex != STYLE_FILL

		remaining.forEachIndexed { offset, pos ->
			val color = when (offset) {
				0 -> simonFirstColor
				1 -> simonSecondColor
				else -> simonThirdColor
			}

			WorldRender.drawBox(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				minX = pos.x - 0.15,
				minY = pos.y + 0.37,
				minZ = pos.z + 0.3,
				maxX = pos.x + 0.05,
				maxY = pos.y + 0.63,
				maxZ = pos.z + 0.7,
				outlineArgb = color.argb,
				fillArgb = color.argb,
				outline = outline,
				fill = fill,
				phase = throughWalls.value,
				lineWidth = lineWidth.value.toFloat(),
			)
		}
	}
}
