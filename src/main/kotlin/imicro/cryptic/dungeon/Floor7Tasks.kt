package imicro.cryptic.dungeon

import imicro.cryptic.dungeon.DungeonTeam.DungeonClass
import net.minecraft.world.phys.Vec3

/**
 * What there is to do in each Goldor section, and who does it. Each section has four or five
 * terminals, one device and one or two levers.
 *
 * Positions are map facts; assignments are a party convention, which is why there can be several.
 * Terminal and lever positions and the [Preset.M7_GUIDES] plan are the M7 Guides community's (via
 * Stella's `terms.json`, credited to NEXD); device positions were measured for Cryptic.
 */
object Floor7Tasks {
	enum class Kind {
		TERMINAL,
		DEVICE,
		LEVER,
	}

	/** The assignments the menu offers. [splitsByClass] says whether the plan depends on class. */
	enum class Preset(val label: String, val splitsByClass: Boolean) {
		ALL_TERMINALS("All terminals", splitsByClass = false),

		M7_GUIDES("M7 Guides", splitsByClass = true),
	}

	/**
	 * One task and where its label floats. [number] is what a party calls it ("terminal 3"),
	 * numbered within the section; devices are numbered by section. [anchor] is already centred on
	 * the task.
	 */
	data class Task(val kind: Kind, val number: Int, val anchor: Vec3)

	private fun at(kind: Kind, x: Int, y: Int, z: Int, number: Int) =
		Task(kind, number, Vec3(x + 0.5, y.toDouble(), z + 0.5))

	private fun terminal(x: Int, y: Int, z: Int, number: Int) = at(Kind.TERMINAL, x, y, z, number)

	private fun lever(x: Int, y: Int, z: Int, number: Int) = at(Kind.LEVER, x, y, z, number)

	private fun device(x: Double, y: Double, z: Double, number: Int) =
		Task(Kind.DEVICE, number, Vec3(x, y, z))

	/** Everything in each section, indexed by section minus one. */
	private val TASKS: List<List<Task>> = listOf(
		listOf(
			terminal(111, 113, 73, 1),
			terminal(111, 119, 79, 2),
			terminal(89, 112, 92, 3),
			terminal(89, 122, 101, 4),
			device(108.0, 120.0, 94.0, 1),
			lever(106, 123, 113, 1),
			lever(94, 123, 113, 2),
		),
		listOf(
			terminal(68, 109, 121, 1),
			terminal(59, 120, 122, 2),
			terminal(47, 109, 121, 3),
			terminal(39, 108, 143, 4),
			terminal(40, 124, 122, 5),
			device(60.5, 132.0, 139.0, 2),
			// The left lever is up on the walkway; the right one is the low emerald block by the
			// way through.
			lever(23, 131, 138, 1),
			lever(27, 123, 127, 2),
		),
		listOf(
			terminal(-3, 109, 112, 1),
			terminal(-3, 119, 93, 2),
			terminal(19, 123, 93, 3),
			terminal(-3, 109, 77, 4),
			device(2.5, 120.0, 77.5, 3),
			lever(2, 121, 55, 1),
			lever(14, 121, 55, 2),
		),
		listOf(
			terminal(41, 109, 29, 1),
			terminal(44, 121, 29, 2),
			terminal(67, 109, 29, 3),
			terminal(72, 115, 48, 4),
			device(63.5, 127.0, 35.5, 4),
			lever(84, 120, 34, 1),
			lever(86, 127, 46, 2),
		),
	)

	/** Who does what, by section and class. Anything the section doesn't have is skipped. */
	private data class Assignment(
		val terminals: List<Int> = emptyList(),
		val devices: List<Int> = emptyList(),
		val levers: List<Int> = emptyList(),
	)

	private val M7_PLAN: List<Map<DungeonClass, Assignment>> = listOf(
		// s1: the healer does Simon Says and everyone leaps to them. The berserk's two terminals
		// are really spares for the tank and archer.
		mapOf(
			DungeonClass.ARCHER to Assignment(terminals = listOf(4, 3)),
			DungeonClass.BERSERK to Assignment(terminals = listOf(3, 1)),
			DungeonClass.HEALER to Assignment(devices = listOf(1)),
			DungeonClass.MAGE to Assignment(levers = listOf(1, 2)),
			DungeonClass.TANK to Assignment(terminals = listOf(2, 1)),
		),
		// s2: Lights On was done before the phase, but only counts once someone touches it during
		// the phase, so it's on both the healer's and the mage's lists.
		mapOf(
			DungeonClass.ARCHER to Assignment(terminals = listOf(4), levers = listOf(2)),
			DungeonClass.BERSERK to Assignment(terminals = listOf(5, 3), levers = listOf(2)),
			DungeonClass.HEALER to Assignment(devices = listOf(2), levers = listOf(1, 2)),
			DungeonClass.MAGE to Assignment(terminals = listOf(2), devices = listOf(2)),
			DungeonClass.TANK to Assignment(terminals = listOf(1, 3), levers = listOf(2)),
		),
		// s3: Arrow Align is on both the berserk's and healer's lists; whoever is free takes it.
		// The mage is at core.
		mapOf(
			DungeonClass.ARCHER to Assignment(terminals = listOf(4), levers = listOf(1, 2)),
			DungeonClass.BERSERK to Assignment(terminals = listOf(3), devices = listOf(3)),
			DungeonClass.HEALER to Assignment(terminals = listOf(2), devices = listOf(3), levers = listOf(2)),
			DungeonClass.MAGE to Assignment(),
			DungeonClass.TANK to Assignment(terminals = listOf(1), levers = listOf(1, 2)),
		),
		// s4: Sharp Shooter is the berserk's early device, listed here where they stand for it; it
		// drops off once done.
		mapOf(
			DungeonClass.ARCHER to Assignment(terminals = listOf(2)),
			DungeonClass.BERSERK to Assignment(terminals = listOf(3), devices = listOf(4), levers = listOf(1, 2)),
			DungeonClass.HEALER to Assignment(terminals = listOf(4), levers = listOf(1, 2)),
			DungeonClass.MAGE to Assignment(),
			DungeonClass.TANK to Assignment(terminals = listOf(1, 3)),
		),
	)

	fun tasksIn(section: Int): List<Task> = TASKS.getOrNull(section - 1).orEmpty()

	/**
	 * What [dungeonClass] does in [section] under [preset]. A plan that splits by class gives
	 * nothing without one, which is safer than a wrong assignment. Empty is a real answer.
	 */
	fun assignedIn(section: Int, dungeonClass: DungeonClass?, preset: Preset): List<Task> {
		val tasks = tasksIn(section)
		if (preset == Preset.ALL_TERMINALS) return tasks.filter { it.kind == Kind.TERMINAL }

		val plan = M7_PLAN.getOrNull(section - 1)?.get(dungeonClass ?: return emptyList())
			?: return emptyList()
		return tasks.filter { task ->
			val wanted = when (task.kind) {
				Kind.TERMINAL -> plan.terminals
				Kind.DEVICE -> plan.devices
				Kind.LEVER -> plan.levers
			}
			task.number in wanted
		}
	}
}
