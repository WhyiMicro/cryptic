package imicro.cryptic.dungeon

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Which terminals, devices and levers of the current section are done. Hypixel puts a named armour
 * stand at each and renames it when finished ("Inactive Terminal" to "Terminal Active"; names as
 * Odin and NoammAddons read them).
 *
 * Stands are only sent within a range smaller than a section, so everything learned is kept for the
 * phase. Chat doesn't help: it gives counts, not which one.
 */
object Floor7Progress {
	/** A quarter second; nothing here changes faster than a click. */
	private const val SCAN_INTERVAL_TICKS = 5

	/** How far a stand may be from the recorded label position. Terminals are at least eight blocks apart. */
	private const val MATCH_RANGE = 5.0

	private const val SEARCH_MARGIN = 24.0

	private fun pendingNames(kind: Floor7Tasks.Kind): Set<String> = when (kind) {
		Floor7Tasks.Kind.TERMINAL -> setOf("Inactive Terminal")
		Floor7Tasks.Kind.DEVICE -> setOf("Inactive", "Inactive Device")
		Floor7Tasks.Kind.LEVER -> setOf("Not Activated", "Inactive Lever")
	}

	/**
	 * Every name a device's stands carry (state on top, "Device" under). Matched by name, since a
	 * section has only one device.
	 */
	private val DEVICE_NAMES = setOf("Inactive", "Inactive Device", "Active", "Device Active", "Device")

	private val formattingPattern = Regex("§.")

	private data class Key(val section: Int, val kind: Floor7Tasks.Kind, val number: Int)

	/**
	 * Tasks seen unfinished. A lever whose stand stopped saying "not activated", while other stands
	 * nearby prove it's in range, counts as done.
	 */
	private val seen = HashSet<Key>()

	/** Kept for the whole phase: it's a fact about the tower, not about where you stand. */
	private val done = HashSet<Key>()

	private var ticksUntilScan = 0

	private var inPhase = false

	fun isDone(section: Int, task: Floor7Tasks.Task): Boolean = Key(section, task.kind, task.number) in done

	fun tick(client: Minecraft, active: Boolean) {
		val level = client.level
		val section = Floor7.p3Section

		// Only leaving the boss room resets this.
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
				mine.any { it.name in pendingNames(task.kind) } -> {
					seen.add(key)
					done.remove(key)
				}
				// A stand is there and none of it says unfinished.
				mine.isNotEmpty() -> done.add(key)
				// Nothing in reach: out of range is far more likely than renamed, so keep the last
				// answer unless it was watched being done.
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
	 * Named stands near the current section and what each task made of them, for `/cryptic debug
	 * devices`.
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

	/** One query for the whole section. */
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

	/** A device's stands are matched by name across the section; terminals and levers by distance. */
	private fun standsFor(task: Floor7Tasks.Task, stands: List<NamedStand>): List<NamedStand> =
		if (task.kind == Floor7Tasks.Kind.DEVICE) {
			stands.filter { it.name in DEVICE_NAMES }
		} else {
			stands.filter {
				it.name !in DEVICE_NAMES && it.position.distanceToSqr(task.anchor) <= MATCH_RANGE * MATCH_RANGE
			}
		}
}
