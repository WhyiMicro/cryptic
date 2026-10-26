package imicro.cryptic.feature

import imicro.cryptic.gui.menu.Box
import imicro.cryptic.gui.menu.MenuStyle
import imicro.cryptic.gui.menu.UiText
import imicro.cryptic.mixin.AnvilScreenAccessor
import imicro.cryptic.mixin.SignEditScreenAccessor
import imicro.cryptic.render.RoundRect
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.client.gui.screens.inventory.AnvilScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import org.lwjgl.glfw.GLFW
import kotlin.math.abs

/**
 * The Party Finder's Search Settings and Group Builder (creating a party) as one page each. Hypixel
 * spreads both over several chests, signs and an anvil. Opening a dropdown or clicking into a box
 * goes to Hypixel's menu for it underneath, so picking or pressing Enter only has to answer it;
 * clicking away answers with the old value. The page stays drawn over Hypixel's menus throughout,
 * and every value is read back from Hypixel's page, so it shows what's really set.
 */
object PartyFinderPages {
	private enum class Page(val title: String, val heading: String, val marker: String) {
		SEARCH("Search Settings", "Search Settings", "Select Dungeon Level"),
		BUILDER("Group Builder", "Create Party", "Set Dungeon Level Required"),
	}

	private enum class Field(val label: String) {
		TYPE("the dungeon"), FLOOR("the floor"), SEARCH("the search"), SORT("the order"),
		CLASS_MIN("the lowest class level"), CLASS_MAX("the highest class level"),
		CATA_MIN("the lowest Catacombs level"), CATA_MAX("the highest Catacombs level"),
		NOTE("the note"), CLASS_REQ("the class level"), CATA_REQ("the Catacombs level"),
	}

	private const val ANY = "Any"
	private const val NORMAL = "The Catacombs"
	private const val MASTER = "Master Mode The Catacombs"
	private val FLOORS = listOf("1" to "Floor I", "2" to "Floor II", "3" to "Floor III", "4" to "Floor IV", "5" to "Floor V", "6" to "Floor VI", "7" to "Floor VII")

	private val client: Minecraft get() = Minecraft.getInstance()

	// ---- Reading Hypixel's pages ------------------------------------------------------

	private val FORMATTING = Regex("§.")

	/** What Hypixel's page last said each field is. */
	private val values = HashMap<Field, String>()

	private fun plain(text: String) = text.replace(FORMATTING, "").trim()

	private fun chestSlots(chest: AbstractContainerScreen<*>): List<Slot> = chest.menu.slots.filter { it.container !is Inventory }

	private fun lore(stack: ItemStack): List<String> = stack.get(DataComponents.LORE)?.lines()?.map { plain(it.string) } ?: emptyList()

	private fun find(chest: AbstractContainerScreen<*>, name: String): Slot? =
		chestSlots(chest).firstOrNull { !it.item.isEmpty && plain(it.item.hoverName.string) == name }

	/** Which of the two pages [screen] is, once its items are in. Kuudra's finder has other items, so it's left alone. */
	private fun pageOf(screen: Screen?): Page? {
		val chest = screen as? AbstractContainerScreen<*> ?: return null
		val page = Page.entries.firstOrNull { it.title == chest.title.string } ?: return null
		return page.takeIf { find(chest, it.marker) != null }
	}

	private fun read(chest: AbstractContainerScreen<*>, page: Page) {
		// The value under an item's "label:", or on the line after when the label ends its line.
		fun current(item: String, label: String): String? {
			val lines = lore(find(chest, item)?.item ?: return null)
			val at = lines.indexOfFirst { it.startsWith(label) }
			if (at < 0) return null
			return lines[at].substringAfter(label).trim().ifEmpty { lines.getOrNull(at + 1).orEmpty() }
		}
		fun level(text: String?): String? = text?.let { if (it.startsWith("None")) "0" else it.filter(Char::isDigit) }
		current("Select Dungeon Type", "Currently Selected:")?.let { values[Field.TYPE] = it }
		current("Select Floor", "Currently Selected:")?.let { values[Field.FLOOR] = it }
		when (page) {
			Page.SEARCH -> {
				current("Enter Search", "Current search:")?.let { values[Field.SEARCH] = if (it == "None!") "" else it }
				current("Sort Groups", "Currently Selected:")?.let { values[Field.SORT] = it }
				current("Select Class Level", "Currently Selected:")?.split('-')?.takeIf { it.size == 2 }?.let {
					values[Field.CLASS_MIN] = it[0].trim()
					values[Field.CLASS_MAX] = it[1].trim()
				}
				current("Select Dungeon Level", "Currently Selected:")?.split('-')?.takeIf { it.size == 2 }?.let {
					values[Field.CATA_MIN] = it[0].trim()
					values[Field.CATA_MAX] = it[1].trim()
				}
			}
			Page.BUILDER -> {
				// Hypixel ends the note it shows with a full stop of its own.
				current("Set Group Note", "Current Note:")?.let { values[Field.NOTE] = if (it == "None!") "" else it.removeSuffix(".") }
				level(current("Set Class Level Required", "Current Level Required:"))?.let { values[Field.CLASS_REQ] = it }
				level(current("Set Dungeon Level Required", "Current Level Required:"))?.let { values[Field.CATA_REQ] = it }
			}
		}
	}

