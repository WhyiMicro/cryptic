package imicro.cryptic.dungeon

import imicro.cryptic.dungeon.DungeonTeam.DungeonClass
import net.minecraft.world.phys.Vec3

/**
 * What there is to do in each section of the Goldor phase, and who does it.
 *
 * The names a party uses, since the code has to pick some: the Goldor phase is
 * **p3**, the third of the boss's four. It is a square of four **sections** —
 * s1 to s4, walked in that order — around a middle called the **core**, which
 * is where the mage goes once s2 is done. Each section holds four or five
 * terminals, one device and one or two levers.
 *
 * Two separate tables, and they are separate because they age differently. The
 * **positions** are facts about the map and will be true for as long as Floor 7
 * exists. An **assignment** is a convention — a party's agreement about who
 * walks where — and conventions get revised, which is why there is more than
 * one of them and why adding another is meant to be easy.
 *
 * The terminal and lever positions and the [Preset.M7_GUIDES] plan are the M7 Guides
 * community's, by way of Stella's `terms.json` (credited there to NEXD); the
 * device positions are the ones measured for Cryptic. Stella is LGPL-3.0, so
 * none of its code is here: block coordinates and a community's agreement about
 * who does what are facts rather than expression. The positions cross-check
 * against NoammAddons' own terminal list, which
 * [imicro.cryptic.feature.TerminalEsp] uses.
 */
object Floor7Tasks {
	enum class Kind {
		TERMINAL,
		DEVICE,
		LEVER,
	}

	/**
	 * The assignments the menu offers, in the order [Preset.entries] lists them.
	 *
	 * [splitsByClass] is what says whether the class matters. A plan that gives
	 * everybody the same thing has no use for one, and asking for it anyway
	 * would mean drawing nothing for a player whose class could not be read.
	 */
	enum class Preset(val label: String, val splitsByClass: Boolean) {
		/** Every terminal, whoever you are — the plan for a party without one. */
		ALL_TERMINALS("All terminals", splitsByClass = false),

		/** The M7 Guides plan, which is where the term order comes from. */
		M7_GUIDES("M7 Guides", splitsByClass = true),
	}

	/**
	 * One thing to do, and where its label floats.
	 *
	 * [number] is the thing's own name — "terminal 3", "device 2" — not its
	 * place in any order. Nobody in a party says "do your second one"; they say
	 * "I've got 3 and 4", so that is what gets drawn. Terminals and levers are
	 * numbered within their section, devices across the whole phase, because
	 * there is only one device to a section and the party calls it by the
	 * number of the section it is in.
	 *
	 * [anchor] is the foot of the label, already centred on the thing itself,
	 * so the height setting is the only offset left to apply.
	 */
	data class Task(val kind: Kind, val number: Int, val anchor: Vec3)

	/** A terminal or lever, named by the block you click and centred on it. */
	private fun at(kind: Kind, x: Int, y: Int, z: Int, number: Int) =
		Task(kind, number, Vec3(x + 0.5, y.toDouble(), z + 0.5))

	private fun terminal(x: Int, y: Int, z: Int, number: Int) = at(Kind.TERMINAL, x, y, z, number)

	private fun lever(x: Int, y: Int, z: Int, number: Int) = at(Kind.LEVER, x, y, z, number)

	/** A device, measured rather than derived, so its centre is given outright. */
	private fun device(x: Double, y: Double, z: Double, number: Int) =
		Task(Kind.DEVICE, number, Vec3(x, y, z))

	/**
	 * Everything in each section, indexed by section number minus one — so the
	 * first list is s1, the second s2, and so on.
	 */
	private val TASKS: List<List<Task>> = listOf(
		listOf(
			terminal(111, 113, 73, 1),
			terminal(111, 119, 79, 2),
			terminal(89, 112, 92, 3),
			terminal(89, 122, 101, 4),
			// Simon Says.
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
			// Lights On.
			device(60.5, 132.0, 139.0, 2),
			// The left lever is up on the walkway; the right one is the low
			// emerald block by the way through, which is the one a party means
			// when it says "the right lever" here.
			lever(23, 131, 138, 1),
			lever(27, 123, 127, 2),
		),
		listOf(
			terminal(-3, 109, 112, 1),
			terminal(-3, 119, 93, 2),
			terminal(19, 123, 93, 3),
			terminal(-3, 109, 77, 4),
			// Arrow Align.
			device(2.5, 120.0, 77.5, 3),
			lever(2, 121, 55, 1),
			lever(14, 121, 55, 2),
		),
		listOf(
			terminal(41, 109, 29, 1),
			terminal(44, 121, 29, 2),
			terminal(67, 109, 29, 3),
			terminal(72, 115, 48, 4),
			// Sharp Shooter.
			device(63.5, 127.0, 35.5, 4),
			lever(84, 120, 34, 1),
			lever(86, 127, 46, 2),
		),
	)

