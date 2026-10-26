package imicro.cryptic.feature

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import imicro.cryptic.Cryptic
import imicro.cryptic.mixin.CameraAccessor
import imicro.cryptic.mixin.GameRendererAccessor
import imicro.cryptic.mixin.SkyRendererAccessors
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.CameraType
import net.minecraft.client.CloudStatus
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.network.chat.Component
import org.joml.Matrix3x2f
import org.joml.Vector4f

/**
 * Spirit Leap's teammate views: each corner of the menu shows the world from that teammate's eyes,
 * so you can see where a leap lands before taking it. Inspired by ADM's POV previews.
 *
 * Each view is the world rendered a second time into an image of its own, from the teammate's
 * position and look, then drawn over their quarter of the screen. That happens in two halves of a
 * frame: the menu, drawn in the GUI's extract, asks for the views and places their images; once
 * your own view is rendered and before the GUI is drawn, [renderFeeds] renders them. Only while the
 * menu is open, and only as many views per frame as the setting allows; the rest keep their last
 * picture, which at a hundred frames a second is too fresh to notice.
 */
object LeapPreview {
	private const val CORNERS = 4

	/** Below this the images are too small to be worth drawing. */
	private const val MIN_SIZE = 16

	/** How long after the menu last asked the images are kept before being freed. */
	private const val KEEP_MILLIS = 1_000L

	private val CLEAR = Vector4f(0.08f, 0.08f, 0.08f, 1f)

	/**
	 * True while a view is being rendered. Camera hooks (Smooth teleport, Fake zpew, Zoom) and the
	 * terrain guards read it, so a teammate's view is their plain view.
	 */
	@JvmField
	@Volatile
	var capturing = false

	private val sodium = FabricLoader.getInstance().isModLoaded("sodium")

	private val feeds = arrayOfNulls<RenderTarget>(CORNERS)

	/** Whether each image has been rendered into since it was made; a fresh one holds nothing yet. */
	private val ready = BooleanArray(CORNERS)

	/** When each view was last rendered, in nanoseconds. */
	private val renderedAt = LongArray(CORNERS)

	/** Whether the views draw players, mobs and particles; off, only the world. */
	@JvmStatic
	fun showsEntities(): Boolean = SpiritLeapOverlay.previewEntities.value

	/** Who each corner shows this frame, as the menu asked. */
	private val targets = arrayOfNulls<AbstractClientPlayer>(CORNERS)

	private var feedWidth = 0
	private var feedHeight = 0
	private var next = 0
	private var askedThisFrame = false
	private var lastAskedAt = 0L

	/** One error anywhere in a render turns the views off until the game restarts, rather than crash. */
	private var broken = false

	private val enabled: Boolean get() = SpiritLeapOverlay.preview.value && !broken

	/**
	 * The menu's half: places each teammate's image in their quarter, under the boxes drawn after
	 * this. [names] are the corners' teammates, top left first, null for an empty corner.
	 */
	fun submit(context: GuiGraphicsExtractor, names: List<String?>) {
		targets.fill(null)
		if (!enabled) return
		val client = Minecraft.getInstance()
		val level = client.level ?: return
		val self = client.player ?: return
		val main = client.gameRenderer.mainRenderTarget()
		val scale = SpiritLeapOverlay.previewResolution.value / 100.0
		val width = (main.width / 2 * scale).toInt()
		val height = (main.height / 2 * scale).toInt()
		if (width < MIN_SIZE || height < MIN_SIZE) return

		// Images are only made or freed here: a placed image is drawn at the end of this frame.
		ensureFeeds(width, height)
		askedThisFrame = true
		lastAskedAt = System.currentTimeMillis()

		val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
		val pose = Matrix3x2f(context.pose())
		val halfW = context.guiWidth() / 2
		val halfH = context.guiHeight() / 2
		for (corner in 0 until CORNERS) {
			val name = names.getOrNull(corner) ?: continue
			val player = level.players().firstOrNull { it !== self && it.name.string == name && !it.isRemoved && it.isAlive }
			targets[corner] = player
			if (player == null || !ready[corner]) continue
			val view = feeds[corner]?.colorTextureView ?: continue
			val left = corner % 2 == 0
			val top = corner / 2 == 0
			context.guiRenderState.addGuiElement(
				BlitRenderState(
					// Opaque: a level render leaves the sky with no alpha, which blending would drop.
					RenderPipelines.GUI_OPAQUE_TEXTURED_BACKGROUND,
					TextureSetup.singleTexture(view, sampler),
					pose,
					if (left) 0 else halfW, if (top) 0 else halfH,
					if (left) halfW else context.guiWidth(), if (top) halfH else context.guiHeight(),
					// The image's first row is its bottom.
					0f, 1f, 1f, 0f,
					-1,
					null,
				),
			)
		}
	}