	// ---- Walking Hypixel's menus ------------------------------------------------------

	/** A click on [item] once the chest called [title] is open. */
	private class Click(val title: String, val item: String)

	/** Where a field's value is answered: a chest of options, a sign or an anvil. */
	private sealed interface Target
	private class Picker(val title: String) : Target
	private data object SignTarget : Target
	private data object AnvilTarget : Target

	private fun routeFor(page: Page, field: Field): Pair<List<Click>, Target> {
		val t = page.title
		return when (field) {
			Field.TYPE -> listOf(Click(t, "Select Dungeon Type")) to Picker("Select Type")
			Field.FLOOR -> listOf(Click(t, "Select Floor")) to Picker("Select Floor")
			Field.SORT -> listOf(Click(t, "Sort Groups")) to Picker("Sort")
			Field.SEARCH -> listOf(Click(t, "Enter Search")) to SignTarget
			Field.CLASS_MIN -> listOf(Click(t, "Select Class Level"), Click("Class Level Range", "Enter Min Level")) to SignTarget
			Field.CLASS_MAX -> listOf(Click(t, "Select Class Level"), Click("Class Level Range", "Enter Max Level")) to SignTarget
			Field.CATA_MIN -> listOf(Click(t, "Select Dungeon Level"), Click("Dungeon Level Range", "Enter Min Level")) to SignTarget
			Field.CATA_MAX -> listOf(Click(t, "Select Dungeon Level"), Click("Dungeon Level Range", "Enter Max Level")) to SignTarget
			Field.NOTE -> listOf(Click(t, "Set Group Note")) to AnvilTarget
			Field.CLASS_REQ -> listOf(Click(t, "Set Class Level Required")) to SignTarget
			Field.CATA_REQ -> listOf(Click(t, "Set Dungeon Level Required")) to SignTarget
		}
	}

	private enum class Phase { OPENING, READY, RETURNING }

	/** One field being set: Hypixel's menu for it is opened, answered, and left again. */
	private class Session(val page: Page, val field: Field) {
		val route: List<Click>
		val target: Target

		init {
			val (r, t) = routeFor(page, field)
			route = r
			target = t
		}

		var phase = Phase.OPENING
		var step = 0

		/** The answer once given, or [CANCEL]; null while the dropdown or box is still open. */
		var pending: String? = null
		var since = 0L
		var actedOn: Screen? = null
		var targetScreen: Screen? = null
		var renamedAt = 0L
	}

	private const val CANCEL = "\u0000cancel"

	private val sessions = ArrayDeque<Session>()

	/** A footer button pressed while a field was still being set, pressed once the page is back. */
	private var pendingAction: Pair<Page, String>? = null

	/** Opening and leaving Hypixel's menus takes a moment; this long without the next one and it's given up. */
	private const val TIMEOUT_MS = 5000L

	/** A chest's items are trusted once they've stopped changing for this many ticks. */
	private const val SETTLE_TICKS = 3

	private fun begin(page: Page, field: Field) {
		// Starting on another field leaves the one before as it was.
		sessions.forEach { if (it.pending == null) it.pending = CANCEL }
		sessions.addLast(Session(page, field))
		notice = null
	}

	private fun answer(page: Page, field: Field, value: String) {
		val open = sessions.lastOrNull { it.field == field && it.pending == null }
		when {
			open != null -> open.pending = value
			value != CANCEL -> {
				begin(page, field)
				sessions.last().pending = value
			}
		}
	}

	/** The value a field shows: the answer on its way, or what Hypixel says. */
	private fun shown(field: Field): String =
		sessions.lastOrNull { it.field == field && it.pending != null && it.pending != CANCEL }?.pending ?: values[field].orEmpty()

	/** What a sign is answered with to leave its field as it was. */
	private fun unchanged(field: Field): String {
		val value = values[field].orEmpty()
		return if ((field == Field.CLASS_REQ || field == Field.CATA_REQ) && value == "0") "" else value
	}

	private var stampScreen: Screen? = null
	private var stamp = 0
	private var stableTicks = 0

	private fun settled(screen: Screen): Boolean {
		val chest = screen as? AbstractContainerScreen<*> ?: return true
		val slots = chestSlots(chest)
		val now = slots.sumOf { (it.item.hoverName.string.hashCode() * 31 + it.item.count) * (it.index + 1) }
		if (screen !== stampScreen || now != stamp) {
			stampScreen = screen
			stamp = now
			stableTicks = 0
			return false
		}
		return ++stableTicks >= SETTLE_TICKS && slots.any { !it.item.isEmpty }
	}

	fun tick() {
		if (client.player == null) {
			sessions.clear()
			pendingAction = null
			return
		}
		val screen = client.gui.screen()
		if (screen is HoldScreen && sessions.isEmpty()) {
			client.gui.setScreen(null)
			return
		}
		val s = sessions.firstOrNull()
		if (s == null) {
			runPendingAction(screen)
			return
		}
		val now = System.currentTimeMillis()
		// Left before anything was opened: there's nothing to undo.
		if (s.pending == CANCEL && s.phase == Phase.OPENING && s.step == 0) {
			sessions.removeFirst()
			return
		}
		if (s.since == 0L) s.since = now
		if (s.phase != Phase.READY && now - s.since > TIMEOUT_MS) return fail("Hypixel didn't answer, so ${s.field.label} wasn't changed.")
		screen ?: return
		// Each menu is acted on once; the next step waits for Hypixel's next menu.
		if (screen === s.actedOn) return
		if (!settled(screen)) return
		when (s.phase) {
			Phase.OPENING -> open(s, screen, now)
			Phase.READY -> respond(s, screen, now)
			Phase.RETURNING -> goBack(s, screen, now)
		}
	}