	/**
	 * Who does what, by section and class, as the numbers a party would say.
	 *
	 * Read as: in this section, this class takes these terminals, these devices
	 * and these levers. An assignment naming something the section does not have
	 * is simply not drawn, rather than being an error — a convention can outlive
	 * a detail of the map, and the map is what decides.
	 */
	private data class Assignment(
		val terminals: List<Int> = emptyList(),
		val devices: List<Int> = emptyList(),
		val levers: List<Int> = emptyList(),
	)

	private val M7_PLAN: List<Map<DungeonClass, Assignment>> = listOf(
		// s1. The healer stands at Simon Says and everybody leaps to them, so
		// the device is the whole of their section. The berserk's two terminals
		// are the tank's and the archer's really — they are there in case the
		// early device left time spare, or somebody drew melody.
		mapOf(
			DungeonClass.ARCHER to Assignment(terminals = listOf(4, 3)),
			DungeonClass.BERSERK to Assignment(terminals = listOf(3, 1)),
			DungeonClass.HEALER to Assignment(devices = listOf(1)),
			DungeonClass.MAGE to Assignment(levers = listOf(1, 2)),
			DungeonClass.TANK to Assignment(terminals = listOf(2, 1)),
		),
		// s2. The healer has already done Lights On before the phase started,
		// but a device done early does not count until somebody touches it
		// during the phase — which is what the mage's flick of any switch is
		// for, so the device is on both their lists.
		mapOf(
			DungeonClass.ARCHER to Assignment(terminals = listOf(4), levers = listOf(2)),
			DungeonClass.BERSERK to Assignment(terminals = listOf(5, 3), levers = listOf(2)),
			DungeonClass.HEALER to Assignment(devices = listOf(2), levers = listOf(1, 2)),
			DungeonClass.MAGE to Assignment(terminals = listOf(2), devices = listOf(2)),
			DungeonClass.TANK to Assignment(terminals = listOf(1, 3), levers = listOf(2)),
		),
		// s3. Arrow Align is on both the berserk's and the healer's lists on
		// purpose: whichever of them is free takes it. The mage has gone to the
		// core by now and has nothing here.
		mapOf(
			DungeonClass.ARCHER to Assignment(terminals = listOf(4), levers = listOf(1, 2)),
			DungeonClass.BERSERK to Assignment(terminals = listOf(3), devices = listOf(3)),
			DungeonClass.HEALER to Assignment(terminals = listOf(2), devices = listOf(3), levers = listOf(2)),
			DungeonClass.MAGE to Assignment(),
			DungeonClass.TANK to Assignment(terminals = listOf(1), levers = listOf(1, 2)),
		),
		// s4. Sharp Shooter is the berserk's early device — they run here the
		// moment the phase opens and shoot it before anybody has reached s1 —
		// so it is listed in the section it stands in, which is where they will
		// be standing when they want it drawn. It stays on the list for the
		// walk through in case the early attempt did not land, and drops off by
		// itself once it is done.
		mapOf(
			DungeonClass.ARCHER to Assignment(terminals = listOf(2)),
			DungeonClass.BERSERK to Assignment(terminals = listOf(3), devices = listOf(4), levers = listOf(1, 2)),
			DungeonClass.HEALER to Assignment(terminals = listOf(4), levers = listOf(1, 2)),
			DungeonClass.MAGE to Assignment(),
			DungeonClass.TANK to Assignment(terminals = listOf(1, 3)),
		),
	)

	/** Everything there is to do in [section], one to four. */
	fun tasksIn(section: Int): List<Task> = TASKS.getOrNull(section - 1).orEmpty()

	/**
	 * What [dungeonClass] is meant to do in [section] under [preset].
	 *
	 * The class may be null, for a plan that does not split by one. A plan that
	 * does gets nothing without it, which is the safe answer — a wrong
	 * assignment sends somebody to a terminal that is not theirs.
	 *
	 * Empty is also a real answer, not a failure: under the M7 plan the berserk
	 * has nothing of their own in the first quarter and is meant to be moving.
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
