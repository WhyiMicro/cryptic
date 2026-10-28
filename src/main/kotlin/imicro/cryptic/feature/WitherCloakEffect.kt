package imicro.cryptic.feature

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import imicro.cryptic.Cryptic
import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.entity.state.CreeperRenderState
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.util.Mth
import net.minecraft.world.entity.monster.Creeper
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Replaces Hypixel's Creeper Veil charged-creeper overlay with orbiting shields. Only your own veil
 * turns it on, by Hypixel's chat line; the invisible charged creepers elsewhere (the Dwarven
 * Mines' ghosts) are never taken for it. While it's on, only the creeper at you loses its charge
 * layer. The shield texture is NotEnoughUpdates'.
 */
object WitherCloakEffect {
	private val shieldCount = SliderModuleSetting(
		id = "shield_count",
		label = "Shield Count",
		defaultValue = 6.0,
		min = 0.0,
		max = 20.0,
		step = 1.0,
	)

	private val shieldSpeed = SliderModuleSetting(
		id = "shield_speed",
		label = "Shield Speed",
		defaultValue = 2.0,
		min = -20.0,
		max = 20.0,
		step = 1.0,
	)

	private val shieldDistance = SliderModuleSetting(
		id = "shield_distance",
		label = "Shield Distance",
		defaultValue = 1.2,
		min = 0.0,
		max = 3.0,
		step = 0.1,
	)

	/** Each shield's size, width and height together. */
	private val shieldSize = SliderModuleSetting(
		id = "shield_size",
		label = "Shield Size",
		defaultValue = 1.0,
		min = 0.25,
		max = 3.0,
		step = 0.05,
	)

	/** How far above your feet the ring sits, in blocks. */
	private val shieldHeight = SliderModuleSetting(
		id = "shield_height",
		label = "Shield Height",
		defaultValue = 0.0,
		min = -2.0,
		max = 3.0,
		step = 0.1,
	)

	@JvmField
	val dungeonsOnly = ToggleModuleSetting(
		id = "dungeons_only",
		label = "Only in dungeons",
		defaultValue = false,
		description = "Leaves the cloak as Hypixel draws it everywhere but the Catacombs.",
	)

	val needsDungeon: Boolean get() = module.enabled && dungeonsOnly.value

	/**
	 * Whether the shields replace the creeper right now; outside a dungeon (when limited) it
	 * behaves as off.
	 */
	private val active: Boolean
		get() = module.enabled &&
			(!dungeonsOnly.value || DungeonLocation.inDungeon || DebugOverrides.forceWitherCloak)

	@JvmField
	val module = Module(
		id = "wither_cloak_effect",
		name = "Custom Wither Cloak",
		description = "Orbiting shields for the Veil",
		category = ModuleCategory.VISUAL,
		supportsKeybind = false,
		settings = listOf(shieldCount, shieldSpeed, shieldDistance, shieldSize, shieldHeight, dungeonsOnly),
	)

	private var initialized = false
	private var cloakActive = false
	private var lastCloakCreeperSeenAt = 0L
	private var ticksUntilScan = 0
	private var renderSnapshot: RenderSnapshot? = null

	fun initialize() {
		if (initialized) return
		initialized = true

		LevelExtractionEvents.END_EXTRACTION.register(::extractRenderState)
		LevelRenderEvents.COLLECT_SUBMITS.register(::submitRenderState)
	}

	fun tick(client: Minecraft) {
		if (!active) {
			reset()
			return
		}

		val level = client.level
		val player = client.player
		if (level == null || player == null) {
			reset()
			return
		}

		val now = System.currentTimeMillis()

		if (DebugOverrides.forceWitherCloak) {
			cloakActive = true
			lastCloakCreeperSeenAt = now
			return
		}

		// Only keeps an active veil alive while its creeper is still around you; never starts one.
		if (cloakActive && ticksUntilScan-- <= 0) {
			ticksUntilScan = SCAN_INTERVAL_TICKS
			val around = level.entitiesForRendering().any { entity ->
				entity is Creeper && entity.isPowered && entity.isInvisible && entity.distanceToSqr(player) < CLOAK_CREEPER_RANGE_SQUARED
			}
			if (around) lastCloakCreeperSeenAt = now
		}

		if (cloakActive && now - lastCloakCreeperSeenAt > CREEPER_GRACE_PERIOD_MS) {
			deactivate(now)
		}
	}

	/** Hides the charge layer of your veil's creeper only: invisible, charged, and at you. */
	@JvmStatic
	fun shouldHideVanillaPower(state: CreeperRenderState): Boolean {
		if (!active || !cloakActive || !state.isPowered) return false
		val yours = (state.isInvisible || state.isInvisibleToPlayer) && state.distanceToCameraSq < CLOAK_CREEPER_FALLBACK_RANGE_SQUARED
		if (yours) lastCloakCreeperSeenAt = System.currentTimeMillis()
		return yours
	}

	/** Hypixel's veil lines, from the chat packet so another mod hiding them can't hide them from this. */
	@JvmStatic
	fun onSystemChat(message: net.minecraft.network.chat.Component, overlay: Boolean) {
		if (!overlay) onGameMessage(message.string)
	}

	private fun onGameMessage(text: String) {
		if (!active) return
		val normalized = text.trim()

		when {
			normalized.contains("Creeper Veil Activated", ignoreCase = true) -> {
				cloakActive = true
				lastCloakCreeperSeenAt = System.currentTimeMillis()
			}
			normalized.contains("Creeper Veil De-activated", ignoreCase = true) ||
				normalized.contains("NOT ENOUGH VITALITY", ignoreCase = true) -> {
				deactivate(System.currentTimeMillis())
			}
		}
	}

