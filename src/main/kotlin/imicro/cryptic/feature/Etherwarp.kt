package imicro.cryptic.feature

import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.MultiSelectModuleSetting
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.render.CrypticRenderPipelines
import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents

/**
 * Shows where a sneak-click would etherwarp to, and optionally replaces the
 * teleport sound.
 *
 * Ported from NoammAddons (CC0). The overlay only appears while sneak is held
 * with a merged Aspect of the End or Void in hand, which is exactly when the
 * teleport is armed.
 */
object Etherwarp {
	private const val MODE_OUTLINE = 0
	private const val MODE_FILL = 1

	/**
	 * Hypixel plays the teleport as an ender dragon hurt at this pitch. Nothing
	 * else in Skyblock uses the pair, so it identifies the sound on its own.
	 */
	private const val TELEPORT_SOUND_PITCH = 0.53968257f

	/** NoammAddons' own default highlight colour, kept so the overlay looks familiar. */
	private const val DEFAULT_HIGHLIGHT_RGB = 0x0086FF

	/** Fills are drawn faint, matching the alpha NoammAddons ships with. */
	private const val DEFAULT_FILL_ALPHA = 50

	private val soundOptions = SoundChoices.options

	@JvmField
	val overlay = ToggleModuleSetting(
		id = "overlay",
		label = "Overlay",
		defaultValue = true,
	)

	@JvmField
	val mode = DropdownModuleSetting(
		id = "mode",
		label = "Mode",
		options = listOf("Outline", "Fill", "Fill + Outline"),
		defaultIndex = MODE_OUTLINE,
		visibleIf = { overlay.value },
	)

	@JvmField
	val fullBlock = ToggleModuleSetting(
		id = "full_block",
		label = "Full block",
		defaultValue = false,
		visibleIf = { overlay.value },
	)

	@JvmField
	val phase = ToggleModuleSetting(
		id = "phase",
		label = "Phase",
		defaultValue = false,
		visibleIf = { overlay.value },
	)

	@JvmField
	val showFail = ToggleModuleSetting(
		id = "show_fail",
		label = "Show fail",
		defaultValue = true,
		visibleIf = { overlay.value },
	)

	@JvmField
	val lineWidth = SliderModuleSetting(
		id = "line_width",
		label = "Line width",
		defaultValue = 2.5,
		min = 1.0,
		max = 10.0,
		step = 0.1,
		visibleIf = { overlay.value && mode.selectedIndex != MODE_FILL },
	)

	@JvmField
	val outlineColor = ColorModuleSetting(
		id = "outline_color",
		label = "Outline",
		defaultRgb = DEFAULT_HIGHLIGHT_RGB,
		visibleIf = { overlay.value && mode.selectedIndex != MODE_FILL },
	)

	@JvmField
	val fillColor = ColorModuleSetting(
		id = "fill_color",
		label = "Fill",
		defaultRgb = DEFAULT_HIGHLIGHT_RGB,
		supportsAlpha = true,
		defaultAlpha = DEFAULT_FILL_ALPHA,
		visibleIf = { overlay.value && mode.selectedIndex != MODE_OUTLINE },
	)

	@JvmField
	val invalidOutlineColor = ColorModuleSetting(
		id = "invalid_outline_color",
		label = "Fail outline",
		defaultRgb = 0xFF0000,
		visibleIf = { overlay.value && showFail.value && mode.selectedIndex != MODE_FILL },
	)

	@JvmField
	val invalidFillColor = ColorModuleSetting(
		id = "invalid_fill_color",
		label = "Fail fill",
		defaultRgb = 0xFF0000,
		supportsAlpha = true,
		defaultAlpha = DEFAULT_FILL_ALPHA,
		visibleIf = { overlay.value && showFail.value && mode.selectedIndex != MODE_OUTLINE },
	)

	// One switch per teleport for each half, the way NoammAddons lays them out:
	// somebody who wants the camera to jump ahead on an etherwarp does not
	// necessarily want it on every Hyperion click. The two etherwarp ones keep
	// the ids they had when they were the only two.

