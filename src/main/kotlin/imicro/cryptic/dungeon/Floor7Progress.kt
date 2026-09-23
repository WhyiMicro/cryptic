package imicro.cryptic.dungeon

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Which terminals, devices and levers of the current section are already done.
 *
 * Hypixel marks every one of them with an invisible armour stand standing at
 * it, and renames that stand as the thing is finished: "Inactive Terminal"
 * becomes "Terminal Active", a device's "Inactive" becomes "Active", a lever's
 * "Not Activated" becomes something that is no longer that. The names are
 * Odin's and NoammAddons' (BSD 3-Clause / CC0), which read the same signal.
 *
 * Both halves are read, and the finished half is what makes a teammate's work
 * show up: absence alone cannot tell *done* from *not sent yet*.
 *
 * What this cannot do is see further than the server is willing to talk about.
 * Armour stands are sent to a client within a range Hypixel chooses, and a
 * section is bigger than that range — so the far end of one is genuinely
 * unknowable until somebody walks towards it. Everything learned is therefore
 * kept for the rest of the phase rather than re-derived: walking past a
 * terminal once is enough to know about it for good.
 *
 * Chat is no use for the rest. Hypixel announces each one — "activated a
 * terminal! (5/7)" — but never says *which*, and the count is per section
 * without naming the section, so two of them can read (5/7) at the same moment
 * for different corners of the tower.
 */
object Floor7Progress {
	/**
	 * How often the stands are re-read, in client ticks.
	 *
	 * A quarter of a second. Nothing here changes faster than somebody can
	 * click, and the alternative is a hundred-block entity query every tick.
	 */
	private const val SCAN_INTERVAL_TICKS = 5

	/**
	 * How far a terminal's or lever's own stand may sit from the position
	 * recorded for it.
	 *
	 * The recorded positions are where a *label* should float, which is not
	 * exactly where Hypixel put the stand, so this has to be loose enough to
	 * cover the difference. The nearest two terminals in any section are eight
	 * blocks apart, so there is room to be loose without them stealing each
	 * other's stands.
	 */
	private const val MATCH_RANGE = 5.0

	/** How far past the outermost task the world is asked for stands. */
	private const val SEARCH_MARGIN = 24.0

	/** What Hypixel calls a thing nobody has finished yet. */
	private fun pendingNames(kind: Floor7Tasks.Kind): Set<String> = when (kind) {
		Floor7Tasks.Kind.TERMINAL -> setOf("Inactive Terminal")
		Floor7Tasks.Kind.DEVICE -> setOf("Inactive", "Inactive Device")
		Floor7Tasks.Kind.LEVER -> setOf("Not Activated", "Inactive Lever")
	}

	/**
	 * Every name a device's own stands are known to carry.
	 *
	 * A device wears two stands, one over the other: the state, and the word
	 * "Device" under it. Knowing the whole set is what lets a device be matched
	 * by name across the section rather than by distance — there is only one of
	 * them in a section, so nothing else can be confused for it, and its stands
	 * need not sit anywhere near the point its label floats at.
	 */
	private val DEVICE_NAMES = setOf("Inactive", "Inactive Device", "Active", "Device Active", "Device")

	/** Strips the section-sign codes Hypixel colours the names with. */
	private val formattingPattern = Regex("§.")

	private data class Key(val section: Int, val kind: Floor7Tasks.Kind, val number: Int)

	/**
	 * Tasks whose stand has been seen standing there unfinished.
	 *
	 * The fallback for a lever, whose finished name is the one not confirmed
	 * here: a stand that was saying "not activated" and has stopped saying it,
	 * while some other stand is still loaded nearby to prove the answer is not
	 * simply out of range.
	 */
	private val seen = HashSet<Key>()

	/**
	 * Kept for the whole phase rather than per section, because it is a fact
	 * about the tower and not about where the player happens to be standing.
	 */
	private val done = HashSet<Key>()

	private var ticksUntilScan = 0

	private var inPhase = false

	/** True once [task] in [section] has been finished, by anyone. */
	fun isDone(section: Int, task: Floor7Tasks.Task): Boolean = Key(section, task.kind, task.number) in done