	private fun open(s: Session, screen: Screen, now: Long) {
		if (s.step < s.route.size) {
			val click = s.route[s.step]
			val chest = screen as? AbstractContainerScreen<*> ?: return
			if (chest.title.string != click.title) return
			val slot = find(chest, click.item) ?: return fail("Hypixel's menu has no ${click.item}.")
			clickSlot(chest, slot.index)
			s.step++
			s.actedOn = screen
			s.since = now
			return
		}
		val ready = when (val target = s.target) {
			is Picker -> (screen as? AbstractContainerScreen<*>)?.title?.string == target.title
			SignTarget -> screen is AbstractSignEditScreen
			AnvilTarget -> screen is AnvilScreen && screen.menu.getSlot(0).hasItem()
		}
		if (!ready) return
		s.phase = Phase.READY
		s.targetScreen = screen
		respond(s, screen, now)
	}

	/** Answers Hypixel's menu once the dropdown or box has been answered. */
	private fun respond(s: Session, screen: Screen, now: Long) {
		if (screen !== s.targetScreen) {
			// Hypixel moved on by itself; make for the page.
			leave(s, null, now)
			return
		}
		val pending = s.pending ?: return
		val cancel = pending == CANCEL
		when (s.target) {
			is Picker -> {
				val chest = screen as AbstractContainerScreen<*>
				val option = if (cancel) null else find(chest, pending)
				if (!cancel && option == null) notice = "Hypixel doesn't offer $pending there."
				val slot = option ?: find(chest, "Go Back") ?: return fail("Hypixel's menu has no way back.")
				clickSlot(chest, slot.index)
			}
			SignTarget -> {
				(screen as SignEditScreenAccessor).`cryptic$messages`()[0] = if (cancel) unchanged(s.field) else pending
				leave(s, screen, now)
				// Closing the editor sends its lines.
				client.gui.setScreen(HoldScreen())
				return
			}
			AnvilTarget -> {
				val anvil = screen as AnvilScreen
				if (s.renamedAt == 0L) {
					(anvil as AnvilScreenAccessor).`cryptic$name`().value = if (cancel) values[Field.NOTE].orEmpty() else pending
					s.renamedAt = now
					return
				}
				// Give the rename a moment to reach Hypixel before taking the result.
				if (now - s.renamedAt < 150L) return
				clickSlot(anvil, 2)
			}
		}
		leave(s, screen, now)
	}

	private fun leave(s: Session, screen: Screen?, now: Long) {
		s.phase = Phase.RETURNING
		s.actedOn = screen
		s.since = now
	}

	private fun goBack(s: Session, screen: Screen, now: Long) {
		val chest = screen as? AbstractContainerScreen<*> ?: return
		if (pageOf(chest) == s.page) return finish(s, chest)
		val back = chestSlots(chest).firstOrNull { plain(it.item.hoverName.string) == "Go Back" && lore(it.item).any { line -> line == "To ${s.page.title}" } }
			?: return
		clickSlot(chest, back.index)
		s.actedOn = screen
		s.since = now
	}

	private fun finish(s: Session, chest: AbstractContainerScreen<*>) {
		read(chest, s.page)
		sessions.removeFirst()
		val asked = s.pending ?: return
		if (asked == CANCEL) return
		val got = values[s.field].orEmpty()
		if (got.equals(asked, ignoreCase = true)) return
		notice = when (s.field) {
			Field.CLASS_REQ, Field.CATA_REQ -> "Hypixel didn't take $asked: a requirement can't be above your own level."
			Field.NOTE -> if (got.isEmpty()) "Hypixel didn't take that note." else "Hypixel changed the note to \"$got\"."
			else -> "Hypixel set ${s.field.label} to ${got.ifEmpty { "nothing" }}."
		}
	}

	private fun runPendingAction(screen: Screen?) {
		val (page, item) = pendingAction ?: return
		if (screen == null || pageOf(screen) != page || !settled(screen)) return
		pendingAction = null
		val chest = screen as AbstractContainerScreen<*>
		find(chest, item)?.let { clickSlot(chest, it.index) }
	}

	private fun fail(message: String) {
		sessions.clear()
		pendingAction = null
		focused = null
		editor = null
		dropdown = null
		notice = message
		Toasts.show("Party Finder", message, error = true)
		if (client.gui.screen() is HoldScreen) client.gui.setScreen(null)
	}

	private fun clickSlot(chest: AbstractContainerScreen<*>, slot: Int) {
		val player = client.player ?: return
		client.gameMode?.handleContainerInput(chest.menu.containerId, slot, 0, ContainerInput.PICKUP, player)
	}

	/** Shown between Hypixel's menus while a field is set, so the page doesn't drop away. */
	private class HoldScreen : Screen(Component.empty()) {
		override fun isPauseScreen() = false
	}

