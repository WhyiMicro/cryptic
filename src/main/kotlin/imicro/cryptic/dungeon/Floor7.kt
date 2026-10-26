package imicro.cryptic.dungeon

import net.minecraft.client.Minecraft
import net.minecraft.world.phys.AABB

/**
 * Where in Goldor's tower you are, from position alone (ported from NoammAddons). The phases are
 * stacked in one shaft, so height gives the phase even after reconnecting mid-fight. Runs only
 * while something wants it.
 */
object Floor7 {
	private val BOSS_ROOM = AABB(-8.0, 0.0, -8.0, 134.0, 254.0, 147.0)

	/**
	 * The four sections of the Goldor phase (p3), s1 to s4. Needed because every section numbers
	 * its terminals and device the same way.
	 */
	private val P3_SECTIONS = arrayOf(
		AABB(90.0, 105.0, 32.0, 111.0, 158.0, 123.0),
		AABB(16.0, 105.0, 122.0, 111.0, 158.0, 143.0),
		AABB(-3.0, 106.0, 48.0, 19.0, 158.0, 142.0),
		AABB(-3.0, 106.0, 30.0, 91.0, 158.0, 50.0),
	)

	var phase: Int? = null
		private set

	var p3Section: Int? = null
		private set

	val inGoldor: Boolean get() = phase == 3

	/**
	 * True anywhere in the boss room. Devices usually want this rather than the phase, since a
	 * party may do them early while the fight is above.
	 */
	val inBossRoom: Boolean get() = phase != null

	/** Every tick: a device solver must start watching the moment you arrive. */
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
