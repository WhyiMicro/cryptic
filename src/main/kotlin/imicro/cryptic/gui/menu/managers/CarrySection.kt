package imicro.cryptic.gui.menu.managers

import imicro.cryptic.carry.Carry
import imicro.cryptic.carry.CarryStore
import imicro.cryptic.feature.CarryManager
import imicro.cryptic.gui.menu.Box
import imicro.cryptic.gui.menu.ManagerSection
import imicro.cryptic.gui.menu.MenuStyle
import imicro.cryptic.gui.menu.TextField
import imicro.cryptic.gui.menu.UiText
import net.minecraft.client.Minecraft

/**
 * The Carry Manager on its module's page.
 *
 * Two views, as the old window had: an overview of the carry under way, the
 * running totals, the warps and the last few finished; and the Voidgloom list,
 * where carries are added and counted.
 */
class CarrySection : ManagerSection("Carries") {
	private enum class View(val title: String) { OVERVIEW("Overview"), VOIDGLOOM("Voidgloom Seraph") }

	private var view = View.OVERVIEW
	private val name = TextField(16) { Character.isLetterOrDigit(it) || it == '_'.code }
	private val amount = TextField(3) { Character.isDigit(it) }.apply { set("1") }
	private var tier = 4
	private val deletes = DeleteConfirm()

	override fun onClose() = CarryStore.save()

	override fun draw(x: Float, y: Float, width: Float): Float {
		var cy = y
		var cx = x
		for (option in View.entries) cx += ui.chip(cx, cy, option.title, option == view) { view = option } + 6f
		cy += 24f + 14f
		cy += when (view) {
			View.OVERVIEW -> drawOverview(x, cy, width)
			View.VOIDGLOOM -> drawVoidgloom(x, cy, width)
		}
		return cy - y
	}

	// ---- Overview --------------------------------------------------------

	private fun drawOverview(x: Float, y: Float, width: Float): Float {
		var cy = y
		val pad = 18f
		val active = CarryManager.activeCarry

		val currentH = 96f
		ui.card(Box(x, cy, width, currentH))
		Sections.heading(ui, "Current carry", x + pad - 2f, cy + 4f)
		if (active == null) {
			ui.text("Nothing on the list", x + pad, cy + 34f, 16f, MenuStyle.TEXT, UiText.Face.MEDIUM)
			ui.text("Add somebody under Voidgloom Seraph, or with /cryptic carry add.", x + pad, cy + 60f, 12f, MenuStyle.MUTED)
		} else {
			ui.text(active.name, x + pad, cy + 32f, 17f, MenuStyle.TEXT, UiText.Face.MEDIUM)
			ui.badge("T${active.tier}", x + pad + ui.width(active.name, 17f, UiText.Face.MEDIUM) + 10f, cy + 30f, height = 22f)
			val count = "${active.done}/${active.ordered}"
			ui.text(count, x + width - pad - ui.width(count, 17f, UiText.Face.MEDIUM), cy + 32f, 17f, MenuStyle.TEXT, UiText.Face.MEDIUM)
			ui.progress(Box(x + pad, cy + 60f, width - pad * 2f, 6f), active.done / active.ordered.coerceAtLeast(1).toFloat())
			val onBoss = CarryManager.currentBossMillis(active)
			ui.text(if (onBoss > 0) "Boss up for ${Sections.duration(onBoss)}" else "Waiting for a boss", x + pad, cy + 74f, 12f, MenuStyle.MUTED)
		}
		cy += currentH + 2f

		// The running totals, in five columns.
		val statsH = 72f
		ui.card(Box(x, cy, width, statsH))
		val totals = CarryStore.totals
		val stats = listOf(
			totals.carriesCompleted.toString() to "Carries done",
			totals.bossesKilled.toString() to "Bosses killed",
			Sections.duration(CarryStore.averageKillMillis()) to "Average kill",
			Sections.duration(totals.lastKillMillis) to "Last kill",
			Sections.duration(totals.fastestKillMillis) to "Fastest kill",
		)
		val column = width / stats.size
		stats.forEachIndexed { index, (value, label) ->
			val box = Box(x + column * index, cy, column, statsH)
			ui.textCentered(value, Box(box.x, cy + 14f, column, 22f), 18f, MenuStyle.TEXT, UiText.Face.MEDIUM)
			ui.textCentered(label, Box(box.x, cy + 42f, column, 16f), 12f, MenuStyle.MUTED)
		}
		cy += statsH + 2f

		// The two warps a carry goes back and forth between.
		val warpH = 58f
		ui.card(Box(x, cy, width, warpH))
		ui.text("Warp", x + pad, cy + (warpH - 14f) / 2f, 14f, MenuStyle.TEXT)
		val buttonW = 150f
		ui.button(Box(x + width - pad - buttonW, cy + 12f, buttonW, 34f), "Voidgloom") { warp("warp void") }
		ui.button(Box(x + width - pad - buttonW * 2f - 8f, cy + 12f, buttonW, 34f), "Island") { warp("warp island") }
		cy += warpH

		val history = CarryStore.history
		if (history.isNotEmpty()) {
			cy += 14f
			Sections.heading(ui, "Finished", x, cy)
			cy += Sections.HEADING
			for (entry in history.asReversed().take(8)) {
				val h = 42f
				ui.card(Box(x, cy, width, h))
				val ty = cy + (h - 13f) / 2f
				ui.text(entry.name, x + pad, ty, 13f, MenuStyle.TEXT, UiText.Face.MEDIUM)
				var tx = x + pad + ui.width(entry.name, 13f, UiText.Face.MEDIUM) + 10f
				tx += ui.badge("T${entry.tier}", tx, cy + 10f) + 10f
				ui.text(entry.bossType, tx, ty, 12f, MenuStyle.MUTED)
				val took = Sections.duration(entry.durationMillis)
				val tookW = ui.width(took, 13f)
				ui.text(took, x + width - pad - tookW, ty, 13f, MenuStyle.MUTED)
				val got = "${entry.count}/${entry.orderedOrCount}"
				ui.text(got, x + width - pad - tookW - 24f - ui.width(got, 13f), ty, 13f, MenuStyle.TEXT)
				cy += h + 2f
			}
		}
		return cy - y
	}

