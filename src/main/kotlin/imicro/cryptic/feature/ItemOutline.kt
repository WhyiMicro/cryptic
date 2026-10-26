package imicro.cryptic.feature

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.resource.GraphicsResourceAllocator
import imicro.cryptic.Cryptic
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ModuleSetting
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.LevelTargetBundle
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Rarity
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.world.item.ItemDisplayContext

/**
 * Effects on the item in your hand: an outline, an overlay on the item itself, sparkles, and
 * particles, each in its own colour (the item's rarity, one colour, or a rainbow). Items on the
 * ground can be outlined too.
 *
 * The hand is drawn after depth is cleared, so the depth buffer then holds exactly the held items;
 * the `item_outline` post effect grows that shape into the outline and paints the overlay on it.
 * Dropped items use the entity outline pass, so they look like Better Glow.
 */
object ItemOutline {
	private const val COLOR_RARITY = 0
	private const val COLOR_CUSTOM = 1
	private const val COLOR_RAINBOW = 2
	private val COLOR_MODES = listOf("Item rarity", "One colour", "Rainbow")

	/** A colour picked three ways, for one of the effects. [prefix] keeps each effect's ids apart. */
	private class ColorChoice(prefix: String, visible: () -> Boolean, defaultMode: Int = COLOR_RARITY) {
		val mode = DropdownModuleSetting(
			id = "${prefix}color_mode",
			label = "Colour",
			options = COLOR_MODES,
			defaultIndex = defaultMode,
			description = "Item rarity uses the SkyBlock rarity in the lore (Legendary is gold, Mythic pink), or the vanilla rarity elsewhere.",
			visibleIf = visible,
		)
		val color = ColorModuleSetting(
			id = "${prefix}color",
			label = "Custom colour",
			defaultRgb = 0xFFFFFF,
			visibleIf = { visible() && mode.selectedIndex == COLOR_CUSTOM },
		)
		val rainbowSpeed = SliderModuleSetting(
			id = "${prefix}rainbow_speed",
			label = "Rainbow speed",
			defaultValue = 1.0, min = 0.1, max = 5.0, step = 0.1,
			visibleIf = { visible() && mode.selectedIndex == COLOR_RAINBOW },
		)

		val settings: Array<ModuleSetting> get() = arrayOf(mode, color, rainbowSpeed)

		fun resolve(stack: ItemStack): Int = when (mode.selectedIndex) {
			COLOR_CUSTOM -> color.rgb
			COLOR_RAINBOW -> java.awt.Color.HSBtoRGB(
				(System.currentTimeMillis() % 100_000L) / 1000f * rainbowSpeed.value.toFloat() * 0.25f, 0.8f, 1f,
			) and 0xFFFFFF
			else -> rarityColor(stack) ?: vanillaColor(stack)
		}
	}

	private val heldSection = SectionModuleSetting("held_section", "Held item")

	// ---- Outline ----

	@JvmField
	val held = ToggleModuleSetting(
		id = "held",
		label = "Outline",
		defaultValue = true,
		description = "Outlines the item in your hand in first person.",
	)

	private const val STYLE_SHARP = 0
	private const val STYLE_GLOW = 1
	private const val STYLE_SMOOTH = 2
	private const val STYLE_SQUARE = 3

	@JvmField
	val style = DropdownModuleSetting(
		id = "style",
		label = "Style",
		options = listOf("Sharp", "Glow", "Smooth", "Square"),
		description = "Sharp follows the item's own pixels at any size; Glow fades out over the width; Smooth rounds the corners; Square is a hard band.",
		visibleIf = { held.value },
	)

	@JvmField
	val width = SliderModuleSetting(
		id = "width",
		label = "Width",
		defaultValue = 2.0, min = 1.0, max = 16.0, step = 1.0, unit = "px",
		visibleIf = { held.value && style.selectedIndex != STYLE_SHARP },
	)

	@JvmField
	val sharpWidth = SliderModuleSetting(
		id = "sharp_width",
		label = "Width",
		defaultValue = 0.35, min = 0.1, max = 2.0, step = 0.05,
		description = "In the item's own pixels: 1 is as wide as one pixel of its texture.",
		visibleIf = { held.value && style.selectedIndex == STYLE_SHARP },
	)