	/** Whether [corner] has a view drawn behind it this frame, after [submit]. */
	fun showing(corner: Int): Boolean = enabled && targets.getOrNull(corner) != null && ready[corner]

	/** The render half, between your own view and the GUI: renders this frame's share of the views. */
	@JvmStatic
	fun renderFeeds(deltaTracker: DeltaTracker) {
		if (capturing || !askedThisFrame) return
		askedThisFrame = false
		if (!enabled) return
		val client = Minecraft.getInstance()
		if (client.level == null || client.player == null) return
		// Each view refreshes at its own rate, so the cost doesn't climb with your frame rate.
		val now = System.nanoTime()
		val interval = (1_000_000_000L / SpiritLeapOverlay.previewRate.value).toLong()
		val wanted = (0 until CORNERS).filter { targets[it] != null && feeds[it] != null && (!ready[it] || now - renderedAt[it] >= interval) }
		if (wanted.isEmpty()) return

		capturing = true
		try {
			val count = SpiritLeapOverlay.previewsPerFrame.value.toInt().coerceIn(1, CORNERS)
			repeat(minOf(count, wanted.size)) {
				val corner = wanted.firstOrNull { it >= next } ?: wanted.first()
				next = corner + 1
				render(corner, targets[corner] ?: return@repeat, deltaTracker)
				renderedAt[corner] = now
			}
		} catch (error: Throwable) {
			broken = true
			Cryptic.LOGGER.error("Spirit Leap teammate views failed and are off until restart", error)
			client.gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §cSpirit Leap's teammate views hit an error and are off until you restart."))
		} finally {
			capturing = false
			// Everything drawn after this frame's level sees your own camera and window again.
			runCatching { restoreOwnView(deltaTracker) }
			EntityCullingSwitch.recull()
		}
	}

