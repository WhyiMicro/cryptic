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
import java.util.EnumMap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * `/cryptic debug structure`: measures a Crystal Hollows structure for the Nucleus map's sized
 * squares. While it's on, a right click with a wooden axe picks the clicked block for the side
 * being measured (four walls, roof, floor), then moves on to the next side not yet picked; sneaking
 * cycles the side instead. Walls give the square, roof and floor the height. `done <label>`
 * reports the box and where the nearest crystal sits in it, then starts the next one.
 */
object StructureMeasure {
	enum class Side(val label: String, val colour: Int) {
		NORTH("north wall", 0xFFFF5555.toInt()),
		EAST("east wall", 0xFFFFAA00.toInt()),
		SOUTH("south wall", 0xFFFFFF55.toInt()),
		WEST("west wall", 0xFF55FF55.toInt()),
		ROOF("roof", 0xFFFF55FF.toInt()),
		FLOOR("floor", 0xFF5555FF.toInt()),
		;

		val id get() = name.lowercase()
	}

	private val picks = EnumMap<Side, BlockPos>(Side::class.java)

	// Pick order, so undo takes back the latest.
	private val order = ArrayList<Side>()

	/** The side the next click goes to; null once all six are picked. */
	private var target: Side? = Side.NORTH

	var active = false
		private set

	private var registered = false

	private const val AREA_COLOUR = 0xFF55FFFF.toInt()
	private const val AREA_FILL = 0x2055FFFF

	val sideIds get() = Side.entries.map { it.id }

	fun toggle(): List<String> {
		register()
		active = !active
		if (!active) return listOf("§7Structure measuring §coff§7.${if (picks.isEmpty()) "" else " The ${picks.size} picked sides are kept for next time."}")
		return listOf(
			"§7Structure measuring §aon§7. Right-click a block of each side with a §fwooden axe§7: the four outer walls, the roof and the floor.",
			"§7Each click fills the side shown, then moves to the next one; §fsneak §7+ right-click cycles the side, or §f... side <side>§7.",
			"§7Then §f/cryptic debug structure done <name>§7. §f... undo §7takes the last side back, §f... clear §7starts over.",
			targetLine(),
		)
	}

	private fun register() {
		if (registered) return
		registered = true
		UseBlockCallback.EVENT.register { player, level, hand, hit ->
			if (!active || !level.isClientSide || !player.mainHandItem.`is`(Items.WOODEN_AXE)) return@register InteractionResult.PASS
			// Taken for the measure, not sent to the server; the off hand mustn't use it either.
			if (hand == InteractionHand.MAIN_HAND) {
				if (player.isShiftKeyDown) cycle() else pick(hit.blockPos)
			}
			InteractionResult.FAIL
		}
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
	}

	private fun chat(lines: List<String>) {
		val chat = Minecraft.getInstance().gui.hud.chat
		lines.forEach { chat.addClientSystemMessage(Component.literal(it)) }
	}

	private fun targetLine(): String {
		val side = target ?: return "§aAll six sides picked. §7Run §f/cryptic debug structure done <name>§7, or sneak-click to redo a side."
		val current = picks[side]?.let { " §8(now ${it.x} ${it.y} ${it.z}, a click replaces it)" } ?: ""
		return "§7Next: §f${side.label}$current"
	}

	private fun cycle() {
		val from = target?.ordinal ?: -1
		target = Side.entries[(from + 1) % Side.entries.size]
		chat(listOf(targetLine()))
	}

	fun select(id: String): List<String> {
		val side = Side.entries.firstOrNull { it.id == id.lowercase() }
			?: return listOf("§cNo side \"$id\". §7Pick one of ${sideIds.joinToString(", ")}.")
		register()
		target = side
		return listOf(targetLine())
	}

	private fun pick(pos: BlockPos) {
		val side = target
		if (side == null) {
			chat(listOf(targetLine()))
			return
		}
		picks[side] = pos
		order.remove(side)
		order += side
		target = Side.entries.let { all -> (1..all.size).map { all[(side.ordinal + it) % all.size] }.firstOrNull { it !in picks } }
		val lines = mutableListOf("§7${side.label.replaceFirstChar(Char::uppercase)}: §f${pos.x} ${pos.y} ${pos.z}")
		if (picks.size >= 2) lines += size()
		lines += targetLine()
		chat(lines)
	}

