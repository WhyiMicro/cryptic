package imicro.cryptic.feature

import imicro.cryptic.Cryptic
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.color.block.BlockTintSources
import net.minecraft.client.renderer.block.FluidModel
import net.minecraft.client.renderer.block.FluidStateModelSet
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.data.AtlasIds
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.client.resources.model.sprite.Material
import net.minecraft.util.ARGB
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids
import java.util.concurrent.atomic.AtomicLong

/**
 * Draws lava with the water texture, so you can see while swimming through it (constantly needed in
 * the Crimson Isle and Crystal Hollows). Only the model changes: it still burns and slows you. The
 * fog goes too, if asked. Ported from NoammAddons.
 */
object LavaToWater {
	@JvmField
	val colorTint = ToggleModuleSetting(
		id = "color_tint",
		label = "Tint it",
		defaultValue = false,
		description = "Draws plain water in exactly the colour chosen, in place of the resource pack's water and the biome's blue.",
	)

	@JvmField
	val tintColor = ColorModuleSetting(
		id = "tint_color",
		label = "Tint",
		defaultRgb = 0x3F76E4,
		visibleIf = { colorTint.value || tintWater.value },
		inlineWith = colorTint,
	)

	@JvmField
	val tintWater = ToggleModuleSetting(
		id = "tint_water",
		label = "Tint water too",
		defaultValue = false,
		description = "Draws real water in the tint colour as well, so lava and water look the same.",
	)

	@JvmField
	val hideFog = ToggleModuleSetting(
		id = "hide_fog",
		label = "Hide the fog",
		defaultValue = true,
		description = "Takes away the orange fog inside lava, which is most of what stops you seeing.",
	)

	@JvmField
	val module = Module(
		id = "lava_to_water",
		name = "Lava to Water",
		description = "Draws lava as water",
		category = ModuleCategory.VISUAL,
		supportsKeybind = false,
		settings = listOf(colorTint, tintColor, tintWater, hideFog),
	)

	/**
	 * What the fluid models were when last applied. Changing them means rebuilding every chunk
	 * mesh, so it only happens when these settings actually change.
	 */
	private var appliedEnabled = false
	private var appliedTint = 0
	private var appliedLavaTint = false
	private var appliedWaterTint = false

	/**
	 * Times lava's model was swapped since the last rebuild, for the debug command. Counted from
	 * chunk builder threads.
	 */
	private val swaps = AtomicLong()
	private var rebuilds = 0

	@JvmStatic
	fun hidesFog(): Boolean = module.enabled && hideFog.value

	@JvmStatic
	fun fogTint(): Int? = if (module.enabled && colorTint.value) tintColor.argb else null

	@JvmStatic
	fun isEnabled(): Boolean = module.enabled

	/**
	 * Water's model when the game asks for lava's; null leaves other fluids, and everything while
	 * off, alone.
	 */
	@JvmStatic
	fun replacement(models: FluidStateModelSet, state: FluidState): FluidModel? {
		if (!module.enabled) return null
		if (state.type == Fluids.WATER || state.type == Fluids.FLOWING_WATER) {
			return if (tintWater.value) tinted else null
		}
		if (state.type != Fluids.LAVA && state.type != Fluids.FLOWING_LAVA) return null

		swaps.incrementAndGet()
		val water = models.get(Fluids.WATER.defaultFluidState())
		if (!colorTint.value) return water

		// Built on the client thread; this is a chunk builder thread and shouldn't look up sprites.
		return tinted ?: water
	}

	/**
	 * The model for tinted lava. Uses Cryptic's own near-white water texture, since a tint
	 * multiplies the texture and resource packs often draw water blue. Untinted lava uses the
	 * pack's water.
	 */
	@Volatile
	private var tinted: FluidModel? = null

	/** The sprites [tinted] was built from, to notice resource reloads. */
	private var tintedStill: TextureAtlasSprite? = null

	private val STILL = Cryptic.id("block/water_still")
	private val FLOWING = Cryptic.id("block/water_flow")