	private fun render(corner: Int, target: AbstractClientPlayer, deltaTracker: DeltaTracker) {
		val feed = feeds[corner] ?: return
		val client = Minecraft.getInstance()
		val gameRenderer = client.gameRenderer
		val renderer = gameRenderer as GameRendererAccessor
		val camera = gameRenderer.mainCamera()
		val cameraFields = camera as CameraAccessor
		val window = client.window
		val options = client.options
		val gui = gameRenderer.gameRenderState().guiRenderState

		val ownEntity = camera.entity()
		val ownTarget = gameRenderer.mainRenderTarget()
		val ownWidth = window.width
		val ownHeight = window.height
		val ownCameraType = options.cameraType
		val ownHudHidden = gui.isHudHidden
		val ownFrustum = cameraFields.`cryptic$capturedFrustum`()
		val ownEye = cameraFields.`cryptic$eyeHeight`()
		val ownEyeOld = cameraFields.`cryptic$eyeHeightOld`()
		val stackDepth = modelViewDepth()
		var culling: Boolean? = null
		var finished = false
		try {
			// Set on the camera itself: Minecraft's own setter would also swap post effects.
			camera.setEntity(target)
			options.cameraType = CameraType.FIRST_PERSON
			// No hand or crosshair in someone else's view.
			gui.isHudHidden = true
			// The projection's aspect comes from the window.
			window.width = feed.width
			window.height = feed.height
			cameraFields.`cryptic$setEyeHeight`(target.eyeHeight)
			cameraFields.`cryptic$setEyeHeightOld`(target.eyeHeight)
			renderer.`cryptic$setMainRenderTarget`(feed)
			pointSky(feed)
			val color = feed.colorTexture
			val depth = feed.depthTexture
			if (color != null && depth != null) RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(color, CLEAR, depth, 0.0)

			camera.update(deltaTracker)
			// Where they look now. Easing from the head's previous angle spun the view of players
			// standing still, whose previous angle can lag far behind.
			if (target is net.minecraft.world.entity.LivingEntity) cameraFields.`cryptic$setRotation`(target.yHeadRot, target.xRot)
			// Vanilla rebuilds its whole visibility graph for a new camera; holding the frustum keeps
			// your own, so the view shows the terrain you can see. Sodium culls cheaply from any
			// camera, so it's left to cull from theirs.
			if (!sodium) cameraFields.`cryptic$setCapturedFrustum`(camera.cullFrustum)
			// EntityCulling's verdicts are from your own eyes and would hide the people in view.
			culling = EntityCullingSwitch.off()

			val worldPartialTicks = deltaTracker.getGameTimeDeltaPartialTick(false)
			renderer.`cryptic$extractWindow`()
			renderer.`cryptic$extractOptions`()
			// Clouds cycle their buffers once a frame and can't be drawn twice in one.
			gameRenderer.gameRenderState().optionsRenderState.cloudStatus = CloudStatus.OFF
			renderer.`cryptic$extractCamera`(deltaTracker, worldPartialTicks, camera.getCameraEntityPartialTicks(deltaTracker))
			client.levelExtractor.extract(deltaTracker, camera, worldPartialTicks)
			// Terrain is drawn relative to the camera position in here, which the frame set to yours.
			updateGlobalSettings(deltaTracker)
			gameRenderer.renderLevel(deltaTracker)
			ready[corner] = true
			finished = true
		} finally {
			// A render that threw part way holds what the next frame needs.
			if (!finished) runCatching { unwind(stackDepth) }
			EntityCullingSwitch.restore(culling)
			renderer.`cryptic$setMainRenderTarget`(ownTarget)
			// Also when the render made a new sky renderer, which took the view's image.
			pointSky(ownTarget)
			window.width = ownWidth
			window.height = ownHeight
			options.cameraType = ownCameraType
			gui.isHudHidden = ownHudHidden
			cameraFields.`cryptic$setCapturedFrustum`(ownFrustum)
			cameraFields.`cryptic$setEyeHeight`(ownEye)
			cameraFields.`cryptic$setEyeHeightOld`(ownEyeOld)
			ownEntity?.let(camera::setEntity)
		}
	}

	/**
	 * Puts back what the level render takes without a finally: the model-view matrix it pushed, and
	 * the feature dispatcher's frame, which the next frame would otherwise find in use and crash on.
	 */
	private fun unwind(depth: Int) {
		if (depth >= 0) {
			val stack = RenderSystem.getModelViewStack()
			while (modelViewDepth() > depth) stack.popMatrix()
		}
		val dispatcher = Minecraft.getInstance().gameRenderer.featureRenderDispatcher()
		val frame = dispatcher.javaClass.getDeclaredField("preparedFrame").apply { isAccessible = true }.get(dispatcher) as AutoCloseable
		val context = frame.javaClass.getDeclaredField("context").apply { isAccessible = true }
		if (context.get(frame) == null) return
		runCatching { frame.close() }.onFailure {
			context.set(frame, null)
			frame.javaClass.getDeclaredField("submitNodeStorage").apply { isAccessible = true }.set(frame, null)
		}
	}

	/** JOML keeps a matrix stack's depth in a private field; -1 when it can't be read. */
	private val stackDepthField by lazy { runCatching { org.joml.Matrix4fStack::class.java.getDeclaredField("curr").apply { isAccessible = true } }.getOrNull() }

	private fun modelViewDepth(): Int = runCatching { stackDepthField?.getInt(RenderSystem.getModelViewStack()) ?: -1 }.getOrDefault(-1)

