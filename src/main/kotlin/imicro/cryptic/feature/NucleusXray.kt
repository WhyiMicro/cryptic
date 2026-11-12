package imicro.cryptic.feature

import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.KeybindModuleSetting
import imicro.cryptic.gui.Restriction
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.render.WorldRender
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.world.level.block.BeaconBeamBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.StainedGlassBlock
import net.minecraft.world.level.block.StainedGlassPaneBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import kotlin.math.roundToInt

/**
 * Boxes coal or gemstones (stained glass, full blocks and panes) through walls, and hides every other block, while its key has it on, for setting up routes in a
 * lobby: gemstones grow back, so a clean path has to go round them. Part of Nucleus Features.
 */
object NucleusXray {
	@JvmField
	val coalKey = KeybindModuleSetting(
		id = "xray_coal_key",
		label = "Coal x-ray key",
		description = "Turns the coal x-ray on and off.",
	)

	@JvmField
	val paneKey = KeybindModuleSetting(
		id = "xray_pane_key",
		label = "Gemstone x-ray key",
		description = "Turns the gemstone x-ray on and off.",
	)

	@JvmField
	val range = SliderModuleSetting(
		id = "xray_range",
		label = "Range",
		defaultValue = 32.0,
		min = 8.0,
		max = 64.0,
		step = 1.0,
		unit = " blocks",
		visibleIf = { highlight.selected != "None" },
	)

	@JvmField
	val hideOthers = ToggleModuleSetting(
		id = "xray_hide_others",
		label = "Hide other blocks",
		defaultValue = true,
		description = "Only the x-rayed blocks are drawn while a key has it on.",
	)

	@JvmField
	val highlight = DropdownModuleSetting(
		id = "xray_highlight",
		label = "Highlight",
		options = listOf("Both", "Outline", "Filled", "None"),
		description = "How the x-rayed blocks are boxed. None shows just the blocks.",
	)

	@JvmField
	val coalColor = ColorModuleSetting("xray_coal_color", "Coal color", defaultRgb = 0xFFFFFF, visibleIf = { highlight.selected != "None" })

	@JvmField
	val fillOpacity = SliderModuleSetting(
		id = "xray_fill",
		label = "Fill",
		defaultValue = 25.0,
		min = 0.0,
		max = 100.0,
		step = 1.0,
		unit = "%",
		description = "Gemstones are boxed in their own colour.",
		visibleIf = { highlight.selected == "Both" || highlight.selected == "Filled" },
	)

	/** Seeing blocks through walls is past legit. */
	@JvmField
	val setting = ToggleModuleSetting(
		id = "xray",
		label = "X-ray",
		defaultValue = false,
		description = "Keys that show only coal or gemstones, for clearing a route in a new lobby.",
		restriction = Restriction.CHEAT,
	).withMore(coalKey, paneKey, hideOthers, highlight, range, coalColor, fillOpacity)

	/** Full scans of a 16-block section each tick: a 32-block range comes round in under a second. */
	private const val SECTIONS_PER_TICK = 12

	private const val SOURCE = "Nucleus Features"

	private var coalOn = false
	private var panesOn = false
	private var coalKeyDown = false
	private var paneKeyDown = false

	/** Hits per section, packed block positions, so a rescan replaces that section's. */
	private val found = HashMap<Long, LongArray>()
	private val queue = ArrayDeque<Long>()
	private var changed = false

	private var blocks = LongArray(0)
	private var colors = IntArray(0)

	private const val HIDE_COAL = 1
	private const val HIDE_GEMS = 2

	/** What the chunk builders keep while hiding the rest, 0 when nothing is hidden. Read on their threads. */
	@Volatile
	private var hideMask = 0

	/** Whether the chunk builders draw nothing but the targets. */
	@JvmStatic
	fun hiding(): Boolean = hideMask != 0

	/** Whether a block is left out of the chunk mesh. */
	@JvmStatic
	fun hides(state: BlockState): Boolean {
		val mask = hideMask
		if (mask == 0) return false
		if (mask and HIDE_COAL != 0 && isCoal(state)) return false
		if (mask and HIDE_GEMS != 0 && isGemstone(state)) return false
		return true
	}

	/** A shown block: its faces are drawn even against a hidden neighbour. */
	@JvmStatic
	fun shows(state: BlockState): Boolean = hideMask != 0 && !hides(state)

	private fun updateHiding(client: Minecraft, enabled: Boolean) {
		val on = enabled && setting.value && hideOthers.value && SkyblockLocation.inCrystalHollows
		val mask = if (!on) 0 else (if (coalOn) HIDE_COAL else 0) or (if (panesOn) HIDE_GEMS else 0)
		if (mask == hideMask) return
		hideMask = mask
		if (client.level != null) client.levelExtractor.allChanged()
	}