	/**
	 * Builds [tinted] for the chosen colour; true if it changed. Sprites are looked up each time,
	 * since a reload replaces the atlas.
	 */
	private fun buildTinted(client: Minecraft): Boolean {
		val atlas = runCatching { client.atlasManager.getAtlasOrThrow(AtlasIds.BLOCKS) }.getOrNull() ?: return false
		// The atlas is stitched after the first resource reload; mods that close the loading screen
		// early let ticks run before that.
		val still = try {
			atlas.getSprite(STILL)
		} catch (_: IllegalStateException) {
			return false
		}
		val tint = ARGB.opaque(tintColor.rgb)
		if (still === tintedStill && tint == appliedTint && tinted != null) return false

		val flowing = atlas.getSprite(FLOWING)
		tintedStill = still
		tinted = FluidModel(
			// Part see-through, like the water it imitates.
			ChunkSectionLayer.TRANSLUCENT,
			Material.Baked(still, false),
			Material.Baked(flowing, false),
			// The face against glass, drawn with the flowing texture's layout.
			Material.Baked(flowing, false),
			// Opaque, since this is each vertex's whole colour, alpha included.
			BlockTintSources.constant(tint, tint),
		)
		return true
	}

	/**
	 * Rebuilds the world's meshes when needed. Called from the tick, so dragging a colour picker
	 * doesn't rebuild every frame.
	 */
	fun tick(client: Minecraft) {
		if (client.level == null) return
		val enabled = module.enabled
		val lavaTint = enabled && colorTint.value
		val waterTint = enabled && tintWater.value
		val tint = if (lavaTint || waterTint) ARGB.opaque(tintColor.rgb) else 0
		// Also true after a resource reload.
		val rebuilt = tint != 0 && buildTinted(client)
		if (enabled == appliedEnabled && tint == appliedTint && lavaTint == appliedLavaTint &&
			waterTint == appliedWaterTint && !rebuilt
		) return

		appliedEnabled = enabled
		appliedTint = tint
		appliedLavaTint = lavaTint
		appliedWaterTint = waterTint
		rebuild()
	}

	/**
	 * Throws away every compiled chunk so the next frame rebuilds them. Goes through the level
	 * extractor, as the video settings and F3+A do in 26.2.
	 */
	private fun rebuild() {
		swaps.set(0)
		rebuilds++
		Minecraft.getInstance().levelExtractor.allChanged()
	}

	/**
	 * What lava is drawn as, for `/cryptic debug lava`, asked the way a chunk builder asks, so
	 * other mods' fluid swaps (SkyHanni's among them) are included. Right here but wrong in the
	 * world means the chunks weren't rebuilt.
	 */
	fun describe(): List<String> {
		val client = Minecraft.getInstance()
		val lines = mutableListOf<String>()
		lines += "§8[Cryptic] §7Lava to Water: module ${if (module.enabled) "§aon" else "§coff"}§7, tint " +
			(if (colorTint.value) "§aon §7(§f#${tintColor.hexDigits}§7)" else "§coff") +
			"§7, swaps since the last rebuild §f${swaps.get()}§7, rebuilds §f$rebuilds"

		val lava = Fluids.LAVA.defaultFluidState()
		val model = client.modelManager.fluidStateModelSet.get(lava)
		val level = client.level
		val player = client.player
		val tint = model.tintSource()
		val color = when {
			tint == null -> "none (drawn white)"
			level == null || player == null -> "not in a world"
			else -> "#%08X".format(tint.colorInWorld(lava.createLegacyBlock(), level, player.blockPosition()))
		}
		lines += "§8[Cryptic] §7Lava's model right now: texture §f${model.stillMaterial().sprite().contents().name()}§7, " +
			"layer §f${model.layer()}§7, tint §f$color"

		val loader = FabricLoader.getInstance()
		val others = listOf("sodium", "skyhanni", "iris").filter(loader::isModLoaded)
		lines += "§8[Cryptic] §7Mods with a say in fluids: §f${others.ifEmpty { listOf("none") }.joinToString(", ")}"
		return lines
	}
}