	@JvmField
	val outlineGlow = ToggleModuleSetting(
		id = "outline_glow",
		label = "Glow",
		defaultValue = false,
		description = "A soft glow in the outline's colour, fading out around it.",
		visibleIf = { held.value && style.selectedIndex != STYLE_GLOW },
	)

	@JvmField
	val glowStrength = SliderModuleSetting(
		id = "glow_strength",
		label = "Glow strength",
		defaultValue = 60.0, min = 10.0, max = 100.0, step = 5.0, unit = "%",
		visibleIf = { held.value && style.selectedIndex != STYLE_GLOW && outlineGlow.value },
	)

	@JvmField
	val glowSize = SliderModuleSetting(
		id = "glow_size",
		label = "Glow size",
		defaultValue = 6.0, min = 2.0, max = 16.0, step = 1.0, unit = "px",
		visibleIf = { held.value && style.selectedIndex != STYLE_GLOW && outlineGlow.value },
	)

	@JvmField
	val opacity = SliderModuleSetting(
		id = "opacity",
		label = "Opacity",
		defaultValue = 100.0, min = 10.0, max = 100.0, step = 5.0, unit = "%",
		visibleIf = { held.value },
	)

	/** Keeps the ids the single colour setting had, so a setup carries over. */
	private val outlineColor = ColorChoice("", { held.value || dropped.value })

	// ---- Overlay ----

	@JvmField
	val overlay = ToggleModuleSetting(
		id = "overlay",
		label = "Overlay",
		defaultValue = false,
		description = "An effect painted over the item itself, keeping its shading.",
	)

	private val OVERLAY_EFFECTS = listOf("Tint", "Pulse", "Shimmer", "Glow dots", "Waves", "Enchantment glint")

	@JvmField
	val overlayEffect = DropdownModuleSetting(
		id = "overlay_effect",
		label = "Effect",
		options = OVERLAY_EFFECTS,
		description = "Tint colours it; Pulse breathes; Shimmer sweeps bands of light across; Glow dots drift slowly over it; Waves roll along it; Enchantment glint is Minecraft's own, in your colour and speed.",
		visibleIf = { overlay.value },
	)

	@JvmField
	val overlayOpacity = SliderModuleSetting(
		id = "overlay_opacity",
		label = "Strength",
		defaultValue = 40.0, min = 5.0, max = 100.0, step = 5.0, unit = "%",
		visibleIf = { overlay.value },
	)

	@JvmField
	val overlaySpeed = SliderModuleSetting(
		id = "overlay_speed",
		label = "Speed",
		defaultValue = 1.0, min = 0.2, max = 3.0, step = 0.1,
		visibleIf = { overlay.value && overlayEffect.selectedIndex != 0 },
	)

	private val overlayColor = ColorChoice("overlay_", { overlay.value })

	@JvmField
	val overlayEnchantedOnly = ToggleModuleSetting(
		id = "overlay_enchanted_only",
		label = "Only enchanted items",
		defaultValue = false,
		description = "Puts the overlay only on items that have the enchantment glint. Wherever the overlay goes, it takes the glint's place.",
		visibleIf = { overlay.value },
	)

	// ---- Sparkles ----

	@JvmField
	val sparkles = ToggleModuleSetting(
		id = "sparkles",
		label = "Sparkles",
		defaultValue = false,
		description = "Little stars twinkling on and around the item.",
	)

	@JvmField
	val sparkleAmount = SliderModuleSetting(
		id = "sparkle_amount",
		label = "Amount",
		defaultValue = 30.0, min = 5.0, max = 100.0, step = 5.0, unit = "%",
		visibleIf = { sparkles.value },
	)

	@JvmField
	val sparkleSize = SliderModuleSetting(
		id = "sparkle_size",
		label = "Size",
		defaultValue = 3.0, min = 1.0, max = 8.0, step = 1.0, unit = "px",
		visibleIf = { sparkles.value },
	)

	@JvmField
	val sparkleSpeed = SliderModuleSetting(
		id = "sparkle_speed",
		label = "Speed",
		defaultValue = 1.0, min = 0.2, max = 3.0, step = 0.1,
		visibleIf = { sparkles.value },
	)

