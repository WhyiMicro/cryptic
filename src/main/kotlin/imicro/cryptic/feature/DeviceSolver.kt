package imicro.cryptic.feature

import imicro.cryptic.device.ArrowAlign
import imicro.cryptic.device.I4
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
import imicro.cryptic.gui.TextModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
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
 * The four Floor 7 devices between the terminals: Arrow Align, Lights On, Simon Says and Sharp
 * Shooter. One module, each device with its own section and switch. Solving lives in
 * [imicro.cryptic.device]; this draws and decides whether clicks go out.
 */
object DeviceSolver {
	private const val COLOR_DYNAMIC = 0

	private const val STYLE_OUTLINE = 0
	private const val STYLE_FILL = 1

	/** Minecraft's chat colours, which the defaults use. */
	private const val MINECRAFT_GREEN = 0x55FF55
	private const val MINECRAFT_GOLD = 0xFFAA00
	private const val MINECRAFT_RED = 0xFF5555

	/** Vanilla title timing. */
	private const val TITLE_FADE_TICKS = 5
	private const val TITLE_STAY_TICKS = 40


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
		label = "Lag threshold",
		unit = "ms",
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
	val i4Terminator = ToggleModuleSetting(
		id = "i4_terminator",
		label = "Terminator mode",
		defaultValue = false,
		description = "Marks the block between two lights instead, so a Terminator's spread hits both.",
		visibleIf = { sharpShooter.value },
	)

	@JvmField
	val i4ShowPrediction = ToggleModuleSetting(
		id = "i4_show_prediction",
		label = "Show prediction",
		defaultValue = true,
		description = "Guesses which block lights up next so the shot can be lined up before it does. In Terminator mode, shows the next aim.",
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

	private val i4Section = SectionModuleSetting(id = "i4_section", label = "i4")

	@JvmField
	val i4Party = ToggleModuleSetting(
		id = "i4_party",
		label = "Party message",
		defaultValue = true,
		description = "Says how your i4 went in party chat. It's an i4 when you're on the Sharp Shooter plate as Goldor starts talking; leaving it before the device is done is a fail.",
	)

	@JvmField
	val i4PartySuccess = TextModuleSetting("i4_party_success", "When done", "i4 done!", maxLength = 100)

	@JvmField
	val i4PartyFail = TextModuleSetting("i4_party_fail", "When failed", "i4 failed!", maxLength = 100)

	@JvmField
	val i4Title = ToggleModuleSetting(
		id = "i4_title",
		label = "Title",
		defaultValue = true,
		description = "A title on your screen when your i4 is done or fails.",
	)

	@JvmField
	val i4TitleSuccess = TextModuleSetting("i4_title_success", "When done", "i4 Done!", maxLength = 40)

	@JvmField
	val i4TitleSuccessColor = ColorModuleSetting("i4_title_success_color", "Done color", MINECRAFT_GREEN)

	@JvmField
	val i4TitleFail = TextModuleSetting("i4_title_fail", "When failed", "i4 Failed!", maxLength = 40)

	@JvmField
	val i4TitleFailColor = ColorModuleSetting("i4_title_fail_color", "Failed color", MINECRAFT_RED)

	private val drawingSection = SectionModuleSetting(
		id = "drawing_section",
		label = "Drawing",
		visibleIf = { anyDevice() },
	)

	private fun anyDevice(): Boolean =
		arrowAlign.value || lightsOn.value || simonSays.value || sharpShooter.value

	/** Lights are always outlined; the others only in a style with an outline. */
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
		description = "Draws the i4 and arrow device marks through walls. Simon Says never is.",
		visibleIf = { anyDevice() },
	)

	@JvmField
	val module = Module(
		id = "device_solver",
		name = "Device Solver",
		description = "Solves Goldor's three devices",
		category = ModuleCategory.FLOOR_7,
		supportsKeybind = false,
		settings = listOf(
			// In the order a run reaches them.
			simonSection, simonSays.withMore(simonStyle, simonFirstColor, simonSecondColor, simonThirdColor),
			simonBlockWrong, simonBlockWrongStart.withMore(simonMaxStartClicks), simonLagGuard.withMore(simonLagMillis),
			simonAnnounceProgress, simonBreakAlert,
			lightsSection, lightsOn.withMore(lightsFill, lightsOutline),
			arrowSection, arrowAlign.withMore(arrowColorStyle, arrowTextColor),
			arrowBlockWrongClicks.withMore(arrowInvertSneak),
			sharpSection, sharpShooter.withMore(i4Style, i4TargetColor, i4DoneColor), i4Terminator,
			i4ShowPrediction.withMore(i4PredictionColor), i4Announce,
			i4Section, i4Party.withMore(i4PartySuccess, i4PartyFail),
			i4Title.withMore(i4TitleSuccess, i4TitleSuccessColor, i4TitleFail, i4TitleFailColor),
			drawingSection, lineWidth, throughWalls,
		),

	)

	val arrowAlignEnabled: Boolean get() = module.enabled && arrowAlign.value
	val simonSaysEnabled: Boolean get() = module.enabled && simonSays.value

