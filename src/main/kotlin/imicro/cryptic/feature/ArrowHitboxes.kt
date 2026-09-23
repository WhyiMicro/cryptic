package imicro.cryptic.feature

import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.render.CrypticRenderPipelines
import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.projectile.arrow.AbstractArrow

/**
 * Outlines arrows in flight.
 *
 * Ported from Athen (BSD 3-Clause, Copyright (c) 2025-2026 Starred). An arrow's
 * model is a thin cross a couple of pixels across and its hitbox is not — which
 * is the whole reason to draw one, whether you are lining a shot up on the
 * Floor 7 device or working out why one went through something.
 */
object ArrowHitboxes {
	@JvmField
	val color = ColorModuleSetting(
		id = "color",
		label = "Outline",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
	)

	@JvmField
	val fillColor = ColorModuleSetting(
		id = "fill_color",
		label = "Fill",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
		defaultAlpha = 0,
		description = "Off at zero alpha, which is how Athen draws it.",
	)

	@JvmField
	val lineWidth = SliderModuleSetting(
		id = "line_width",
		label = "Thickness",
		defaultValue = 2.0,
		min = 1.0,
		max = 10.0,
		step = 0.5,
	)

	@JvmField
	val throughWalls = ToggleModuleSetting(
		id = "through_walls",
		label = "Phase",
		defaultValue = false,
		description = "Shows arrows through walls.",
	)

	@JvmField
	val module = Module(
		id = "arrow_hitboxes",
		name = "Arrow Hitboxes",
		description = "Outlines arrows in flight",
		category = ModuleCategory.VISUAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(color, fillColor, lineWidth, throughWalls),
	)

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		CrypticRenderPipelines.touch()
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
	}

	private fun render(context: LevelRenderContext) {
		if (!module.enabled) return
		val client = Minecraft.getInstance()
		val level = client.level ?: return

		val outline = color.alpha > 0
		val fill = fillColor.alpha > 0
		if (!outline && !fill) return

		val partialTick = client.deltaTracker.getGameTimeDeltaPartialTick(false)

		for (entity in level.entitiesForRendering()) {
			if (entity !is AbstractArrow) continue

			// Built around the interpolated position rather than taken from the
			// entity outright: an arrow crosses several blocks in a tick, so its
			// ticked box would trail the arrow being drawn inside it by most of
			// its own length.
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
