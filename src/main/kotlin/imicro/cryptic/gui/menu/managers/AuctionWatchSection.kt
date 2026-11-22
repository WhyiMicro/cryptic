package imicro.cryptic.gui.menu.managers

import imicro.cryptic.feature.AuctionHouse
import imicro.cryptic.feature.AuctionWatch
import imicro.cryptic.feature.AuctionWatch.Entry
import imicro.cryptic.feature.Bazaar
import imicro.cryptic.gui.menu.Box
import imicro.cryptic.gui.menu.ManagerSection
import imicro.cryptic.gui.menu.MenuStyle
import imicro.cryptic.gui.menu.TextField
import imicro.cryptic.gui.menu.UiText
import imicro.cryptic.render.RoundRect
import imicro.cryptic.skyblock.AuctionFilters
import imicro.cryptic.skyblock.BazaarMarket
import net.minecraft.client.input.KeyEvent
import org.lwjgl.glfw.GLFW

/**
 * The Auction House's watch list: a card to add or edit a watch (an item, the most to pay and its
 * filters), above the watches.
 */
class AuctionWatchSection : ManagerSection("Watch list") {
	private val item = TextField(64)
	private val price = TextField(16) { Character.isLetterOrDigit(it) || it == '.'.code || it == ','.code }
	private val filter = TextField(96)
	private var binOnly = true
	private val filters = ArrayList<String>()

	/** The entry being edited, or null when adding. */
	private var editing: Entry? = null

	private var error: String? = null
	private var errorFor = ""

	private val deletes = DeleteConfirm()

	/** Item names under the item field, as the chat command suggests them. */
	private var nameAnchor = Box.NONE
	private val nameRows = ArrayList<Pair<String, Box>>()
	private var nameIndex = 0
	private var namesFor = ""
	private var names: List<String> = emptyList()

	override fun onClose() = AuctionWatch.save()

	private fun nameSuggestions(): List<String> {
		if (!ui.isFocused(item)) return emptyList()
		val typed = item.text.trim()
		if (typed != namesFor) {
			namesFor = typed
			nameIndex = 0
			names = if (typed.length < 2) emptyList() else {
				BazaarMarket.ensureItems()
				val all = BazaarMarket.itemNames().filter { it.contains(typed, ignoreCase = true) && !it.equals(typed, ignoreCase = true) }
				// Names that start with the text first, then shorter ones.
				all.sortedWith(compareBy<String>({ !it.startsWith(typed, ignoreCase = true) }, { it.length }, { it })).take(MAX_NAMES)
			}
		}
		return names
	}

	private fun acceptName(name: String) {
		item.set(name)
		namesFor = name
		names = emptyList()
		ui.focus(price)
	}

	override fun drawOverlays() {
		nameRows.clear()
		val options = nameSuggestions()
		if (options.isEmpty() || nameAnchor === Box.NONE) return
		val rowH = 26f
		val pad = 4f
		val box = Box(nameAnchor.x, nameAnchor.bottom + 4f, nameAnchor.w, options.size * rowH + pad * 2f)
		RoundRect.fill(ui.graphics, box.x, box.y, box.right, box.bottom, MenuStyle.fade(MenuStyle.POPUP, ui.alpha), 8f)
		RoundRect.outline(ui.graphics, box.x, box.y, box.right, box.bottom, MenuStyle.fade(MenuStyle.FIELD_EDGE, ui.alpha), 8f, 1f)
		for ((index, option) in options.withIndex()) {
			val row = Box(box.x + pad, box.y + pad + index * rowH, box.w - pad * 2f, rowH)
			nameRows += option to row
			val lit = row.contains(ui.mx, ui.my) || index == nameIndex
			if (lit) RoundRect.fill(ui.graphics, row.x, row.y, row.right, row.bottom, MenuStyle.fade(MenuStyle.POPUP_HOVER, ui.alpha), 6f)
			ui.text(ui.fit(option, 12f, row.w - 16f), row.x + 8f, row.y + (rowH - 12f) / 2f, 12f, if (lit) MenuStyle.TEXT else MenuStyle.TAB_HOVER)
		}
	}

