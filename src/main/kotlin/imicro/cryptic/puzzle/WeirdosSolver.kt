package imicro.cryptic.puzzle

import imicro.cryptic.dungeon.map.DungeonRoom
import imicro.cryptic.feature.PuzzleSolver
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.floor

/**
 * Three Weirdos: which chest holds the reward. Lines are matched against two fixed sets (Odin's):
 * ones only the truthful weirdo at the right chest says, and ones that rule the speaker out. Chest
 * hiding and protection are NoammAddons'.
 *
 * A weirdo's chest is one step east of it in the room's coordinates. Positions are rounded, not
 * truncated, since Catacombs coordinates are negative.
 */
object WeirdosSolver {
	private const val PUZZLE = "Three Weirdos"

	private const val CHEST_Y = 69

	private var correct: BlockPos? = null
	private val spokeWrong = CopyOnWriteArraySet<BlockPos>()

	private val hidden = CopyOnWriteArraySet<BlockPos>()

	private val chests = CopyOnWriteArraySet<BlockPos>()

	private var announced = false

	/** Every line heard and what was made of it, for the debug report. */
	private val heard = java.util.concurrent.CopyOnWriteArrayList<String>()

	fun onRoomEnter(room: DungeonRoom) {
		// Leaving the room puts hidden chests back.
		if (room.data?.name != PUZZLE) reset()
	}

	fun onNpcMessage(npc: String, message: String) {
		val right = matches(solutions, message)
		val wrong = !right && matches(wrongLines, message)
		if (!right && !wrong) {
			heard.add("$npc: \"$message\" -> matched nothing")
			return
		}

		val client = Minecraft.getInstance()
		val level = client.level ?: return

		val speaker = level.entitiesForRendering()
			.firstOrNull { it is ArmorStand && it.name.string.contains(npc) }
		if (speaker == null) {
			heard.add("$npc: ${if (right) "right" else "wrong"}, but no armour stand by that name")
			return
		}

		// Looked for until all three are found, before any are hidden, since a hidden chest can't
		// be found again.
		if (chests.size + hidden.size < WEIRDOS) {
			chests.addAll(findChests(level, speaker.x, speaker.z))
		}

		val chest = chestFor(speaker.x, speaker.z)
		heard.add(
			"$npc at ${floor(speaker.x).toInt()}, ${floor(speaker.z).toInt()}: " +
				"${if (right) "right" else "wrong"} -> " +
				(chest?.let { "${it.x}, ${it.y}, ${it.z}" } ?: "no chest worked out"),
		)
		if (chest == null) return

		if (right) correct = chest else spokeWrong.add(chest)
		settle()
	}

	/**
	 * Fills in what the lines didn't say: with one reward among three chests, knowing the right one
	 * makes the others wrong, and two wrong ones make the last right.
	 */
	private fun settle() {
		val known = correct
		if (known != null) {
			spokeWrong.addAll(chests.filter { it != known })
		} else if (chests.size == WEIRDOS && spokeWrong.size == WEIRDOS - 1) {
			// Only with all three chests known; otherwise the unfound one could be the answer.
			correct = chests.firstOrNull { it !in spokeWrong }
		}

		if (correct != null && !announced) {
			announced = true
			Minecraft.getInstance().player?.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 2f, 1f)
		}

