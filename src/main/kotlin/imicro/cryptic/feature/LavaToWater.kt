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
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.client.resources.model.sprite.Material
import net.minecraft.util.ARGB
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids
import java.util.concurrent.atomic.AtomicLong

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
		description = "Draws plain water in exactly the colour chosen, in place of the resource pack's water and the biome's blue.",
	)

	@JvmField
	val tintColor = ColorModuleSetting(
		id = "tint_color",
		label = "Tint",
		defaultRgb = 0x3F76E4,
		visibleIf = { colorTint.value },
		inlineWith = colorTint,
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
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(colorTint, tintColor, hideFog),
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

	/**
	 * How many times lava's model has been swapped since the chunks were last
	 * rebuilt, for the debug command. Counted from the chunk builders' threads.
	 */
	private val swaps = AtomicLong()
	private var rebuilds = 0

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

		swaps.incrementAndGet()
		val water = models.get(Fluids.WATER.defaultFluidState())
		if (!colorTint.value) return water

		// Built on the client thread and only read here: this is a chunk
		// builder's thread, which has no business looking a sprite up.
		return tinted ?: water
	}

	/**
	 * The model a tinted lava is drawn with, or null before it has been built.
	 *
	 * Cryptic's own water rather than the game's, and the reason is the tint. A
	 * tint multiplies the texture, so the colour you pick only comes out as that
	 * colour on a texture that is white — and whether the game's water is white
	 * is up to the resource pack. Vanilla's is grey and relies on the biome to
	 * colour it, but a pack that draws its water blue makes every tint a shade
	 * of blue, white included. So a tinted lava uses a texture nobody else gets
	 * to replace: near-white, with ripples of its own. Untinted, the pack's
	 * water is exactly what was asked for and is left alone.
	 */
	@Volatile
	private var tinted: FluidModel? = null

	/** The sprites [tinted] was built from, which is how a resource reload is noticed. */
	private var tintedStill: TextureAtlasSprite? = null

	private val STILL = Cryptic.id("block/water_still")
	private val FLOWING = Cryptic.id("block/water_flow")

	/**
	 * Builds [tinted] for the colour currently chosen. True if it changed.
	 *
	 * The sprites are looked up again every time, cheaply, because a resource
	 * reload stitches a new atlas and the old sprites point into a texture that
	 * is gone.
	 */
	private fun buildTinted(client: Minecraft): Boolean {
		val atlas = client.textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS) as? TextureAtlas ?: return false
		// The atlas is only stitched once the first resource reload finishes, and
		// a mod that closes the loading screen early lets ticks run before that.
		// Not ready yet is tried again next tick.
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
			// The texture is part see-through, like the water it stands in for.
			ChunkSectionLayer.TRANSLUCENT,
			Material.Baked(still, false),
			Material.Baked(flowing, false),
			// The face against glass, which the game draws with the flowing
			// texture's layout.
			Material.Baked(flowing, false),
			// Opaque, because the renderer takes this as the whole colour of every
			// vertex, alpha included.
			BlockTintSources.constant(tint, tint),
		)
		return true
	}

	/**
	 * Rebuilds the world's meshes, which is the only way a model swap is seen.
	 *
	 * Called from the tick rather than from the settings, because a colour
	 * picker being dragged would otherwise rebuild every chunk on every frame
	 * of the drag.
	 */
	fun tick(client: Minecraft) {
		// No world, no chunks to rebuild: the title screen can wait for one.
		if (client.level == null) return
		val enabled = module.enabled
		val tint = if (enabled && colorTint.value) ARGB.opaque(tintColor.rgb) else 0
		// Also true after a resource reload, when the sprites are new ones.
		val rebuilt = tint != 0 && buildTinted(client)
		if (enabled == appliedEnabled && tint == appliedTint && !rebuilt) return

		appliedEnabled = enabled
		appliedTint = tint
		rebuild()
	}

	/**
	 * Throws away every compiled chunk, so the next frame rebuilds them with the
	 * model swap applied.
	 *
	 * Through the level extractor, which is the call the video settings and F3+A
	 * make in 26.2: it clears the colour caches as well, and leaves the renderer
	 * to throw its meshes away at the point in the frame it expects to.
	 */
	private fun rebuild() {
		swaps.set(0)
		rebuilds++
		Minecraft.getInstance().levelExtractor.allChanged()
	}

	/**
	 * What lava is actually being drawn as, for `/cryptic debug lava`.
	 *
	 * The model is asked for the way a chunk builder asks for it, so the answer
	 * has been through every mod that has a say in it — this one, and any other
	 * that swaps fluid models, of which SkyHanni is one. If what comes back here
	 * is right and the world is still wrong, the chunks have not been rebuilt;
	 * if what comes back is wrong, something after Cryptic changed it.
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
