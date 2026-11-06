package imicro.cryptic.debug

import imicro.cryptic.Cryptic
import imicro.cryptic.feature.HollowsMap
import imicro.cryptic.feature.NucleusQol
import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Items
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * `/cryptic debug structure`: measures a Crystal Hollows structure for the Nucleus map's sized
 * squares. While it's on, a right click with a wooden axe picks the block clicked; one on each of the
 * structure's four outer walls gives the square (roof and floor don't count), drawn in the world as
 * it grows. `done <label>` reports how big it is and where the nearest crystal sits against it,
 * since the map centres a crystal structure's square on its crystal, then starts the next one.
 */
object StructureMeasure {
	private val points = ArrayList<BlockPos>()

	var active = false
		private set

	private var registered = false

	private const val POINT_COLOUR = 0xFFFFD24A.toInt()
	private const val AREA_COLOUR = 0xFF55FFFF.toInt()
	private const val AREA_FILL = 0x2055FFFF

	fun toggle(): List<String> {
		register()
		active = !active
		if (!active) return listOf("§7Structure measuring §coff§7.${if (points.isEmpty()) "" else " The ${points.size} picked blocks are kept for next time."}")
		return listOf(
			"§7Structure measuring §aon§7. Right-click a block of each of the structure's four outer walls with a §fwooden axe§7,",
			"§7then §f/cryptic debug structure done <name>§7. §f... undo §7takes the last block back, §f... clear §7starts over.",
		)
	}

	private fun register() {
		if (registered) return
		registered = true
		UseBlockCallback.EVENT.register { player, level, hand, hit ->
			if (!active || !level.isClientSide || !player.mainHandItem.`is`(Items.WOODEN_AXE)) return@register InteractionResult.PASS
			// Taken for the measure, not sent to the server; the off hand mustn't use it either.
			if (hand == InteractionHand.MAIN_HAND) pick(hit.blockPos)
			InteractionResult.FAIL
		}
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
	}

	private fun pick(pos: BlockPos) {
		if (points.lastOrNull() == pos) return
		points += pos
		val lines = mutableListOf("§7Block ${points.size}: §f${pos.x} ${pos.y} ${pos.z}")
		if (points.size >= 2) lines += size()
		lines += when {
			points.size < 4 -> "§7${4 - points.size} more, one on each wall."
			points.size == 4 -> "§aAll four walls. §7Run §f/cryptic debug structure done <name>§7."
			else -> "§eMore than four blocks: the square takes in all of them."
		}
		val chat = Minecraft.getInstance().gui.hud.chat
		lines.forEach { chat.addClientSystemMessage(Component.literal(it)) }
	}

	fun undo(): List<String> {
		if (points.isEmpty()) return listOf("No blocks to take back.")
		val pos = points.removeAt(points.size - 1)
		return listOf("§7Took back §f${pos.x} ${pos.y} ${pos.z}§7; ${points.size} left.")
	}

	fun clear(): List<String> {
		points.clear()
		return listOf("§7Picked blocks cleared.")
	}

	/** Reports the square as [label], writes it to the log, and clears it for the next structure. */
	fun done(label: String): List<String> {
		if (points.size < 2) return listOf("§7Pick a block on each of the four walls first (${points.size} so far).")
		val out = report(label)
		points.clear()
		return out
	}

	private class Box(val minX: Int, val maxX: Int, val minZ: Int, val maxZ: Int) {
		val width get() = maxX - minX + 1
		val depth get() = maxZ - minZ + 1
		val centreX get() = (minX + maxX + 1) / 2.0
		val centreZ get() = (minZ + maxZ + 1) / 2.0
		fun contains(x: Double, z: Double) = x >= minX && x <= maxX + 1 && z >= minZ && z <= maxZ + 1
	}

	private fun currentBox() = Box(points.minOf { it.x }, points.maxOf { it.x }, points.minOf { it.z }, points.maxOf { it.z })

	private fun size(): String {
		val box = currentBox()
		return "§7Square so far: §f${box.width} §7x §f${box.depth} §7blocks (x ${box.minX}..${box.maxX}, z ${box.minZ}..${box.maxZ})"
	}

	private class Spot(val place: HollowsMap.Place, val x: Double, val y: Double, val z: Double, val what: String)

	/** Every crystal whose space Nucleus Features has found this lobby. */
	private fun crystals(): List<Spot> = HollowsMap.Place.entries.mapNotNull { place ->
		val crystal = place.crystal ?: return@mapNotNull null
		NucleusQol.crystalSpaceCenter(crystal)?.let { Spot(place, it.x, it.y, it.z, "$crystal crystal") }
	}