	fun undo(): List<String> {
		val side = order.removeLastOrNull() ?: return listOf("No sides to take back.")
		val pos = picks.remove(side)!!
		target = side
		return listOf("§7Took back the ${side.label} §f${pos.x} ${pos.y} ${pos.z}§7; ${picks.size} left.", targetLine())
	}

	fun clear(): List<String> {
		picks.clear()
		order.clear()
		target = Side.NORTH
		return listOf("§7Picked sides cleared.", targetLine())
	}

	/** Reports the box as [label], writes it to the log, and clears it for the next structure. */
	fun done(label: String): List<String> {
		if (picks.size < 2) return listOf("§7Pick a block on at least two sides first (${picks.size} so far).")
		val out = report(label)
		picks.clear()
		order.clear()
		target = Side.NORTH
		return out
	}

	private class Box(
		val minX: Int, val maxX: Int, val minY: Int, val maxY: Int, val minZ: Int, val maxZ: Int,
		/** Whether the roof and floor are both picked, so the height is real rather than the picks' spread. */
		val heightKnown: Boolean,
	) {
		val width get() = maxX - minX + 1
		val depth get() = maxZ - minZ + 1
		val height get() = maxY - minY + 1
		val centreX get() = (minX + maxX + 1) / 2.0
		val centreY get() = (minY + maxY + 1) / 2.0
		val centreZ get() = (minZ + maxZ + 1) / 2.0
		fun contains(x: Double, z: Double) = x >= minX && x <= maxX + 1 && z >= minZ && z <= maxZ + 1
	}

	// A picked side fixes its edge; an edge with no pick falls back to the spread of all the picks.
	private fun currentBox(): Box {
		val all = picks.values
		val minX = picks[Side.WEST]?.x ?: all.minOf { it.x }
		val maxX = picks[Side.EAST]?.x ?: all.maxOf { it.x }
		val minY = picks[Side.FLOOR]?.y ?: all.minOf { it.y }
		val maxY = picks[Side.ROOF]?.y ?: all.maxOf { it.y }
		val minZ = picks[Side.NORTH]?.z ?: all.minOf { it.z }
		val maxZ = picks[Side.SOUTH]?.z ?: all.maxOf { it.z }
		return Box(
			minOf(minX, maxX), maxOf(minX, maxX), minOf(minY, maxY), maxOf(minY, maxY), minOf(minZ, maxZ), maxOf(minZ, maxZ),
			heightKnown = Side.ROOF in picks && Side.FLOOR in picks,
		)
	}

	/** Sides picked the wrong way round (a west wall east of the east wall, say). */
	private fun swapped(): List<String> = buildList {
		val w = picks[Side.WEST]; val e = picks[Side.EAST]
		val n = picks[Side.NORTH]; val s = picks[Side.SOUTH]
		val f = picks[Side.FLOOR]; val r = picks[Side.ROOF]
		if (w != null && e != null && w.x > e.x) add("west and east")
		if (n != null && s != null && n.z > s.z) add("north and south")
		if (f != null && r != null && f.y > r.y) add("floor and roof")
	}

