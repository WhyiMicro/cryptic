package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.render.WorldRender
import imicro.cryptic.terminal.ServerTicks
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.Mth
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import java.util.Locale

/**
 * Finds the real Livid among the nine on Floor 5, and boxes it.
 *
 * Odin's Livid Solver and NoammAddons' (BSD 3-Clause, Copyright (c) 2025
 * odtheking; CC0, Noamm9). Livid splits into nine copies, each named for a
 * colour, and only one can be hurt. Hypixel gives it away above the arena: a
 * block of wool in the ceiling is dyed the real one's colour. Reading that
 * block is the whole solve; the rest is making the answer easy to aim at.
 *
 * The invulnerability timer is theirs too: Livid cannot be hurt for 390 server
 * ticks after his greeting, which is when an Ice Spray lands best.
 */
object LividSolver {
	private const val STYLE_OUTLINE = 0
	private const val STYLE_FILL = 1

	/** How long Livid shrugs off damage after his greeting. */
	private const val INVULNERABLE_TICKS = 390L

	private const val GREETING =
		"[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows."

	/** Odin reads the ceiling wool here, NoammAddons three blocks along; either says it. */
	private val WOOL_POSITIONS = listOf(BlockPos(5, 108, 43), BlockPos(5, 108, 40))

	private val FORMATTING = Regex("§.")

	/** Each Livid's name and the wool, and colour, that gives it away. */
	private enum class Livid(val title: String, val color: Int, val wool: () -> Block) {
		VENDETTA("Vendetta", 0xFFFFFF, { Blocks.WOOL.white() }),
		CROSSED("Crossed", 0xFF55FF, { Blocks.WOOL.magenta() }),
		ARCADE("Arcade", 0xFFFF55, { Blocks.WOOL.yellow() }),
		SMILE("Smile", 0x55FF55, { Blocks.WOOL.lime() }),
		DOCTOR("Doctor", 0xAAAAAA, { Blocks.WOOL.gray() }),
		PURPLE("Purple", 0xAA00AA, { Blocks.WOOL.purple() }),
		SCREAM("Scream", 0x5555FF, { Blocks.WOOL.blue() }),
		FROG("Frog", 0x00AA00, { Blocks.WOOL.green() }),
		HOCKEY("Hockey", 0xFF5555, { Blocks.WOOL.red() });

		val entityName: String get() = "$title Livid"
	}

	@JvmField
	val style = DropdownModuleSetting(
		id = "style",
		label = "Style",
		options = listOf("Outline", "Fill", "Fill + outline"),
		defaultIndex = 2,
	)

	@JvmField
	val highlightColor = ColorModuleSetting(
		id = "highlight_color",
		label = "Highlight",
		defaultRgb = 0xFF55FF,
		supportsAlpha = true,
		defaultAlpha = 0xFF,
		visibleIf = { style.selectedIndex != STYLE_FILL },
	)

	@JvmField
	val fillColor = ColorModuleSetting(
		id = "fill_color",
		label = "Fill",
		defaultRgb = 0xFF55FF,
		supportsAlpha = true,
		defaultAlpha = 0x40,
		visibleIf = { style.selectedIndex != STYLE_OUTLINE },
	)

	@JvmField
	val tracer = ToggleModuleSetting(
		id = "tracer",
		label = "Tracer",
		defaultValue = false,
		description = "A line from your crosshair to the real Livid.",
	)

	@JvmField
	val tracerColor = ColorModuleSetting(
		id = "tracer_color",
		label = "Tracer",
		defaultRgb = 0xFF55FF,
		visibleIf = { tracer.value },
		inlineWith = tracer,
	)

	@JvmField
	val showHp = ToggleModuleSetting(
		id = "show_hp",
		label = "Show HP",
		defaultValue = true,
		description = "Writes the real Livid's health above it.",
	)

	@JvmField
	val hideWrong = ToggleModuleSetting(
		id = "hide_wrong",
		label = "Hide wrong Livids",
		defaultValue = false,
		description = "Stops drawing the eight copies, and their name tags.",
	)

