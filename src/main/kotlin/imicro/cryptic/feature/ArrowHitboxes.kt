package imicro.cryptic.feature

import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.render.CrypticRenderPipelines
import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.projectile.arrow.AbstractArrow

/** Outlines arrows in flight, whose hitbox is much bigger than their thin model. Ported from Athen. Part of [BowTweaks]. */
object ArrowHitboxes {
	private const val STYLE_OUTLINE = 0
	private const val STYLE_FILL = 1

	@JvmField
	val style = DropdownModuleSetting(
		id = "hitbox_style",
		label = "Style",
		options = listOf("Outline", "Fill", "Fill + Outline"),
		defaultIndex = STYLE_OUTLINE,
	)

	@JvmField
	val color = ColorModuleSetting(
		id = "hitbox_color",
		label = "Outline",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
		visibleIf = { style.selectedIndex != STYLE_FILL },
	)

	@JvmField
	val fillColor = ColorModuleSetting(
		id = "hitbox_fill_color",
		label = "Fill",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
		defaultAlpha = 0x50,
		visibleIf = { style.selectedIndex != STYLE_OUTLINE },
	)

	@JvmField
	val lineWidth = SliderModuleSetting(
		id = "hitbox_line_width",
		label = "Thickness",
		defaultValue = 2.0,
		min = 1.0,
		max = 10.0,
		step = 0.5,
		visibleIf = { style.selectedIndex != STYLE_FILL },
	)

	@JvmField
	val throughWalls = ToggleModuleSetting(
		id = "hitbox_through_walls",
		label = "Phase",
		defaultValue = false,
		description = "Shows arrows through walls.",
	)

	val settings = listOf(style, color, fillColor, lineWidth, throughWalls)

	private val enabled: Boolean get() = BowTweaks.module.enabled && BowTweaks.hitboxes.value

	private var initialized = false

	/** The arrows in the world, found once a tick so drawing them doesn't check every entity every frame. */
	private var arrows: List<AbstractArrow> = emptyList()

	fun initialize() {
		if (initialized) return
		initialized = true

		CrypticRenderPipelines.touch()
		ClientTickEvents.END_CLIENT_TICK.register { client ->
			val level = client.level
			arrows = if (enabled && level != null) level.entitiesForRendering().filterIsInstance<AbstractArrow>() else emptyList()
		}
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
	}

	private fun render(context: LevelRenderContext) {
		if (!enabled) return
		val client = Minecraft.getInstance()

		val outline = style.selectedIndex != STYLE_FILL && color.alpha > 0
		val fill = style.selectedIndex != STYLE_OUTLINE && fillColor.alpha > 0
		if (!outline && !fill) return

		val partialTick = client.deltaTracker.getGameTimeDeltaPartialTick(false)

		for (entity in arrows) {
			if (entity.isRemoved) continue

			// Built at the interpolated position: an arrow crosses several blocks a tick, so its
			// ticked box would trail it.
			val box = entity.type.dimensions.makeBoundingBox(
				Mth.lerp(partialTick.toDouble(), entity.xOld, entity.x),
				Mth.lerp(partialTick.toDouble(), entity.yOld, entity.y),
				Mth.lerp(partialTick.toDouble(), entity.zOld, entity.z),
			)

			WorldRender.drawBox(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				minX = box.minX,
				minY = box.minY,
				minZ = box.minZ,
				maxX = box.maxX,
				maxY = box.maxY,
				maxZ = box.maxZ,
				outlineArgb = color.argb,
				fillArgb = fillColor.argb,
				outline = outline,
				fill = fill,
				phase = throughWalls.value,
				lineWidth = lineWidth.value.toFloat(),
			)
		}
	}
}
