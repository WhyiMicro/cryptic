package imicro.cryptic.gui.menu.managers

import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.menu.Box
import imicro.cryptic.gui.menu.ManagerSection
import imicro.cryptic.gui.menu.MenuStyle
import imicro.cryptic.gui.menu.UiText
import net.minecraft.util.Util
import java.net.URI

/**
 * Credits: the mods Cryptic was inspired by, the ones it took ideas from, and the data, fonts and
 * icons it carries, with links. Buttons at the top open THIRD_PARTY_NOTICES.md and Cryptic's
 * licence.
 */
class CreditsSection : ManagerSection("Credits") {
	override fun draw(x: Float, y: Float, width: Float): Float {
		var cy = y
		val headH = 76f
		ui.card(Box(x, cy, width, headH))
		ui.text("Made with help", x + 18f, cy + 14f, 15f, MenuStyle.TEXT, UiText.Face.MEDIUM)
		var right = x + width - 18f
		val buttonY = cy + (headH - 34f) / 2f
		for ((label, url) in listOf("Notices" to CreditList.NOTICES_URL, "Licence" to CreditList.LICENCE_URL)) {
			val w = ui.buttonWidth(label)
			ui.button(Box(right - w, buttonY, w, 34f), label) { open(url) }
			right -= w + 8f
		}
		val blurb = "Cryptic grew out of what these projects worked out first. Thank you to everyone behind them."
		var ty = cy + 36f
		for (line in UiText.wrap(blurb, 12f, right - x - 34f)) {
			ui.text(line, x + 18f, ty, 12f, MenuStyle.MUTED)
			ty += 16f
		}
		cy += headH + 14f

		for (group in CreditList.groups) {
			Sections.heading(ui, group.title, x, cy)
			cy += Sections.HEADING
			if (group.blurb.isNotEmpty()) {
				ui.text(group.blurb, x + 2f, cy - 6f, 12f, MenuStyle.MUTED)
				cy += 18f
			}
			for (credit in group.credits) cy += drawRow(credit, x, cy, width) + 2f
			cy += 14f
		}
		return cy - y
	}

	private fun drawRow(credit: Credit, x: Float, y: Float, width: Float): Float {
		val pad = 18f
		val buttonW = if (credit.url != null) ui.buttonWidth("Open") else 0f
		val textW = width - pad * 2 - (if (buttonW > 0f) buttonW + 16f else 0f)
		val lines = UiText.wrap(credit.what, 12f, textW)
		val h = 40f + lines.size * 16f
		ui.card(Box(x, y, width, h))

		ui.text(credit.name, x + pad, y + 12f, 14f, MenuStyle.TEXT, UiText.Face.MEDIUM)
		credit.licence?.let { ui.badge(it, x + pad + ui.width(credit.name, 14f, UiText.Face.MEDIUM) + 10f, y + 10f) }
		var ty = y + 33f
		for (line in lines) {
			ui.text(line, x + pad, ty, 12f, MenuStyle.MUTED)
			ty += 16f
		}
		credit.url?.let { url ->
			ui.button(Box(x + width - pad - buttonW, y + (h - 30f) / 2f, buttonW, 30f), "Open") { open(url) }
		}
		return h
	}

	private fun open(url: String) = Util.getPlatform().openUri(URI.create(url))

	companion object {
		/** The card in Settings the Credits page hangs from. */
		val module = Module(
			id = "credits",
			name = "Credits",
			description = "What inspired Cryptic",
			category = ModuleCategory.MISC,
			supportsToggle = false,
		)
	}
}