	/** Points the sky renderer, which keeps the target it was made with, at [target]. */
	private fun pointSky(target: RenderTarget) {
		val sky = (Minecraft.getInstance().levelRenderer as SkyRendererAccessors.Level).`cryptic$skyRenderer`() ?: return
		(sky as SkyRendererAccessors.Sky).`cryptic$setRenderTarget`(target)
	}

	private fun restoreOwnView(deltaTracker: DeltaTracker) {
		val client = Minecraft.getInstance()
		if (client.level == null || client.player == null) return
		val gameRenderer = client.gameRenderer
		val renderer = gameRenderer as GameRendererAccessor
		val camera = gameRenderer.mainCamera()
		camera.update(deltaTracker)
		renderer.`cryptic$extractWindow`()
		renderer.`cryptic$extractOptions`()
		renderer.`cryptic$extractCamera`(deltaTracker, deltaTracker.getGameTimeDeltaPartialTick(false), camera.getCameraEntityPartialTicks(deltaTracker))
		updateGlobalSettings(deltaTracker)
	}

	/** What [net.minecraft.client.renderer.GameRenderer.render] sets at the start of a frame, from the current render state. */
	private fun updateGlobalSettings(deltaTracker: DeltaTracker) {
		val client = Minecraft.getInstance()
		val gameRenderer = client.gameRenderer
		val state = gameRenderer.gameRenderState()
		val options = state.optionsRenderState
		(gameRenderer as GameRendererAccessor).`cryptic$globalSettings`().update(
			state.windowRenderState.width,
			state.windowRenderState.height,
			options.glintStrength,
			client.level?.gameTime ?: 0L,
			deltaTracker,
			options.menuBackgroundBlurriness,
			state.levelRenderState.cameraRenderState.pos,
			options.textureFiltering == net.minecraft.client.TextureFilteringMethod.RGSS,
		)
	}

	private fun ensureFeeds(width: Int, height: Int) {
		if (feedWidth == width && feedHeight == height && feeds.all { it != null }) return
		free()
		feedWidth = width
		feedHeight = height
		for (corner in 0 until CORNERS) feeds[corner] = TextureTarget("cryptic:leap_view_$corner", width, height, true, GpuFormat.RGBA8_UNORM)
	}

	private fun free() {
		for (corner in 0 until CORNERS) {
			feeds[corner]?.destroyBuffers()
			feeds[corner] = null
			ready[corner] = false
		}
		feedWidth = 0
		feedHeight = 0
	}

	/** Frees the images once the menu has been closed a moment. On the client thread. */
	fun tick() {
		if (feeds[0] != null && System.currentTimeMillis() - lastAskedAt > KEEP_MILLIS) free()
	}

	/**
	 * EntityCulling, by reflection since it's optional. Its master switch is one public static
	 * field, and its cull task re-checks when asked.
	 */
	private object EntityCullingSwitch {
		private val present = FabricLoader.getInstance().isModLoaded("entityculling")

		private val enabledField by lazy {
			if (!present) null
			else runCatching { Class.forName("dev.tr7zw.entityculling.versionless.EntityCullingVersionlessBase").getField("enabled") }.getOrNull()
		}

		private val baseClass by lazy {
			if (!present) null else runCatching { Class.forName("dev.tr7zw.entityculling.EntityCullingModBase") }.getOrNull()
		}

		/** Turns it off; returns what it was, or null when there's nothing to put back. */
		fun off(): Boolean? {
			val field = enabledField ?: return null
			return runCatching { field.getBoolean(null).also { field.setBoolean(null, false) } }.getOrNull()
		}

		fun restore(was: Boolean?) {
			val field = enabledField ?: return
			if (was != null) runCatching { field.setBoolean(null, was) }
		}

		/** Your own verdicts again: the cull task only re-runs when the camera moves or it's asked. */
		fun recull() {
			val base = baseClass ?: return
			runCatching {
				val instance = base.getField("instance").get(null) ?: return
				val task = base.getField("cullTask").get(instance) ?: return
				task.javaClass.getField("requestCull").setBoolean(task, true)
			}
		}
	}
}