	/**
	 * Hypixel closes one menu before opening the next. While a field is being set, that gap gets a
	 * screen of Cryptic's instead of none, so the page stays up and the mouse isn't grabbed.
	 */
	@JvmStatic
	fun holdInstead(screen: Screen?): Screen? =
		if (screen == null && sessions.isNotEmpty() && PartyFinder.module.enabled && PartyFinder.customPages.value) HoldScreen() else screen

	// ---- Text boxes ---------------------------------------------------------------------

	/** A box's text, caret and selection; [anchor] is where a selection started. */
	private class TextEditor(text: String, val maxLength: Int, val digitsOnly: Boolean) {
		var text = text
		var cursor = text.length
		var anchor = cursor
		var scroll = 0f

		val start get() = minOf(cursor, anchor)
		val end get() = maxOf(cursor, anchor)
		val selected get() = text.substring(start, end)

		fun insert(input: String) {
			val clean = input.replace('\n', ' ').filter { it >= ' ' && (!digitsOnly || it.isDigit()) }
			val room = maxLength - (text.length - (end - start))
			val added = clean.take(room.coerceAtLeast(0))
			text = text.substring(0, start) + added + text.substring(end)
			cursor = start + added.length
			anchor = cursor
		}

		fun erase(forward: Boolean, word: Boolean) {
			if (cursor == anchor) cursor = if (forward) (if (word) wordRight() else cursor + 1).coerceAtMost(text.length) else (if (word) wordLeft() else cursor - 1).coerceAtLeast(0)
			insert("")
		}

		fun move(to: Int, select: Boolean) {
			cursor = to.coerceIn(0, text.length)
			if (!select) anchor = cursor
		}

		fun selectAll() {
			anchor = 0
			cursor = text.length
		}

		fun wordLeft(): Int {
			var i = cursor
			while (i > 0 && text[i - 1] == ' ') i--
			while (i > 0 && text[i - 1] != ' ') i--
			return i
		}

		fun wordRight(): Int {
			var i = cursor
			while (i < text.length && text[i] == ' ') i++
			while (i < text.length && text[i] != ' ') i++
			return i
		}

		fun selectWord() {
			anchor = wordLeft().let { if (cursor > 0 && cursor < text.length && text[cursor - 1] == ' ') cursor else it }
			cursor = wordRight()
		}
	}

	private const val TEXT_SIZE = 17f
	private const val TEXT_PAD = 12f

	private var focused: Field? = null
	private var editor: TextEditor? = null
	private var dragging = false
	private var lastClickAt = 0L

	/** Where text starts in [input], for [editor]'s text when it's the one being edited. */
	private fun textX(input: Input, text: String, ed: TextEditor?): Float {
		val b = input.box
		return if (input.numeric) b.x + (b.w - UiText.width(text, TEXT_SIZE)) / 2f else b.x + TEXT_PAD - (ed?.scroll ?: 0f)
	}

	private fun indexAt(input: Input, ed: TextEditor, mx: Float): Int {
		val x = mx - textX(input, ed.text, ed)
		var best = 0
		var bestDistance = abs(x)
		for (i in 1..ed.text.length) {
			val distance = abs(UiText.width(ed.text.substring(0, i), TEXT_SIZE) - x)
			if (distance < bestDistance) {
				best = i
				bestDistance = distance
			}
		}
		return best
	}

	private fun maxLength(field: Field) = when (field) {
		Field.NOTE -> 50
		Field.SEARCH -> 40
		else -> 2
	}

	private fun numeric(field: Field) = field != Field.NOTE && field != Field.SEARCH

	private fun focus(page: Page, input: Input, mx: Float) {
		val field = input.field
		val ed = TextEditor(shown(field), maxLength(field), numeric(field))
		focused = field
		editor = ed
		// A number is usually typed over; text is edited where it was clicked.
		if (input.numeric) ed.selectAll() else ed.move(indexAt(input, ed, mx), false)
		dragging = !input.numeric
		begin(page, field)
	}

	/** Leaves the box: Enter sends what's typed, anything else keeps the old value. */
	private fun unfocus(page: Page, send: Boolean) {
		val field = focused ?: return
		val ed = editor
		focused = null
		editor = null
		dragging = false
		if (!send || ed == null) return answer(page, field, CANCEL)
		var value = ed.text.trim()
		if (numeric(field)) {
			val fallback = if (field == Field.CLASS_MAX || field == Field.CATA_MAX) 50 else 0
			value = (value.toIntOrNull() ?: fallback).coerceIn(0, 50).toString()
		}
		answer(page, field, value)
	}

	// ---- The page -----------------------------------------------------------------------

	private sealed class Control(val box: Box)
	private class Dropdown(box: Box, val field: Field, val options: List<Pair<String, String>>, val up: Boolean) : Control(box)
	private class Input(box: Box, val field: Field, val numeric: Boolean, val placeholder: String) : Control(box)
	private class Words(box: Box, val text: String) : Control(box)
	private class Action(box: Box, val label: String, val item: String, val primary: Boolean) : Control(box)

	private class Layout(val k: Float, val panel: Box, val controls: List<Control>, val labels: List<Pair<String, Float>>)

