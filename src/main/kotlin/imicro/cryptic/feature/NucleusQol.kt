package imicro.cryptic.feature

import com.mojang.blaze3d.platform.InputConstants
import imicro.cryptic.CrypticClient
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.KeybindSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.render.CrypticRenderPipelines
import imicro.cryptic.render.WorldRender
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos

/**
 * Small conveniences for the Crystal Nucleus in the Crystal Hollows.
 *
 * Two for now: a key that warps to the nucleus, and a set of waypoints worked
 * out from where you are standing. Both only act inside the Crystal Hollows —
 * `/warp nucleus` means nothing anywhere else, and waypoints measured from a
 * spot in the hub would point at nothing.
 */
object NucleusQol {
	private const val SOURCE = "Nucleus QoL"

	/** Indices into [mode]. */
	private const val MODE_OUTLINE = 0
	private const val MODE_FILL = 1

	/**
	 * Where each waypoint sits relative to the block the player is standing in,
	 * as (x, y, z).
	 */
	private val OFFSETS = listOf(
		Triple(4, 10, 65),
		Triple(29, -32, 65),
		Triple(29, -32, 48),
	)

	private val waypointsSection = SectionModuleSetting("waypoints_section", "Waypoints")

	private val calculate = ButtonModuleSetting("calculate", "Calculate waypoints", action = { calculate() })

	private val clear = ButtonModuleSetting(
		id = "clear",
		label = "Clear waypoints",
		action = {
			waypoints = emptyList()
			Toasts.show(SOURCE, "Cleared the waypoints")
		},
		visibleIf = { waypoints.isNotEmpty() },
	)

	@JvmField
	val mode = DropdownModuleSetting(
		id = "mode",
		label = "Mode",
		options = listOf("Outline", "Fill", "Fill + Outline"),
		defaultIndex = 2,
	)

	@JvmField
	val fillColor = ColorModuleSetting(
		id = "fill_color",
		label = "Fill",
		defaultRgb = 0xFF55FF,
		supportsAlpha = true,
		defaultAlpha = 0x50,
		visibleIf = { mode.selectedIndex != MODE_OUTLINE },
	)

	@JvmField
	val outlineColor = ColorModuleSetting(
		id = "outline_color",
		label = "Outline",
		defaultRgb = 0xFF55FF,
		supportsAlpha = true,
		defaultAlpha = 0xFF,
		visibleIf = { mode.selectedIndex != MODE_FILL },
	)

	@JvmField
	val lineWidth = SliderModuleSetting(
		id = "line_width",
		label = "Outline width",
		defaultValue = 2.0,
		min = 1.0,
		max = 10.0,
		step = 0.5,
		visibleIf = { mode.selectedIndex != MODE_FILL },
	)

	@JvmField
	val phase = ToggleModuleSetting(
		id = "phase",
		label = "Phase",
		defaultValue = true,
		description = "Draws the waypoints through the walls.",
	)

	/** The module card's bind button, pointed at the real key mapping. */
	private val warpKey = KeybindSetting(
		currentKeyName = {
			if (CrypticClient.nucleusWarpKey.isUnbound) {
				"None"
			} else {
				CrypticClient.nucleusWarpKey.translatedKeyMessage.string.uppercase()
			}
		},
		onKeyChanged = { key ->
			CrypticClient.nucleusWarpKey.setKey(key ?: InputConstants.UNKNOWN)
			KeyMapping.resetMapping()
			Minecraft.getInstance().options.save()
		},
	)

	@JvmField
	val module = Module(
		id = "nucleus_qol",
		name = "Nucleus QoL",
		description = "Nucleus warp key and waypoints",
		category = ModuleCategory.GENERAL,
		hasDemoSettings = false,
		supportsKeybind = true,
		keybind = warpKey,
		settings = listOf(
			waypointsSection, calculate, clear, mode, fillColor, outlineColor, lineWidth, phase,
		),
	)

	/** The blocks being marked, fixed at the moment they were calculated. */
	private var waypoints: List<BlockPos> = emptyList()

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		CrypticRenderPipelines.touch()
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		// Waypoints are measured from a spot in one lobby. Every server hop is a
		// different lobby, where the same numbers mark nothing.
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> waypoints = emptyList() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> waypoints = emptyList() }
	}

	/**
	 * The bound key was pressed in-game.
	 *
	 * Silent everywhere but the Hollows. A key that does nothing where it makes
	 * no sense needs no explanation — the key is bound to something whose name
	 * says where it works, and a notification every time it is pressed for some
	 * other reason is noise.
	 */
	fun onWarpKey(client: Minecraft) {
		if (!module.enabled || !SkyblockLocation.inCrystalHollows) return
		client.connection?.sendCommand("warp nucleus")
	}

	private fun calculate() {
		val player = Minecraft.getInstance().player
		when {
			!module.enabled -> Toasts.show(SOURCE, "Enable the module first", true)
			player == null -> Toasts.show(SOURCE, "Not in a world", true)
			!SkyblockLocation.inCrystalHollows -> Toasts.show(SOURCE, "Only works in the Crystal Hollows", true)
			else -> {
				val origin = player.blockPosition()
				waypoints = OFFSETS.map { (x, y, z) -> origin.offset(x, y, z) }
				Toasts.show(SOURCE, "Set ${waypoints.size} waypoints")
			}
		}
	}

	private fun render(context: LevelRenderContext) {
		if (!module.enabled || waypoints.isEmpty()) return

		val fill = mode.selectedIndex != MODE_OUTLINE
		val outline = mode.selectedIndex != MODE_FILL

		for (pos in waypoints) {
			WorldRender.drawBlock(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				pos = pos,
				outlineArgb = outlineColor.argb,
				fillArgb = fillColor.argb,
				outline = outline,
				fill = fill,
				phase = phase.value,
				lineWidth = lineWidth.value.toFloat(),
				fullBlock = true,
			)
		}
	}
}
