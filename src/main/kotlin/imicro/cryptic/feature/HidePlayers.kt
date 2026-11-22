package imicro.cryptic.feature

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import imicro.cryptic.Cryptic
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.duck.EntityRenderStateHolder
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.rendertype.LayeringTransform
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.Identifier
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.`object`.skull.SkullModel
import net.minecraft.world.entity.Avatar
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.player.PlayerModel
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import com.mojang.blaze3d.vertex.PoseStack
import imicro.cryptic.mixin.ModelPartAccessor
import org.joml.Matrix4f
import org.joml.Vector3f
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player

/**
 * Stops drawing other players near you (still there and still hittable). Ported from Odin. Only
 * real players: Hypixel's NPCs are player entities with version-two uuids; real accounts are
 * version four.
 *
 * Head mode draws players with back faces culled (the idea behind Patcher's back-face culling),
 * so the part your camera is inside, usually a head, isn't drawn from within. Outer skin layers
 * (3D Skin Layers' voxels too) have faces pointing inwards as well, so the layer of the part the
 * camera is inside is left out. Head mode also takes NPCs, since it only changes the view from
 * inside.
 */
object HidePlayers {
	private const val REAL_PLAYER_UUID_VERSION = 4
	private const val MODE_HEAD = 1

	/** How far past a part's box the camera still counts as inside, in model pixels: 3D voxels stick out. */
	private const val PART_MARGIN = 1.5f

	@JvmField
	val mode = DropdownModuleSetting(
		id = "mode",
		label = "Mode",
		options = listOf("Whole body", "Head"),
		description = "Whole body hides players near you. Head only hides the part of a player you are inside.",
	)

	@JvmField
	val onlyInDungeons = ToggleModuleSetting(
		id = "only_in_dungeons",
		label = "Only in dungeons",
		defaultValue = true,
		description = "Only hides players inside a dungeon.",
	)

	@JvmField
	val hideAll = ToggleModuleSetting(
		id = "hide_all",
		label = "Hide all",
		description = "Hides every player at any distance, rather than only the ones crowding you.",
		visibleIf = { mode.selectedIndex != MODE_HEAD },
	)

	@JvmField
	val distance = SliderModuleSetting(
		id = "distance",
		label = "Distance",
		defaultValue = 3.0,
		min = 0.0,
		max = 32.0,
		step = 0.5,
		description = "How close a player has to be before they are hidden, in blocks.",
		visibleIf = { mode.selectedIndex != MODE_HEAD && !hideAll.value },
	)

	@JvmField
	val module = Module(
		id = "hide_players",
		name = "Hide Players",
		description = "Hides players crowding you",
		category = ModuleCategory.VISUAL,
		supportsKeybind = false,
		settings = listOf(mode, onlyInDungeons, hideAll, distance),
	)