	private const val PANEL_W = 760f
	private const val PAD = 24f
	private const val HEADER = 76f
	private const val ROW_H = 40f
	private const val ROW_STEP = 54f
	private const val FOOTER = 88f
	private const val LABEL_W = 190f
	private const val NUMBER_W = 76f
	private const val DROPDOWN_W = 220f
	private const val OPTION_H = 36f
	private const val WINDOW_SHADOW = 0xB4000000.toInt()

	private var dropdown: Field? = null
	private var notice: String? = null
	private var lastPageScreen: Screen? = null

	private fun layout(screen: Screen, page: Page): Layout {
		val window = client.window
		val k = (window.screenHeight / 1080f) / window.guiScale.toFloat()
		val rows = if (page == Page.SEARCH) 6 else 5
		val panelH = HEADER + rows * ROW_STEP - (ROW_STEP - ROW_H) + FOOTER
		val panel = Box((screen.width / k - PANEL_W) / 2f, (screen.height / k - panelH) / 2f, PANEL_W, panelH)
		val controls = ArrayList<Control>()
		val labels = ArrayList<Pair<String, Float>>()
		val left = panel.x + PAD + LABEL_W
		val right = panel.right - PAD
		var y = panel.y + HEADER

		fun row(label: String, fill: () -> Unit) {
			labels += label to y
			fill()
			y += ROW_STEP
		}
		fun dropdown(field: Field, options: List<Pair<String, String>>) {
			// Opens upward when the list would run off the bottom of the screen.
			val up = y + ROW_H + 4f + options.size * OPTION_H > screen.height / k - 8f
			controls += Dropdown(Box(left, y, DROPDOWN_W, ROW_H), field, options, up)
		}
		fun range(min: Field, max: Field) {
			controls += Input(Box(left, y, NUMBER_W, ROW_H), min, true, "0")
			controls += Words(Box(left + NUMBER_W, y, 40f, ROW_H), "to")
			controls += Input(Box(left + NUMBER_W + 40f, y, NUMBER_W, ROW_H), max, true, "50")
		}

		val floors = listOf("Entrance" to "Entrance") + FLOORS.map { (n, value) -> "Floor $n" to value }
		when (page) {
			Page.SEARCH -> {
				row("Dungeon") { dropdown(Field.TYPE, listOf("Any" to ANY, "Normal" to NORMAL, "Master" to MASTER)) }
				row("Floor") { dropdown(Field.FLOOR, listOf("Any" to ANY) + floors) }
				row("Search") { controls += Input(Box(left, y, right - left, ROW_H), Field.SEARCH, false, "Words in a party's note") }
				row("Class level") { range(Field.CLASS_MIN, Field.CLASS_MAX) }
				row("Catacombs level") { range(Field.CATA_MIN, Field.CATA_MAX) }
				row("Sort by") { dropdown(Field.SORT, listOf("Newest" to "Recently Created", "Highest level" to "Highest Dungeon Level", "Largest party" to "Largest Group Size")) }
			}
			Page.BUILDER -> {
				row("Dungeon") { dropdown(Field.TYPE, listOf("Normal" to NORMAL, "Master" to MASTER)) }
				row("Floor") { dropdown(Field.FLOOR, floors) }
				row("Note") { controls += Input(Box(left, y, right - left, ROW_H), Field.NOTE, false, "What the party plans to do") }
				row("Class level") { controls += Input(Box(left, y, NUMBER_W, ROW_H), Field.CLASS_REQ, true, "0") }
				row("Catacombs level") { controls += Input(Box(left, y, NUMBER_W, ROW_H), Field.CATA_REQ, true, "0") }
			}
		}

		// The footer: back, and for a new party the button that lists it.
		val footY = panel.bottom - PAD - ROW_H
		var x = right
		if (page == Page.BUILDER) {
			val w = UiText.width("Create party", 18f, UiText.Face.MEDIUM) + 40f
			x -= w
			controls += Action(Box(x, footY, w, ROW_H), "Create party", "Confirm Group", true)
			x -= 8f
		}
		val backW = UiText.width("Back", 18f, UiText.Face.MEDIUM) + 40f
		controls += Action(Box(x - backW, footY, backW, ROW_H), "Back", "Go Back", false)
		return Layout(k, panel, controls, labels)
	}

	/** An open dropdown's options, under it (or over it, when there's no room below). */
	private fun optionBoxes(control: Dropdown): List<Pair<Box, Pair<String, String>>> {
		val top = if (control.up) control.box.y - 4f - control.options.size * OPTION_H else control.box.bottom + 4f
		return control.options.mapIndexed { i, option -> Box(control.box.x, top + i * OPTION_H, control.box.w, OPTION_H) to option }
	}

	/**
	 * Whether Hypixel offers [value] for [field]: once its picker is open underneath, only what's in
	 * it is; until then everything is, and a miss is reported.
	 */
	private fun offered(field: Field, value: String): Boolean {
		val session = sessions.firstOrNull { it.field == field && it.phase == Phase.READY } ?: return true
		val chest = session.targetScreen as? AbstractContainerScreen<*> ?: return true
		return find(chest, value) != null
	}