	private fun extractRenderState(context: LevelExtractionContext) {
		if (!active || !cloakActive) {
			renderSnapshot = null
			return
		}

		val player = Minecraft.getInstance().player ?: run {
			renderSnapshot = null
			return
		}
		val partialTick = context.deltaTracker().getGameTimeDeltaPartialTick(true)
		val camera = context.camera().position()
		val x = Mth.lerp(partialTick.toDouble(), player.xo, player.x) - camera.x
		val y = Mth.lerp(partialTick.toDouble(), player.yo, player.y) - camera.y
		val z = Mth.lerp(partialTick.toDouble(), player.zo, player.z) - camera.z

		renderSnapshot = RenderSnapshot(
			x = x,
			y = y,
			z = z,
			count = shieldCount.value.roundToInt().coerceIn(0, 20),
			speed = shieldSpeed.value.coerceIn(-20.0, 20.0),
			distance = shieldDistance.value.coerceIn(0.0, 3.0),
			size = shieldSize.value.coerceIn(0.25, 3.0),
			height = shieldHeight.value.coerceIn(-2.0, 3.0),
		)
	}

	private fun submitRenderState(context: LevelRenderContext) {
		val snapshot = renderSnapshot ?: return
		if (snapshot.count == 0) return

		val poseStack = PoseStack()
		poseStack.pushPose()
		poseStack.translate(snapshot.x, snapshot.y, snapshot.z)
		context.submitNodeCollector().submitCustomGeometry(
			poseStack,
			RenderTypes.entityTranslucentEmissive(TEXTURE),
		) { pose, consumer ->
			// Positive speeds turn clockwise seen from above.
			val seconds = (System.currentTimeMillis() % SPIN_PERIOD_MS) / 1000.0
			val baseAngle = -seconds * snapshot.speed * DEGREES_PER_SECOND_PER_SPEED * DEGREES_TO_RADIANS
			val angleStep = (2.0 * PI) / snapshot.count
			for (index in 0 until snapshot.count) {
				drawShield(consumer, pose, baseAngle + angleStep * index, snapshot.distance, snapshot.size, snapshot.height)
			}
		}
		poseStack.popPose()
	}

	private fun drawShield(consumer: VertexConsumer, pose: PoseStack.Pose, angle: Double, distance: Double, size: Double, lift: Double) {
		val radialX = sin(angle)
		val radialZ = cos(angle)
		val tangentX = cos(angle)
		val tangentZ = -sin(angle)
		val centerX = radialX * distance
		val centerZ = radialZ * distance
		val halfWidth = SHIELD_WIDTH * size / 2.0
		val bottom = lift
		val top = lift + SHIELD_HEIGHT * size
		val leftX = centerX - tangentX * halfWidth
		val leftZ = centerZ - tangentZ * halfWidth
		val rightX = centerX + tangentX * halfWidth
		val rightZ = centerZ + tangentZ * halfWidth
		vertex(consumer, pose, leftX, bottom, leftZ, 0f, 1f, radialX, radialZ)
		vertex(consumer, pose, rightX, bottom, rightZ, 1f, 1f, radialX, radialZ)
		vertex(consumer, pose, rightX, top, rightZ, 1f, 0f, radialX, radialZ)
		vertex(consumer, pose, leftX, top, leftZ, 0f, 0f, radialX, radialZ)

		// Visible from both sides as it orbits.
		vertex(consumer, pose, rightX, bottom, rightZ, 1f, 1f, -radialX, -radialZ)
		vertex(consumer, pose, leftX, bottom, leftZ, 0f, 1f, -radialX, -radialZ)
		vertex(consumer, pose, leftX, top, leftZ, 0f, 0f, -radialX, -radialZ)
		vertex(consumer, pose, rightX, top, rightZ, 1f, 0f, -radialX, -radialZ)
	}

	private fun vertex(
		consumer: VertexConsumer,
		pose: PoseStack.Pose,
		x: Double,
		y: Double,
		z: Double,
		u: Float,
		v: Float,
		normalX: Double,
		normalZ: Double,
	) {
		consumer.addVertex(pose, x.toFloat(), y.toFloat(), z.toFloat())
			.setColor(255, 255, 255, 255)
			.setUv(u, v)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(FULL_BRIGHT_LIGHT)
			.setNormal(pose, normalX.toFloat(), 0f, normalZ.toFloat())
	}

	private fun deactivate(now: Long) {
		cloakActive = false
		renderSnapshot = null
	}

	private fun reset() {
		cloakActive = false
		lastCloakCreeperSeenAt = 0L
		ticksUntilScan = 0
		renderSnapshot = null
	}

	private data class RenderSnapshot(
		val x: Double,
		val y: Double,
		val z: Double,
		val count: Int,
		val speed: Double,
		val distance: Double,
		val size: Double,
		val height: Double,
	)

	private val TEXTURE = Cryptic.id("textures/effect/wither_cloak_shield.png")
	private const val CLOAK_CREEPER_RANGE_SQUARED = 7.5 * 7.5
	private const val CLOAK_CREEPER_FALLBACK_RANGE_SQUARED = 16.0 * 16.0
	private const val SCAN_INTERVAL_TICKS = 4
	private const val CREEPER_GRACE_PERIOD_MS = 2_000L
	private const val DEGREES_TO_RADIANS = PI / 180.0

	private const val DEGREES_PER_SECOND_PER_SPEED = 50.0 / 3.0

	/** The clock is taken modulo this to keep the angle small; every speed turns whole circles in it. */
	private const val SPIN_PERIOD_MS = 21_600_000L
	/** A shield's size in blocks at Shield Size 1. */
	private const val SHIELD_WIDTH = 0.8
	private const val SHIELD_HEIGHT = 2.0
	private const val FULL_BRIGHT_LIGHT = 0x00F000F0
}