		spokeWrong.forEach(::hideWrong)
	}

	/**
	 * The puzzle's chests, found in a box around the speaker wide enough for all three. No other
	 * chest is at that height.
	 */
	private fun findChests(level: Level, x: Double, z: Double): List<BlockPos> {
		val middle = BlockPos(floor(x).toInt(), CHEST_Y, floor(z).toInt())
		val found = mutableListOf<BlockPos>()
		for (dx in -ROOM_SEARCH..ROOM_SEARCH) {
			for (dz in -ROOM_SEARCH..ROOM_SEARCH) {
				val pos = middle.offset(dx, 0, dz)
				if (level.getBlockState(pos).block == Blocks.CHEST) found.add(pos)
			}
		}
		return found
	}

	/**
	 * The chest in front of a weirdo: step one east in room coordinates and convert back, as Odin
	 * does. The nearest chest is a last resort for a room whose coordinates aren't known.
	 */
	private fun chestFor(x: Double, z: Double): BlockPos? {
		val world = BlockPos(floor(x).toInt(), CHEST_Y, floor(z).toInt())

		val room = PuzzleRooms.named(PUZZLE)
		if (room != null) {
			val relative = room.getRelativeCoords(world)
			if (relative != null) {
				val stepped = room.getRealCoords(relative.offset(1, 0, 0))
				if (stepped != null) return stepped
			}
		}

		return chests
			.filter { it !in spokeWrong && it != correct }
			.minByOrNull { (it.x - world.x) * (it.x - world.x) + (it.z - world.z) * (it.z - world.z) }
	}

	/** For `/cryptic debug room`. */
	fun describe(): List<String> {
		fun where(pos: BlockPos?) = pos?.let { "${it.x}, ${it.y}, ${it.z}" } ?: "none"
		val room = PuzzleRooms.named(PUZZLE)

		return listOf(
			"Three Weirdos: room ${if (room == null) "not placed yet" else "placed, turned ${room.rotation}"}",
			"  Chests found: ${chests.size} (${chests.joinToString(" | ") { where(it) }})",
			"  Correct: ${where(correct)}",
			"  Wrong: ${spokeWrong.joinToString(" | ") { where(it) }}",
			"  Hidden: ${hidden.size}",
			"  Heard: ${heard.size} lines",
		) + heard.map { "    $it" }
	}

	/**
	 * Removes a wrong chest from this client only, by setting the block to air (a chest is drawn
	 * from both its block and block entity). Put back when the room is left; the server never
	 * knows.
	 */
	private fun hideWrong(pos: BlockPos) {
		if (!PuzzleSolver.weirdosHideWrong.value) return
		val level = Minecraft.getInstance().level ?: return
		if (level.getBlockState(pos).block != Blocks.CHEST) return
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), UPDATE_FLAGS)
		hidden.add(pos)
	}

	private fun restoreHidden() {
		val level = Minecraft.getInstance().level
		if (level != null) {
			hidden.forEach { pos ->
				if (level.getBlockState(pos).isAir) {
					level.setBlock(pos, Blocks.CHEST.defaultBlockState(), UPDATE_FLAGS)
				}
			}
		}
		hidden.clear()
	}

	fun render(context: LevelRenderContext) {
		if (!PuzzleSolver.weirdosEnabled.value) return
		if (!PuzzleRooms.clearing) return
		renderPlaceholders(context)

		val style = PuzzleSolver.weirdosStyle.selectedIndex
		correct?.let { PuzzleRender.block(context, it, PuzzleSolver.weirdosColor.argb, style, phase = false) }

		// Hidden chests need no box; one that should have been hidden but wasn't still gets one.
		spokeWrong.filter { it !in hidden }.forEach {
			PuzzleRender.block(context, it, PuzzleSolver.weirdosWrongColor.argb, style, phase = false)
		}
	}

	/**
	 * Whether to drop a click on this block: once the right chest is known every other one; before
	 * that, the ones known to be wrong.
	 */
	@JvmStatic
	fun blocksClick(pos: BlockPos): Boolean {
		if (!PuzzleSolver.weirdosEnabled.value || !PuzzleSolver.weirdosBlockWrong.value) return false
		if (correct == null && spokeWrong.isEmpty()) return false
		if (!PuzzleRooms.clearing) return false
		if (pos == correct) return false
		if (pos in spokeWrong) return true
		return correct != null && isChest(pos)
	}

	private fun isChest(pos: BlockPos): Boolean {
		if (pos.y != CHEST_Y) return false
		val level = Minecraft.getInstance().level ?: return false
		return level.getBlockState(pos).block == Blocks.CHEST
	}

	fun reset() {
		restoreHidden()
		correct = null
		spokeWrong.clear()
		chests.clear()
		announced = false
		heard.clear()
		waiting.clear()
		places = emptyList()
	}

	// ---- Talking before they load ------------------------------------------
	//
	// The weirdos appear a moment after the room loads. Their spots are known from the start (one
	// step west of each chest), so a box is drawn on each. Right-clicking a box talks to that
	// weirdo now, or as soon as its "CLICK" stand appears. The box fills while waiting; a second
	// click cancels.

	private var places: List<BlockPos> = emptyList()

	private val waiting = LinkedHashMap<BlockPos, Long>()

	/**
	 * Where the weirdos will stand: from the usual chest positions, or a search of the room if the
	 * chests aren't there.
	 */
	private fun standingPlaces(): List<BlockPos> {
		if (places.size == WEIRDOS) return places
		val room = PuzzleRooms.named(PUZZLE) ?: return emptyList()
		val level = Minecraft.getInstance().level ?: return emptyList()
		val isChest = { pos: BlockPos -> level.getBlockState(pos).block == Blocks.CHEST }

		var chestsHere = USUAL_CHESTS.mapNotNull(room::getRealCoords).filter(isChest)
		if (chestsHere.size != WEIRDOS) {
			val middle = room.getRealCoords(BlockPos(15, CHEST_Y, 15)) ?: return emptyList()
			chestsHere = findChests(level, middle.x + 0.5, middle.z + 0.5)
		}
		places = chestsHere.mapNotNull { chest -> room.getRelativeCoords(chest)?.west()?.let(room::getRealCoords) }
			.takeIf { it.size == WEIRDOS }.orEmpty()
		return places
	}

	private fun bodyAt(place: BlockPos) =
		net.minecraft.world.phys.AABB(place.x + 0.2, place.y.toDouble(), place.z + 0.2, place.x + 0.8, place.y + 1.8, place.z + 0.8)

	private fun talkingEarly(): Boolean =
		PuzzleSolver.weirdosEnabled.value && PuzzleSolver.weirdosInstant.value && PuzzleRooms.inside(PUZZLE)

	/** The spot under the crosshair within reach, the nearest if two are crossed. */
	private fun aimedPlace(): BlockPos? {
		val player = Minecraft.getInstance().player ?: return null
		val from = player.eyePosition
		val to = from.add(player.lookAngle.scale(REACH))
		var best: BlockPos? = null
		var bestDistance = Double.MAX_VALUE
		for (place in standingPlaces()) {
			val hit = bodyAt(place).clip(from, to).orElse(null) ?: continue
			val distance = from.distanceToSqr(hit)
			if (distance < bestDistance) {
				best = place
				bestDistance = distance
			}
		}
		return best
	}

	/**
	 * A right click before the game handles it. True when it hit one of the three spots and was
	 * taken here.
	 */
	@JvmStatic
	fun onUseItem(): Boolean {
		if (!talkingEarly()) return false
		val place = aimedPlace() ?: return false
		val stand = standFor(place)
		when {
			stand != null -> talkTo(stand)
			waiting.remove(place) == null -> waiting[place] = System.currentTimeMillis()
		}
		return true
	}

	/** An entity's data arriving: when a stand gets its "CLICK" name. */
	@JvmStatic
	fun onEntityData(id: Int) {
		if (waiting.isEmpty() || !talkingEarly()) return
		val stand = Minecraft.getInstance().level?.getEntity(id) as? ArmorStand ?: return
		if (!isTalkStand(stand)) return
		val place = waiting.keys.firstOrNull { standsAt(stand, it) } ?: return
		waiting.remove(place)
		talkTo(stand)
	}

	/** Drops waits that ran out or whose weirdo arrived. */
	fun tick() {
		if (waiting.isEmpty()) return
		if (!talkingEarly()) {
			waiting.clear()
			return
		}
		val now = System.currentTimeMillis()
		val iterator = waiting.entries.iterator()
		while (iterator.hasNext()) {
			val (place, clickedAt) = iterator.next()
			if (now - clickedAt > ARRIVAL_WAIT_MILLIS) {
				iterator.remove()
				continue
			}
			val stand = standFor(place) ?: continue
			iterator.remove()
			talkTo(stand)
		}
	}

	private fun isTalkStand(stand: ArmorStand): Boolean =
		stand.customName?.string?.contains("CLICK", ignoreCase = true) == true

	private fun standsAt(stand: ArmorStand, place: BlockPos): Boolean {
		val dx = stand.x - (place.x + 0.5)
		val dz = stand.z - (place.z + 0.5)
		return dx * dx + dz * dz <= SAME_PLACE_SQ
	}

	private fun standFor(place: BlockPos): ArmorStand? =
		Minecraft.getInstance().level
			?.getEntitiesOfClass(ArmorStand::class.java, bodyAt(place).inflate(1.0)) { isTalkStand(it) && standsAt(it, place) }
			?.firstOrNull()

	/** Right-clicks [stand] as the game would, if in reach. */
	private fun talkTo(stand: ArmorStand) {
		val client = Minecraft.getInstance()
		val player = client.player ?: return
		val target = stand.boundingBox.center
		if (player.eyePosition.distanceTo(target) > REACH) return
		client.gameMode?.interact(player, stand, net.minecraft.world.phys.EntityHitResult(stand, target), net.minecraft.world.InteractionHand.MAIN_HAND)
	}

	/** A box on each empty spot, filled while a click waits. */
	private fun renderPlaceholders(context: LevelRenderContext) {
		if (!talkingEarly()) return
		val client = Minecraft.getInstance()
		val level = client.level ?: return
		val player = client.player ?: return
		for (place in standingPlaces()) {
			val body = bodyAt(place)
			val taken = level.getEntitiesOfClass(net.minecraft.world.entity.player.Player::class.java, body.inflate(0.6)) { it !== player }.isNotEmpty()
			if (taken) continue
			val style = if (place in waiting) PuzzleRender.STYLE_FILLED else PuzzleRender.STYLE_BOTH
			PuzzleRender.box(context, body, PuzzleSolver.weirdosPlaceholderColor.argb, style, phase = true)
		}
	}

	/** The three chests in every Three Weirdos room, in room coordinates. */
	private val USUAL_CHESTS = listOf(BlockPos(14, CHEST_Y, 24), BlockPos(16, CHEST_Y, 25), BlockPos(18, CHEST_Y, 24))

	private const val REACH = 5.0

	private const val ARRIVAL_WAIT_MILLIS = 4000L

	/** How far from a spot's middle its stand can be, squared, sideways only. */
	private const val SAME_PLACE_SQ = 1.0


	/** Block update flags: draw the change, don't send it to the server. */
	private const val ROOM_SEARCH = 14

	private const val WEIRDOS = 3

	private const val UPDATE_FLAGS = 19

	/**
	 * A line with what varies removed: colour codes first (Hypixel colours weirdo names
	 * mid-sentence, which broke matching), then punctuation, case, spacing and apostrophe style.
	 */
	private fun normalize(text: String): String = text
		.replace(FORMATTING, "")
		.replace('’', '\'')
		.replace('‘', '\'')
		.replace(PUNCTUATION, "")
		.replace(WHITESPACE, " ")
		.trim()
		.lowercase()

	private val FORMATTING = Regex("§.")
	private val PUNCTUATION = Regex("[.,!?;]")
	private val WHITESPACE = Regex("\\s+")

	private fun line(template: String): Regex =
		Regex(normalize(template).split("%s").joinToString(".+") { Regex.escape(it) })

	private fun matches(patterns: List<Regex>, message: String): Boolean {
		val normalized = normalize(message)
		return patterns.any { it.matches(normalized) }
	}

	private val solutions = listOf(
		line("The reward is not in my chest!"),
		line("At least one of them is lying, and the reward is not in %s's chest."),
		line("My chest doesn't have the reward. We are all telling the truth."),
		line("My chest has the reward and I'm telling the truth!"),
		line("The reward isn't in any of our chests."),
		line("Both of them are telling the truth. Also, %s has the reward in their chest."),
	)

	private val wrongLines = listOf(
		line("One of us is telling the truth!"),
		line("They are both telling the truth. The reward isn't in %s's chest."),
		line("We are all telling the truth!"),
		line("%s is telling the truth and the reward is in his chest."),
		line("My chest doesn't have the reward. At least one of the others is telling the truth!"),
		line("One of the others is lying."),
		line("They are both telling the truth, the reward is in %s's chest."),
		line("They are both lying, the reward is in my chest!"),
		line("The reward is in my chest."),
		line("The reward is not in my chest. They are both lying."),
		line("%s is telling the truth."),
		line("My chest has the reward."),
	)
}