	/** The page drawn over [screen]: its own page, or the one being set while Hypixel's menus pass under it. */
	private fun coveredPage(screen: Screen): Page? {
		if (!PartyFinder.module.enabled || !PartyFinder.customPages.value) return null
		pageOf(screen)?.let { return it }
		val session = sessions.firstOrNull() ?: return null
		return session.page.takeIf { screen is AbstractContainerScreen<*> || screen is AbstractSignEditScreen || screen is HoldScreen }
	}

	@JvmStatic
	fun render(screen: Screen, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int): Boolean {
		val page = coveredPage(screen) ?: return false
		// The menu font is baked for the real pixel size; set here too, as the menu may not have opened yet.
		UiText.pixelsPerUnit = client.window.screenHeight / 1080f
		if (pageOf(screen) == page) {
			// A fresh visit starts clean; Hypixel reopening the page mid-change doesn't.
			if (screen !== lastPageScreen) {
				if (sessions.isEmpty() && pendingAction == null) {
					focused = null
					editor = null
					dropdown = null
					notice = null
				}
				lastPageScreen = screen
			}
			read(screen as AbstractContainerScreen<*>, page)
		}
		graphics.fill(0, 0, screen.width, screen.height, 0xA0000000.toInt())
		val l = layout(screen, page)
		val mx = mouseX / l.k
		val my = mouseY / l.k
		val pose = graphics.pose()
		pose.pushMatrix()
		pose.scale(l.k, l.k)

		val p = l.panel
		RoundRect.shadow(graphics, p.x, p.y, p.right, p.bottom, WINDOW_SHADOW, 14f, 24f)
		RoundRect.fill(graphics, p.x, p.y, p.right, p.bottom, MenuStyle.WINDOW_BG, 14f)
		RoundRect.outline(graphics, p.x, p.y, p.right, p.bottom, MenuStyle.WINDOW_EDGE, 14f, 1f)
		UiText.draw(graphics, page.heading, p.x + PAD, p.y + PAD + 4f, 22f, MenuStyle.TEXT, UiText.Face.MEDIUM)

		for ((label, y) in l.labels) UiText.draw(graphics, label, p.x + PAD, y + 10f, 18f, MenuStyle.TAB_HOVER)
		val popupOpen = dropdown != null
		for (control in l.controls) {
			val b = control.box
			val over = !popupOpen && b.contains(mx, my)
			when (control) {
				is Dropdown -> drawDropdown(graphics, control, over)
				is Input -> drawInput(graphics, control, over)
				is Words -> UiText.draw(graphics, control.text, if (control.text == "to") b.x + (b.w - UiText.width("to", 16f)) / 2f else b.x, b.y + 11f, 16f, MenuStyle.MUTED)
				is Action -> {
					val waiting = control.primary && changing()
					val fill = when {
						waiting -> MenuStyle.FIELD
						control.primary && over -> MenuStyle.cardOnHover()
						control.primary -> MenuStyle.cardOn()
						over -> MenuStyle.BUTTON_HOVER
						else -> MenuStyle.FIELD
					}
					RoundRect.fill(graphics, b.x, b.y, b.right, b.bottom, fill, 8f)
					if (!control.primary) RoundRect.outline(graphics, b.x, b.y, b.right, b.bottom, if (over) MenuStyle.FIELD_EDGE_HOVER else MenuStyle.FIELD_EDGE, 8f, 1f)
					centeredText(graphics, control.label, b, 18f, if (waiting) MenuStyle.ICON_OFF else MenuStyle.TEXT, UiText.Face.MEDIUM)
				}
			}
		}

		// What's happening, or what went wrong, by the footer buttons.
		val head = sessions.firstOrNull()
		val busy = head != null && head.phase != Phase.READY && head.pending != null && head.pending != CANCEL
		val status = if (busy) "Setting ${head.field.label}..." else notice
		if (status != null) {
			val footer = l.controls.filterIsInstance<Action>().minOf { it.box.x }
			UiText.draw(graphics, UiText.fit(status, 16f, footer - p.x - PAD * 2), p.x + PAD, p.bottom - PAD - ROW_H + 11f, 16f, if (busy) MenuStyle.MUTED else MenuStyle.DANGER)
		}

		// An open dropdown's list, over everything.
		l.controls.filterIsInstance<Dropdown>().firstOrNull { it.field == dropdown }?.let { control ->
			val boxes = optionBoxes(control)
			val top = boxes.first().first.y
			val bottom = boxes.last().first.bottom
			RoundRect.shadow(graphics, control.box.x, top, control.box.right, bottom, 0x90000000.toInt(), 8f, 12f)
			RoundRect.fill(graphics, control.box.x, top, control.box.right, bottom, MenuStyle.POPUP, 8f)
			RoundRect.outline(graphics, control.box.x, top, control.box.right, bottom, MenuStyle.FIELD_EDGE, 8f, 1f)
			for ((box, option) in boxes) {
				val enabled = offered(control.field, option.second)
				val selected = shown(control.field) == option.second
				if (enabled && box.contains(mx, my)) RoundRect.fill(graphics, box.x + 3f, box.y + 2f, box.right - 3f, box.bottom - 2f, MenuStyle.POPUP_HOVER, 6f)
				val color = if (!enabled) MenuStyle.ICON_OFF else if (selected) MenuStyle.accent() else MenuStyle.TEXT
				UiText.draw(graphics, option.first, box.x + 14f, box.y + 9f, 17f, color, if (selected) UiText.Face.MEDIUM else UiText.Face.REGULAR)
				if (selected) UiText.drawIcon(graphics, MenuStyle.Icons.CHECK, box.right - 30f, box.y + 10f, 15f, MenuStyle.accent())
			}
		}
		pose.popMatrix()
		return true
	}

