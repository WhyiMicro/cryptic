package imicro.cryptic.feature

import imicro.cryptic.gui.MultiSelectModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Where each crystal place's own area begins: the one the sidebar names and Hypixel acts on (the
 * Jungle Temple's slow, Bal's spawns, the Metal Detector). Measured with `/cryptic debug structure`
 * as the last block you can stand on outside each side, so the area starts one block in from the
 * walls, two above the floor block, and just under your feet on the roof block. Across, each place
 * is its [HollowsMap.Place.span] around its crystal; the heights are here.
 */
object HollowsBounds {
	/** Feet heights inside, from the crystal's middle; places sit at different heights per lobby. */
	private class Heights(val low: Double, val high: Double)

	private val HEIGHTS = mapOf(
		// The Metal Detector's own reach: a block less at the roof and the floor than the zone.
		HollowsMap.Place.MINES_OF_DIVAN to Heights(-33.5, 86.5),
		HollowsMap.Place.JUNGLE_TEMPLE to Heights(-11.5, 107.5),
		// Nothing happens on entering, so taken a block in at the roof and the floor like the mines.
		HollowsMap.Place.GOBLIN_QUEENS_DEN to Heights(-61.5, 49.5),
		HollowsMap.Place.KHAZAD_DUM to Heights(-10.5, 32.5),
	)

	/** The Jade crystal's middle height, for the mines placed by their Keepers before it's found. */
	private const val JADE_Y = 98.5

	private const val HOLLOWS_BOTTOM = 31.0
	private const val HOLLOWS_TOP = 189.0

	private fun anchor(place: HollowsMap.Place): Vec3? {
		val crystal = place.crystal ?: return null
		val centre = NucleusQol.crystalSpaceCenter(crystal)
		if (centre != null) return centre
		if (place != HollowsMap.Place.MINES_OF_DIVAN) return null
		val middle = NucleusQol.minesMiddle() ?: return null
		return Vec3(middle.x, JADE_Y, middle.z)
	}

	/** [place]'s area, for feet positions; null until its crystal (or for the mines, a Keeper) is found. */
	fun inside(place: HollowsMap.Place): AABB? {
		val at = anchor(place) ?: return null
		val heights = HEIGHTS[place]
		val low: Double
		val high: Double
		when {
			heights == null -> { low = HOLLOWS_BOTTOM; high = HOLLOWS_TOP }
			else -> { low = at.y + heights.low; high = at.y + heights.high }
		}
		val cx = at.x + place.centreOffsetX
		val cz = at.z + place.centreOffsetZ
		return AABB(cx - place.span / 2.0, low, cz - place.spanZ / 2.0, cx + place.span / 2.0, high, cz + place.spanZ / 2.0)
	}

	// ---- Borders --------------------------------------------------------------------------------

	private val BORDER_PLACES = listOf(
		HollowsMap.Place.KHAZAD_DUM, HollowsMap.Place.JUNGLE_TEMPLE, HollowsMap.Place.MINES_OF_DIVAN,
		HollowsMap.Place.GOBLIN_QUEENS_DEN, HollowsMap.Place.LOST_PRECURSOR_CITY,
	)

	@JvmField
	val borders = ToggleModuleSetting(
		id = "area_borders",
		label = "Area borders",
		defaultValue = true,
		description = "A line on the ground where you cross into Khazad-dûm (Bal spawns) or the Jungle Temple (you're slowed), " +
			"in its crystal's colour, and a tint on the last floor and roof blocks you can stand on outside. Shown once its crystal is found.",
	)

	@JvmField
	val borderPlaces = MultiSelectModuleSetting(
		id = "area_border_places",
		label = "Places",
		options = BORDER_PLACES.map { it.title },
		defaultSelected = setOf(HollowsMap.Place.KHAZAD_DUM.title, HollowsMap.Place.JUNGLE_TEMPLE.title),
		visibleIf = { borders.value },
	)

	@JvmField
	val borderTint = ToggleModuleSetting(
		id = "area_border_tint",
		label = "Floor and roof tint",
		defaultValue = true,
		description = "The blocks under and over the area you can stand on without being in it, tinted.",
		visibleIf = { borders.value },
	)

	/** [tint] is minX, y, minZ, maxX, maxZ per rectangle. */
	private class Drawn(val line: Int, val runs: List<List<Vec3>>, val tint: DoubleArray, val tintArgb: Int)

	private var drawn: List<Drawn> = emptyList()
	private var ticksUntilUpdate = 0

	/** How near a border is drawn, sideways and up or down. */
	private const val RANGE = 48

	/** How far up or down from your feet a wall's ground is looked for. */
	private const val GROUND_SEARCH = 12

	/** A ledge taller than this breaks the line rather than draw a wall of it. */
	private const val MAX_STEP = 3.0

	private const val LIFT = 0.03
	private const val LINE_WIDTH = 2.5f
	private const val TINT_ALPHA = 0x48

	fun reset() {
		drawn = emptyList()
	}