	@JvmField
	val announce = ToggleModuleSetting(
		id = "announce",
		label = "Say which one",
		defaultValue = true,
		description = "Prints the real Livid's name in your chat once the ceiling gives it away.",
	)

	private val timerSection = SectionModuleSetting("timer_section", "Invulnerability")

	@JvmField
	val timer = ToggleModuleSetting(
		id = "timer",
		label = "Invulnerability timer",
		defaultValue = true,
		description = "Counts down the 390 ticks Livid cannot be hurt for after his greeting.",
	)

	@JvmField
	val iceSprayTitle = ToggleModuleSetting(
		id = "ice_spray_title",
		label = "Ice Spray title",
		defaultValue = true,
		description = "Says Ice Spray Livid the moment the timer runs out.",
		visibleIf = { timer.value },
	)

	@JvmField
	val iceSpraySound = ToggleModuleSetting(
		id = "ice_spray_sound",
		label = "Ice Spray sound",
		defaultValue = true,
		visibleIf = { timer.value },
	)

	@JvmField
	val module = Module(
		id = "livid_solver",
		name = "Livid Solver",
		description = "Floor 5: finds and boxes the real Livid",
		category = ModuleCategory.FLOOR_7,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(
			style, highlightColor, fillColor, tracer, tracerColor, showHp, hideWrong, announce,
			timerSection, timer, iceSprayTitle, iceSpraySound,
		),
	)

	private var current: Livid? = null
	private var announced: Livid? = null

	/** The real Livid's entity, once it is in range. */
	private var entityId: Int? = null

