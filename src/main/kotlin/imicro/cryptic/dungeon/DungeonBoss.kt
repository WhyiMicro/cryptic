package imicro.cryptic.dungeon

import imicro.cryptic.mixin.BossHealthOverlayAccessor
import net.minecraft.client.Minecraft

/**
 * Which of Floor 7's four withers is being fought, from the boss bar (as NoammAddons reads it).
 * Runs only while something wants it.
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

	var current: Wither? = null
		private set

	/** A few times a second is plenty; boss bars only change between phases. */
	fun tick(client: Minecraft, active: Boolean) {
		if (!active) {
			clear()
			return
		}

		if (ticksUntilRefresh-- > 0) return
		ticksUntilRefresh = REFRESH_INTERVAL_TICKS

		val overlay = client.gui.hud.bossOverlay as? BossHealthOverlayAccessor
		if (overlay == null) {
			clear()
			return
		}

		// Hypixel decorates the name, so bars are matched on the boss's own word.
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