	/** Called while in the Hollows with the module on. Reads the world, so a few times a second. */
	fun tick(client: Minecraft) {
		if (ticksUntilUpdate-- > 0) return
		ticksUntilUpdate = 4
		val level = client.level
		val player = client.player
		if (!borders.value || level == null || player == null) {
			drawn = emptyList()
			return
		}
		val at = player.position()
		drawn = BORDER_PLACES.filter { borderPlaces.isSelected(it.title) }.mapNotNull { place ->
			val box = inside(place) ?: return@mapNotNull null
			if (!box.inflate(RANGE.toDouble()).contains(at)) return@mapNotNull null
			val rgb = place.color and 0xFFFFFF
			// The city's roof and floor aren't measured, so it has only its walls.
			val tint = if (borderTint.value && place in HEIGHTS) tint(level, box, at) else DoubleArray(0)
			Drawn(0xFF000000.toInt() or rgb, walls(level, box, at), tint, (TINT_ALPHA shl 24) or rgb)
		}
	}

	fun render(context: LevelRenderContext) {
		val list = drawn
		if (list.isEmpty()) return
		for (d in list) {
			for (run in d.runs) WorldRender.drawLine(context.poseStack(), context.submitNodeCollector(), run, d.line, LINE_WIDTH, phase = false)
			WorldRender.drawTops(context.poseStack(), context.submitNodeCollector(), d.tint, d.tintArgb, phase = false)
		}
	}

	private fun block(v: Double) = floor(v + 1e-6).toInt()

	/** Each wall's line along the ground of its outside column, near [at]. */
	private fun walls(level: Level, box: AABB, at: Vec3): List<List<Vec3>> {
		val runs = ArrayList<List<Vec3>>()
		val minX = block(box.minX)
		val maxX = block(box.maxX)
		val minZ = block(box.minZ)
		val maxZ = block(box.maxZ)
		val lowY = block(box.minY)
		val highY = block(box.maxY) - 1
		val feet = floor(at.y).toInt()
		val probe = BlockPos.MutableBlockPos()
		val px = floor(at.x).toInt()
		val pz = floor(at.z).toInt()
		// West and east: the line runs north to south on the plane between the outside and inside columns.
		for (plane in intArrayOf(minX, maxX)) {
			if (abs(at.x - plane) > RANGE) continue
			val outside = if (plane == minX) minX - 1 else maxX
			wall(runs, max(minZ, pz - RANGE), min(maxZ - 1, pz + RANGE), { z -> ground(level, probe, outside, z, feet, lowY, highY) }) { along, y ->
				Vec3(plane.toDouble(), y, along)
			}
		}
		for (plane in intArrayOf(minZ, maxZ)) {
			if (abs(at.z - plane) > RANGE) continue
			val outside = if (plane == minZ) minZ - 1 else maxZ
			wall(runs, max(minX, px - RANGE), min(maxX - 1, px + RANGE), { x -> ground(level, probe, x, outside, feet, lowY, highY) }) { along, y ->
				Vec3(along, y, plane.toDouble())
			}
		}
		return runs
	}

	private inline fun wall(runs: MutableList<List<Vec3>>, from: Int, to: Int, ground: (Int) -> Double, point: (Double, Double) -> Vec3) {
		var run = ArrayList<Vec3>()
		var last = Double.NaN
		for (i in from..to) {
			val y = ground(i)
			if (y.isNaN() || (!last.isNaN() && abs(y - last) > MAX_STEP)) {
				if (run.size >= 2) runs += run
				run = ArrayList()
				last = Double.NaN
				if (y.isNaN()) continue
			}
			// A step joins at the shared edge.
			if (y != last) run += point(i.toDouble(), y + LIFT)
			run += point(i + 1.0, y + LIFT)
			last = y
		}
		if (run.size >= 2) runs += run
	}

	/**
	 * The height you'd stand at in column [x] [z] with your feet in the block nearest [feet] within
	 * [low]..[high], on a slab's or step's top where there is one; NaN if there's nowhere.
	 */
	private fun ground(level: Level, probe: BlockPos.MutableBlockPos, x: Int, z: Int, feet: Int, low: Int, high: Int): Double {
		for (d in 0..GROUND_SEARCH) {
			val down = feet - d
			if (down in low..high) standHeight(level, probe, x, down, z).let { if (!it.isNaN()) return it }
			val up = feet + d
			if (d > 0 && up in low..high) standHeight(level, probe, x, up, z).let { if (!it.isNaN()) return it }
		}
		return Double.NaN
	}