	/** The server tick Livid becomes vulnerable on, or 0 when no timer is running. */
	private var vulnerableAt = 0L

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		Hud.register(TimerElement())
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
	}

	private fun reset() {
		current = null
		announced = null
		entityId = null
		vulnerableAt = 0L
	}

	/** True while the floor has to be read for this module. */
	val needsDungeon: Boolean get() = module.enabled

	private val inBoss: Boolean get() = module.enabled && DungeonLocation.floor == 5 && DungeonRun.inBoss

	fun tick(client: Minecraft) {
		if (!inBoss) {
			if (current != null) reset()
			return
		}
		val level = client.level ?: return

		// The ceiling says which; it can change once, when the copies split.
		WOOL_POSITIONS.firstNotNullOfOrNull { pos ->
			val block = level.getBlockState(pos).block
			Livid.entries.firstOrNull { it.wool() == block }
		}?.let { current = it }
		val livid = current ?: return

		if (announce.value && announced != livid) {
			announced = livid
			client.gui.hud.chat.addClientSystemMessage(
				Component.literal("§8[Cryptic] §7Livid: ").append(
					Component.literal(livid.entityName).withColor(livid.color),
				),
			)
		}

		val known = entityId?.let(level::getEntity)
		if (known == null || known.isRemoved || known.name.string != livid.entityName) {
			entityId = level.players().firstOrNull { it.name.string == livid.entityName }?.id
		}

		if (vulnerableAt != 0L && ServerTicks.total >= vulnerableAt) {
			vulnerableAt = 0L
			if (timer.value && iceSprayTitle.value) {
				client.gui.hud.setTitle(Component.literal("§bIce Spray Livid!"))
			}
			if (timer.value && iceSpraySound.value) {
				client.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.0f))
			}
		}
	}

	/** A chat packet, on the client thread. */
	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !module.enabled || DungeonLocation.floor != 5) return
		if (message.string.replace(FORMATTING, "").trim() == GREETING) {
			vulnerableAt = ServerTicks.total + INVULNERABLE_TICKS
		}
	}

	/** Whether [entity] is one of the copies, or a copy's name tag, that Hide wrong Livids drops. */
	@JvmStatic
	fun hides(entity: Entity): Boolean {
		if (!hideWrong.value || !inBoss) return false
		val livid = current ?: return false
		return when (entity) {
			is Player -> entity.name.string.endsWith(" Livid") && entity.name.string != livid.entityName
			is ArmorStand -> {
				val tag = entity.customName?.string?.replace(FORMATTING, "") ?: return false
				"Livid" in tag && livid.title !in tag
			}
			else -> false
		}
	}

	private fun render(context: LevelRenderContext) {
		if (!inBoss) return
		val client = Minecraft.getInstance()
		// Blind at the start of the fight, when every copy is the same and
		// nothing can be told apart yet.
		if (client.player?.hasEffect(MobEffects.BLINDNESS) == true) return
		val entity = entityId?.let { client.level?.getEntity(it) } as? AbstractClientPlayer ?: return
		if (entity.isDeadOrDying) return

		val partialTick = client.deltaTracker.getGameTimeDeltaPartialTick(false).toDouble()
		val x = Mth.lerp(partialTick, entity.xOld, entity.x)
		val y = Mth.lerp(partialTick, entity.yOld, entity.y)
		val z = Mth.lerp(partialTick, entity.zOld, entity.z)
		val box = entity.boundingBox.move(x - entity.x, y - entity.y, z - entity.z)

		WorldRender.drawBox(
			poseStack = context.poseStack(),
			collector = context.submitNodeCollector(),
			minX = box.minX,
			minY = box.minY,
			minZ = box.minZ,
			maxX = box.maxX,
			maxY = box.maxY,
			maxZ = box.maxZ,
			outlineArgb = highlightColor.argb,
			fillArgb = fillColor.argb,
			outline = style.selectedIndex != STYLE_FILL,
			fill = style.selectedIndex != STYLE_OUTLINE,
			phase = true,
			lineWidth = 2f,
		)

		if (tracer.value) {
			WorldRender.drawTracer(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				x = x,
				y = y + entity.bbHeight / 2,
				z = z,
				argb = 0xFF000000.toInt() or tracerColor.rgb,
				lineWidth = 2f,
				phase = true,
			)
		}

		if (showHp.value) {
			val fraction = entity.health / entity.maxHealth
			val color = when {
				fraction > 0.66f -> 0xFF55FF55.toInt()
				fraction > 0.33f -> 0xFFFFFF55.toInt()
				else -> 0xFFFF5555.toInt()
			}
			WorldRender.drawText(
				poseStack = context.poseStack(),
				collector = context.submitNodeCollector(),
				orientation = client.gameRenderer.mainCamera().rotation(),
				text = Component.literal(formatHealth(entity.health)).withColor(color and 0xFFFFFF),
				x = x,
				y = y + entity.bbHeight + 0.9,
				z = z,
				scale = 1.5f,
				seeThrough = true,
			)
		}
	}

	private fun formatHealth(health: Float): String = when {
		health >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", health / 1_000_000f)
		health >= 1_000 -> String.format(Locale.ROOT, "%.1fk", health / 1_000f)
		else -> health.toInt().toString()
	}

	private fun ticksLeft(): Long = (vulnerableAt - ServerTicks.total).coerceAtLeast(0L)

	private class TimerElement : HudElement("livid_timer", "Livid Invulnerability", 0.44, 0.38) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int get() = font.width("Livid: 19.5s")
		override val height: Int get() = font.lineHeight

		override fun isVisible(): Boolean =
			module.enabled && timer.value && (DebugOverrides.sampleHudValues || (inBoss && vulnerableAt != 0L))

		override fun showInEditor(): Boolean = module.enabled && timer.value

		override fun render(context: GuiGraphicsExtractor) {
			if (vulnerableAt == 0L) return renderExample(context)
			draw(context, ticksLeft())
		}

		override fun renderExample(context: GuiGraphicsExtractor) = draw(context, INVULNERABLE_TICKS / 2)

		private fun draw(context: GuiGraphicsExtractor, ticks: Long) {
			val color = when {
				ticks > 260 -> 0xFF55FF55.toInt()
				ticks > 130 -> 0xFFFFFF55.toInt()
				else -> 0xFFFF5555.toInt()
			}
			val label = "Livid: "
			val value = String.format(Locale.ROOT, "%.1fs", ticks / 20.0)
			val x = (width - font.width(label + value)) / 2
			context.text(font, label, x, 0, 0xFFAA00AA.toInt())
			context.text(font, value, x + font.width(label), 0, color)
		}
	}
}
