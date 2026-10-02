package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.terminal.ServerTicks
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.core.BlockPos
import net.minecraft.util.ARGB
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Ghast
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import java.util.Locale

/**
 * Thorn's Spirit Bear, on Floor 4: how close it is to spawning, and where it is.
 *
 * Odin's Spirit Bear and NoammAddons' F4 Features (BSD 3-Clause, Copyright (c)
 * 2025 odtheking; CC0, Noamm9). Every spirit animal killed in Thorn's arena
 * lights one of the sea lanterns ringing it — twenty-five on Floor 4, thirty
 * in Master Mode — and the last one lighting starts the bear's spawn, 68
 * server ticks later. So the ring is a progress bar the game already draws,
 * just one nobody can read from the middle of the fight; the HUD reads it.
 *
 * The highlights are NoammAddons': Thorn himself (a ghast), the Spirit Bear
 * (a player-shaped NPC by that name), and the stand holding the Spirit Bow.
 */
object SpiritBear {
	private const val SPAWN_TICKS = 68L

	/** The lantern that lights last, which is the one that starts the spawn. */
	private val LAST_LANTERN = BlockPos(7, 77, 34)

	@JvmField
	val hud = ToggleModuleSetting(
		id = "hud",
		label = "Spirit Bear HUD",
		defaultValue = true,
		description = "Counts the lanterns lit towards the bear, then the seconds until it spawns.",
	)

	private val highlightSection = SectionModuleSetting("highlight_section", "Highlights")

	@JvmField
	val highlightBear = ToggleModuleSetting(
		id = "highlight_bear",
		label = "Spirit Bear",
		defaultValue = true,
	)

	@JvmField
	val bearColor = ColorModuleSetting(
		id = "bear_color",
		label = "Bear",
		defaultRgb = 0xFF55FF,
		visibleIf = { highlightBear.value },
		inlineWith = highlightBear,
	)

	@JvmField
	val highlightBow = ToggleModuleSetting(
		id = "highlight_bow",
		label = "Spirit Bow",
		defaultValue = true,
	)

	@JvmField
	val bowColor = ColorModuleSetting(
		id = "bow_color",
		label = "Bow",
		defaultRgb = 0x55FFFF,
		visibleIf = { highlightBow.value },
		inlineWith = highlightBow,
	)

	@JvmField
	val highlightThorn = ToggleModuleSetting(
		id = "highlight_thorn",
		label = "Thorn",
		defaultValue = false,
	)

	@JvmField
	val thornColor = ColorModuleSetting(
		id = "thorn_color",
		label = "Thorn",
		defaultRgb = 0xFF5555,
		visibleIf = { highlightThorn.value },
		inlineWith = highlightThorn,
	)

	@JvmField
	val module = Module(
		id = "spirit_bear",
		name = "Spirit Bear",
		description = "Floor 4: the bear's spawn, and highlights",
		category = ModuleCategory.FLOOR_7,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(
			hud,
			highlightSection, highlightBear, bearColor, highlightBow, bowColor, highlightThorn, thornColor,
		),
	)

	/** Lanterns lit so far. */
	private var lit = 0

	/** The server tick the bear spawns on, or 0 while it is not coming. */
	private var spawnAt = 0L