	/** Where you'd stand with your feet in block [feet]: on a low block in it, or on what's under it. */
	private fun standHeight(level: Level, probe: BlockPos.MutableBlockPos, x: Int, feet: Int, z: Int): Double {
		probe.set(x, feet + 1, z)
		if (!level.getBlockState(probe).getCollisionShape(level, probe).isEmpty) return Double.NaN
		probe.set(x, feet, z)
		val inFeet = level.getBlockState(probe).getCollisionShape(level, probe)
		if (!inFeet.isEmpty) {
			// A bottom slab, carpet or snow: stood on inside the block. Anything taller is a block to climb.
			val top = inFeet.max(Direction.Axis.Y)
			return if (top <= LOW_BLOCK) feet + top else Double.NaN
		}
		probe.set(x, feet - 1, z)
		val under = level.getBlockState(probe).getCollisionShape(level, probe)
		if (under.isEmpty) return Double.NaN
		val top = under.max(Direction.Axis.Y)
		// A low block under is stood on one block down; a fence's top counts here.
		return if (top <= LOW_BLOCK) Double.NaN else feet - 1 + top
	}

	private const val LOW_BLOCK = 0.5 + 1e-6

	/** Where you can stand just outside under the floor and over the roof, near [at], as rectangles. */
	private fun tint(level: Level, box: AABB, at: Vec3): DoubleArray {
		val out = ArrayList<Double>()
		val probe = BlockPos.MutableBlockPos()
		val px = floor(at.x).toInt()
		val pz = floor(at.z).toInt()
		val fromX = max(block(box.minX), px - RANGE)
		val toX = min(block(box.maxX) - 1, px + RANGE)
		val fromZ = max(block(box.minZ), pz - RANGE)
		val toZ = min(block(box.maxZ) - 1, pz + RANGE)
		// Feet one block under the area, or just over it.
		for (feet in intArrayOf(block(box.minY) - 1, block(box.maxY))) {
			if (abs(at.y - feet) > RANGE) continue
			for (x in fromX..toX) {
				// Joined along each row where the height matches, so the tint has no seams.
				var start = 0
				var height = Double.NaN
				for (z in fromZ..toZ + 1) {
					val h = if (z > toZ) Double.NaN else standHeight(level, probe, x, feet, z)
					if (h == height && !h.isNaN()) continue
					if (!height.isNaN()) {
						out += x.toDouble(); out += height; out += start.toDouble(); out += x + 1.0; out += z.toDouble()
					}
					start = z
					height = h
				}
			}
		}
		return out.toDoubleArray()
	}

	fun describe(): List<String> {
		val player = Minecraft.getInstance().player ?: return listOf("Not in a world")
		val at = player.position()
		val lines = HEIGHTS.keys.plus(HollowsMap.Place.LOST_PRECURSOR_CITY).mapTo(ArrayList()) { place ->
			val box = inside(place) ?: return@mapTo "${place.title}: not found yet"
			"${place.title}: x %.0f..%.0f, y %.0f..%.0f, z %.0f..%.0f (feet), you're %s".format(
				box.minX, box.maxX - 1, box.minY, box.maxY - 1, box.minZ, box.maxZ - 1,
				if (box.contains(at)) "§ainside" else "§coutside",
			)
		}
		lines += describeLookedAt()
		return lines
	}

	/** The column under the crosshair as the border line reads it, for a gap in the line. */
	private fun describeLookedAt(): List<String> {
		val client = Minecraft.getInstance()
		val level = client.level ?: return emptyList()
		val hit = client.hitResult as? net.minecraft.world.phys.BlockHitResult ?: return listOf("§7Look at a block to see how its column reads.")
		if (hit.type != net.minecraft.world.phys.HitResult.Type.BLOCK) return listOf("§7Look at a block to see how its column reads.")
		val pos = hit.blockPos
		val probe = BlockPos.MutableBlockPos()
		val lines = arrayListOf("§7Looking at ${pos.x} ${pos.y} ${pos.z} (${level.getBlockState(pos).block.name.string}):")
		for (place in BORDER_PLACES) {
			val box = inside(place) ?: continue
			val minX = block(box.minX); val maxX = block(box.maxX); val minZ = block(box.minZ); val maxZ = block(box.maxZ)
			val wall = when {
				pos.x == minX - 1 && pos.z in minZ until maxZ -> "west"
				pos.x == maxX && pos.z in minZ until maxZ -> "east"
				pos.z == minZ - 1 && pos.x in minX until maxX -> "north"
				pos.z == maxZ && pos.x in minX until maxX -> "south"
				else -> null
			}
			lines += "  ${place.title}: ${wall?.let { "outside column of the $it wall" } ?: "not a wall's outside column"}, feet y ${block(box.minY)}..${block(box.maxY) - 1}"
		}
		for (feet in pos.y - 1..pos.y + 3) {
			probe.set(pos.x, feet, pos.z)
			val state = level.getBlockState(probe)
			val shape = state.getCollisionShape(level, probe)
			val h = standHeight(level, probe, pos.x, feet, pos.z)
			probe.set(pos.x, feet, pos.z)
			lines += "  y $feet: ${state.block.name.string}, collision ${if (shape.isEmpty) "none" else "%.2f..%.2f".format(shape.min(Direction.Axis.Y), shape.max(Direction.Axis.Y))}" +
				", feet here stand at ${if (h.isNaN()) "-" else "%.2f".format(h)}"
		}
		return lines
	}
}