	fun tick(client: Minecraft, enabled: Boolean) {
		val free = client.gui.screen() == null
		val coal = free && coalKey.isDown(client.window)
		val pane = free && paneKey.isDown(client.window)
		if (enabled && setting.value && SkyblockLocation.inCrystalHollows) {
			if (coal && !coalKeyDown) toggle(coal = true)
			if (pane && !paneKeyDown) toggle(coal = false)
		}
		coalKeyDown = coal
		paneKeyDown = pane
		updateHiding(client, enabled)

		// With no highlight there is nothing to box, so nothing to scan for.
		if (!enabled || !setting.value || !SkyblockLocation.inCrystalHollows || (!coalOn && !panesOn) || highlight.selected == "None") {
			if (found.isNotEmpty() || blocks.isNotEmpty()) clear()
			return
		}
		val level = client.level ?: return
		val player = client.player ?: return

		if (queue.isEmpty()) {
			val reach = range.value
			val centre = SectionPos.of(player.blockPosition())
			val sections = (reach / 16.0).toInt() + 1
			val minSection = level.minSectionY
			val maxSection = level.maxSectionY
			val around = ArrayList<Long>()
			for (dx in -sections..sections) for (dy in -sections..sections) for (dz in -sections..sections) {
				val sy = centre.y() + dy
				if (sy < minSection || sy > maxSection) continue
				around += SectionPos.asLong(centre.x() + dx, sy, centre.z() + dz)
			}
			around.sortBy { key ->
				val dx = SectionPos.x(key) - centre.x(); val dy = SectionPos.y(key) - centre.y(); val dz = SectionPos.z(key) - centre.z()
				dx * dx + dy * dy + dz * dz
			}
			// Sections left behind are dropped once a round no longer reaches them.
			val wanted = around.toHashSet()
			found.keys.removeIf { it !in wanted }
			queue.addAll(around)
			// Once a round, so the range follows you even where nothing changed.
			changed = true
		}
		repeat(SECTIONS_PER_TICK) {
			val key = queue.removeFirstOrNull() ?: return@repeat
			scan(level, key)
		}
		if (changed) rebuild(player.blockPosition())
	}

	private fun toggle(coal: Boolean) {
		if (coal) coalOn = !coalOn else panesOn = !panesOn
		val on = if (coal) coalOn else panesOn
		Toasts.show(SOURCE, "${if (coal) "Coal" else "Gemstone"} x-ray ${if (on) "§aon" else "§coff"}", color = Toasts.Colors.MINING)
		// Start over, nearest first, so the change shows at once.
		found.clear()
		queue.clear()
		changed = true
	}

	private fun clear() {
		found.clear()
		queue.clear()
		blocks = LongArray(0)
		colors = IntArray(0)
		changed = false
	}

	private fun matches(state: BlockState): Boolean =
		(coalOn && isCoal(state)) || (panesOn && isGemstone(state))

	private fun isCoal(state: BlockState): Boolean =
		state.`is`(Blocks.COAL_ORE) || state.`is`(Blocks.DEEPSLATE_COAL_ORE) || state.`is`(Blocks.COAL_BLOCK)

	/** Gemstones grow as stained glass, both full blocks and panes. */
	private fun isGemstone(state: BlockState): Boolean {
		val block = state.block
		return block is StainedGlassPaneBlock || block is StainedGlassBlock
	}

	private fun scan(level: net.minecraft.world.level.Level, key: Long) {
		val sx = SectionPos.x(key)
		val sy = SectionPos.y(key)
		val sz = SectionPos.z(key)
		val chunk = level.chunkSource.getChunk(sx, sz, ChunkStatus.FULL, false) as? LevelChunk
		val section = chunk?.sections?.getOrNull(chunk.getSectionIndexFromSectionY(sy))
		val hits = if (section == null || section.hasOnlyAir() || !section.maybeHas(::matches)) {
			null
		} else {
			val out = ArrayList<Long>()
			val baseX = SectionPos.sectionToBlockCoord(sx)
			val baseY = SectionPos.sectionToBlockCoord(sy)
			val baseZ = SectionPos.sectionToBlockCoord(sz)
			for (y in 0..15) for (z in 0..15) for (x in 0..15) {
				if (matches(section.getBlockState(x, y, z))) out += BlockPos.asLong(baseX + x, baseY + y, baseZ + z)
			}
			out.toLongArray().takeIf { it.isNotEmpty() }
		}
		val before = found[key]
		if (hits == null) {
			if (before != null) { found.remove(key); changed = true }
		} else if (before == null || !before.contentEquals(hits)) {
			found[key] = hits
			changed = true
		}
	}

	/** The drawn list: hits inside the range, coloured once here rather than every frame. */
	private fun rebuild(centre: BlockPos) {
		changed = false
		val level = Minecraft.getInstance().level ?: return
		val reach = range.value * range.value
		val coal = coalColor.argb
		val outBlocks = ArrayList<Long>()
		val outColors = ArrayList<Int>()
		for (hits in found.values) for (packed in hits) {
			val dx = (BlockPos.getX(packed) - centre.x).toDouble()
			val dy = (BlockPos.getY(packed) - centre.y).toDouble()
			val dz = (BlockPos.getZ(packed) - centre.z).toDouble()
			if (dx * dx + dy * dy + dz * dz > reach) continue
			val block = level.getBlockState(BlockPos.of(packed)).block
			outBlocks += packed
			outColors += if (block is StainedGlassPaneBlock || block is StainedGlassBlock) (block as BeaconBeamBlock).color.textureDiffuseColor else coal
		}
		blocks = outBlocks.toLongArray()
		colors = outColors.toIntArray()
	}

	fun render(context: LevelRenderContext) {
		if (blocks.isEmpty()) return
		val style = highlight.selected
		if (style == "None") return
		WorldRender.drawBlocks(
			poseStack = context.poseStack(),
			collector = context.submitNodeCollector(),
			blocks = blocks,
			colors = colors,
			fillAlpha = if (style == "Outline") 0 else (fillOpacity.value / 100.0 * 255.0).roundToInt().coerceIn(0, 255),
			phase = true,
			lineWidth = 2f,
			outline = style != "Filled",
		)
	}

	/** A new lobby: what was found is gone, but a key left on stays on. */
	fun reset() = clear()
}