	/** The other places the map knows, where they were found. */
	private fun otherPlaces(): List<Spot> = HollowsMap.Place.entries.mapNotNull { place ->
		val mark = HollowsMap.markOf(place) ?: return@mapNotNull null
		if (mark.source == HollowsMap.Source.CRYSTAL) return@mapNotNull null
		Spot(place, mark.pos.x + 0.5, mark.pos.y.toDouble(), mark.pos.z + 0.5, "map mark (${mark.source.name.lowercase()})")
	}

	private fun report(label: String): List<String> {
		val box = currentBox()
		val name = label.ifBlank { "unnamed" }
		val out = mutableListOf(
			"§a$name: §f${box.width} §7x §f${box.depth} §7blocks (east-west x north-south), x ${box.minX}..${box.maxX}, " +
				"z ${box.minZ}..${box.maxZ}, centre §f${fmt(box.centreX)} ${fmt(box.centreZ)}§7, from ${points.size} blocks.",
		)
		val distance = { spot: Spot -> hypot(spot.x - box.centreX, spot.z - box.centreZ) }
		val crystal = crystals().minByOrNull(distance)
		if (crystal == null) {
			out += "§7No crystal found this lobby yet. Walk close to the structure's crystal so Nucleus Features boxes it, then run done again."
		} else {
			out += describe(crystal, box, distance(crystal))
			// What the map would need to centre the square on the structure from that crystal.
			out += "§7Centre from the crystal: §f${fmt(box.centreX - crystal.x)} §7east, §f${fmt(box.centreZ - crystal.z)} §7south " +
				"§8(map now: ${crystal.place.span} x ${crystal.place.spanZ}, centre offset ${fmt(crystal.place.centreOffsetX)}, ${fmt(crystal.place.centreOffsetZ)})"
		}
		otherPlaces().minByOrNull(distance)?.takeIf { distance(it) < (crystal?.let(distance) ?: Double.MAX_VALUE) }?.let { place ->
			out += describe(place, box, distance(place)).replace("Closest crystal", "Closer place")
			out += "§7Centre from it: §f${fmt(box.centreX - place.x)} §7east, §f${fmt(box.centreZ - place.z)} §7south " +
				"§8(map now: ${place.place.span} x ${place.place.spanZ}, centre offset ${fmt(place.place.centreOffsetX)}, ${fmt(place.place.centreOffsetZ)})"
		}
		Cryptic.LOGGER.info("[structure] {} blocks {}", name, points.joinToString("; ") { "${it.x} ${it.y} ${it.z}" })
		out.forEach { Cryptic.LOGGER.info("[structure] {}", it.replace(Regex("§."), "")) }
		return out
	}

	private fun describe(spot: Spot, box: Box, distance: Double): String {
		val dx = spot.x - box.centreX
		val dz = spot.z - box.centreZ
		val inside = box.contains(spot.x, spot.z)
		return "§7Closest crystal: §f${spot.place.title}§7's ${spot.what} at §f${fmt(spot.x)} ${fmt(spot.y)} ${fmt(spot.z)}§7, " +
			"${fmt(distance)} blocks from the centre (${direction(dx, "east", "west")}, ${direction(dz, "south", "north")}), " +
			"${if (inside) "inside" else "§coutside§7"} the square."
	}

	private fun render(context: LevelRenderContext) {
		if (!active || points.isEmpty()) return
		val pose = context.poseStack()
		val collector = context.submitNodeCollector()
		for (pos in points) {
			WorldRender.drawBlock(pose, collector, pos, POINT_COLOUR, 0, outline = true, fill = false, phase = true, lineWidth = 3f, fullBlock = true)
		}
		if (points.size < 2) return
		val box = currentBox()
		val minY = points.minOf { it.y }.toDouble()
		val maxY = points.maxOf { it.y } + 1.0
		WorldRender.drawBox(
			pose, collector,
			box.minX.toDouble(), minY, box.minZ.toDouble(), box.maxX + 1.0, maxY, box.maxZ + 1.0,
			AREA_COLOUR, AREA_FILL, outline = true, fill = true, phase = true, lineWidth = 2f,
		)
	}

	private fun direction(offset: Double, positive: String, negative: String): String = when {
		abs(offset) < 0.5 -> "level ${if (positive == "east") "east to west" else "north to south"}"
		offset > 0 -> "${fmt(offset)} $positive"
		else -> "${fmt(-offset)} $negative"
	}

	private fun fmt(value: Double): String =
		if (value == value.toLong().toDouble()) value.toLong().toString() else String.format(Locale.ROOT, "%.1f", value)
}