	@JvmField
	val fakeZpew = ToggleModuleSetting(
		id = "fake_zpew",
		label = "Etherwarp",
		defaultValue = false,
		description = "Puts your view at the landing spot as you click an etherwarp. Can't be on while Smooth teleport glides etherwarps.",
	)

	@JvmField
	val fakeZpewTransmission = ToggleModuleSetting(
		id = "fake_zpew_transmission",
		label = "Instant Transmission",
		defaultValue = false,
		description = "The same for the eight-block teleport on an Aspect of the End or Void.",
	)

	@JvmField
	val fakeZpewImpact = ToggleModuleSetting(
		id = "fake_zpew_impact",
		label = "Wither Impact",
		defaultValue = false,
		description = "The same for the teleport on a Hyperion, Valkyrie, Astraea or Scylla.",
	)

	@JvmField
	val noRotate = ToggleModuleSetting(
		id = "no_rotate",
		label = "Etherwarp",
		defaultValue = false,
		description = "Keeps your head where you have turned it when an etherwarp lands.",
	)

	@JvmField
	val noRotateTransmission = ToggleModuleSetting(
		id = "no_rotate_transmission",
		label = "Instant Transmission",
		defaultValue = false,
		description = "The same for the eight-block teleport on an Aspect of the End or Void.",
	)

	@JvmField
	val noRotateImpact = ToggleModuleSetting(
		id = "no_rotate_impact",
		label = "Wither Impact",
		defaultValue = false,
		description = "The same for the teleport on a Hyperion, Valkyrie, Astraea or Scylla.",
	)

	// ---- Smooth teleport -------------------------------------------------

	@JvmField
	val smoothTeleport = ToggleModuleSetting(
		id = "smooth_teleport",
		label = "Smooth teleport",
		defaultValue = false,
		description = "Glides the camera from where a teleport started to where it landed, instead of snapping, as Skyblocker's Smooth AOTE does. Only the view moves.",
	)

	@JvmField
	val smoothKinds = MultiSelectModuleSetting(
		id = "smooth_items",
		label = "Smooth these",
		options = SmoothTeleport.KINDS,
		defaultSelected = setOf(SmoothTeleport.INSTANT, SmoothTeleport.ETHER, SmoothTeleport.IMPACT),
		description = "Instant Transmission: Aspect of the End or Void. Etherwarp: a merged Aspect while sneaking, or the Etherwarp Conduit. " +
			"Wither Impact: Hyperion, Astraea, Scylla, Valkyrie, Necron's Blade. Sinrecall: Sinseeker Scythe. Weird Transmission: Aspect of the Leech. A kind can't be ticked while its Fake zpew switch is on.",
		visibleIf = { smoothTeleport.value },
	)

	@JvmField
	val smoothPredictive = ToggleModuleSetting(
		id = "smooth_predictive",
		label = "Predictive",
		defaultValue = true,
		description = "Starts gliding the moment you click, towards where the teleport will land, timed to your ping. Off, the glide waits for the server and starts from there.",
		visibleIf = { smoothTeleport.value },
	)

	@JvmField
	val smoothThirdPerson = ToggleModuleSetting(
		id = "smooth_third_person",
		label = "In third person",
		defaultValue = true,
		description = "Glides in third person too. Off, teleports only glide in first person.",
		visibleIf = { smoothTeleport.value },
	)

	@JvmField
	val smoothOutsideDungeons = ToggleModuleSetting(
		id = "smooth_disable_in_dungeons",
		label = "Disable in dungeons",
		defaultValue = false,
		description = "Teleports snap in the Catacombs, as they do without this, and glide everywhere else.",
		visibleIf = { smoothTeleport.value },
	)

	@JvmField
	val smoothTime = SliderModuleSetting(
		id = "smooth_lag",
		label = "Smoothing time",
		unit = "ms",
		defaultValue = 100.0,
		min = 0.0,
		max = 500.0,
		step = 5.0,
		description = "How long the glide takes: Skyblocker's Maximum Added Lag. Lower is faster, and 0 turns it off. The teleport itself is never held up.",
		visibleIf = { smoothTeleport.value },
	)