	fun tick(client: Minecraft, active: Boolean) {
		val level = client.level
		val section = Floor7.p3Section

		// Leaving the boss room entirely is the only thing that makes any of
		// this untrue; walking out of a section is not.
		if (!Floor7.inBossRoom) {
			if (inPhase) forget()
			return
		}
		inPhase = true

		if (!active || level == null || section == null) return
		if (ticksUntilScan-- > 0) return
		ticksUntilScan = SCAN_INTERVAL_TICKS

		val tasks = Floor7Tasks.tasksIn(section)
		if (tasks.isEmpty()) return

		val stands = namedStandsAround(client, tasks)
		if (stands.isEmpty()) return

		for (task in tasks) {
			val key = Key(section, task.kind, task.number)
			val mine = standsFor(task, stands)

			when {
				// Said to be unfinished, which settles it.
				mine.any { it.name in pendingNames(task.kind) } -> {
					seen.add(key)
					done.remove(key)
				}
				// Something is standing there and none of it says unfinished.
				mine.isNotEmpty() -> done.add(key)
				// Nothing within reach. Either finished and renamed to
				// something unrecognised, or simply out of the range the server
				// sends stands over — and the second is far more common, so the
				// last answer stands unless this one was watched being done.
				key in seen -> done.add(key)
				else -> Unit
			}
		}
	}

	fun forget() {
		seen.clear()
		done.clear()
		ticksUntilScan = 0
		inPhase = false
	}

	/**
	 * Every named stand near the current section, and what each task made of
	 * them — for `/cryptic debug devices`.
	 *
	 * The positions and names Hypixel actually uses are the one thing here that
	 * cannot be worked out from a distance, so there is a way to ask.
	 */
	fun describe(): List<String> {
		val client = Minecraft.getInstance()
		val section = Floor7.p3Section
			?: return listOf("Not in a Goldor section. Phase: ${Floor7.phase ?: "outside the boss room"}.")

		val tasks = Floor7Tasks.tasksIn(section)
		val stands = namedStandsAround(client, tasks)

		val lines = mutableListOf("Section s$section — ${stands.size} named armour stands in reach:")
		stands.sortedBy { it.name }.forEach {
			lines += "  \"${it.name}\" at ${"%.1f".format(it.position.x)} / " +
				"${"%.1f".format(it.position.y)} / ${"%.1f".format(it.position.z)}"
		}

		lines += "Tasks:"
		tasks.forEach { task ->
			val key = Key(section, task.kind, task.number)
			val mine = standsFor(task, stands).map { it.name }
			val state = when {
				key in done -> "done"
				mine.isEmpty() -> "unknown (nothing matched)"
				else -> "pending"
			}
			lines += "  ${task.kind} ${task.number}: $state, matched ${mine.ifEmpty { "nothing" }}"
		}
		return lines
	}

	private data class NamedStand(val name: String, val position: Vec3)

	/** One query for the whole section rather than one per thing in it. */
	private fun namedStandsAround(client: Minecraft, tasks: List<Floor7Tasks.Task>): List<NamedStand> {
		val level = client.level ?: return emptyList()
		val box = AABB(
			tasks.minOf { it.anchor.x } - SEARCH_MARGIN,
			tasks.minOf { it.anchor.y } - SEARCH_MARGIN,
			tasks.minOf { it.anchor.z } - SEARCH_MARGIN,
			tasks.maxOf { it.anchor.x } + SEARCH_MARGIN,
			tasks.maxOf { it.anchor.y } + SEARCH_MARGIN,
			tasks.maxOf { it.anchor.z } + SEARCH_MARGIN,
		)

		return level.getEntitiesOfClass(ArmorStand::class.java, box) { it.customName != null }
			.mapNotNull { stand ->
				val name = stand.customName?.string?.replace(formattingPattern, "") ?: return@mapNotNull null
				NamedStand(name, stand.position())
			}
	}

	/**
	 * The stands belonging to one task.
	 *
	 * A device is matched by name across the whole section, because there is
	 * only one of them in it and its stands do not sit where its label does.
	 * Terminals and levers are matched by distance, since a section holds
	 * several of each and only position tells them apart.
	 */
	private fun standsFor(task: Floor7Tasks.Task, stands: List<NamedStand>): List<NamedStand> =
		if (task.kind == Floor7Tasks.Kind.DEVICE) {
			stands.filter { it.name in DEVICE_NAMES }
		} else {
			stands.filter {
				it.name !in DEVICE_NAMES && it.position.distanceToSqr(task.anchor) <= MATCH_RANGE * MATCH_RANGE
			}
		}
}