	val simonBreakAlertEnabled: Boolean get() = simonSaysEnabled && simonBreakAlert.value
	val sharpShooterEnabled: Boolean get() = module.enabled && sharpShooter.value
	val i4MessagesEnabled: Boolean get() = module.enabled && (i4Party.value || i4Title.value)

	val needsPhaseTracking: Boolean
		get() = module.enabled &&
			(arrowAlign.value || lightsOn.value || simonSays.value || sharpShooter.value)

	/** What [once] remembers of the last use. */
	private var lastUseTick = Long.MIN_VALUE
	private var lastUseTarget: Any? = null
	private var lastUseBlocked = false

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		CrypticRenderPipelines.touch()
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		// Chat is read in [onSystemChat] from the packet, since Goldor's greeting is boss dialogue
		// that other mods hide. Each floor is a new server, so nothing carries over.
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> forget() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> forget() }
	}

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay) return
		SimonSays.onChatMessage(message.string)
		SharpShooter.onChatMessage(message.string)
		I4.onChatMessage(message.string)
	}

	private fun forget() {
		ArrowAlign.reset()
		SimonSays.forget()
		SharpShooter.forget()
		I4.forget()
	}

	/** Always called: the arrow scan releases what it held when off. */
	fun tick(client: Minecraft) {
		ArrowAlign.tick(client)
		SharpShooter.tick(client)
		I4.tick()
	}

	/**
	 * The device reporting itself finished, the only certain end. The shot count is the client's
	 * own tally and can be a block off.
	 */
	fun announceSharpShooterDone(shot: Int, total: Int) {
		if (!i4Announce.value) return
		val client = Minecraft.getInstance()
		client.gui.hud.setTimes(TITLE_FADE_TICKS, TITLE_STAY_TICKS, TITLE_FADE_TICKS)
		client.gui.hud.setTitle(Component.literal("§aDevice complete"))
		if (shot < total) client.gui.hud.setSubtitle(Component.literal("§eCounted $shot/$total"))
	}

	/** The i4's outcome, as set in the i4 section. Shown over Sharp Shooter's own finish title. */
	fun announceI4(success: Boolean) {
		if (!module.enabled) return
		if (i4Party.value) {
			val text = (if (success) i4PartySuccess else i4PartyFail).value.trim()
			if (text.isNotEmpty()) PartyFeatures.enqueue("pc $text")
		}
		if (i4Title.value) {
			val text = (if (success) i4TitleSuccess else i4TitleFail).value.trim()
			val color = (if (success) i4TitleSuccessColor else i4TitleFailColor).rgb
			if (text.isEmpty()) return
			val hud = Minecraft.getInstance().gui.hud
			hud.setTimes(TITLE_FADE_TICKS, TITLE_STAY_TICKS, TITLE_FADE_TICKS)
			hud.setTitle(Component.literal(text).withColor(color))
			hud.setSubtitle(Component.empty())
		}
	}

	/**
	 * Whether to swallow a right click on a block. Only Simon Says uses blocks; the arrows are
	 * frames ([blocksEntityUse]).
	 */
	@JvmStatic
	fun blocksBlockUse(pos: BlockPos): Boolean = once(pos) { SimonSays.blocksClick(pos) }

	/** The same for an entity, and where a frame turn is recorded, so the count moves with the click. */
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
	 * Answers a use of [target] once per tick. The game tries each hand in turn, and the answers
	 * here have memory (a frame turning, a start press counted), so a second hand mustn't count
	 * twice.
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

	/** The lit block, the guess at the next, and those already shot. Only from the platform. */
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

		if (i4Terminator.value) {
			// The aims take the target's colour; the lit block itself is left plain so there's one
			// thing to aim at.
			val aims = SharpShooter.aims
			if (i4ShowPrediction.value) aims.getOrNull(1)?.let { mark(it, i4PredictionColor) }
			aims.firstOrNull()?.let { mark(it, i4TargetColor) }
			return
		}

		// The guess goes under the target, so a correct guess is drawn as the target.
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
	 * Green when nearly there, amber past halfway, red for the longest, so you can pick frames
	 * without reading numbers.
	 */
	private fun arrowCountColor(count: Int): Int = when {
		arrowColorStyle.selectedIndex != COLOR_DYNAMIC -> arrowTextColor.rgb
		count < 3 -> MINECRAFT_GREEN
		count < 5 -> MINECRAFT_GOLD
		else -> MINECRAFT_RED
	}

	/**
	 * Gated on the boss room rather than the phase (as Skyblocker does), since the levers are often
	 * done early, two phases below the fight.
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
				// Never through the wall: the six levers sit on one face, so a highlight carrying
				// through would mark the other side too.
				phase = false,
				lineWidth = lineWidth.value.toFloat(),
				fullBlock = false,
			)
		}
	}

	/**
	 * The boxes trace each button's face rather than the whole block, so neighbouring buttons stay
	 * distinct.
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
				// Never through walls: the board's buttons sit side by side, and the far side's would show too.
				phase = false,
				lineWidth = lineWidth.value.toFloat(),
			)
		}
	}
}