	/** True while any of the six is on, which is when the three timing settings mean anything. */
	private fun predicts(): Boolean =
		fakeZpew.value || fakeZpewTransmission.value || fakeZpewImpact.value ||
			noRotate.value || noRotateTransmission.value || noRotateImpact.value

	@JvmField
	val resyncTimeout = SliderModuleSetting(
		id = "resync_timeout",
		label = "Resync timeout",
		unit = "ms",
		defaultValue = 500.0,
		min = 300.0,
		max = 1000.0,
		step = 50.0,
		description = "How long a prediction is believed. Also used by no rotate.",
		visibleIf = { predicts() },
	)

	@JvmField
	val waitOutLag = ToggleModuleSetting(
		id = "wait_out_lag",
		label = "Wait out lag",
		defaultValue = true,
		description = "Holds the prediction while the server is not ticking, instead of giving up mid-freeze.",
		visibleIf = { predicts() },
	)

	@JvmField
	val maxLagWait = SliderModuleSetting(
		id = "max_lag_wait",
		label = "Max lag wait",
		unit = "ms",
		defaultValue = 3000.0,
		min = 500.0,
		max = 8000.0,
		step = 250.0,
		description = "The longest a freeze may hold a prediction open before it is given up on anyway.",
		visibleIf = { predicts() && waitOutLag.value },
	)

	@JvmField
	val customSound = ToggleModuleSetting(
		id = "custom_sound",
		label = "Custom sound",
		defaultValue = false,
	)

	@JvmField
	val sound = DropdownModuleSetting(
		id = "sound",
		label = "Sound",
		options = soundOptions.map { it.first },
		visibleIf = { customSound.value },
	)

	@JvmField
	val volume = SliderModuleSetting(
		id = "volume",
		label = "Volume",
		defaultValue = 0.5,
		min = 0.0,
		max = 1.0,
		step = 0.1,
		visibleIf = { customSound.value },
	)

	@JvmField
	val pitch = SliderModuleSetting(
		id = "pitch",
		label = "Pitch",
		defaultValue = 1.0,
		min = 0.5,
		max = 2.0,
		step = 0.1,
		visibleIf = { customSound.value },
	)

	@JvmField
	val previewSound = ButtonModuleSetting(
		id = "preview_sound",
		label = "Play sound",
		action = { playCustomSound() },
		visibleIf = { customSound.value },
	)

