package imicro.cryptic.feature

import imicro.cryptic.dungeon.BossTimings.Timing
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Guardian
import java.util.Locale

/**
 * The Professor's fight on Floor 3 and Master 3: when to cast Fire Freeze, and each Guardian's
 * health written large on it. The health idea is Skyblocker's.
 */
object F3Features {
	private val fireFreezeSection = SectionModuleSetting("fire_freeze_section", "Fire Freeze")

	@JvmField
	val fireFreezeTimer = ToggleModuleSetting(
		id = "fire_freeze_timer",
		label = "Fire Freeze timer",
		defaultValue = true,
		description = "Counts down to using Fire Freeze on the Professor, from his line about his Guardians.",
	)

	private val guardianSection = SectionModuleSetting("guardian_section", "Guardians")

	@JvmField
	val guardianHealth = ToggleModuleSetting(
		id = "guardian_health",
		label = "Guardian health",
		defaultValue = true,
		description = "Each Guardian's and the Professor's health, written large on them so it reads from across the room.",
	)

	@JvmField
	val healthThroughWalls = ToggleModuleSetting(
		id = "guardian_health_through_walls",
		label = "Through walls",
		defaultValue = true,
		description = "Shows the health behind blocks too.",
	)

	@JvmField
	val healthColor = ColorModuleSetting(
		id = "guardian_health_color",
		label = "Colour",
		defaultRgb = 0x55FF55,
	)

	@JvmField
	val module = Module(
		id = "f3_features",
		name = "F3/M3 Features",
		description = "The Professor's fight",
		category = ModuleCategory.FLOOR_7,
		supportsKeybind = false,
		settings = listOf(
			fireFreezeSection, fireFreezeTimer,
			guardianSection, guardianHealth.withMore(healthThroughWalls, healthColor),
		),
	)

	/** How far into the Professor window the ability actually goes out. */
	private const val FIRE_FREEZE_CAST = 100

	private val fireFreezeLine = Regex("""^\[BOSS] The Professor: Oh\? You found my Guardians. one weakness\?$""")

	private val FORMATTING = Regex("§.")

	/** "Healthy Guardian 1.2M❤", "﴾ The Professor 3M❤ ﴿". */
	private val GUARDIAN_TAG = Regex("""^.*? Guardian ([\d.,]+[kKmMbB]?)❤$""")
	private val PROFESSOR_TAG = Regex("""^﴾ The Professor ([\d.,]+[kKmMbB]?)❤ ﴿$""")

	/** Ticks left of the Fire Freeze window, or -1. Counted on the network thread. */
	@Volatile
	private var fireFreezeTicks = -1

	/** The Professor has spoken this world: his fight is on. */
	private var professorSpoke = false

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		Hud.register(FireFreezeElement())
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> forget() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> forget() }
	}

	private fun forget() {
		fireFreezeTicks = -1
		professorSpoke = false
	}

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !module.enabled) return
		val line = message.string.replace(FORMATTING, "")
		if (line.startsWith("[BOSS] The Professor:")) professorSpoke = true
		if (fireFreezeLine.matches(line)) fireFreezeTicks = Timing.FIRE_FREEZE.ticks
	}

	@JvmStatic
	fun onServerTick() {
		if (fireFreezeTicks >= 0) fireFreezeTicks--
	}

	private fun inFight(): Boolean =
		DungeonLocation.inDungeon && DungeonLocation.floor == 3 && (professorSpoke || DungeonRun.inBoss)

	private fun render(context: LevelRenderContext) {
		if (!module.enabled || !guardianHealth.value || !inFight()) return
		val client = Minecraft.getInstance()
		val level = client.level ?: return
		val player = client.player ?: return
		val partialTick = client.deltaTracker.getGameTimeDeltaPartialTick(false)
		val camera = client.gameRenderer.mainCamera().position()
		val guardians = level.getEntitiesOfClass(Guardian::class.java, player.boundingBox.inflate(SEARCH_RADIUS))
		for (guardian in guardians) {
			if (!guardian.isAlive) continue
			// The name tag is an armour stand just above the Guardian.
			val health = level.getEntitiesOfClass(ArmorStand::class.java, guardian.boundingBox.inflate(0.0, 2.0, 0.0)) { it.hasCustomName() }
				.firstNotNullOfOrNull { healthOf(it.customName?.string ?: return@firstNotNullOfOrNull null) } ?: continue
			val at = guardian.getPosition(partialTick)
			val y = at.y + guardian.bbHeight / 2
			// Grows with distance, so it stays readable across the room.
			val scale = (1.0 + camera.distanceTo(at) / 10.0).toFloat()
			WorldRender.drawText(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				orientation = client.gameRenderer.mainCamera().rotation(),
				text = Component.literal(health),
				x = at.x, y = y, z = at.z,
				scale = scale,
				seeThrough = healthThroughWalls.value,
				argb = healthColor.argb or (0xFF shl 24),
				centered = true,
			)
		}
	}

	private fun healthOf(name: String): String? {
		val plain = name.replace(FORMATTING, "").trim()
		return (PROFESSOR_TAG.matchEntire(plain) ?: GUARDIAN_TAG.matchEntire(plain))?.groupValues?.get(1)
	}

	private const val SEARCH_RADIUS = 64.0

	fun describe(): String =
		"F3/M3: in the fight ${inFight()} (floor ${DungeonLocation.floor}, Professor spoke $professorSpoke, boss ${DungeonRun.inBoss}), Fire Freeze $fireFreezeTicks"

	/** The Fire Freeze countdown, from the cast a hundred ticks into the Professor's window. */
	private class FireFreezeElement : HudElement("fire_freeze_timer", "Fire Freeze Timer", 0.02, 0.36) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int get() = font.width(text(SAMPLE_TICKS, "§a"))
		override val height: Int get() = font.lineHeight

		private fun leftTicks(): Int = fireFreezeTicks - FIRE_FREEZE_CAST

		override fun isVisible(): Boolean = module.enabled && fireFreezeTimer.value && leftTicks() >= 0

		override fun showInEditor(): Boolean = module.enabled && fireFreezeTimer.value

		override fun render(context: GuiGraphicsExtractor) {
			val left = leftTicks()
			val window = Timing.FIRE_FREEZE.ticks - FIRE_FREEZE_CAST
			val color = when {
				left >= window * 0.66f -> "§a"
				left >= window * 0.33f -> "§6"
				else -> "§c"
			}
			context.text(font, text(left, color), 0, 0, WHITE)
		}

		override fun renderExample(context: GuiGraphicsExtractor) = context.text(font, text(SAMPLE_TICKS, "§a"), 0, 0, WHITE)

		private fun text(ticks: Int, color: String): String =
			"§bFire Freeze: $color" + String.format(Locale.ROOT, "%.2fs", ticks / 20f)
	}

	private const val SAMPLE_TICKS = 106
	private const val WHITE = 0xFFFFFFFF.toInt()
}
