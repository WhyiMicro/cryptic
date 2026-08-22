package imicro.cryptic.dungeon

import imicro.cryptic.mixin.BossHealthOverlayAccessor
import net.minecraft.client.Minecraft

/**
 * Tracks which of Floor 7's four withers is currently being fought.
 *
 * Hypixel names the boss in the boss bar, which is the same place NoammAddons
 * (CC0) reads it from. The scan lives here rather than inside a feature so any
 * other Floor 7 module can ask which phase the fight is in, and it runs while
 * a consumer wants it and idles otherwise.
 */
object DungeonBoss {
	enum class Wither {
		MAXOR,
		STORM,
		GOLDOR,
		NECRON,
	}

	private const val REFRESH_INTERVAL_TICKS = 5

	private var ticksUntilRefresh = 0

	/** The boss whose bar is on screen, or null when none of them is. */
	var current: Wither? = null
		private set

	/**
	 * Boss bars change only between phases, so a few times a second is plenty.
	 * [active] is false when no module wants the data, which stops the scan
	 * without any module having to know about the others.
	 */
	fun tick(client: Minecraft, active: Boolean) {
		if (!active) {
			clear()
			return
		}

		if (ticksUntilRefresh-- > 0) return
		ticksUntilRefresh = REFRESH_INTERVAL_TICKS

		val overlay = client.gui.bossOverlay as? BossHealthOverlayAccessor
		if (overlay == null) {
			clear()
			return
		}

		// Hypixel decorates the name, so each bar is matched on the boss's own
		// word rather than on the whole line.
		for (event in overlay.`cryptic$events`().values) {
			val name = event.name.string
			val boss = Wither.entries.firstOrNull { name.contains(it.name, ignoreCase = true) }
			if (boss != null) {
				current = boss
				return
			}
		}

		current = null
	}

	private fun clear() {
		if (current == null && ticksUntilRefresh == 0) return
		current = null
		ticksUntilRefresh = 0
	}
}