	private val sparkleColor = ColorChoice("sparkle_", { sparkles.value })

	// ---- Particles ----

	@JvmField
	val particles = ToggleModuleSetting(
		id = "particles",
		label = "Particles",
		defaultValue = false,
		description = "Particles drifting off the held item, seen in first and third person.",
	)

	private val PARTICLE_TYPES = listOf("Dust", "Enchant", "End rod", "Glow", "Spark", "Flame")
	private const val PARTICLE_DUST = 0

	@JvmField
	val particleType = DropdownModuleSetting(
		id = "particle_type",
		label = "Particle",
		options = PARTICLE_TYPES,
		description = "Dust takes the colour below; the others are Minecraft's own.",
		visibleIf = { particles.value },
	)

	@JvmField
	val particleRate = SliderModuleSetting(
		id = "particle_rate",
		label = "Per second",
		defaultValue = 8.0, min = 1.0, max = 40.0, step = 1.0,
		visibleIf = { particles.value },
	)

	@JvmField
	val particleOthers = ToggleModuleSetting(
		id = "particle_others",
		label = "Other players' items",
		defaultValue = false,
		description = "Gives other players' held items particles as well.",
		visibleIf = { particles.value },
	)

	private val particleColor = ColorChoice("particle_", { particles.value && particleType.selectedIndex == PARTICLE_DUST })

	@JvmField
	val dropped = ToggleModuleSetting(
		id = "dropped",
		label = "Dropped items",
		defaultValue = false,
		description = "Outlines items lying on the ground in the outline's colour, drawn like Better Glow.",
	)

	@JvmField
	val module = Module(
		id = "item_outline",
		name = "Item Outline",
		description = "Outlines the item you hold",
		category = ModuleCategory.VISUAL,
		supportsKeybind = false,
		settings = listOf(
			heldSection,
			held.withMore(style, width, sharpWidth, opacity, outlineGlow, glowStrength, glowSize, *outlineColor.settings),
			overlay.withMore(overlayEffect, overlayOpacity, overlaySpeed, *overlayColor.settings, overlayEnchantedOnly),
			sparkles.withMore(sparkleAmount, sparkleSize, sparkleSpeed, *sparkleColor.settings),
			particles.withMore(particleType, particleRate, particleOthers, *particleColor.settings),
			dropped,
		),
	)

	private val CHAIN: Identifier = Cryptic.id("item_outline")
	private val PARAMS: Identifier = Cryptic.id("textures/effect/item_outline_params.png")
	private const val PARAMS_WIDTH = 6

	private var params: DynamicTexture? = null

	/** One failure turns the held effects off until a restart, rather than every frame throwing. */
	private var broken = false

	/** How far past the item sparkles may sit. */
	private const val SPARKLE_REACH = 6

