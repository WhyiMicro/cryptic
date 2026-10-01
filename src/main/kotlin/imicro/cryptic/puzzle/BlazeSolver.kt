package imicro.cryptic.puzzle

import imicro.cryptic.feature.PuzzleSolver
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.decoration.ArmorStand

/**
 * Which blaze to shoot next.
 *
 * Ported from Odin (BSD 3-Clause, Copyright (c) 2025 odtheking). The puzzle is
 * to kill ten blazes in order of health, and the health is written on each
 * one's nametag — so the order is a sort, and the difficulty is only ever
 * reading twenty numbers in the air fast enough. Lower Blaze counts down from
 * the highest, Higher Blaze counts up from the lowest.
 *
 * The blazes themselves are armour stands: the tag is the entity, and the
 * blaze under it is drawn from the tag's own box moved down.
 */
object BlazeSolver {
	private const val LOWER = "Lower Blaze"
	private const val HIGHER = "Higher Blaze"

	private val ROOMS = setOf(LOWER, HIGHER)

	private val blazes = mutableListOf<ArmorStand>()

	/** How many were up last time, so the last one dying can be noticed. */
	private var lastCount = 10

	/**
	 * A blaze's health tag, read as loosely as it can safely be.
	 *
	 * Odin matches the whole line, level prefix and all, with the health as
	 * plain digits and commas. Hypixel writes big numbers short - 12.4M - and
	 * puts a level prefix on some floors and not others, so this looks only for
	 * the part that matters: the word Blaze, a fraction, and the heart that
	 * ends every health tag in the game.
	 */
	private val healthTag = Regex("""Blaze\s+[\d.,]+[kKmMbB]?\s*/\s*([\d.,]+)([kKmMbB]?)\x{2764}""")

	/** `12.4M` as a number, so two tags can be put in order. */
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

	/** Re-read every few ticks: a blaze dying changes the whole order. */
	fun scan() {
		if (!PuzzleRooms.clearing) return
		// A room Cryptic has not named yet is still scanned, which is Odin's
		// behaviour and worth keeping: the tags are the puzzle, and a room whose
		// chunks arrived late would otherwise be missed for as long as the map
		// took to catch up.
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

		// Lower Blaze is shot from the biggest health down, Higher Blaze from
		// the smallest up.
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
			// One left last frame and none now is the puzzle being finished,
			// which nothing else announces.
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

	/**
	 * Every armour stand near you and what this solver makes of its name.
	 *
	 * For `/cryptic debug blaze`. The whole puzzle is a health tag read off a
	 * nametag, so when it draws nothing the only question worth asking is what
	 * the tags actually say - and that is a thing no log will ever show.
	 */
	fun describe(): List<String> {
		val client = Minecraft.getInstance()
		val player = client.player ?: return listOf("No player.")
		val level = client.level ?: return listOf("No world.")

		val lines = mutableListOf(
			"Blaze: room=${PuzzleRooms.name() ?: "unnamed"} clearing=${PuzzleRooms.clearing} tracked=${blazes.size}",
		)

		// Every named entity, not only the armour stands this solver reads, so
		// that a tag carried by something else shows up here rather than as
		// nothing at all.
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
