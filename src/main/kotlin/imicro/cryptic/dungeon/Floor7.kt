package imicro.cryptic.dungeon

import net.minecraft.client.Minecraft
import net.minecraft.world.phys.AABB

/**
 * Where in Goldor's tower the player is standing.
 *
 * Ported from NoammAddons (CC0, Copyright (c) Noamm9), whose `LocationUtils`
 * answers both questions from position alone. Floor 7's boss room is one tall
 * shaft with the five phases stacked in it, so height says which phase is being
 * fought without waiting for a boss bar or a chat line — which matters, because
 * a player who reconnects mid-fight never sees either.
 *
 * The scan lives here rather than inside a feature because the terminal ESP and
 * every device solver ask the same two questions, and it idles while none of
 * them is switched on.
 */
object Floor7 {
	/** The whole of Floor 7's boss room, phases one to five. */
	private val BOSS_ROOM = AABB(-8.0, 0.0, -8.0, 134.0, 254.0, 147.0)

	/**
	 * The four sections of the Goldor phase, walked in order.
	 *
	 * The names a party uses, since the code has to pick some: the Goldor phase
	 * is **p3**, the third of the boss's four. It is a square of four sections —
	 * **s1** to **s4** — around a middle called the **core**, which is where the
	 * mage goes once s2 is done and which is not one of these boxes.
	 *
	 * They exist because every section holds its terminals and its device under
	 * the same numbers as the next, so "which terminals are these" cannot be
	 * answered by position alone.
	 */
	private val P3_SECTIONS = arrayOf(
		AABB(90.0, 105.0, 32.0, 111.0, 158.0, 123.0),
		AABB(16.0, 105.0, 122.0, 111.0, 158.0, 143.0),
		AABB(-3.0, 106.0, 48.0, 19.0, 158.0, 142.0),
		AABB(-3.0, 106.0, 30.0, 91.0, 158.0, 50.0),
	)

	/** The phase being fought, one to five, or null outside Floor 7's boss. */
	var phase: Int? = null
		private set

	/** Which section of the Goldor phase the player is in, s1 to s4. */
	var p3Section: Int? = null
		private set

	/** True while the terminals and devices of the Goldor phase are live. */
	val inGoldor: Boolean get() = phase == 3

	/**
	 * True anywhere in Floor 7's boss room, whatever is being fought.
	 *
	 * What a device wants to know is usually this rather than the phase: a
	 * party doing its devices early is standing at one while the fight is still
	 * two phases above, and gating on the phase would hide the answer from the
	 * person who went to get it.
	 */
	val inBossRoom: Boolean get() = phase != null

	/**
	 * Read every tick rather than on an interval: the phases are separated by a
	 * lift ride, and the moment the player arrives is the moment a device solver
	 * has to start watching. [active] is false when no module wants the data.
	 */
	fun tick(client: Minecraft, active: Boolean) {
		val player = client.player
		if (!active || player == null || !DungeonLocation.inFloor7 ||
			!BOSS_ROOM.contains(player.x, player.y, player.z)
		) {
			phase = null
			p3Section = null
			return
		}

		phase = when {
			player.y > 210 -> 1
			player.y > 155 -> 2
			player.y > 100 -> 3
			player.y > 45 -> 4
			else -> 5
		}

		p3Section = if (phase != 3) {
			null
		} else {
			P3_SECTIONS.indexOfFirst { it.contains(player.x, player.y, player.z) }
				.takeIf { it >= 0 }
				?.plus(1)
		}
	}
}
