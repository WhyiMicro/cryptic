package imicro.cryptic.feature

import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonTeam
import imicro.cryptic.dungeon.Floor7
import imicro.cryptic.dungeon.PersonalBests
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.render.CrypticRenderPipelines
import imicro.cryptic.render.WorldRender
import imicro.cryptic.terminal.ServerTicks
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import kotlin.math.hypot

/**
 * M7's relics: the cauldron for the relic you carry, boxed with a line to it; when the relics spawn
 * after Necron; and how long each took to place, with personal bests. Ported from NoammAddons,
 * without its automatic parts.
 */
object Relics {
	@JvmField
	val box = ToggleModuleSetting(id = "box", label = "Cauldron box", defaultValue = true,
		description = "Boxes the cauldron for the relic you're carrying, with a line to it.")

	@JvmField
	val spawnTimer = ToggleModuleSetting(id = "spawn_timer", label = "Spawn timer", defaultValue = true,
		description = "Counts down on screen to the relics spawning, after Necron dies.")

	@JvmField
	val placeTimes = ToggleModuleSetting(id = "place_times", label = "Place times", defaultValue = true,
		description = "Once all five are placed, lists how long each took after Necron died, marking your personal bests.")

	@JvmField
	val module = Module(
		id = "relics",
		name = "Relics",
		description = "M7 relic cauldrons and times",
		category = ModuleCategory.FLOOR_7,
		supportsKeybind = false,
		settings = listOf(box, spawnTimer, placeTimes),
	)

	private enum class Relic(val itemName: String, val code: Char, val rgb: Int, val cauldron: BlockPos, val placedAt: Pair<Double, Double>) {
		RED("Corrupted Red Relic", 'c', 0xFF0000, BlockPos(51, 7, 42), 52.0 to 43.0),
		ORANGE("Corrupted Orange Relic", '6', 0xFF7200, BlockPos(57, 7, 42), 58.0 to 43.0),
		GREEN("Corrupted Green Relic", 'a', 0x00FF00, BlockPos(49, 7, 44), 50.0 to 45.0),
		BLUE("Corrupted Blue Relic", 'b', 0x008AFF, BlockPos(59, 7, 44), 60.0 to 45.0),
		PURPLE("Corrupted Purple Relic", '5', 0x81006F, BlockPos(54, 7, 41), 55.0 to 42.0);

		val label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

		companion object {
			fun named(name: String): Relic? = entries.firstOrNull { it.itemName.equals(name, ignoreCase = true) }
		}
	}

	/** Relics spawn this many ticks after Necron's last line. */
	private const val SPAWN_DELAY_TICKS = 61L

	private const val NECRON_DEAD = "[BOSS] Necron: All this, for nothing..."
	private val PICKED_UP = Regex("""^(\w{3,16}) picked the Corrupted (\w{3,6}) Relic!$""")
	private val FORMATTING = Regex("§.")

	private class Pickup(val relic: Relic, val player: String) {
		var placedSeconds: Double? = null
		var best = false
	}

	private val pickups = ArrayList<Pickup>()

	/** Server tick of Necron's death, the start of phase five. */
	private var p5StartTick = -1L
	private var spawnAtTick = -1L

	private val client: Minecraft get() = Minecraft.getInstance()

	private val inM7: Boolean get() = DungeonLocation.floor == 7 && DungeonLocation.masterMode