	override fun mouseClicked(mx: Float, my: Float, button: Int, doubleClick: Boolean): Boolean {
		// The list lies over the fields below it, so it takes the click first.
		val hit = nameRows.firstOrNull { it.second.contains(mx, my) }
		if (hit != null && button == 0) {
			acceptName(hit.first)
			return true
		}
		return super.mouseClicked(mx, my, button, doubleClick)
	}

	override fun draw(x: Float, y: Float, width: Float): Float {
		var cy = y
		cy += drawEditor(x, cy, width) + Sections.HEADING_GAP

		cy += Sections.heading(ui, "Watching (${AuctionWatch.entries.size})", x, cy, x + width)
		if (AuctionWatch.entries.isEmpty()) {
			ui.text(ui.fit("Nothing yet. Add an item above, hover one in a menu and press the watch key, or type /cryptic ahwatch add <item> [price] [filters].", 13f, width - 4f), x + 2f, cy, 13f, MenuStyle.MUTED)
			return cy + 20f - y
		}
		ui.beginGroup(x, cy, width)
		for (entry in AuctionWatch.entries.sortedByDescending { it.added }) cy += drawRow(entry, x, cy, width)
		ui.endGroup(cy)
		return cy - y
	}

	// ---- The editor ------------------------------------------------------------------------------

	/** What the filter box suggests: field names while one is being picked, then its values. */
	private class Suggestions(val help: String?, val chips: List<Pair<String, String>>)

	private var chipsFor = ""
	private var chips: List<Pair<String, String>> = emptyList()

	private fun suggestions(): Suggestions {
		val typed = filter.text.trim()
		val name = typed.substringBefore(':').substringBefore('.').substringBefore('>').substringBefore('<').substringBefore('=').substringBefore('!')
		val field = AuctionFilters.field(name)
		if (field != null && typed.length > name.length) {
			// Drawn each frame, so completed once per text (and again when the lists arrive).
			val key = "$typed\u0000${BazaarMarket.itemIds().size}\u0000${BazaarMarket.products.size}"
			if (key != chipsFor) {
				chipsFor = key
				chips = AuctionFilters.complete(typed, MAX_CHIPS).map { chipLabel(it) to it }
			}
			val shown = if (field.param != null) "${field.name}.<${field.param}>" else field.name
			return Suggestions("$shown: ${field.help}", chips)
		}
		val matching = AuctionFilters.fields.filter { it.name.startsWith(name, ignoreCase = true) }
		val shown = if (typed.isEmpty()) matching.filter { it.name in COMMON } else matching.take(14)
		return Suggestions(
			if (typed.isEmpty()) "Common filters below. /cryptic ahwatch filters lists all ${AuctionFilters.fields.size}." else null,
			shown.map { (if (it.param != null) "${it.name}.<${it.param}>" else it.name) to (it.name + if (it.param != null) "." else ":") },
		)
	}

	/** A completion as its chip reads: the name after the dot, or the value after the operator. */
	private fun chipLabel(fill: String): String {
		if (fill.endsWith(':')) return fill.substringAfter('.').dropLast(1)
		var at = fill.indexOfAny(OPS)
		while (at < fill.length && fill[at] in OPS) at++
		return fill.substring(at)
	}

