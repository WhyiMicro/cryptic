package imicro.cryptic.feature

import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.Minecraft
import net.minecraft.client.color.block.BlockTintSources
import net.minecraft.client.renderer.block.FluidModel
import net.minecraft.client.renderer.block.FluidStateModelSet
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids

/**
 * Draws lava with the water texture.
 *
 * Ported from NoammAddons (CC0, Copyright (c) Noamm9). Lava is opaque and
 * bright, and swimming through it — which SkyBlock asks for constantly, in the
 * Crimson Isle and the Crystal Hollows — means moving blind through an orange
 * screen. Water is transparent, so the same swim is one you can see through.
 *
 * Nothing about the lava changes but its model: it still burns, still slows
 * you, and the server is not told anything different. Only the fog goes with
 * it, and only if asked, because a clear texture behind a solid orange fog is
 * no clearer than before.
 */
object LavaToWater {
	@JvmField
	val colorTint = ToggleModuleSetting(
		id = "color_tint",
		label = "Tint it",
		defaultValue = false,
		description = "Colours the water rather than leaving it the blue of the biome you are in.",
	)

	@JvmField
	val tintColor = ColorModuleSetting(
		id = "tint_color",
		label = "Tint",
		defaultRgb = 0x3F76E4,
		visibleIf = { colorTint.value },
	)

	@JvmField
	val hideFog = ToggleModuleSetting(
		id = "hide_fog",
		label = "Hide the fog",
		defaultValue = true,
		description = "Takes away the orange fog inside lava, which is most of what stops you seeing.",
	)

	private val reset = ButtonModuleSetting("reset", "Reset", action = {
		colorTint.reset()
		tintColor.reset()
		hideFog.reset()
		rebuild()
	})

	@JvmField
	val module = Module(
		id = "lava_to_water",
		name = "Lava to Water",
		description = "Draws lava as water",
		category = ModuleCategory.VISUAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(colorTint, tintColor, hideFog, reset),
	)

	/**
	 * What the fluid models were when this was last applied.
	 *
	 * Fluid models are baked into the chunk meshes, so changing the answer means
	 * rebuilding them — and rebuilding every chunk is far too expensive to do
	 * on the chance that something changed. Watching the two settings that
	 * matter is how it is done only when it has.
	 */
	private var appliedEnabled = false
	private var appliedTint = 0

	@JvmStatic
	fun hidesFog(): Boolean = module.enabled && hideFog.value

	/** The tint to draw lava fog in, or null to leave water's own colour. */
	@JvmStatic
	fun fogTint(): Int? = if (module.enabled && colorTint.value) tintColor.argb else null

	@JvmStatic
	fun isEnabled(): Boolean = module.enabled

	/**
	 * Hands back water's model when the game asks for lava's.
	 *
	 * Returning null means "leave it alone", which is every fluid that is not
	 * lava and every moment the module is off.
	 */
	@JvmStatic
	fun replacement(models: FluidStateModelSet, state: FluidState): FluidModel? {
		if (!module.enabled) return null
		if (state.type != Fluids.LAVA && state.type != Fluids.FLOWING_LAVA) return null

		val water = models.get(Fluids.WATER.defaultFluidState())
		if (!colorTint.value) return water

		val tint = tintColor.rgb
		return FluidModel(
			water.layer(),
			water.stillMaterial(),
			water.flowingMaterial(),
			water.overlayMaterial(),
			BlockTintSources.constant(tint, tint),
		)
	}

	/**
	 * Rebuilds the world's meshes, which is the only way a model swap is seen.
	 *
	 * Called from the tick rather than from the settings, because a colour
	 * picker being dragged would otherwise rebuild every chunk on every frame
	 * of the drag.
	 */
	fun tick(client: Minecraft) {
		val enabled = module.enabled
		val tint = if (enabled && colorTint.value) tintColor.argb else 0
		if (enabled == appliedEnabled && tint == appliedTint) return

		appliedEnabled = enabled
		appliedTint = tint
		rebuild()
	}

	/**
	 * Throws away every compiled chunk, so the next frame rebuilds them with the
	 * model swap applied. 26.2 renamed this from `allChanged` and made it ask
	 * for what it used to fetch itself, which means there is nothing to rebuild
	 * without a world.
	 */
	private fun rebuild() {
		val client = Minecraft.getInstance()
		val level = client.level ?: return
		client.levelRenderer.invalidateCompiledGeometry(level, client.options, client.gameRenderer.mainCamera(), client.blockColors)
	}
}
