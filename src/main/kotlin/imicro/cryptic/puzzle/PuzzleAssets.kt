package imicro.cryptic.puzzle

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import imicro.cryptic.Cryptic
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos

/**
 * The answer sheets the puzzle solvers read.
 *
 * All five files are Odin's, copied verbatim (BSD 3-Clause, Copyright (c) 2025
 * odtheking); the licence is in `licenses/Odin-LICENSE.txt`. None of them is
 * anything Cryptic could work out for itself: they are the known solutions to
 * puzzles whose layouts Hypixel picks from a fixed set, collected by playing
 * them.
 *
 * Read on first use rather than at startup, the same as the room list, because
 * the resource manager is only ready once a world is being joined.
 */
object PuzzleAssets {
	private val gson = Gson()

	/** A position in a schematic, as the ice fill file writes them. */
	private data class Xyz(val x: Int = 0, val y: Int = 0, val z: Int = 0)

	private data class IceFillFile(
		val identifier: List<List<List<Xyz>>> = emptyList(),
		val easy: List<List<List<Xyz>>> = emptyList(),
		val hard: List<List<List<Xyz>>> = emptyList(),
	)

	/**
	 * Which floor of the ice fill puzzle is which, and the line to walk on it.
	 *
	 * [identifier] is a pair of positions per pattern — one that must be air
	 * and one that must not — which is how the floor in front of you is told
	 * from the other four it could have been.
	 */
	class IceFillData(
		val identifier: List<List<List<BlockPos>>>,
		val easy: List<List<List<BlockPos>>>,
		val hard: List<List<List<BlockPos>>>,
	)

	/** Water: optimised → pattern → which slots are out → lever → times. */
	var water: Map<String, Map<String, Map<String, Map<String, List<Double>>>>> = emptyMap()
		private set

	/** Boulder: the board read as ones and zeroes → the clicks that solve it. */
	var boulder: Map<String, List<List<Int>>> = emptyMap()
		private set

	/** Creeper beams: pairs of lantern positions, one pair per line. */
	var beams: List<List<Int>> = emptyList()
		private set

	/** Quiz: a question, or enough of one to recognise it, and its answers. */
	var quiz: Map<String, List<String>> = emptyMap()
		private set

	var iceFill: IceFillData = IceFillData(emptyList(), emptyList(), emptyList())
		private set

	private var loaded = false

	fun ensureLoaded() {
		if (loaded) return
		loaded = true

		water = read("water-solutions.json") ?: emptyMap()
		boulder = read("boulder-solutions.json") ?: emptyMap()
		beams = read("creeper-beams-solutions.json") ?: emptyList()
		quiz = read("quiz-answers.json") ?: emptyMap()

		val ice: IceFillFile? = read("ice-fill-floors.json")
		if (ice != null) {
			iceFill = IceFillData(
				ice.identifier.map { floor -> floor.map { pattern -> pattern.map(::toBlockPos) } },
				ice.easy.map { floor -> floor.map { pattern -> pattern.map(::toBlockPos) } },
				ice.hard.map { floor -> floor.map { pattern -> pattern.map(::toBlockPos) } },
			)
		}
	}

	private fun toBlockPos(pos: Xyz): BlockPos = BlockPos(pos.x, pos.y, pos.z)

	private inline fun <reified T> read(file: String): T? {
		val resource = Minecraft.getInstance().resourceManager
			.getResource(Cryptic.id("puzzles/$file"))
			.orElse(null)

		if (resource == null) {
			Cryptic.LOGGER.error("Cryptic's puzzle answers are missing: {}", file)
			return null
		}

		return runCatching {
			resource.open().bufferedReader().use { reader ->
				gson.fromJson<T>(reader, object : TypeToken<T>() {}.type)
			}
		}.getOrElse {
			Cryptic.LOGGER.error("Cryptic could not read $file", it)
			null
		}
	}
}