	private fun drawEditor(x: Float, y: Float, width: Float): Float {
		val pad = 20f
		val inner = width - pad * 2f
		val typed = item.text + "\u0000" + price.text + "\u0000" + filter.text
		if (typed != errorFor) error = null
		val suggestions = suggestions()
		val filterChips = filters.map { "$it  ×" }

		val fieldH = 34f
		var height = 18f + 22f + 6f + fieldH + 12f + 24f + 8f
		height += 20f + (if (filters.isEmpty()) 18f else ui.chipsHeight(inner, filterChips)) + 10f
		height += fieldH + 8f
		if (suggestions.help != null) height += 20f
		if (suggestions.chips.isNotEmpty()) height += ui.chipsHeight(inner, suggestions.chips.map { it.first }) + 8f
		if (error != null) height += 20f
		height += 18f + 14f
		ui.card(Box(x, y, width, height))

		var cy = y + 18f
		ui.text(editing?.let { "Edit ${AuctionWatch.title(it)}" } ?: "Watch an item", x + pad, cy, 14f, MenuStyle.TEXT, UiText.Face.MEDIUM)
		cy += 22f + 6f

		var right = x + width - pad
		val saveLabel = if (editing == null) "Add" else "Save"
		val saveW = ui.buttonWidth(saveLabel)
		ui.button(Box(right - saveW, cy, saveW, fieldH), saveLabel, primary = true) { submit() }
		right -= saveW + 8f
		if (editing != null) {
			val cancelW = ui.buttonWidth("Cancel")
			ui.button(Box(right - cancelW, cy, cancelW, fieldH), "Cancel") { clear() }
			right -= cancelW + 8f
		}
		val priceW = 140f
		ui.field(Box(right - priceW, cy, priceW, fieldH), price, "Max price (any)")
		right -= priceW + 8f
		nameAnchor = Box(x + pad, cy, right - x - pad, fieldH)
		ui.field(nameAnchor, item, "Item name or ID (empty: any item)")
		cy += fieldH + 12f

		ui.switch(Box(x + pad, cy + 4f, 32f, 15f), binOnly, this) { binOnly = !binOnly }
		ui.text("BIN only", x + pad + 42f, cy + 3f, 13f, MenuStyle.TEXT)
		cy += 24f + 8f

		ui.text("Filters (all must match; click one to remove it)", x + pad, cy, 12f, MenuStyle.MUTED, UiText.Face.MEDIUM)
		cy += 20f
		if (filters.isEmpty()) {
			ui.text("None: every listing of the item counts.", x + pad, cy + 2f, 12f, MenuStyle.MUTED)
			cy += 18f
		} else {
			cy += ui.chips(x + pad, cy, inner, filters.toList(), { "$it  ×" }, { false }) { filters.remove(it) }
		}
		cy += 10f

		val addW = ui.buttonWidth("Add filter")
		ui.field(Box(x + pad, cy, inner - addW - 8f, fieldH), filter, "Filter, e.g. quality:50, tier:10, skin:GOLDOR_CELESTIAL")
		ui.button(Box(x + width - pad - addW, cy, addW, fieldH), "Add filter") { addFilter() }
		cy += fieldH + 8f
		suggestions.help?.let {
			ui.text(ui.fit(it, 12f, inner), x + pad, cy, 12f, MenuStyle.TAB_HOVER)
			cy += 20f
		}
		if (suggestions.chips.isNotEmpty()) {
			cy += ui.chips(x + pad, cy, inner, suggestions.chips, { it.first }, { false }) { (_, fill) ->
				filter.set(fill)
				ui.focus(filter)
			} + 8f
		}

		error?.let {
			ui.text(it, x + pad, cy, 12f, Sections.DANGER)
			cy += 20f
		}
		val state = when {
			!AuctionHouse.module.enabled -> "The module is off: nothing is checked until you turn it on."
			!AuctionHouse.watch.value -> "Notify on new listings is off: nothing is checked until you turn it on."
			else -> "Prices like 800m, 1.5b or 250k. A name the items list knows is matched by ID, anything else by name."
		}
		ui.text(ui.fit(state, 12f, inner), x + pad, cy, 12f, MenuStyle.MUTED)
		return height
	}

	private fun addFilter(): Boolean {
		val text = filter.text.trim()
		if (text.isEmpty()) return false
		val parsed = AuctionFilters.parse(text).getOrElse {
			fail(it.message ?: "That isn't a filter.")
			return true
		}
		// A field given twice keeps the newer condition.
		filters.removeIf { it.substringBefore(':') == parsed.text.substringBefore(':') }
		filters += parsed.text
		filter.clear()
		return true
	}

