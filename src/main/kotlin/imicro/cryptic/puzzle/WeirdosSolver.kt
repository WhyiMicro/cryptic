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
 * Which of the three chests the reward is in.
 *
 * The lines and what they mean are Odin's (BSD 3-Clause, Copyright (c) 2025
 * odtheking); how a chest is found, hidden and protected is NoammAddons' (CC0,
 * Noamm9). Each weirdo says one line, and the lines come from two fixed sets:
 * the ones only a truthful weirdo standing in front of the right chest can say,
 * and the ones that give nothing away.
 *
 * A chest is the block one step along from the weirdo who owns it, in the
 * direction the room's schematic calls east. No scan and no written-down
 * positions, which is what the first two attempts here got wrong — and the
 * rounding matters, because every Catacombs coordinate is negative and
 * truncating an entity's position towards zero puts the chest a block out in
 * both directions.
 */
object WeirdosSolver {
	private const val PUZZLE = "Three Weirdos"

	/** The height every chest in the room stands at. */
	private const val CHEST_Y = 69

	private var correct: BlockPos? = null
	private val spokeWrong = CopyOnWriteArraySet<BlockPos>()

	/** Chests taken out of the world, to be put back when the room is left. */
	private val hidden = CopyOnWriteArraySet<BlockPos>()

	/** Every chest the room has, recorded before any of them are hidden. */
	private val chests = CopyOnWriteArraySet<BlockPos>()

	/** Whether the right chest has already been announced, so it is said once. */
	private var announced = false

	/** Every line this room has said and what was made of it, for the debug report. */
	private val heard = java.util.concurrent.CopyOnWriteArrayList<String>()

	fun onRoomEnter(room: DungeonRoom) {
		// Anything hidden belonged to the room being left, so it goes back.
		if (room.data?.name != PUZZLE) reset()
	}

	/** An NPC line, already split into who said it and what they said. */
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

		// Every time, until all three are accounted for, and before anything is
		// taken out of the world: what is taken out cannot be found again. The
		// three stand far enough apart that a box around the first speaker can
		// miss the last one, and a room where only two chests were ever found
		// is a room where the third can never be worked out.
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
	 * Fills in what the three lines did not say outright.
	 *
	 * Only one weirdo can say a line that names the right chest, so two of the
	 * three only ever rule themselves out — and a room where the right one
	 * speaks first leaves the last weirdo with nothing said about its chest at
	 * all. That chest is not unknown, though: the puzzle has three chests and
	 * exactly one reward, so knowing the right one makes every other chest
	 * wrong, and knowing two wrong ones makes the last one right. Working that
	 * out here rather than per line is what stops a chest being left unmarked.
	 */
	private fun settle() {
		val known = correct
		if (known != null) {
			spokeWrong.addAll(chests.filter { it != known })
		} else if (chests.size == WEIRDOS && spokeWrong.size == WEIRDOS - 1) {
			// Only with all three in hand. Two chests and one wrong one leaves
			// the other looking like the answer, and it is only the answer if
			// the chest that was never found is not.
			correct = chests.firstOrNull { it !in spokeWrong }
		}

		if (correct != null && !announced) {
			announced = true
			Minecraft.getInstance().player?.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 2f, 1f)
		}

		spokeWrong.forEach(::hideWrong)
	}

	/**
	 * Every chest belonging to the puzzle, found around the weirdo who spoke.
	 *
	 * The three stand along one wall, so a box around the first speaker that is
	 * wide enough to reach the other two catches all three chests and nothing
	 * else: the room has no other chest at this height.
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
	 * The chest a weirdo is standing in front of.
	 *
	 * Odin's derivation, character for character: take where the weirdo stands,
	 * read it in the room's own coordinates, step one east *there*, and read it
	 * back out. Earlier versions here did the step in world coordinates with a
	 * rotated offset, which is the same arithmetic only if this mod's idea of
	 * which way the room is turned agrees with Odin's — and there is no reason
	 * it has to, so the conversion is done the way the room itself does it and
	 * the question never comes up.
	 *
	 * The nearest chest is kept as a last resort for a room whose coordinates
	 * cannot be worked out at all. It is right whenever the three are spread
	 * out and wrong when two stand close enough that one's chest is nearer the
	 * other, which is why it is a last resort and not the rule.
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

	/**
	 * What the solver has worked out, in words, for `/cryptic debug room`.
	 *
	 * This puzzle has been wrong in three different ways now, and each time the
	 * only thing visible in game was that nothing was marked — which is what a
	 * line that did not match, a chest in the wrong place and a room whose
	 * coordinates were not ready all look like from the outside.
	 */
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
	 * Takes a wrong chest out of the world
