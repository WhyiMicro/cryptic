package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonTeam
import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player

/**
 * Draws dungeon teammates' name tags as their name and class letter. Ported from Blade Addons.
 * Hypixel's tag is hidden by [imicro.cryptic.mixin.EntityRendererMixin]. Settings live on
 * [ClassColors]; only the drawing is here.
 */
object ClassNames {
	/** Blade Addons draws the letter yellow whatever the class colour. */
	private const val LETTER_COLOR = 0xFFFF55

	private const val LETTER_LEFT = 1

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		// "Through walls" keeps the label from being hidden by its own teammate.
		LevelRenderEvents.COLLECT_SUBMITS.register(::renderNames)
	}

	@JvmStatic
	fun replacesNameTag(player: Player): Boolean {
		if (!isActive()) return false
		if (player === Minecraft.getInstance().player) return false
		return DungeonTeam.isTeammate(player.name.string)
	}

	private fun isActive() =
		ClassColors.module.enabled && ClassColors.showNames.value && DungeonTeam.inDungeons

	private fun renderNames(context: LevelRenderContext) {
		if (!isActive()) return

		val client = Minecraft.getInstance()
		val level = client.level ?: return
		val self = client.player
		val orientation = context.levelState().cameraRenderState.orientation
		val partialTick = client.deltaTracker.getGameTimeDeltaPartialTick(false).toDouble()

		for (player in level.players()) {
			if (player === self) continue
			val dungeonClass = DungeonTeam.classOf(player.name.string) ?: continue

			val name = Component.literal(player.name.string)
				.withColor(ClassColors.getClassColor(dungeonClass))
			val text = when {
				!ClassColors.showLetter.value -> name
				// Built from whichever piece comes first, since components only append.
				ClassColors.letterSide.selectedIndex == LETTER_LEFT ->
					Component.literal("[${dungeonClass.initial}] ").withColor(LETTER_COLOR).append(name)
				else ->
					name.append(Component.literal(" [${dungeonClass.initial}]").withColor(LETTER_COLOR))
			}

			// The entity's interpolated position, so the label doesn't lag a frame.
			WorldRender.drawText(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				orientation = orientation,
				text = text,
				x = Mth.lerp(partialTick, player.xo, player.x),
				y = Mth.lerp(partialTick, player.yo, player.y) + ClassColors.nameHeight.value,
				z = Mth.lerp(partialTick, player.zo, player.z),
				scale = ClassColors.nameScale.value.toFloat(),
				seeThrough = ClassColors.namesThroughWalls.value,
				dropShadow = CustomNametags.dropShadow.value,
				backgroundArgb = CustomNametags.backgroundArgb(),
			)
		}
	}
}