	private fun warp(command: String) {
		val minecraft = Minecraft.getInstance()
		minecraft.execute {
			minecraft.connection?.sendCommand(command)
			minecraft.gui.setScreen(null)
		}
	}

	// ---- Voidgloom ---------------------------------------------------------

	private fun drawVoidgloom(x: Float, y: Float, width: Float): Float {
		var cy = y
		val pad = 18f
		val suggestions = lobbyMatches()
		val addH = 18f + 22f + 34f + 18f + if (suggestions.isEmpty()) 0f else 8f + 24f
		ui.card(Box(x, cy, width, addH))
		ui.text("Add a carry", x + pad, cy + 18f, 14f, MenuStyle.TEXT, UiText.Face.MEDIUM)
		val row = cy + 18f + 28f
		val nameW = 220f
		ui.field(Box(x + pad, row, nameW, 34f), name, "Username")
		var cx = x + pad + nameW + 14f
		for (value in 1..4) cx += ui.chip(cx, row + 5f, "T$value", value == tier) { tier = value } + 6f
		cx += 8f
		ui.text("Bosses", cx, row + 10f, 13f, MenuStyle.MUTED)
		cx += ui.width("Bosses", 13f) + 10f
		ui.field(Box(cx, row, 64f, 34f), amount, "1")
		val addW = ui.buttonWidth("Add")
		ui.button(Box(x + width - pad - addW, row, addW, 34f), "Add", primary = true) { submit() }

		if (suggestions.isNotEmpty()) {
			val sy = row + 34f + 8f
			ui.text("In this lobby:", x + pad, sy + 5f, 12f, MenuStyle.MUTED)
			var sx = x + pad + ui.width("In this lobby:", 12f) + 10f
			for (player in suggestions) {
				if (sx + ui.chipWidth(player) > x + width - pad) break
				sx += ui.chip(sx, sy, player, false) { name.set(player) } + 6f
			}
		}
		cy += addH + 14f

		val carries = CarryManager.carries
		if (carries.isEmpty()) {
			ui.text("No carries yet.", x + 2f, cy + 4f, 13f, MenuStyle.MUTED)
			return cy + 24f - y
		}
		for (carry in carries.toList()) cy += drawCarry(carry, x, cy, width) + 2f
		return cy - y
	}

	private fun drawCarry(carry: Carry, x: Float, y: Float, width: Float): Float {
		val h = 62f
		ui.card(Box(x, y, width, h))
		val pad = 18f
		val top = y + 12f

		ui.text(carry.name, x + pad, top + 2f, 14f, MenuStyle.TEXT, UiText.Face.MEDIUM)
		ui.badge("T${carry.tier}", x + pad + ui.width(carry.name, 14f, UiText.Face.MEDIUM) + 10f, top - 2f)

		var right = x + width - pad
		right -= Sections.deleteButton(ui, deletes, carry, right, top + 7f) { CarryManager.remove(carry) } + 4f
		ui.iconButton(Box(right - 28f, top - 7f, 28f, 28f), Sections.CHECK) { CarryManager.complete(carry) }
		right -= 28f + 12f
		ui.iconButton(Box(right - 28f, top - 7f, 28f, 28f), Sections.PLUS) {
			carry.done++
			CarryStore.save()
		}
		right -= 28f + 4f
		ui.iconButton(Box(right - 28f, top - 7f, 28f, 28f), Sections.MINUS) {
			if (carry.done > 0) carry.done--
			CarryStore.save()
		}
		right -= 28f + 12f
		val count = "${carry.done}/${carry.ordered}"
		ui.text(count, right - ui.width(count, 14f), top + 2f, 14f, MenuStyle.MUTED)
		ui.progress(Box(x + pad, y + h - 18f, width - pad * 2f, 6f), carry.done / carry.ordered.coerceAtLeast(1).toFloat())
		return h
	}

	/** Players in this lobby whose names start with what has been typed. */
	private fun lobbyMatches(): List<String> {
		val typed = name.text.trim()
		if (typed.isEmpty()) return emptyList()
		val connection = Minecraft.getInstance().connection ?: return emptyList()
		return connection.onlinePlayers
			.map { it.profile.name }
			.filter { it.startsWith(typed, ignoreCase = true) && !it.equals(typed, ignoreCase = true) }
			.sorted()
			.take(5)
	}

	private fun submit() {
		val count = amount.text.toIntOrNull()?.coerceIn(1, MAX_ORDER) ?: 1
		if (CarryManager.add(name.text, tier, count)) {
			name.clear()
			amount.set("1")
			ui.blur()
		}
	}

	private companion object {
		const val MAX_ORDER = 100
	}
}