	private fun drawDropdown(graphics: GuiGraphicsExtractor, control: Dropdown, over: Boolean) {
		val b = control.box
		val open = dropdown == control.field
		MenuStyle.drawField(graphics, b, over, open, 1f)
		val value = shown(control.field)
		val label = control.options.firstOrNull { it.second == value }?.first ?: value
		UiText.draw(graphics, UiText.fit(label, TEXT_SIZE, b.w - 52f), b.x + TEXT_PAD, b.y + 10f, TEXT_SIZE, MenuStyle.TEXT)
		UiText.drawIcon(graphics, MenuStyle.Icons.CHEVRON_DOWN, b.right - 30f, b.y + 12f, 14f, if (open || over) MenuStyle.TEXT else MenuStyle.MUTED)
	}

	private fun drawInput(graphics: GuiGraphicsExtractor, control: Input, over: Boolean) {
		val b = control.box
		val ed = editor.takeIf { focused == control.field }
		MenuStyle.drawField(graphics, b, over, ed != null, 1f)
		if (ed == null) {
			val text = shown(control.field)
			if (text.isEmpty()) {
				UiText.draw(graphics, UiText.fit(control.placeholder, TEXT_SIZE, b.w - TEXT_PAD * 2), b.x + TEXT_PAD, b.y + 10f, TEXT_SIZE, MenuStyle.PLACEHOLDER)
			} else {
				val fitted = UiText.fit(text, TEXT_SIZE, b.w - TEXT_PAD * 2)
				UiText.draw(graphics, fitted, textX(control, fitted, null), b.y + 10f, TEXT_SIZE, MenuStyle.TEXT)
			}
			return
		}
		// Keep the caret in view.
		val inner = b.w - TEXT_PAD * 2
		val caretAt = UiText.width(ed.text.substring(0, ed.cursor), TEXT_SIZE)
		if (!control.numeric) {
			if (caretAt - ed.scroll > inner) ed.scroll = caretAt - inner
			if (caretAt < ed.scroll) ed.scroll = caretAt
			ed.scroll = ed.scroll.coerceIn(0f, (UiText.width(ed.text, TEXT_SIZE) - inner).coerceAtLeast(0f))
		}
		val x = textX(control, ed.text, ed)
		graphics.enableScissor((b.x + 4f).toInt(), b.y.toInt(), (b.right - 4f).toInt(), b.bottom.toInt())
		if (ed.cursor != ed.anchor) {
			val from = x + UiText.width(ed.text.substring(0, ed.start), TEXT_SIZE)
			val to = x + UiText.width(ed.text.substring(0, ed.end), TEXT_SIZE)
			RoundRect.fill(graphics, from, b.y + 9f, to, b.bottom - 9f, MenuStyle.selection(), 2f)
		}
		UiText.draw(graphics, ed.text, x, b.y + 10f, TEXT_SIZE, MenuStyle.TEXT)
		if ((System.currentTimeMillis() / 500) % 2 == 0L) {
			val cx = x + caretAt
			graphics.fill(cx.toInt(), (b.y + 10f).toInt(), (cx + 1.5f).toInt().coerceAtLeast(cx.toInt() + 1), (b.bottom - 10f).toInt(), MenuStyle.TEXT)
		}
		graphics.disableScissor()
	}

	private fun centeredText(graphics: GuiGraphicsExtractor, text: String, box: Box, size: Float, color: Int, face: UiText.Face) {
		UiText.draw(graphics, text, box.x + (box.w - UiText.width(text, size, face)) / 2f, box.y + (box.h - size) / 2f - 1f, size, color, face)
	}