	/** True once the last lantern has lit and the bear is up (or coming). */
	private var spawning = false

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		Hud.register(BearElement())
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
	}

	private fun reset() {
		lit = 0
		spawnAt = 0L
		spawning = false
	}

	private val inBoss: Boolean get() = module.enabled && DungeonLocation.floor == 4 && DungeonRun.inBoss

	/** True while the floor has to be read for this module. */
	val needsDungeon: Boolean get() = module.enabled

	private val lanterns: Set<BlockPos> get() = if (DungeonLocation.masterMode) M4_LANTERNS else F4_LANTERNS

	/**
	 * A block the server has set, with what was there before. A lantern going
	 * from coal to sea lantern is a kill counted, and back is one taken away.
	 */
	@JvmStatic
	fun onBlockChanged(pos: BlockPos, old: BlockState, new: BlockState) {
		if (!inBoss || pos !in lanterns) return
		when {
			new.block == Blocks.SEA_LANTERN && old.block == Blocks.COAL_BLOCK -> {
				lit = (lit + 1).coerceAtMost(lanterns.size)
				if (pos == LAST_LANTERN) {
					spawning = true
					spawnAt = ServerTicks.total + SPAWN_TICKS
				}
			}
			new.block == Blocks.COAL_BLOCK && old.block == Blocks.SEA_LANTERN -> {
				lit = (lit - 1).coerceAtLeast(0)
				if (pos == LAST_LANTERN) {
					spawning = false
					spawnAt = 0L
				}
			}
		}
	}

	/** The outline colour for [entity], or [EntityRenderState.NO_OUTLINE]. */
	@JvmStatic
	fun outlineColorFor(entity: Entity): Int {
		if (!inBoss) return EntityRenderState.NO_OUTLINE
		val color = when {
			highlightThorn.value && entity is Ghast -> thornColor.rgb
			highlightBear.value && entity is Player &&
				entity.gameProfile.name().lowercase(Locale.ROOT).startsWith("spirit bear") -> bearColor.rgb
			highlightBow.value && entity is ArmorStand &&
				entity.getItemBySlot(EquipmentSlot.MAINHAND).hoverName.string == "Bow" -> bowColor.rgb
			else -> return EntityRenderState.NO_OUTLINE
		}
		return ARGB.opaque(color)
	}

	/** What the HUD says: lanterns, a countdown, or that the bear is up. */
	private fun status(): Pair<String, Int> {
		if (!spawning) return "$lit/${lanterns.size}" to 0xFFFF55FF.toInt()
		val left = spawnAt - ServerTicks.total
		if (left > 0) return String.format(Locale.ROOT, "%.2fs", left / 20.0) to 0xFFFFFF55.toInt()
		return "Alive!" to 0xFF55FF55.toInt()
	}

	private class BearElement : HudElement("spirit_bear", "Spirit Bear", 0.46, 0.55) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int get() = font.width("Bear: 30/30")
		override val height: Int get() = font.lineHeight

		override fun isVisible(): Boolean =
			module.enabled && hud.value && (DebugOverrides.sampleHudValues || (inBoss && !DungeonRun.ended))

		override fun showInEditor(): Boolean = module.enabled && hud.value

		override fun render(context: GuiGraphicsExtractor) {
			if (DebugOverrides.sampleHudValues && !inBoss) return renderExample(context)
			val (text, color) = status()
			draw(context, text, color)
		}

		override fun renderExample(context: GuiGraphicsExtractor) = draw(context, "1.45s", 0xFFFFFF55.toInt())

		private fun draw(context: GuiGraphicsExtractor, value: String, color: Int) {
			val label = "Bear: "
			val x = (width - font.width(label + value)) / 2
			context.text(font, label, x, 0, 0xFFFFAA00.toInt())
			context.text(font, value, x + font.width(label), 0, color)
		}
	}

	private val F4_LANTERNS = hashSetOf(
		BlockPos(-3, 77, 33), BlockPos(-9, 77, 31), BlockPos(-16, 77, 26), BlockPos(-20, 77, 20), BlockPos(-23, 77, 13),
		BlockPos(-24, 77, 6), BlockPos(-24, 77, 0), BlockPos(-22, 77, -7), BlockPos(-18, 77, -13), BlockPos(-12, 77, -19),
		BlockPos(-5, 77, -22), BlockPos(1, 77, -24), BlockPos(8, 77, -24), BlockPos(14, 77, -23), BlockPos(21, 77, -19),
		BlockPos(27, 77, -14), BlockPos(31, 77, -8), BlockPos(33, 77, -1), BlockPos(34, 77, 5), BlockPos(33, 77, 12),
		BlockPos(31, 77, 19), BlockPos(27, 77, 25), BlockPos(20, 77, 30), BlockPos(14, 77, 33), BlockPos(7, 77, 34),
	)

	private val M4_LANTERNS = hashSetOf(
		BlockPos(-2, 77, 33), BlockPos(-7, 77, 32), BlockPos(-13, 77, 28), BlockPos(-17, 77, 24), BlockPos(-21, 77, 18),
		BlockPos(-23, 77, 13), BlockPos(-24, 77, 7), BlockPos(-24, 77, 2), BlockPos(-23, 77, -4), BlockPos(-21, 77, -9),
		BlockPos(-17, 77, -14), BlockPos(-12, 77, -19), BlockPos(-6, 77, -22), BlockPos(-1, 77, -23), BlockPos(5, 77, -24),
		BlockPos(10, 77, -24), BlockPos(16, 77, -22), BlockPos(21, 77, -19), BlockPos(27, 77, -15), BlockPos(30, 77, -10),
		BlockPos(32, 77, -5), BlockPos(34, 77, 1), BlockPos(34, 77, 7), BlockPos(33, 77, 12), BlockPos(31, 77, 18),
		BlockPos(28, 77, 23), BlockPos(23, 77, 28), BlockPos(18, 77, 31), BlockPos(12, 77, 33), BlockPos(7, 77, 34),
	)
}
