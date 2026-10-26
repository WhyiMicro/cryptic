package imicro.cryptic.puzzle

import imicro.cryptic.feature.PuzzleSolver
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.decoration.ArmorStand

/**
 * Blaze: which blaze to shoot next, sorted by the health on their tags. Lower Blaze goes from
 * highest health down, Higher Blaze from lowest up. Ported from Odin.
 */
object BlazeSolver {
	private const val LOWER = "Lower Blaze"
	private const val HIGHER = "Higher Blaze"

	private val ROOMS = setOf(LOWER, HIGHER)

	private val blazes = mutableListOf<ArmorStand>()

	private var lastCount = 10

	/**
	 * Matches only the essentials (the word Blaze, a fraction, and the heart), since Hypixel
	 * shortens big numbers (12.4M) and only sometimes adds a level prefix.
	 */
	private val healthTag = Regex("""Blaze\s+[\d.,]+[kKmMbB]?\s*/\s*([\d.,]+)([kKmMbB]?)\x{2764}""")

	private fun healthOf(name: String): Double? {
		val match = healthTag.find(name) ?: return null
		val digits = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
		return when (match.groupValues[2].lowercase()) {
			"k" -> digits * 1_000
			"m" -> digits * 1_000_000
			"b" -> digits * 1_000_000_000
			else -> digits
		}
	}

	fun scan() {
		if (!PuzzleRooms.clearing) return
		// Rooms not identified yet are still scanned, so a room whose chunks arrived late isn't
		// missed.
		val room = PuzzleRooms.name()
		if (room != null && room !in ROOMS) {
			blazes.clear()
			return
		}
		val level = Minecraft.getInstance().level ?: return

		val health = HashMap<ArmorStand, Double>()
		blazes.clear()
		level.entitiesForRendering().forEach { entity ->
			if (entity !is ArmorStand) return@forEach
			val found = healthOf(entity.name.string) ?: return@forEach
			health[entity] = found
			blazes.add(entity)
		}

		if (room == LOWER) blazes.sortByDescending { health[it] } else blazes.sortBy { health[it] }
	}

	fun render(context: LevelRenderContext) {
		if (!PuzzleSolver.blazeEnabled.value) return
		if (!PuzzleRooms.clearing) return
		val room = PuzzleRooms.name()
		if (room != null && room !in ROOMS) return

		val level = Minecraft.getInstance().level ?: return
		blazes.removeAll { level.getEntity(it.id) == null }

		if (blazes.isEmpty()) {
			// One left and now none: the puzzle is done, which nothing else announces.
			if (lastCount == 1) {
				room?.let(PuzzleSolver::onPuzzleComplete)
				if (PuzzleSolver.blazeSendComplete.value) {
					Minecraft.getInstance().connection?.sendCommand("pc Blaze puzzle solved!")
				}
				lastCount = 0
			}
			return
		}
		lastCount = blazes.size

		val style = PuzzleSolver.blazeStyle.selectedIndex
		val width = PuzzleSolver.blazeLineWidth.value.toFloat()

		blazes.forEachIndexed { index, stand ->
			val color = when (index) {
				0 -> PuzzleSolver.blazeFirstColor.argb
				1 -> PuzzleSolver.blazeSecondColor.argb
				2 -> PuzzleSolver.blazeThirdColor.argb
				else -> PuzzleSolver.blazeOtherColor.argb
			}

			val box = stand.boundingBox.inflate(0.5, 1.0, 0.5).move(0.0, -1.0, 0.0)
			PuzzleRender.box(context, box, color, style, phase = false, lineWidth = width)

			if (PuzzleSolver.blazeNextLine.value && index > 0 && index <= PuzzleSolver.blazeLineCount.value.toInt()) {
				val previous = blazes[index - 1]
				PuzzleRender.line(
					context,
					listOf(previous.position().add(0.0, previous.bbHeight / 2.0, 0.0), box.center),
					color,
					phase = false,
					lineWidth = width,
				)
			}
		}
	}

	/** Every named entity nearby and what the solver makes of it, for `/cryptic debug blaze`. */
	fun describe(): List<String> {
		val client = Minecraft.getInstance()
		val player = client.player ?: return listOf("No player.")
		val level = client.level ?: return listOf("No world.")

		val lines = mutableListOf(
			"Blaze: room=${PuzzleRooms.name() ?: "unnamed"} clearing=${PuzzleRooms.clearing} tracked=${blazes.size}",
		)

		level.getEntities(player, player.boundingBox.inflate(DEBUG_RADIUS)) { true }.forEach { entity ->
			val name = entity.name.string
			if (name.isBlank() || name == "Armor Stand") return@forEach
			val kind = if (entity is ArmorStand) "stand" else entity.type.description.string
			lines += "  [$kind] \"$name\" -> ${healthOf(name)?.toLong()?.toString() ?: "no health read"}"
		}

		if (lines.size == 1) lines += "  Nothing named within ${DEBUG_RADIUS.toInt()} blocks."
		return lines
	}

	private const val DEBUG_RADIUS = 24.0

	fun reset() {
		blazes.clear()
		lastCount = 10
	}
}
