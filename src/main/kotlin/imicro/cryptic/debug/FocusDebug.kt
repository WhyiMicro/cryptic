package imicro.cryptic.debug

import imicro.cryptic.Cryptic
import imicro.cryptic.experiment.ExperimentTracker
import imicro.cryptic.feature.ChocolateFactory
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

/**
 * `/cryptic debug focus`: once a second, to the game log, whether the game is focused, how many
 * ticks ran, which menu is open, and when Chocolate Factory and the Experimentation Table last
 * acted, to see what stops while you're tabbed out.
 */
object FocusDebug {
	private var until = 0L
	private var nextLine = 0L
	private var ticks = 0

	fun start(seconds: Int): String {
		until = System.currentTimeMillis() + seconds * 1000L
		nextLine = 0L
		ticks = 0
		return "Logging focus and ticks for ${seconds}s to the game log. Tab out with the menu open, then come back."
	}

	fun tick(client: Minecraft) {
		if (until == 0L) return
		ticks++
		val now = System.currentTimeMillis()
		if (now < nextLine) return
		nextLine = now + 1000L
		val window = client.window
		val screen = client.gui.screen()
		val title = (screen as? AbstractContainerScreen<*>)?.title?.string ?: screen?.javaClass?.simpleName ?: "none"
		val factory = ChocolateFactory.lastClickAt.takeIf { it > 0 }?.let { "${now - it}ms ago" } ?: "never"
		val experiment = ExperimentTracker.current?.let { "${it.game} waiting=${it.waiting} next=${it.clickOrder().firstOrNull()}" } ?: "none"
		Cryptic.LOGGER.info(
			"[focus] focused={} iconified={} ticks={} fps={} screen='{}' factoryClick={} experiment={}",
			window.isFocused, window.isIconified, ticks, client.fps, title, factory, experiment,
		)
		ticks = 0
		if (now >= until) {
			until = 0L
			Cryptic.LOGGER.info("[focus] done")
		}
	}
}