	override fun keyPressed(event: KeyEvent): Boolean {
		val enter = event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER
		val names = nameSuggestions()
		if (names.isNotEmpty()) when (event.key()) {
			GLFW.GLFW_KEY_TAB -> {
				names.getOrNull(nameIndex)?.let(::acceptName)
				return true
			}
			GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_UP -> {
				nameIndex = (nameIndex + if (event.key() == GLFW.GLFW_KEY_DOWN) 1 else names.size - 1) % names.size
				return true
			}
		}
		// Tab takes the first suggested filter or value, as in chat.
		if (event.key() == GLFW.GLFW_KEY_TAB && ui.isFocused(filter)) {
			suggestions().chips.firstOrNull()?.let { filter.set(it.second) }
			return true
		}
		if (enter && ui.isFocused(filter) && addFilter()) return true
		if (enter && (ui.isFocused(item) || ui.isFocused(price))) {
			submit()
			return true
		}
		return super.keyPressed(event)
	}

	// ---- The list --------------------------------------------------------------------------------

	private fun drawRow(entry: Entry, x: Float, y: Float, width: Float): Float {
		val h = 56f
		ui.row(Box(x, y, width, h), if (editing === entry) MenuStyle.rowSelected() else MenuStyle.ROW, entry)
		if (y + h < ui.area.y || y > ui.area.bottom) return h
		val pad = 20f
		val cy = y + h / 2f

		var right = x + width - pad
		right -= Sections.deleteButton(ui, deletes, entry, right, cy) {
			AuctionWatch.remove(entry)
			if (editing === entry) clear()
		} + 4f
		ui.iconButton(Box(right - 28f, cy - 14f, 28f, 28f), Sections.EDIT) { edit(entry) }
		right -= 28f + 12f

		val textX = x + pad
		ui.text(ui.fit(AuctionWatch.title(entry), 14f, right - textX, UiText.Face.MEDIUM), textX, cy - 15f, 14f, MenuStyle.TEXT, UiText.Face.MEDIUM)
		val detail = AuctionWatch.describe(entry) + (if (entry.id.isNotEmpty()) "  ·  ${entry.id}" else "")
		ui.text(ui.fit(detail, 12f, right - textX), textX, cy + 3f, 12f, MenuStyle.TAB_HOVER)
		return h
	}

	private fun submit() {
		// A filter still in its box counts, so Add doesn't lose it.
		if (filter.text.isNotBlank()) {
			addFilter()
			if (error != null) return
		}
		val name = item.text.trim()
		val max = price.text.trim().takeIf { it.isNotEmpty() }?.let { AuctionWatch.parsePrice(it) ?: -1L } ?: 0L
		val problem = when {
			name.isEmpty() && filters.isEmpty() -> "Give an item, or filters for any item."
			max < 0 -> "That isn't a price. Try 800m, 1.5b or 250000."
			else -> null
		}
		if (problem != null) return fail(problem)
		val current = editing
		// An edit that leaves the name alone keeps what it matched, a hovered pet's id among them.
		val entry = if (current != null && name == current.item) current.deepCopy() else AuctionWatch.resolve(name)
		entry.maxPrice = max
		entry.binOnly = binOnly
		entry.added = current?.added ?: 0L
		entry.filters = filters.toMutableList()
		AuctionWatch.put(entry, current)
		clear()
	}

	private fun fail(message: String) {
		error = message
		errorFor = item.text + "\u0000" + price.text + "\u0000" + filter.text
	}

	private fun edit(entry: Entry) {
		editing = entry
		item.set(entry.item)
		price.set(if (entry.maxPrice > 0) Bazaar.short(entry.maxPrice.toDouble()).lowercase() else "")
		binOnly = entry.binOnly
		filters.clear()
		filters += entry.filters
		filter.clear()
		error = null
	}

	private fun clear() {
		editing = null
		item.clear()
		price.clear()
		filter.clear()
		filters.clear()
		binOnly = true
		error = null
		ui.blur()
	}

	private companion object {
		const val MAX_NAMES = 6
		const val MAX_CHIPS = 14
		val OPS = charArrayOf(':', '=', '<', '>', '!')

		/** Shown before anything is typed. */
		val COMMON = setOf("price", "stars", "reforge", "skin", "quality", "tier", "recombobulated", "hot_potato", "enchant", "gem", "pet_level", "pet_item", "rarity", "dye")
	}
}
