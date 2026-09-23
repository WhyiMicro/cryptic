package imicro.cryptic.feature

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

	private val soundOptions = listOf(
		"Experience Orb" to SoundEvents.EXPERIENCE_ORB_PICKUP,
		"Pling" to SoundEvents.NOTE_BLOCK_PLING.value(),
		"Bell" to SoundEvents.NOTE_BLOCK_BELL.value(),
		"Harp" to SoundEvents.NOTE_BLOCK_HARP.value(),
		"Button Click" to SoundEvents.UI_BUTTON_CLICK.value(),
		"Amethyst Chime" to SoundEvents.AMETHYST_BLOCK_CHIME,
		"Enderman Teleport" to SoundEvents.ENDERMAN_TELEPORT,
		"Arrow Hit" to SoundEvents.ARROW_HIT_PLAYER,
		"Level Up" to SoundEvents.PLAYER_LEVELUP,
		"Anvil Land" to SoundEvents.ANVIL_LAND,
	)

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

	@JvmField
	val fakeZpew = ToggleModuleSetting(
		id = "fake_zpew",
		label = "Fake zpew",
		defaultValue = false,
		description = "Puts your view at the landing spot as you click.",
	)

	@JvmField
	val noRotate = ToggleModuleSetting(
		id = "no_rotate",
		label = "No rotate",
		defaultValue = false,
		description = "Keeps your head where you have turned it.",
	)

	@JvmField
	val resyncTimeout = SliderModuleSetting(
		id = "resync_timeout",
		label = "Resync timeout",
		defaultValue = 500.0,
		min = 300.0,
		max = 1000.0,
		step = 50.0,
		description = "How long a prediction is believed.",
		visibleIf = { fakeZpew.value || noRotate.value },
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
		description = "Highlights where your etherwarp lands",
		category = ModuleCategory.GENERAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		// Grouped by the question each answers: whether the overlay shows and
		// what shape it takes, what it is painted in, and what you hear.
		settings = listOf(
			SectionModuleSetting("overlay_section", "Overlay"),
			overlay,
			mode,
			lineWidth,
			fullBlock,
			phase,
			showFail,
			SectionModuleSetting("colors_section", "Colors"),
			outlineColor,
			fillColor,
			invalidOutlineColor,
			invalidFillColor,
			SectionModuleSetting("zero_ping_section", "Zero ping"),
			fakeZpew,
			noRotate,
			resyncTimeout,
			SectionModuleSetting("sound_section", "Sound"),
			customSound,
			sound,
			volume,
			pitch,
			previewSound,
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
	}

	/** True while the zero-ping halves need to know which dungeon floor this is. */
	val needsFloorTracking: Boolean
		get() = EtherwarpZeroPing.needsFloorTracking

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