	private fun size(): String {
		val box = currentBox()
		val height = if (box.heightKnown) " §7x §f${box.height}" else ""
		return "§7Box so far: §f${box.width} §7x §f${box.depth}$height §7blocks " +
			"(x ${box.minX}..${box.maxX}, z ${box.minZ}..${box.maxZ}${if (box.heightKnown) ", y ${box.minY}..${box.maxY}" else ""})"
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
		val missing = Side.entries.filter { it !in picks }
		val out = mutableListOf(
			"§a$name: §f${box.width} §7x §f${box.depth}${if (box.heightKnown) " §7x §f${box.height}" else ""} §7blocks " +
				"(east-west x north-south${if (box.heightKnown) " x height" else ""}), x ${box.minX}..${box.maxX}, " +
				"y ${box.minY}..${box.maxY}${if (box.heightKnown) "" else " §8(spread of the picks)§7"}, z ${box.minZ}..${box.maxZ}, " +
				"centre §f${fmt(box.centreX)} ${fmt(box.centreY)} ${fmt(box.centreZ)}§7.",
			if (missing.isEmpty()) "§7All six sides picked." else "§7Picked ${picks.size} sides; §emissing ${missing.joinToString(", ") { it.label }}§7.",
		)
		swapped().takeIf { it.isNotEmpty() }?.let { out += "§eSwapped: ${it.joinToString(", ")} §7(the box uses them the right way round)." }
		val distance = { spot: Spot -> hypot(spot.x - box.centreX, spot.z - box.centreZ) }
		val crystal = crystals().minByOrNull(distance)
		if (crystal == null) {
			out += "§7No crystal found this lobby yet. Walk close to the structure's crystal so Nucleus Features boxes it, then run done again."
		} else {
			out += describe(crystal, box, distance(crystal))
			// What the map would need to centre the square on the structure from that crystal.
			out += "§7Centre from the crystal: §f${fmt(box.centreX - crystal.x)} §7east, §f${fmt(box.centreZ - crystal.z)} §7south" +
				"${if (box.heightKnown) ", §f${fmt(box.centreY - crystal.y)} §7up" else ""} " +
				"§8(map now: ${crystal.place.span} x ${crystal.place.spanZ}, centre offset ${fmt(crystal.place.centreOffsetX)}, ${fmt(crystal.place.centreOffsetZ)})"
			heights(crystal, box)?.let { out += it }
		}
		otherPlaces().minByOrNull(distance)?.takeIf { distance(it) < (crystal?.let(distance) ?: Double.MAX_VALUE) }?.let { place ->
			out += describe(place, box, distance(place)).replace("Closest crystal", "Closer place")
			out += "§7Centre from it: §f${fmt(box.centreX - place.x)} §7east, §f${fmt(box.centreZ - place.z)} §7south" +
				"${if (box.heightKnown) ", §f${fmt(box.centreY - place.y)} §7up" else ""} " +
				"§8(map now: ${place.place.span} x ${place.place.spanZ}, centre offset ${fmt(place.place.centreOffsetX)}, ${fmt(place.place.centreOffsetZ)})"
			heights(place, box)?.let { out += it }
		}
		Cryptic.LOGGER.info("[structure] {} sides {}", name, Side.entries.joinToString("; ") { side ->
			"${side.id} ${picks[side]?.let { "${it.x} ${it.y} ${it.z}" } ?: "-"}"
		})
		out.forEach { Cryptic.LOGGER.info("[structure] {}", it.replace(Regex("§."), "")) }
		return out
	}

	/** Where [spot] sits between the floor and the roof, when either is picked. */
	private fun heights(spot: Spot, box: Box): String? {
		val floor = picks[Side.FLOOR]
		val roof = picks[Side.ROOF]
		if (floor == null && roof == null) return null
		val parts = buildList {
			if (floor != null) add("§f${fmt(spot.y - (box.minY + 1))} §7above the floor's top")
			if (roof != null) add("§f${fmt(box.maxY - spot.y)} §7below the roof's underside")
		}
		return "§7It sits ${parts.joinToString(", ")}."
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
		if (!active || picks.isEmpty()) return
		val pose = context.poseStack()
		val collector = context.submitNodeCollector()
		val orientation = Minecraft.getInstance().gameRenderer.mainCamera().rotation()
		for ((side, pos) in picks) {
			WorldRender.drawBlock(pose, collector, pos, side.colour, 0, outline = true, fill = false, phase = true, lineWidth = 3f, fullBlock = true)
			WorldRender.drawText(
				poseStack = pose,
				collector = collector,
				orientation = orientation,
				text = Component.literal(side.label),
				x = pos.x + 0.5,
				y = pos.y + 1.4,
				z = pos.z + 0.5,
				scale = 1.5f,
				seeThrough = true,
				argb = side.colour,
				centered = true,
			)
		}
		if (picks.size < 2) return
		val box = currentBox()
		WorldRender.drawBox(
			pose, collector,
			box.minX.toDouble(), box.minY.toDouble(), box.minZ.toDouble(), box.maxX + 1.0, box.maxY + 1.0, box.maxZ + 1.0,
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