	// ---- Input --------------------------------------------------------------------------

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
			ScreenMouseEvents.allowMouseClick(screen).register { _, event ->
				val page = coveredPage(screen) ?: return@register true
				click(screen, page, event.x(), event.y(), (event.modifiers() and GLFW.GLFW_MOD_SHIFT) != 0)
				false
			}
			ScreenMouseEvents.allowMouseDrag(screen).register { _, event, _, _ ->
				val page = coveredPage(screen) ?: return@register true
				drag(screen, page, event.x(), event.y())
				false
			}
			ScreenMouseEvents.allowMouseRelease(screen).register { _, _ ->
				coveredPage(screen) ?: return@register true
				dragging = false
				false
			}
			ScreenKeyboardEvents.allowKeyPress(screen).register { _, event ->
				val page = coveredPage(screen) ?: return@register true
				!key(screen, page, event)
			}
			ScreenKeyboardEvents.allowCharType(screen).register { _, event ->
				coveredPage(screen) ?: return@register true
				editor?.insert(String(Character.toChars(event.codepoint())))
				// Typing never reaches the sign or chest underneath.
				false
			}
		}
	}

	private fun click(screen: Screen, page: Page, x: Double, y: Double, shift: Boolean) {
		val l = layout(screen, page)
		val mx = (x / l.k).toFloat()
		val my = (y / l.k).toFloat()

		// Create party waits until every change has gone through, so a party is never listed with the
		// old note or level; the click does nothing at all, so an edit under way carries on.
		val onPopup = l.controls.filterIsInstance<Dropdown>().firstOrNull { it.field == dropdown }
			?.let { control -> optionBoxes(control).any { (box, _) -> box.contains(mx, my) } } == true
		val pressed = l.controls.firstOrNull { it.box.contains(mx, my) }
		if (!onPopup && pressed is Action && pressed.primary && changing()) return

		// An open dropdown takes the click: an option answers it, anywhere else leaves it as it was.
		dropdown?.let { field ->
			dropdown = null
			val control = l.controls.filterIsInstance<Dropdown>().firstOrNull { it.field == field }
			val picked = control?.let { optionBoxes(it).firstOrNull { (box, _) -> box.contains(mx, my) } }
			if (control != null && picked != null && offered(field, picked.second.second)) {
				answer(page, field, picked.second.second)
				return
			}
			answer(page, field, CANCEL)
			if (control?.box?.contains(mx, my) == true) return
		}

		val hit = l.controls.firstOrNull { it.box.contains(mx, my) }
		val ed = editor
		if (hit is Input && hit.field == focused && ed != null) {
			val now = System.currentTimeMillis()
			ed.move(indexAt(hit, ed, mx), shift)
			if (now - lastClickAt < 300L) ed.selectWord()
			lastClickAt = now
			dragging = true
			return
		}
		if (focused != null) unfocus(page, send = false)
		when (hit) {
			is Dropdown -> {
				dropdown = hit.field
				begin(page, hit.field)
			}
			is Input -> {
				focus(page, hit, mx)
				lastClickAt = System.currentTimeMillis()
			}
			is Action -> {
				val chest = screen as? AbstractContainerScreen<*>
				if (sessions.isEmpty() && chest != null && pageOf(chest) == page) {
					find(chest, hit.item)?.let { clickSlot(chest, it.index) }
				} else {
					pendingAction = page to hit.item
				}
			}
			else -> Unit
		}
	}

	/** A box being edited, a dropdown open, or a change still on its way through Hypixel's menus. */
	private fun changing(): Boolean = focused != null || dropdown != null || sessions.isNotEmpty()

	private fun drag(screen: Screen, page: Page, x: Double, y: Double) {
		val ed = editor ?: return
		if (!dragging) return
		val l = layout(screen, page)
		val input = l.controls.filterIsInstance<Input>().firstOrNull { it.field == focused } ?: return
		ed.move(indexAt(input, ed, (x / l.k).toFloat()), true)
	}

	/** True when the key was the page's. */
	private fun key(screen: Screen, page: Page, event: KeyEvent): Boolean {
		val ctrl = (event.modifiers() and GLFW.GLFW_MOD_CONTROL) != 0
		val shift = (event.modifiers() and GLFW.GLFW_MOD_SHIFT) != 0
		val ed = editor
		if (ed != null) {
			when (event.key()) {
				GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> unfocus(page, send = true)
				GLFW.GLFW_KEY_ESCAPE -> unfocus(page, send = false)
				GLFW.GLFW_KEY_BACKSPACE -> ed.erase(forward = false, word = ctrl)
				GLFW.GLFW_KEY_DELETE -> ed.erase(forward = true, word = ctrl)
				GLFW.GLFW_KEY_LEFT -> ed.move(if (ctrl) ed.wordLeft() else if (!shift && ed.cursor != ed.anchor) ed.start else ed.cursor - 1, shift)
				GLFW.GLFW_KEY_RIGHT -> ed.move(if (ctrl) ed.wordRight() else if (!shift && ed.cursor != ed.anchor) ed.end else ed.cursor + 1, shift)
				GLFW.GLFW_KEY_HOME -> ed.move(0, shift)
				GLFW.GLFW_KEY_END -> ed.move(ed.text.length, shift)
				GLFW.GLFW_KEY_A -> if (ctrl) ed.selectAll()
				GLFW.GLFW_KEY_C -> if (ctrl && ed.selected.isNotEmpty()) client.keyboardHandler.clipboard = ed.selected
				GLFW.GLFW_KEY_X -> if (ctrl && ed.selected.isNotEmpty()) {
					client.keyboardHandler.clipboard = ed.selected
					ed.insert("")
				}
				GLFW.GLFW_KEY_V -> if (ctrl) ed.insert(client.keyboardHandler.clipboard)
			}
			// Every key stays in the box, so typing an E doesn't close the menu.
			return true
		}
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			if (dropdown != null) {
				answer(page, dropdown!!, CANCEL)
				dropdown = null
				return true
			}
			// Leaving mid-change gives it up; a sign underneath keeps its old value as it closes.
			val head = sessions.firstOrNull()
			if (head != null && screen is AbstractSignEditScreen && head.targetScreen === screen) {
				(screen as SignEditScreenAccessor).`cryptic$messages`()[0] = unchanged(head.field)
			}
			sessions.clear()
			pendingAction = null
			return false
		}
		// While Hypixel's menus pass underneath, keys would land on them.
		return sessions.isNotEmpty()
	}
}