	@JvmField
	val module = Module(
		id = "etherwarp",
		name = "Etherwarp Customization",
		// Etherwarp lives on an item rather than in dungeons, so the module sits
		// under General even though dungeons are where it is used most.
		description = "Etherwarp overlay, fake zpew and no rotate",
		category = ModuleCategory.GENERAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		// One switch a row on the card, with what tunes it in the switch's "⋯"
		// panel: the overlay's shape and colours, the timing every prediction
		// shares, the glide, and the sound.
		settings = listOf(
			SectionModuleSetting("overlay_section", "Overlay"),
			overlay.withMore(mode, lineWidth, fullBlock, phase, outlineColor, fillColor),
			showFail.withMore(invalidOutlineColor, invalidFillColor),
			SectionModuleSetting("zero_ping_section", "Fake zpew").withMore(resyncTimeout, waitOutLag, maxLagWait),
			fakeZpew,
			fakeZpewTransmission,
			fakeZpewImpact,
			SectionModuleSetting("no_rotate_section", "No rotate"),
			noRotate,
			noRotateTransmission,
			noRotateImpact,
			SectionModuleSetting("smooth_section", "Smooth teleport"),
			smoothTeleport.withMore(smoothPredictive, smoothThirdPerson, smoothOutsideDungeons, smoothTime),
			smoothKinds,
			SectionModuleSetting("sound_section", "Sound"),
			customSound.withMore(sound, volume, pitch, previewSound),
		),
	)

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		// Pipelines are gathered while the game starts, so they are registered
		// now rather than on the first frame that draws the overlay.
		CrypticRenderPipelines.touch()
		LevelRenderEvents.COLLECT_SUBMITS.register(::renderOverlay)
		EtherwarpZeroPing.initialize()
		SmoothTeleport.initialize()
	}

	/** True while the zero-ping halves need to know which dungeon floor this is. */
	val needsFloorTracking: Boolean
		get() = EtherwarpZeroPing.needsFloorTracking ||
			(module.enabled && smoothTeleport.value && (smoothOutsideDungeons.value || smoothPredictive.value))

	/**
	 * Called by the client packet mixin. Returns true when Hypixel's teleport
	 * sound should be swallowed, because Cryptic is playing its own instead.
	 */
	@JvmStatic
	fun replaceTeleportSound(packet: ClientboundSoundPacket): Boolean {
		if (!module.enabled || !customSound.value) return false
		if (packet.sound.value() != SoundEvents.ENDER_DRAGON_HURT) return false
		if (packet.pitch != TELEPORT_SOUND_PITCH) return false

		// The packet may still be on the network thread here, and the sound
		// manager expects to be touched from the client thread.
		Minecraft.getInstance().execute(::playCustomSound)
		return true
	}

	private fun renderOverlay(context: LevelRenderContext) {
		if (!module.enabled || !overlay.value) return

		val client = Minecraft.getInstance()
		if (!client.options.keyShift.isDown) return
		val player = client.player ?: return

		val held = player.mainHandItem.takeUnless { it.isEmpty } ?: return
		val range = EtherwarpHelper.etherwarpRange(held) ?: return

		val target = EtherwarpHelper.target(player.position(), player.lookAngle, range)
		if (!target.valid && !showFail.value) return
		val pos = target.pos ?: return

		WorldRender.drawBlock(
			poseStack = context.poseStack(),
			collector = context.submitNodeCollector(),
			pos = pos,
			outlineArgb = if (target.valid) outlineColor.argb else invalidOutlineColor.argb,
			fillArgb = if (target.valid) fillColor.argb else invalidFillColor.argb,
			outline = mode.selectedIndex != MODE_FILL,
			fill = mode.selectedIndex != MODE_OUTLINE,
			phase = phase.value,
			lineWidth = lineWidth.value.toFloat(),
			fullBlock = fullBlock.value,
		)
	}

	/**
	 * Whether the player is lining up an etherwarp right now.
	 *
	 * Crouching with an item that can warp, which is the whole of what makes
	 * the overlay appear. [BlockOverlay] asks so it can get out of the way:
	 * two boxes on two different blocks, one under the crosshair and one where
	 * you would land, is a confusing thing to aim with.
	 */
	fun isAiming(): Boolean {
		val client = Minecraft.getInstance()
		if (!client.options.keyShift.isDown) return false
		val held = client.player?.mainHandItem?.takeUnless { it.isEmpty } ?: return false
		return EtherwarpHelper.etherwarpRange(held) != null
	}

	/** Command-friendly names for [soundOptions], e.g. "experience_orb". */
	val soundIds: List<String> = soundOptions.map { it.first.lowercase().replace(' ', '_') }

	/**
	 * Selects a sound by its [soundIds] name and plays it, so the command both
	 * applies the choice and lets you hear it. Returns the label that was
	 * selected, or null when the name is not one Cryptic offers.
	 */
	fun selectSound(id: String): String? {
		val index = soundIds.indexOf(id.lowercase()).takeIf { it >= 0 } ?: return null
		sound.selectedIndex = index
		playCustomSound()
		return soundOptions[index].first
	}

	private fun playCustomSound() {
		val client = Minecraft.getInstance()
		client.soundManager.play(
			SimpleSoundInstance.forUI(selectedSound(), pitch.value.toFloat(), volume.value.toFloat()),
		)
	}

	private fun selectedSound(): SoundEvent =
		soundOptions[sound.selectedIndex.coerceIn(soundOptions.indices)].second
}