	/**
	 * Right after the hand is drawn, while depth still holds only the held items. The params
	 * texture, one pixel each:
	 * 0. the outline's colour and opacity;
	 * 1. the reach (red), style (green), sparkle amount (blue) and outline width (alpha);
	 * 2. the overlay's strength (red) and effect (green), sparkle size (blue) and speed (alpha);
	 * 3. the overlay's colour, with its speed in alpha;
	 * 4. the sparkles' colour;
	 * 5. the outline glow's size (red) and strength (green).
	 */
	@JvmStatic
	fun afterHand(target: RenderTarget, allocator: GraphicsResourceAllocator, hudFov: Float) {
		this.hudFov = hudFov
		// The hand is done; nothing else drawn in this context later in the frame is the held item.
		heldContext = null
		sharpContext = null
		if (!module.enabled || broken || !(held.value || overlay.value || sparkles.value)) return
		val client = Minecraft.getInstance()
		val player = client.player ?: return
		val stack = player.mainHandItem
		// The arm is drawn with an empty main hand, and would be outlined with the item.
		if (!eligible(stack)) return
		try {
			val texture = ensureParams()
			val pixels = texture.pixels ?: return
			fun byte(value: Double) = value.toInt().coerceIn(0, 255)
			// Sharp is drawn with the item (see [sharpOffsets]); the chain draws the other styles, and
			// the glow, which for Sharp grows from the outline the item drew.
			val outlineWidth = if (held.value && style.selectedIndex != STYLE_SHARP) width.value.toInt().coerceIn(1, 16) else 0
			val glowing = held.value && outlineGlow.value && style.selectedIndex != STYLE_GLOW
			val glowPixels = if (glowing) glowSize.value.toInt().coerceIn(2, 16) else 0
			val reach = maxOf(outlineWidth + glowPixels, if (sparkles.value) outlineWidth + SPARKLE_REACH else 0)
			// The shader's styles: hard square band, glow, smooth.
			val shaderStyle = when (style.selectedIndex) {
				STYLE_GLOW -> 1
				STYLE_SMOOTH -> 2
				else -> 0
			}
			val outlineAlpha = if (held.value) byte(opacity.value / 100.0 * 255) else 0
			pixels.setPixel(0, 0, (outlineAlpha shl 24) or outlineColor.resolve(stack))
			val sparkleAmountByte = if (sparkles.value) byte(sparkleAmount.value / 100.0 * 255) else 0
			pixels.setPixel(1, 0, (outlineWidth shl 24) or (reach shl 16) or (shaderStyle shl 8) or sparkleAmountByte)
			pixels.setPixel(5, 0, (0xFF shl 24) or (glowPixels shl 16) or (byte(glowStrength.value / 100.0 * 255) shl 8))
			val overlayByte = if (overlay.value) byte(overlayOpacity.value / 100.0 * 255) else 0
			pixels.setPixel(
				2, 0,
				(byte(sparkleSpeed.value * 50) shl 24) or (overlayByte shl 16) or (overlayEffect.selectedIndex shl 8) or byte(sparkleSize.value),
			)
			pixels.setPixel(3, 0, (byte(overlaySpeed.value * 50) shl 24) or overlayColor.resolve(stack))
			pixels.setPixel(4, 0, (0xFF shl 24) or sparkleColor.resolve(stack))
			texture.upload()
			// The overlay is drawn with the item itself (see [overlayType]); the chain does the rest.
			if (!held.value && !sparkles.value) return
			val chain = client.shaderManager.getPostChain(CHAIN, LevelTargetBundle.MAIN_TARGETS) ?: return
			chain.process(target, allocator)
		} catch (error: Exception) {
			broken = true
			Cryptic.LOGGER.error("Item Outline could not draw; the held effects are off until a restart", error)
		}
	}

	// ---- Where the first-person item is --------------------------------------------------------

	/** The held item's centre as last drawn, from the camera, before the hand's field of view. */
	private var handPoint: Vector3f? = null
	private var handPointAt = 0L
	private var hudFov = 70f

	/**
	 * The first-person item's pose as it's drawn, swing, bob and equip included. Its centre sits at
	 * the item transform's offset, about this far along the arm.
	 */
	@JvmStatic
	fun captureHandPose(pose: Matrix4f, rightHand: Boolean, stack: ItemStack, context: ItemDisplayContext) {
		heldContext = if (module.enabled && overlay.value && eligible(stack) && (!overlayEnchantedOnly.value || stack.hasFoil())) context else null
		sharpContext = if (module.enabled && held.value && style.selectedIndex == STYLE_SHARP && eligible(stack)) context else null
		if (sharpContext != null) {
			val alpha = (opacity.value / 100.0 * 255).toInt().coerceIn(0, 255)
			sharpArgb = (alpha shl 24) or outlineColor.resolve(stack)
		}
		handPoint = pose.transformPosition(Vector3f(if (rightHand) 0.07f else -0.07f, 0.2f, 0.07f), Vector3f())
		handPointAt = System.currentTimeMillis()
	}


	// ---- The overlay, drawn like the enchantment glint ------------------------------------------

	/** The display context the overlaid item is drawn in this frame, or null for none. */
	private var heldContext: ItemDisplayContext? = null

	private fun ensureParams(): DynamicTexture = params ?: DynamicTexture({ "Cryptic item outline" }, NativeImage(PARAMS_WIDTH, 1, true)).also {
		Minecraft.getInstance().textureManager.register(PARAMS, it)
		params = it
	}

