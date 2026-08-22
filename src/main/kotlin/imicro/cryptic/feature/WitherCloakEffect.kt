package imicro.cryptic.feature

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import imicro.cryptic.Cryptic
import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext
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
 * Replaces Hypixel's Creeper Veil charged-creeper overlay with orbiting shields.
 *
 * The behavior is adapted for modern Minecraft from NotEnoughUpdates' original
 * WitherCloakChanger. Detection happens during client ticks, while all live-world
 * values needed for drawing are copied during Fabric's render extraction phase.
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

	@JvmField
	val module = Module(
		id = "wither_cloak_effect",
		name = "Wither Cloak Effect",
		description = "Replaces the Creeper Veil with orbiting shields",
		category = ModuleCategory.VISUAL,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(shieldCount, shieldSpeed, shieldDistance),
	)

	private var initialized = false
	private var cloakActive = false
	private var lastCloakCreeperSeenAt = 0L
	private var lastDeactivatedAt = 0L
	private var ticksUntilScan = 0
	private var renderSnapshot: RenderSnapshot? = null

	fun initialize() {
		if (initialized) return
		initialized = true

		ClientReceiveMessageEvents.GAME.register { message, _ ->
			onGameMessage(message.string)
		}
		ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ ->
			onGameMessage(message.string)
		}
		LevelRenderEvents.END_EXTRACTION.register(::extractRenderState)
		LevelRenderEvents.COLLECT_SUBMITS.register(::submitRenderState)
	}

	fun tick(client: Minecraft) {
		if (!module.enabled) {
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

		// A developer override stands in for Hypixel's veil, so the shields can
		// be looked at without a charged creeper to trigger them.
		if (DebugOverrides.forceWitherCloak) {
			cloakActive = true
			lastCloakCreeperSeenAt = now
			return
		}

		// Walking every loaded entity is only the fallback detection path: chat
		// messages and the creeper render layer both activate the effect the
		// moment they see it. Scanning a few times a second rather than every
		// tick stays well inside the grace periods used below.
		if (ticksUntilScan-- <= 0) {
			ticksUntilScan = SCAN_INTERVAL_TICKS

			var foundCloakCreeper = false
			for (entity in level.entitiesForRendering()) {
				if (entity !is Creeper || !entity.isPowered || !entity.isInvisible) continue
				if (entity.distanceToSqr(player) >= CLOAK_CREEPER_RANGE_SQUARED) continue
				foundCloakCreeper = true
				break
			}

			if (foundCloakCreeper) {
				lastCloakCreeperSeenAt = now
				if (now - lastDeactivatedAt >= VANILLA_OVERLAY_GRACE_PERIOD_MS) {
					cloakActive = true
				}
			}
		}

		if (cloakActive && now - lastCloakCreeperSeenAt > CREEPER_GRACE_PERIOD_MS) {
			deactivate(now)
		}
	}

	/** Called by the creeper power-layer mixin to prevent the vanilla overlay overlapping ours. */
	@JvmStatic
	fun shouldHideVanillaPower(state: CreeperRenderState): Boolean {
		if (!module.enabled || !state.isPowered) return false
		val now = System.currentTimeMillis()
		val looksLikeCloakCreeper =
			(state.isInvisible || state.isInvisibleToPlayer) &&
				state.distanceToCameraSq < CLOAK_CREEPER_FALLBACK_RANGE_SQUARED

		if (cloakActive) {
			lastCloakCreeperSeenAt = now
			return true
		}

		if (looksLikeCloakCreeper) {
			lastCloakCreeperSeenAt = now
			if (now - lastDeactivatedAt >= VANILLA_OVERLAY_GRACE_PERIOD_MS) cloakActive = true
			// Hypixel can keep the marker creeper for a fraction of a second after
			// deactivation. Keep its vanilla layer hidden without reactivating ours.
			return true
		}
		return false
	}

	private fun onGameMessage(text: String) {
		if (!module.enabled) return
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
		if (!module.enabled || !cloakActive) {
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
			val baseAngle = (((System.currentTimeMillis() / 30.0) * snapshot.speed * -0.5) % 360.0) * DEGREES_TO_RADIANS
			val angleStep = (2.0 * PI) / snapshot.count
			for (index in 0 until snapshot.count) {
				drawShield(consumer, pose, baseAngle + angleStep * index, snapshot.distance)
			}
		}
		poseStack.popPose()
	}

	private fun drawShield(consumer: VertexConsumer, pose: PoseStack.Pose, angle: Double, distance: Double) {
		val radialX = sin(angle)
		val radialZ = cos(angle)
		val tangentX = cos(angle)
		val tangentZ = -sin(angle)
		val centerX = radialX * distance
		val centerZ = radialZ * distance
		val halfWidth = SHIELD_WIDTH / 2.0
		val leftX = centerX - tangentX * halfWidth
		val leftZ = centerZ - tangentZ * halfWidth
		val rightX = centerX + tangentX * halfWidth
		val rightZ = centerZ + tangentZ * halfWidth
		vertex(consumer, pose, leftX, 0.0, leftZ, 0f, 1f, radialX, radialZ)
		vertex(consumer, pose, rightX, 0.0, rightZ, 1f, 1f, radialX, radialZ)
		vertex(consumer, pose, rightX, SHIELD_HEIGHT, rightZ, 1f, 0f, radialX, radialZ)
		vertex(consumer, pose, leftX, SHIELD_HEIGHT, leftZ, 0f, 0f, radialX, radialZ)

		// The image must remain visible from both sides while it orbits the camera.
		vertex(consumer, pose, rightX, 0.0, rightZ, 1f, 1f, -radialX, -radialZ)
		vertex(consumer, pose, leftX, 0.0, leftZ, 0f, 1f, -radialX, -radialZ)
		vertex(consumer, pose, leftX, SHIELD_HEIGHT, leftZ, 0f, 0f, -radialX, -radialZ)
		vertex(consumer, pose, rightX, SHIELD_HEIGHT, rightZ, 1f, 0f, -radialX, -radialZ)
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
		if (cloakActive) lastDeactivatedAt = now
		cloakActive = false
		renderSnapshot = null
	}

	private fun reset() {
		cloakActive = false
		lastCloakCreeperSeenAt = 0L
		lastDeactivatedAt = 0L
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
	)

	private val TEXTURE = Cryptic.id("textures/effect/wither_cloak_shield.png")
	private const val CLOAK_CREEPER_RANGE_SQUARED = 7.5 * 7.5
	private const val CLOAK_CREEPER_FALLBACK_RANGE_SQUARED = 16.0 * 16.0
	private const val SCAN_INTERVAL_TICKS = 4
	private const val CREEPER_GRACE_PERIOD_MS = 2_000L
	private const val VANILLA_OVERLAY_GRACE_PERIOD_MS = 300L
	private const val DEGREES_TO_RADIANS = PI / 180.0
	private const val SHIELD_WIDTH = 0.8
	private const val SHIELD_HEIGHT = 2.0
	private const val FULL_BRIGHT_LIGHT = 0x00F000F0
}