, on this client only.
	 *
	 * NoammAddons' approach, and the only one that worked: a chest is drawn
	 * from its block entity and from the block it sits in, so refusing to draw
	 * either one leaves the other. Setting the block to air removes both, and
	 * it is put back when the room is left. Nothing is sent to the server,
	 * which never learns the block is gone.
	 */
	private fun hideWrong(pos: BlockPos) {
		if (!PuzzleSolver.weirdosHideWrong.value) return
		val level = Minecraft.getInstance().level ?: return
		if (level.getBlockState(pos).block != Blocks.CHEST) return
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), UPDATE_FLAGS)
		hidden.add(pos)
	}

	/** Puts back every chest this solver took out. */
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

		val style = PuzzleSolver.weirdosStyle.selectedIndex
		correct?.let { PuzzleRender.block(context, it, PuzzleSolver.weirdosColor.argb, style, phase = false) }

		// A chest taken out of the world needs no box, and a box around nothing
		// is worse than no box at all. One that was meant to be hidden and was
		// not still does, though - hiding it failed, and silently drawing
		// neither is how a wrong chest ends up looking like an untouched one.
		spokeWrong.filter { it !in hidden }.forEach {
			PuzzleRender.block(context, it, PuzzleSolver.weirdosWrongColor.argb, style, phase = false)
		}
	}

	/**
	 * Whether a click on this block should be dropped.
	 *
	 * Opening a wrong chest fails the puzzle, and the three stand close enough
	 * together for that to be an ordinary mis-click. Once the right one is
	 * known every other chest in the room is wrong; before that, only the ones
	 * that have given themselves away are stopped.
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
	}


	/**
	 * Change the block, light it, do not tell the server.
	 *
	 * Two and sixteen: the first asks for the change to be drawn, the second
	 * keeps it off the wire.
	 */
	/** How far from the weirdo who spoke the room's chests are looked for. */
	private const val ROOM_SEARCH = 14

	/** How many of them there are, which is what the name says. */
	private const val WEIRDOS = 3

	private const val UPDATE_FLAGS = 19

	/**
	 * A line with everything that varies about it taken out.
	 *
	 * Colour codes first, and they are the whole story. Hypixel colours the
	 * name of the weirdo a line is *about*, and writes that colour as a code in
	 * the middle of the sentence — so every line naming somebody carried two
	 * codes inside it and matched nothing, while the lines naming nobody matched
	 * fine. That is exactly the shape of this puzzle's failures: never all three
	 * weirdos, never the same two, and never anything visibly wrong.
	 *
	 * Then punctuation, case, spacing and the shape of the apostrophe, none of
	 * which carries meaning and all of which Hypixel is inconsistent about. Odin
	 * and NoammAddons get away with writing punctuation into their patterns only
	 * because they leave the full stops unescaped, so each one quietly matches
	 * any character — a bug that happens to make the pattern tolerant. Taking it
	 * out of both sides does the same job on purpose.
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

	/** A line of the riddle, with `%s` wherever a weirdo's name appears. */
	private fun line(template: String): Regex =
		Regex(normalize(template).split("%s").joinToString(".+") { Regex.escape(it) })

	private fun matches(patterns: List<Regex>, message: String): Boolean {
		val normalized = normalize(message)
		return patterns.any { it.matches(normalized) }
	}

	/** Lines only the weirdo in front of the right chest can say. */
	private val solutions = listOf(
		line("The reward is not in my chest!"),
		line("At least one of them is lying, and the reward is not in %s's chest."),
		line("My chest doesn't have the reward. We are all telling the truth."),
		line("My chest has the reward and I'm telling the truth!"),
		line("The reward isn't in any of our chests."),
		line("Both of them are telling the truth. Also, %s has the reward in their chest."),
	)

	/** Lines that rule their speaker out. */
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