	/** Vanilla's player skin pipeline with culling on. */
	private val SKIN_CULL: RenderPipeline = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
			.withLocation(Cryptic.id("pipeline/player_skin_cull"))
			.withShaderDefine("ALPHA_CUTOUT", 0.1f)
			.withShaderDefine("PER_FACE_LIGHTING")
			.withBindGroupLayout(BindGroupLayouts.SAMPLER1)
			.withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
			.withCull(true)
			.build(),
	)

	/** Vanilla's armour pipeline with culling on. */
	private val ARMOUR_CULL: RenderPipeline = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
			.withLocation(Cryptic.id("pipeline/player_armour_cull"))
			.withShaderDefine("ALPHA_CUTOUT", 0.1f)
			.withShaderDefine("NO_OVERLAY")
			.withShaderDefine("PER_FACE_LIGHTING")
			.withCull(true)
			.build(),
	)

	private val skinTypes = HashMap<Identifier, RenderType>()
	private val armourTypes = HashMap<Identifier, RenderType>()

	/** Registers the pipelines with vanilla's at start-up. */
	fun touch() {
		SKIN_CULL
		ARMOUR_CULL
	}

	/** Asked per player per frame, so the cheapest tests come first. */
	@JvmStatic
	fun hides(entity: Entity): Boolean =
		mode.selectedIndex != MODE_HEAD && applies(entity, head = false) &&
			(hideAll.value || withinDistance(entity))

	/** Whether [entity] is drawn with back faces culled. Asked per player per frame. */
	@JvmStatic
	fun cullsInside(entity: Entity?): Boolean =
		entity != null && mode.selectedIndex == MODE_HEAD && applies(entity, head = true)

	/** [cullsInside] for the entity a render state came from. */
	@JvmStatic
	fun cullsInsideState(state: Any?): Boolean =
		mode.selectedIndex == MODE_HEAD && state is EntityRenderStateHolder && cullsInside(state.`cryptic$entity`())

	/**
	 * Leaves out the outer layer of each part of [model] the camera is inside. [pose] is the model's
	 * transform, relative to the camera.
	 */
	@JvmStatic
	fun hideOuterLayersAround(model: PlayerModel, state: AvatarRenderState, pose: PoseStack) {
		if (!cullsInsideState(state)) return
		// Poses the parts for this player; vanilla does the same again before drawing.
		model.setupAnim(state)
		val root = model.root()
		if (state.showHat && cameraInside(pose, root, model.head, model.hat)) state.showHat = false
		if (state.showJacket && cameraInside(pose, root, model.body, model.jacket)) state.showJacket = false
		if (state.showLeftSleeve && cameraInside(pose, root, model.leftArm, model.leftSleeve)) state.showLeftSleeve = false
		if (state.showRightSleeve && cameraInside(pose, root, model.rightArm, model.rightSleeve)) state.showRightSleeve = false
		if (state.showLeftPants && cameraInside(pose, root, model.leftLeg, model.leftPants)) state.showLeftPants = false
		if (state.showRightPants && cameraInside(pose, root, model.rightLeg, model.rightPants)) state.showRightPants = false
	}

	/** Whether a worn player skull, drawn at [pose], has the camera inside it. */
	@JvmStatic
	fun hidesSkullHat(state: LivingEntityRenderState, pose: PoseStack): Boolean {
		if (state.wornHeadType != SkullBlock.Types.PLAYER || !cullsInsideState(state)) return false
		val camera = cameraLocal(pose)
		return inside(camera, -4f, -8f, -4f, 4f, 0f, 4f)
	}

	/** Whether the camera is inside a cube of the last part of [chain], which runs from the model's root. */
	private fun cameraInside(pose: PoseStack, vararg chain: ModelPart): Boolean {
		pose.pushPose()
		for (part in chain) part.translateAndRotate(pose)
		val camera = cameraLocal(pose)
		pose.popPose()
		return (chain.last() as ModelPartAccessor).`cryptic$cubes`().any {
			inside(camera, it.minX, it.minY, it.minZ, it.maxX, it.maxY, it.maxZ)
		}
	}

	/** The camera, which sits at the origin of camera-relative space, in [pose]'s local pixels. */
	private fun cameraLocal(pose: PoseStack): Vector3f =
		Matrix4f(pose.last().pose()).invert().transformPosition(Vector3f()).mul(16f)

	private fun inside(p: Vector3f, minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float): Boolean =
		p.x >= minX - PART_MARGIN && p.x <= maxX + PART_MARGIN &&
			p.y >= minY - PART_MARGIN && p.y <= maxY + PART_MARGIN &&
			p.z >= minZ - PART_MARGIN && p.z <= maxZ + PART_MARGIN

	/** A player skull without its outer layer, worn in place of the usual one while inside. */
	@JvmStatic
	val skullWithoutHat: SkullModel by lazy {
		SkullModel(LayerDefinition.create(SkullModel.createHeadModel(), 64, 64).bakeRoot())
	}

	/** The culled stand-in for `RenderTypes.entityTranslucent(texture)`: skins and worn heads. */
	@JvmStatic
	fun skinType(texture: Identifier): RenderType = skinTypes.getOrPut(texture) {
		RenderType.create(
			"cryptic_player_skin_cull",
			RenderSetup.builder(SKIN_CULL)
				.withTexture("Sampler0", texture)
				.useLightmap()
				.useOverlay()
				.affectsCrumbling()
				.sortOnUpload()
				.setOutline(RenderSetup.OutlineProperty.AFFECTS_OUTLINE)
				.createRenderSetup(),
		)
	}

	/** The culled stand-in for `RenderTypes.armorCutoutNoCull(texture)`. */
	@JvmStatic
	fun armourType(texture: Identifier): RenderType = armourTypes.getOrPut(texture) {
		RenderType.create(
			"cryptic_player_armour_cull",
			RenderSetup.builder(ARMOUR_CULL)
				.withTexture("Sampler0", texture)
				.useLightmap()
				.useOverlay()
				.setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
				.affectsCrumbling()
				.setOutline(RenderSetup.OutlineProperty.AFFECTS_OUTLINE)
				.createRenderSetup(),
		)
	}

	private fun applies(entity: Entity, head: Boolean): Boolean {
		if (!module.enabled) return false
		if (head) {
			if (entity !is Avatar) return false
		} else if (entity !is Player || entity.uuid.version() != REAL_PLAYER_UUID_VERSION) {
			return false
		}
		if (entity === Minecraft.getInstance().player) return false
		return !onlyInDungeons.value || DungeonLocation.inDungeon
	}

	private fun withinDistance(entity: Entity): Boolean {
		val self = Minecraft.getInstance().player ?: return false
		val range = distance.value
		return entity.distanceToSqr(self) <= range * range
	}
}