	val needsPhase: Boolean get() = module.enabled && DungeonLocation.inDungeon

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		CrypticRenderPipelines.touch()
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		Hud.register(SpawnElement())
		ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
			pickups.clear()
			p5StartTick = -1
			spawnAtTick = -1
		}
	}

	@JvmStatic
	fun onSystemChat(content: Component, overlay: Boolean) {
		if (overlay || !module.enabled) return
		val text = content.string.replace(FORMATTING, "").trim()
		if (text == NECRON_DEAD && inM7) {
			p5StartTick = ServerTicks.total
			spawnAtTick = ServerTicks.total + SPAWN_DELAY_TICKS
			pickups.clear()
			return
		}
		val match = PICKED_UP.matchEntire(text) ?: return
		if (!inM7 || p5StartTick < 0) return
		val relic = Relic.named("Corrupted ${match.groupValues[2]} Relic") ?: return
		client.execute { pickups += Pickup(relic, match.groupValues[1]) }
	}

	fun tick() {
		if (!module.enabled || !placeTimes.value || pickups.isEmpty() || Floor7.phase != 5) return
		val level = client.level ?: return
		val waiting = pickups.filter { it.placedSeconds == null }
		if (waiting.isNotEmpty()) {
			val stands = level.entitiesForRendering().filterIsInstance<ArmorStand>()
				.filter { it.getItemBySlot(EquipmentSlot.HEAD).hoverName.string.contains("Relic") }
			for (pickup in waiting) {
				val (x, z) = pickup.relic.placedAt
				if (stands.none { hypot(it.x - x, it.z - z) < 1.5 }) continue
				val seconds = (ServerTicks.total - p5StartTick) / 20.0
				pickup.placedSeconds = Math.round(seconds * 100) / 100.0
				if (pickup.player == client.user.name) pickup.best = PersonalBests.record("M7 ${pickup.relic.label} relic", seconds).second
			}
		}
		if (pickups.size == Relic.entries.size && pickups.all { it.placedSeconds != null }) {
			pickups.sortedBy { it.placedSeconds }.forEach { pickup ->
				val best = if (pickup.best) " §d§l(PB)" else ""
				client.gui.hud.chat.addClientSystemMessage(
					Component.literal("§8[Cryptic] §${pickup.relic.code}${pickup.relic.label} §arelic placed by §f${pickup.player} §ain §e${pickup.placedSeconds}s§a.$best"),
				)
			}
			pickups.clear()
		}
	}

	private fun render(context: LevelRenderContext) {
		if (!module.enabled || !box.value || Floor7.phase != 5) return
		val player = client.player ?: return
		val relic = Relic.named(player.inventory.getItem(8).hoverName.string.replace(FORMATTING, "")) ?: return
		val color = 0xFF000000.toInt() or relic.rgb
		WorldRender.drawBlock(context.poseStack(), context.submitNodeCollector(), relic.cauldron,
			outlineArgb = color, fillArgb = 0x40000000 or relic.rgb, outline = true, fill = true, phase = true, lineWidth = 2f, fullBlock = true)
		WorldRender.drawTracer(context.poseStack(), context.submitNodeCollector(),
			relic.cauldron.x + 0.5, relic.cauldron.y + 0.5, relic.cauldron.z + 0.5, color, 2f, phase = true)
	}

	private class SpawnElement : HudElement("relic_spawn_timer", "Relic Spawn Timer", 0.5, 0.45) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int get() = font.width("3.05")
		override val height: Int get() = font.lineHeight

		private val ticksLeft: Long get() = spawnAtTick - ServerTicks.total

		override fun isVisible(): Boolean =
			module.enabled && spawnTimer.value && (DebugOverrides.sampleHudValues || (spawnAtTick >= 0 && ticksLeft > 0))

		override fun showInEditor(): Boolean = module.enabled && spawnTimer.value

		override fun render(context: GuiGraphicsExtractor) {
			if (spawnAtTick < 0 || ticksLeft <= 0) return renderExample(context)
			draw(context, ticksLeft)
		}

		override fun renderExample(context: GuiGraphicsExtractor) = draw(context, 25)

		private fun draw(context: GuiGraphicsExtractor, ticks: Long) {
			val text = String.format("%.2f", ticks / 20.0)
			val own = Minecraft.getInstance().player?.name?.string?.let(DungeonTeam::classOf)
			val color = own?.let { 0xFF000000.toInt() or ClassColors.getClassColor(it) } ?: 0xFFFFFFFF.toInt()
			context.text(font, text, (width - font.width(text)) / 2, 0, color)
		}
	}
}