	private fun overlayPipeline(name: String, blend: BlendFunction): RenderPipeline =
		RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
				.withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
				.withBindGroupLayout(BindGroupLayouts.FOG)
				.withLocation(Cryptic.id("pipeline/$name"))
				.withVertexShader(Cryptic.id("core/item_overlay"))
				.withFragmentShader(Cryptic.id("core/item_overlay"))
				.withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1)
				.withCull(false)
				.withColorTargetState(ColorTargetState(blend))
				.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
				.withPrimitiveTopology(PrimitiveTopology.QUADS)
				.withDepthStencilState(DepthStencilState(CompareOp.EQUAL, false))
				.build(),
		)

	/** Painted over the item (tint, pulse, waves), or added as light (shimmer, glow dots). */
	private val OVERLAY_PIPELINE: RenderPipeline by lazy { overlayPipeline("item_overlay", BlendFunction.TRANSLUCENT) }
	private val GLOW_PIPELINE: RenderPipeline by lazy { overlayPipeline("item_overlay_glow", BlendFunction.LIGHTNING) }

	private val OVERLAY_TYPE: RenderType by lazy {
		RenderType.create("cryptic_item_overlay", overlaySetup(OVERLAY_PIPELINE))
	}

	// ---- The Sharp outline, drawn with the item ------------------------------------------------

	/** The display context the sharply outlined item is drawn in this frame, or null for none. */
	private var sharpContext: ItemDisplayContext? = null
	private var sharpArgb = -1

	/**
	 * The item's shape in the outline colour, drawn only where nothing nearer already is, so the
	 * item covers it and only the rim around it shows. It writes depth, so overlapping copies don't
	 * stack their colour.
	 */
	private val SILHOUETTE_PIPELINE: RenderPipeline by lazy {
		RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
				.withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
				.withBindGroupLayout(BindGroupLayouts.FOG)
				.withLocation(Cryptic.id("pipeline/item_silhouette"))
				.withVertexShader(Cryptic.id("core/item_silhouette"))
				.withFragmentShader(Cryptic.id("core/item_silhouette"))
				.withBindGroupLayout(BindGroupLayouts.SAMPLER0)
				.withCull(false)
				.withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
				.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
				.withPrimitiveTopology(PrimitiveTopology.QUADS)
				.withDepthStencilState(DepthStencilState(CompareOp.GREATER_THAN, true))
				.build(),
		)
	}

	private val silhouetteTypes = HashMap<Identifier, RenderType>()

	/** The silhouette for quads on [atlas], whose alpha cuts the item's shape out. */
	@JvmStatic
	fun silhouetteType(atlas: Identifier): RenderType = silhouetteTypes.getOrPut(atlas) {
		RenderType.create("cryptic_item_silhouette", RenderSetup.builder(SILHOUETTE_PIPELINE).withTexture("Sampler0", atlas).createRenderSetup())
	}

	@JvmStatic
	fun drawsSharpOutline(context: ItemDisplayContext): Boolean = sharpContext == context && !broken

	@JvmStatic
	fun sharpColor(): Int = sharpArgb

	/**
	 * Where to draw the item's shape again for the Sharp outline: shifted on the item's own grid in
	 * eight directions, in rings half an item pixel apart so wide outlines have no gaps. They're
	 * pushed back along the view by more than the item is thick, so the item's own edges stay in
	 * front of them and the outline only shows around the item, never over its sides.
	 */
	private const val PUSH_BACK = 1.08f

	@JvmStatic
	fun sharpOffsets(pose: Matrix4f): List<Matrix4f> {
		val pixels = sharpWidth.value.toFloat()
		val rings = maxOf(1, Math.ceil(pixels / 0.5).toInt())
		val result = ArrayList<Matrix4f>(rings * 8)
		val pushed = Matrix4f().scaling(PUSH_BACK).mul(pose)
		for (ring in 1..rings) {
			val d = pixels * ring / rings / 16f
			for (dx in -1..1) for (dy in -1..1) {
				if (dx == 0 && dy == 0) continue
				result += Matrix4f(pushed).translate(dx * d, dy * d, 0f)
			}
		}
		return result
	}

	private val GLOW_TYPE: RenderType by lazy {
		RenderType.create("cryptic_item_overlay_glow", overlaySetup(GLOW_PIPELINE))
	}

	/** The params, and the vanilla glint's texture for the Enchantment glint effect. */
	private fun overlaySetup(pipeline: RenderPipeline): RenderSetup = RenderSetup.builder(pipeline)
		.withTexture("Sampler0", PARAMS)
		.withTexture("Sampler1", net.minecraft.client.renderer.feature.ItemFeatureRenderer.ENCHANTED_GLINT_ITEM)
		.createRenderSetup()

	private const val EFFECT_SHIMMER = 2
	private const val EFFECT_GLOW_DOTS = 3
	private const val EFFECT_GLINT = 5

	/** Registers the overlay's pipelines with the others at start-up. */
	fun touch() {
		OVERLAY_PIPELINE
		GLOW_PIPELINE
		SILHOUETTE_PIPELINE
	}

	/** The render type to draw [context]'s item again with, as the glint is, or null. */
	@JvmStatic
	fun overlayType(context: ItemDisplayContext): RenderType? {
		if (heldContext != context || broken) return null
		ensureParams()
		val effect = overlayEffect.selectedIndex
		return if (effect == EFFECT_SHIMMER || effect == EFFECT_GLOW_DOTS || effect == EFFECT_GLINT) GLOW_TYPE else OVERLAY_TYPE
	}

	/**
	 * With the overlay on, the vanilla glint never shows in first person, not just on the item the
	 * overlay is drawn on: for a frame after a swap the hand draws a stack that isn't the held one
	 * yet, and the glint would flash.
	 */
	@JvmStatic
	fun hidesGlint(context: ItemDisplayContext): Boolean =
		heldContext == context || (module.enabled && overlay.value && !broken && context.firstPerson())


	/**
	 * Where the held item appears, as a point in the world a particle can be put at. The hand is
	 * drawn with its own field of view, so the point is moved to sit at the same place on screen
	 * through the world's.
	 */
	private fun firstPersonHand(client: Minecraft): Vec3? {
		val point = handPoint ?: return null
		if (System.currentTimeMillis() - handPointAt > 250) return null
		val camera = client.gameRenderer.mainCamera()
		val left = camera.leftVector()
		val up = camera.upVector()
		val forward = camera.forwardVector()
		val k = (Math.tan(Math.toRadians(camera.fov.toDouble() / 2)) / Math.tan(Math.toRadians(hudFov.toDouble() / 2))).toFloat()
		val a = point.dot(Vector3f(left)) * k
		val b = point.dot(Vector3f(up)) * k
		val c = point.dot(Vector3f(forward))
		val offset = Vector3f(left).mul(a).add(Vector3f(up).mul(b)).add(Vector3f(forward).mul(c))
		return camera.position().add(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble())
	}

	// ---- Particles ----------------------------------------------------------------------------

	private val random = java.util.Random()
	private val owed = HashMap<Int, Double>()

	fun tick(client: Minecraft) {
		if (!module.enabled || !particles.value) {
			owed.clear()
			return
		}
		val level = client.level ?: return
		val self = client.player ?: return
		val perTick = particleRate.value / 20.0
		for (player in level.players()) {
			if (player !== self && (!particleOthers.value || player.distanceToSqr(self) > 48.0 * 48.0)) continue
			if (player.isInvisible) continue
			val stack = player.mainHandItem
			if (!eligible(stack)) continue
			val due = (owed[player.id] ?: 0.0) + perTick
			val count = due.toInt()
			owed[player.id] = due - count
			if (count == 0) continue
			val firstPerson = player === self && client.options.cameraType.isFirstPerson
			val at = (if (firstPerson) firstPersonHand(client) else handOf(player)) ?: continue
			val rgb = particleColor.resolve(stack)
			repeat(count) { spawn(level, at, rgb, firstPerson) }
		}
		owed.keys.retainAll(level.players().map { it.id }.toSet())
	}

	/** Roughly where a player's main hand holds its item, from the body's facing. */
	private fun handOf(player: Player): Vec3 {
		val yaw = Math.toRadians(player.yBodyRot.toDouble())
		val side = if (player.mainArm == HumanoidArm.RIGHT) 1.0 else -1.0
		val forwardX = -Math.sin(yaw)
		val forwardZ = Math.cos(yaw)
		val rightX = -Math.cos(yaw) * side
		val rightZ = -Math.sin(yaw) * side
		val height = if (player.isCrouching) 0.55 else 0.75
		return Vec3(player.x + rightX * 0.38 + forwardX * 0.3, player.y + height, player.z + rightZ * 0.38 + forwardZ * 0.3)
	}

	/**
	 * First-person particles sit under a metre from your eyes, so they're smaller. The hand is drawn
	 * over the world, so they go in a ring around the item, across the view, where they can be seen.
	 */
	private fun spawn(level: ClientLevel, centre: Vec3, rgb: Int, firstPerson: Boolean) {
		val at = if (firstPerson) aroundOnScreen(centre) else centre
		val range = if (firstPerson) 0.04 else 0.25
		fun spread() = (random.nextDouble() - 0.5) * range
		val options: ParticleOptions = when (particleType.selectedIndex) {
			PARTICLE_DUST -> DustParticleOptions(rgb, if (firstPerson) 0.25f else 0.6f)
			1 -> ParticleTypes.ENCHANT
			2 -> ParticleTypes.END_ROD
			3 -> ParticleTypes.GLOW
			4 -> ParticleTypes.ELECTRIC_SPARK
			else -> ParticleTypes.SMALL_FLAME
		}
		level.addParticle(
			options,
			at.x + spread(), at.y + spread(), at.z + spread(),
			(random.nextDouble() - 0.5) * 0.02, 0.01 + random.nextDouble() * 0.02, (random.nextDouble() - 0.5) * 0.02,
		)
	}

	private fun aroundOnScreen(centre: Vec3): Vec3 {
		val camera = Minecraft.getInstance().gameRenderer.mainCamera()
		val angle = random.nextDouble() * Math.PI * 2
		val radius = 0.22 + random.nextDouble() * 0.16
		val left = camera.leftVector()
		val up = camera.upVector()
		val x = Math.cos(angle) * radius
		val y = Math.sin(angle) * radius
		return centre.add(left.x() * x + up.x() * y, left.y() * x + up.y() * y, left.z() * x + up.z() * y)
	}

	// ---- Items --------------------------------------------------------------------------------

	/** Dropped items' colour in the entity outline pass, or none. */
	@JvmStatic
	fun outlineColorFor(entity: Entity): Int {
		if (!module.enabled || !dropped.value || entity !is ItemEntity || !eligible(entity.item)) return EntityRenderState.NO_OUTLINE
		return outlineColor.resolve(entity.item) or (0xFF shl 24)
	}

	/** Whether [stack] gets the effects at all. */
	private fun eligible(stack: ItemStack): Boolean = !stack.isEmpty

	private const val COMMON = 0xFFFFFF

	/** SkyBlock's rarities and their colours, longest names first so "VERY SPECIAL" wins. */
	private val RARITIES = listOf(
		"VERY SPECIAL" to 0xFF5555,
		"SPECIAL" to 0xFF5555,
		"ULTIMATE" to 0xAA0000,
		"ADMIN" to 0xAA0000,
		"DIVINE" to 0x55FFFF,
		"MYTHIC" to 0xFF55FF,
		"LEGENDARY" to 0xFFAA00,
		"EPIC" to 0xAA00AA,
		"UNCOMMON" to 0x55FF55,
		"RARE" to 0x5555FF,
		"COMMON" to COMMON,
	)

	private val RARITY_WORD = Regex("""\b(${RARITIES.joinToString("|") { it.first }})\b""")
	private val FORMATTING = Regex("§.")

	/** The rarity line is the lore's last, after any blank lines; a few lines up covers mods' additions. */
	private fun rarityColor(stack: ItemStack): Int? {
		val lines = stack.get(DataComponents.LORE)?.lines() ?: return null
		for (line in lines.asReversed().take(4)) {
			val text = line.string.replace(FORMATTING, "").trim()
			if (text.isEmpty() || text != text.uppercase()) continue
			val word = RARITY_WORD.find(text)?.groupValues?.get(1) ?: continue
			return RARITIES.first { it.first == word }.second
		}
		return null
	}

	private fun vanillaColor(stack: ItemStack): Int = when (stack.rarity) {
		Rarity.UNCOMMON -> 0xFFFF55
		Rarity.RARE -> 0x55FFFF
		Rarity.EPIC -> 0xFF55FF
		else -> COMMON
	}
}
