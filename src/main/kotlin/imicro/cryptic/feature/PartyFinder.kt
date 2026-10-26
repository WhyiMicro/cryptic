package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonTeam.DungeonClass
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.menu.Box
import imicro.cryptic.gui.menu.MenuStyle
import imicro.cryptic.gui.menu.Scrollbar
import imicro.cryptic.gui.menu.UiText
import imicro.cryptic.render.RoundRect
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import org.lwjgl.glfw.GLFW
import java.util.Locale

/**
 * Hypixel's dungeon Party Finder as a list of party cards: the leader, floor, members with their
 * classes, what the party asks for and its note, with a search box. Everything is read from the
 * chest and every click goes to its slot, so Hypixel's menu does the work. Searching and creating a
 * party get pages of their own in [PartyFinderPages]. Inspired by Skyblocker's Party Finder.
 */
object PartyFinder {
	@JvmField
	val hideLocked = ToggleModuleSetting(
		id = "hide_locked",
		label = "Hide parties you can't join",
		defaultValue = false,
		description = "Leaves out parties whose requirements you don't meet, instead of showing them greyed out.",
	)

	@JvmField
	val customPages = ToggleModuleSetting(
		id = "custom_pages",
		label = "Search and create pages",
		defaultValue = true,
		description = "Shows Search Settings and creating a party as one page each, setting Hypixel's menus for you. Off, they stay Hypixel's chests.",
	)

	@JvmField
	val hideQueueScreen = ToggleModuleSetting(
		id = "hide_queue_screen",
		label = "Hide queue screen",
		defaultValue = false,
		description = "Closes the \"Creating instance\" menu Hypixel opens once your party queues, so you can keep moving while the dungeon starts.",
	)

	@JvmField
	val module = Module(
		id = "party_finder",
		name = "Party Finder",
		description = "Better dungeon party finder",
		category = ModuleCategory.DUNGEON,
		supportsKeybind = false,
		settings = listOf(hideLocked, customPages, hideQueueScreen) + PartyKick.settings,
	)

	private const val TITLE = "Party Finder"
	private const val DESIGN_HEIGHT = 1080f

	private class Member(val name: String, val clazz: String, val level: Int)

	private class Party(
		val slot: Int,
		val leader: String,
		val floor: String,
		val master: Boolean,
		val members: List<Member>,
		val classLevel: Int,
		val cataLevel: Int,
		val note: String,
		val lockReason: String?,
		val yours: Boolean,
	) {
		val badge: String get() = (if (master) "M" else "F") + floorNumber(floor)
		val searchText: String by lazy { (listOf(leader, floor, badge, note) + members.map { it.name + " " + it.clazz }).joinToString(" ").lowercase(Locale.ROOT) }
	}

	/** The parties, and the menu's own buttons, as last read from the chest. */
	private var parties: List<Party> = emptyList()
	private var noParties = false
	private var refreshSlot = -1
	private var previousSlot = -1
	private var nextSlot = -1
	private var settingsSlot = -1
	private var createSlot = -1
	private var delistSlot = -1
	private var readFrom: Int = 0

	private var search = ""
	private var searchFocused = false
	private var scroll = 0f
	private var scrollTarget = 0f
	private var maxScroll = 0f
	private var lastFrame = 0L
	private val scrollbar = Scrollbar()

	private fun resetScroll() {
		scroll = 0f
		scrollTarget = 0f
	}

	private fun setScroll(value: Float) {
		scrollTarget = value
		scroll = value
	}
	private var waitingSince = 0L

	private val client: Minecraft get() = Minecraft.getInstance()

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
			if (screen !is AbstractContainerScreen<*> || screen.title.string != TITLE) return@register
			resetScroll()
			ScreenMouseEvents.allowMouseClick(screen).register { _, event -> !(takesOver(screen) && click(screen, event.x(), event.y(), event.button())) }
			ScreenMouseEvents.allowMouseDrag(screen).register { _, event, _, _ ->
				if (!takesOver(screen)) return@register true
				scrollbar.drag((event.y() / layout(screen).k).toFloat(), maxScroll, ::setScroll)
				false
			}
			ScreenMouseEvents.allowMouseRelease(screen).register { _, _ -> !(takesOver(screen) && scrollbar.release()) }
			ScreenMouseEvents.allowMouseScroll(screen).register { _, _, _, _, amount ->
				if (!takesOver(screen)) return@register true
				scrollTarget = (scrollTarget - amount.toFloat() * CARD_STEP).coerceIn(0f, maxScroll)
				false
			}
			ScreenKeyboardEvents.allowKeyPress(screen).register { _, event -> !(takesOver(screen) && searchFocused && key(event.key())) }
			ScreenKeyboardEvents.allowCharType(screen).register { _, event ->
				if (!takesOver(screen) || !searchFocused) return@register true
				val c = event.codepoint()
				if (c >= ' '.code && search.length < 40) search += String(Character.toChars(c))
				resetScroll()
				false
			}
		}
	}

	private const val SKYBLOCKER_SCREEN = "de.hysky.skyblocker.skyblock.dungeon.partyfinder.PartyFinderScreen"

	/**
	 * Skyblocker swaps Hypixel's party finder chests for its own screen. With this module on, that
	 * screen is turned back into the plain chest, so Cryptic's finder (or Hypixel's own pages) show.
	 */
	@JvmStatic
	fun replaceScreen(screen: Screen?): Screen? {
		if (screen == null || !module.enabled || screen.javaClass.name != SKYBLOCKER_SCREEN) return screen
		return runCatching {
			val menu = screen.javaClass.getMethod("getHandler").invoke(screen) as net.minecraft.world.inventory.ChestMenu
			val inventory = screen.javaClass.getDeclaredField("inventory").apply { isAccessible = true }.get(screen) as Inventory
			net.minecraft.client.gui.screens.inventory.ContainerScreen(menu, inventory, screen.title)
		}.getOrElse {
			imicro.cryptic.Cryptic.LOGGER.warn("Couldn't take over Skyblocker's party finder", it)
			screen
		}
	}

	/** Whether the open chest is the dungeon Party Finder this draws. Kuudra's is left alone. */
	private fun takesOver(screen: Screen): Boolean {
		if (!module.enabled) return false
		val chest = screen as? AbstractContainerScreen<*> ?: return false
		if (chest.title.string != TITLE) return false
		val slots = chest.menu.slots
		if (slots.size < 54) return false
		return slotLore(slots[45].item).none { "Kuudra" in it }
	}

	// ---- Reading the chest ------------------------------------------------------

	private val FORMATTING = Regex("§.")
	private val TRAILING_NUMBER = Regex("""(\d+)$""")
	private val MEMBER_LEVEL = Regex("""\((\d+)\)""")

	private fun slotLore(stack: ItemStack): List<String> =
		stack.get(DataComponents.LORE)?.lines()?.map { it.string.replace(FORMATTING, "") } ?: emptyList()

	private fun read(chest: AbstractContainerScreen<*>) {
		val slots = chest.menu.slots
		// The chest's slots, without the player's inventory.
		val chestSlots = slots.filter { it.container !is Inventory }
		val stamp = chestSlots.sumOf { (it.item.hoverName.string.hashCode() * 31 + it.item.count) * (it.index + 1) }
		if (stamp == readFrom && parties.isNotEmpty()) return
		if (stamp != readFrom) waitingSince = 0L
		readFrom = stamp

		val found = ArrayList<Party>()
		refreshSlot = -1; previousSlot = -1; nextSlot = -1; settingsSlot = -1; createSlot = -1; delistSlot = -1
		noParties = chestSlots.any { it.item.`is`(Items.BEDROCK) }
		val bottomRow = chestSlots.size - 9
		var yourStack: Pair<Int, ItemStack>? = null
		for (slot in chestSlots) {
			val stack = slot.item
			if (stack.isEmpty) continue
			val name = stack.hoverName.string.replace(FORMATTING, "").lowercase(Locale.ROOT)
			when {
				stack.`is`(Items.ARROW) && "previous" in name -> previousSlot = slot.index
				stack.`is`(Items.ARROW) && "next" in name -> nextSlot = slot.index
				slot.index >= bottomRow && stack.`is`(Items.EMERALD_BLOCK) -> refreshSlot = slot.index
				slot.index >= bottomRow && stack.`is`(Items.REDSTONE_BLOCK) -> createSlot = slot.index
				slot.index >= bottomRow && stack.`is`(Items.NETHER_STAR) -> settingsSlot = slot.index
				slot.index >= bottomRow && stack.`is`(Items.BOOKSHELF) -> delistSlot = slot.index
				slot.index >= bottomRow && stack.`is`(Items.PLAYER_HEAD) -> yourStack = slot.index to stack
				slot.index < bottomRow && stack.`is`(Items.PLAYER_HEAD) && !noParties -> parse(slot.index, stack, false)?.let(found::add)
			}
		}
		val me = client.user.name
		// Your own party can show up in the results before it shows by the de-list button.
		val own = yourStack?.let { (slot, stack) -> parse(if (delistSlot >= 0) delistSlot else slot, stack, true) }
			?: found.firstOrNull { it.leader == me }?.let { parse(if (delistSlot >= 0) delistSlot else it.slot, chestSlots.first { s -> s.index == it.slot }.item, true) }
		found.removeIf { it.leader == me }
		parties = listOfNotNull(own) + found
	}

	private fun parse(slot: Int, stack: ItemStack, yours: Boolean): Party? {
		val lines = stack.get(DataComponents.LORE)?.lines() ?: return null
		val host = stack.hoverName.string.replace(FORMATTING, "").substringBefore("'s").trim()
		var floor = "?"
		var dungeon = ""
		var note = ""
		var classLevel = -1
		var cataLevel = -1
		var lock: String? = null
		val members = ArrayList<Member>()
		var inMembers = false
		for (line in lines) {
			val text = line.string.replace(FORMATTING, "")
			val lower = text.lowercase(Locale.ROOT)
			when {
				lower.startsWith("members:") -> inMembers = true
				inMembers && text.startsWith(" ") -> {
					val (who, rest) = text.split(":", limit = 2).let { it[0].trim() to it.getOrElse(1) { "" } }
					if (who != "Empty" && who.isNotEmpty()) {
						val clazz = rest.trim().substringBefore(' ')
						members += Member(who, clazz, MEMBER_LEVEL.find(rest)?.groupValues?.get(1)?.toIntOrNull() ?: -1)
					}
				}
				else -> {
					inMembers = false
					when {
						"class level" in lower -> classLevel = TRAILING_NUMBER.find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: -1
						"dungeon level" in lower -> cataLevel = TRAILING_NUMBER.find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: -1
						lower.startsWith("floor:") -> floor = text.substringAfter(":").trim()
						lower.startsWith("dungeon:") -> dungeon = text.substringAfter(":").trim()
						lower.startsWith("note:") -> note = text.substringAfter(":").trim()
						// Hypixel writes why you can't join in red.
						!yours && !text.startsWith(" ") && text.isNotBlank() && hasRed(line) -> lock = text.trim()
					}
				}
			}
		}
		val leader = members.firstOrNull { it.name == host }?.name ?: host.ifEmpty { members.firstOrNull()?.name ?: "?" }
		return Party(slot, leader, floor, dungeon.contains("Master", ignoreCase = true), members, classLevel, cataLevel, note, lock, yours)
	}

	private const val RED = 0xFF5555
	private val RAW_RED = Regex("""§c[^§\s]""")

	/** Red text anywhere in the line, styled or written with a raw §c code. */
	private fun hasRed(line: Component): Boolean {
		if (RAW_RED.containsMatchIn(line.string)) return true
		var red = false
		line.visit({ style, piece ->
			if (piece.isNotBlank() && style.color?.value == RED) red = true
			java.util.Optional.empty<Unit>()
		}, net.minecraft.network.chat.Style.EMPTY)
		return red
	}

	private fun floorNumber(floor: String): String = when (floor.substringAfterLast(' ').uppercase(Locale.ROOT)) {
		"I" -> "1"; "II" -> "2"; "III" -> "3"; "IV" -> "4"; "V" -> "5"; "VI" -> "6"; "VII" -> "7"
		"ENTRANCE" -> "E"
		else -> floor.filter(Char::isDigit).ifEmpty { "?" }
	}

	// ---- Drawing ------------------------------------------------------------------

	private const val PANEL_W = 980f
	private const val WINDOW_SHADOW = 0xB4000000.toInt()
	private const val CARD_H = 112f
	private const val CARD_GAP = 10f
	private const val CARD_STEP = CARD_H + CARD_GAP
	private const val HEADER_H = 64f
	private const val PAD = 20f

	private class Layout(val k: Float, val panel: Box, val list: Box, val search: Box, val buttons: List<Pair<Box, Button>>)

	private enum class Button(val icon: String, val tip: String) {
		REFRESH(Char(0xF021).toString(), "Refresh"),
		PREVIOUS(Char(0xF053).toString(), "Previous page"),
		NEXT(Char(0xF054).toString(), "Next page"),
		SETTINGS(Char(0xF0B0).toString(), "Search settings"),
		CREATE(Char(0xF067).toString(), "Create a party"),
	}

	private val DELIST_ICON = Char(0xF1F8).toString()
	private const val BUTTON = 40f

	private fun layout(screen: Screen): Layout {
		val window = client.window
		val k = (window.screenHeight / DESIGN_HEIGHT) / window.guiScale.toFloat()
		val w = screen.width / k
		val h = screen.height / k
		val panelH = (h - 120f).coerceAtMost(900f)
		val panel = Box((w - PANEL_W) / 2f, (h - panelH) / 2f, PANEL_W, panelH)
		val top = panel.y + PAD
		val buttons = ArrayList<Pair<Box, Button>>()
		var right = panel.right - PAD
		for (button in listOf(Button.CREATE, Button.NEXT, Button.PREVIOUS, Button.REFRESH)) {
			right -= BUTTON
			buttons += Box(right, top, BUTTON, BUTTON) to button
			right -= 8f
		}
		// The filter sits against the search box it belongs with.
		val settings = Box(right - 8f - BUTTON, top, BUTTON, BUTTON)
		buttons += settings to Button.SETTINGS
		val searchX = panel.x + PAD + 190f
		val search = Box(searchX, top, settings.x - 8f - searchX, BUTTON)
		val list = Box(panel.x + PAD, top + HEADER_H, PANEL_W - PAD * 2, panelH - HEADER_H - PAD * 2)
		return Layout(k, panel, list, search, buttons)
	}

	private fun shown(): List<Party> {
		val query = search.trim().lowercase(Locale.ROOT)
		return parties.filter { party ->
			(party.yours || !hideLocked.value || party.lockReason == null) &&
				(query.isEmpty() || query.split(' ').filter(String::isNotEmpty).all { it in party.searchText })
		}
	}

	/** Draws the finder instead of the chest. True when it did. */
	@JvmStatic
	fun render(screen: Screen, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int): Boolean {
		if (!takesOver(screen)) return false
		val chest = screen as AbstractContainerScreen<*>
		read(chest)
		// The menu font is baked for the real pixel size; set here too, as the menu may not have opened yet.
		UiText.pixelsPerUnit = client.window.screenHeight / DESIGN_HEIGHT
		graphics.fill(0, 0, screen.width, screen.height, 0xA0000000.toInt())
		val l = layout(screen)
		val mx = mouseX / l.k
		val my = mouseY / l.k
		val pose = graphics.pose()
		pose.pushMatrix()
		pose.scale(l.k, l.k)

		val p = l.panel
		RoundRect.shadow(graphics, p.x, p.y, p.right, p.bottom, WINDOW_SHADOW, 14f, 24f)
		RoundRect.fill(graphics, p.x, p.y, p.right, p.bottom, MenuStyle.WINDOW_BG, 14f)
		RoundRect.outline(graphics, p.x, p.y, p.right, p.bottom, MenuStyle.WINDOW_EDGE, 14f, 1f)
		UiText.draw(graphics, "Party Finder", p.x + PAD, p.y + PAD + 9f, 22f, MenuStyle.TEXT, UiText.Face.MEDIUM)

		// The search box.
		val s = l.search
		MenuStyle.drawField(graphics, s, s.contains(mx, my), searchFocused, 1f)
		val shownSearch = if (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0L) "$search|" else search
		if (search.isEmpty() && !searchFocused) {
			UiText.draw(graphics, UiText.fit("Search parties, players, notes...", 17f, s.w - 28f), s.x + 14f, s.y + 11f, 17f, MenuStyle.PLACEHOLDER)
		} else {
			UiText.draw(graphics, UiText.fit(shownSearch, 17f, s.w - 28f), s.x + 14f, s.y + 11f, 17f, MenuStyle.TEXT)
		}

		for ((box, button) in l.buttons) {
			val enabled = slotOf(button) >= 0
			val over = enabled && box.contains(mx, my)
			RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, if (over) MenuStyle.BUTTON_HOVER else MenuStyle.FIELD, 8f)
			RoundRect.outline(graphics, box.x, box.y, box.right, box.bottom, if (over) MenuStyle.FIELD_EDGE_HOVER else MenuStyle.FIELD_EDGE, 8f, 1f)
			val icon = if (button == Button.CREATE && delistSlot >= 0) DELIST_ICON else button.icon
			val color = if (!enabled) MenuStyle.ICON_OFF else if (over) MenuStyle.TEXT else MenuStyle.TAB_HOVER
			UiText.drawIconCentered(graphics, icon, box.x, box.y, BUTTON, 17f, color)
		}

		// The cards, clipped to the list.
		val list = l.list
		val cards = shown()
		val now = System.nanoTime()
		val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceAtMost(0.1f)
		lastFrame = now
		val contentH = cards.size * CARD_STEP - CARD_GAP
		maxScroll = (contentH - list.h).coerceAtLeast(0f)
		scrollTarget = scrollTarget.coerceIn(0f, maxScroll)
		scroll = MenuStyle.approach(scroll, scrollTarget, 16f, dt).coerceIn(0f, maxScroll)
		// In the scaled pose, like the menu.
		graphics.enableScissor(list.x.toInt(), list.y.toInt(), list.right.toInt() + 1, list.bottom.toInt() + 1)
		when {
			noParties && cards.none { !it.yours } -> centered(graphics, list, "No parties are looking right now.")
			cards.isEmpty() && search.isNotBlank() -> centered(graphics, list, "No party matches \"$search\".")
		}
		cards.forEachIndexed { index, party ->
			val y = list.y + index * CARD_STEP - scroll
			if (y > list.bottom || y + CARD_H < list.y) return@forEachIndexed
			// Inset a little, so the hover outline isn't cut by the list's edge.
			val card = Box(list.x + 2f, y + 1f, list.w - 4f, CARD_H - 2f)
			drawCard(graphics, party, card, list.contains(mx, my) && card.contains(mx, my))
		}
		MenuStyle.drawScrollFades(graphics, list, scroll, maxScroll, 1f)
		graphics.disableScissor()
		// In the panel's right margin, like the menu's.
		scrollbar.draw(graphics, list.right + Scrollbar.GAP, list.y, list.h, contentH, scroll, maxScroll, mx, my, dt, 1f)

		// What the hovered icon does, under it.
		l.buttons.firstOrNull { (box, button) -> slotOf(button) >= 0 && box.contains(mx, my) }?.let { (box, button) ->
			val tip = if (button == Button.CREATE && delistSlot >= 0) "De-list your party" else button.tip
			val w = UiText.width(tip, 15f) + 20f
			val x = (box.x + (box.w - w) / 2f).coerceAtMost(p.right - 8f - w)
			RoundRect.fill(graphics, x, box.bottom + 6f, x + w, box.bottom + 34f, MenuStyle.POPUP, 6f)
			RoundRect.outline(graphics, x, box.bottom + 6f, x + w, box.bottom + 34f, MenuStyle.FIELD_EDGE, 6f, 1f)
			UiText.draw(graphics, tip, x + 10f, box.bottom + 12f, 15f, MenuStyle.TEXT)
		}

		if (waitingSince > 0 && System.currentTimeMillis() - waitingSince < 3000) {
			UiText.draw(graphics, "Waiting for Hypixel...", p.x + PAD, p.bottom - PAD + 2f, 15f, MenuStyle.MUTED)
		}
		pose.popMatrix()
		return true
	}

	private fun centered(graphics: GuiGraphicsExtractor, box: Box, text: String) {
		UiText.draw(graphics, text, box.x + (box.w - UiText.width(text, 18f)) / 2f, box.y + 40f, 18f, MenuStyle.MUTED)
	}

	private fun drawCard(graphics: GuiGraphicsExtractor, party: Party, box: Box, hovered: Boolean) {
		val locked = party.lockReason != null
		val fill = if (hovered && !locked) MenuStyle.CARD_OFF_HOVER else MenuStyle.CARD_OFF
		RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, fill, 10f)
		if (party.yours) RoundRect.outline(graphics, box.x, box.y, box.right, box.bottom, MenuStyle.accent(), 10f, 1.5f)
		else if (hovered && !locked) RoundRect.outline(graphics, box.x, box.y, box.right, box.bottom, MenuStyle.FIELD_EDGE_HOVER, 10f, 1f)

		// Everyone, leader first, in two even columns.
		val ordered = party.members.sortedByDescending { it.name == party.leader }
		val people = ordered.ifEmpty { listOf(Member(party.leader, "", -1)) }
		people.take(MAX_MEMBERS).forEachIndexed { i, member ->
			val x = box.x + 16f + (i % 2) * MEMBER_COLUMN
			val y = box.y + 14f + (i / 2) * MEMBER_ROW
			val leader = member.name == party.leader
			if (leader) UiText.drawIcon(graphics, CROWN, x, y + 3f, 16f, MenuStyle.STAR)
			val faceX = x + CROWN_ROOM
			face(graphics, member.name, faceX, y, FACE)
			val nameX = faceX + FACE + 8f
			UiText.draw(graphics, member.name, nameX, y + 2f, 18f, classColor(member), if (leader) UiText.Face.MEDIUM else UiText.Face.REGULAR)
			if (member.level >= 0) UiText.draw(graphics, "${member.level}", nameX + UiText.width(member.name, 18f, if (leader) UiText.Face.MEDIUM else UiText.Face.REGULAR) + 8f, y + 3f, 16f, MenuStyle.MUTED)
		}

		// The floor, in a pill, and what the party asks for beside it.
		val badgeW = UiText.width(party.badge, 20f, UiText.Face.MEDIUM) + 24f
		val badgeX = box.right - 16f - badgeW
		RoundRect.fill(graphics, badgeX, box.y + 12f, badgeX + badgeW, box.y + 42f, if (party.master) 0xFF5A1E22.toInt() else 0xFF1E3A2A.toInt(), 15f)
		UiText.draw(graphics, party.badge, badgeX + 12f, box.y + 18f, 20f, if (party.master) 0xFFFF8A8A.toInt() else 0xFF8AFFB0.toInt(), UiText.Face.MEDIUM)
		val needs = listOfNotNull(
			party.classLevel.takeIf { it > 0 }?.let { "Class $it" },
			party.cataLevel.takeIf { it > 0 }?.let { "Cata $it" },
		).joinToString("  ·  ")
		if (needs.isNotEmpty()) UiText.draw(graphics, needs, badgeX - 14f - UiText.width(needs, 16f), box.y + 19f, 16f, MenuStyle.TAB_HOVER)

		// The note matters most after the floor: readable, up to two lines.
		val noteX = box.x + 16f + MEMBER_COLUMN * 2 + 10f
		val noteW = box.right - 16f - noteX
		if (party.note.isNotEmpty()) {
			wrap(party.note, 18f, noteW).take(2).forEachIndexed { line, text ->
				UiText.draw(graphics, text, noteX, box.y + 54f + line * 24f, 18f, MenuStyle.TEXT)
			}
		}
		val hint = when {
			party.yours && hovered && delistSlot >= 0 -> "Click to de-list"
			party.yours -> "Your party"
			hovered && !locked -> "Click to join"
			else -> ""
		}
		if (hint.isNotEmpty()) UiText.draw(graphics, hint, box.right - 16f - UiText.width(hint, 15f), box.bottom - 24f, 15f, if (party.yours) MenuStyle.accent() else MenuStyle.MUTED)

		// A party you can't join is greyed out; hovering it says which requirement it is.
		party.lockReason?.let { reason ->
			RoundRect.fill(graphics, box.x, box.y, box.right, box.bottom, 0x70000000, 10f)
			val text = if (hovered) reason else "Requirements not met"
			UiText.draw(graphics, text, box.right - 16f - UiText.width(text, 15f, UiText.Face.MEDIUM), box.bottom - 24f, 15f, MenuStyle.DANGER, UiText.Face.MEDIUM)
		}
	}

	private const val MAX_MEMBERS = 5
	private const val MEMBER_COLUMN = 250f
	private const val MEMBER_ROW = 30f
	private const val FACE = 20f
	private const val CROWN_ROOM = 22f
	private val CROWN = Char(0xF521).toString()

	private fun classColor(member: Member): Int {
		val clazz = DungeonClass.entries.firstOrNull { it.name.equals(member.clazz, ignoreCase = true) } ?: return MenuStyle.TEXT
		return 0xFF000000.toInt() or ClassColors.getClassColor(clazz)
	}

	/** [text] split into lines no wider than [width], on spaces where it can. */
	private fun wrap(text: String, size: Float, width: Float): List<String> {
		val lines = ArrayList<String>()
		var line = ""
		for (word in text.split(' ')) {
			val next = if (line.isEmpty()) word else "$line $word"
			if (UiText.width(next, size) <= width || line.isEmpty()) line = next else {
				lines += line
				line = word
			}
		}
		if (line.isNotEmpty()) lines += line
		return lines.map { UiText.fit(it, size, width) }
	}

	private fun memberTag(graphics: GuiGraphicsExtractor, member: Member, x: Float, y: Float) {
		val clazz = DungeonClass.entries.firstOrNull { it.name.equals(member.clazz, ignoreCase = true) }
		val color = clazz?.let { 0xFF000000.toInt() or ClassColors.getClassColor(it) } ?: MenuStyle.MUTED
		val text = "${member.clazz.take(1).uppercase(Locale.ROOT)}${if (member.level >= 0) " ${member.level}" else ""}"
		UiText.draw(graphics, text, x, y + 1f, 15f, color, UiText.Face.MEDIUM)
	}

	/** A player's face, from the skin cache (a default face until it loads). */
	private fun face(graphics: GuiGraphicsExtractor, name: String, x: Float, y: Float, size: Float) {
		if (name.isBlank() || name == "?") return
		val skin = client.playerSkinRenderCache().getOrDefault(ResolvableProfile.createUnresolved(name)).playerSkin()
		val pose = graphics.pose()
		pose.pushMatrix()
		pose.translate(x, y)
		pose.scale(size / 8f, size / 8f)
		PlayerFaceExtractor.extractRenderState(graphics, skin, 0, 0, 8)
		pose.popMatrix()
	}

	// ---- Input ----------------------------------------------------------------------

	private fun slotOf(button: Button): Int = when (button) {
		Button.REFRESH -> refreshSlot
		Button.PREVIOUS -> previousSlot
		Button.NEXT -> nextSlot
		Button.SETTINGS -> settingsSlot
		Button.CREATE -> if (delistSlot >= 0) delistSlot else createSlot
	}

	/** True when the click was the finder's. */
	private fun click(screen: Screen, x: Double, y: Double, button: Int): Boolean {
		val chest = screen as AbstractContainerScreen<*>
		val l = layout(screen)
		val mx = (x / l.k).toFloat()
		val my = (y / l.k).toFloat()
		searchFocused = l.search.contains(mx, my)
		if (searchFocused) return true
		if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true
		l.buttons.firstOrNull { it.first.contains(mx, my) }?.let { (_, which) ->
			val slot = slotOf(which)
			if (slot >= 0) {
				clickSlot(chest, slot)
				if (which != Button.SETTINGS && which != Button.CREATE) resetScroll()
			}
			return true
		}
		if (scrollbar.press(mx, my, maxScroll, ::setScroll)) return true
		if (!l.list.contains(mx, my)) return true
		val index = ((my - l.list.y + scroll) / CARD_STEP).toInt()
		val inCard = (my - l.list.y + scroll) - index * CARD_STEP <= CARD_H
		val party = shown().getOrNull(index)?.takeIf { inCard } ?: return true
		if (party.lockReason == null && (!party.yours || delistSlot >= 0)) {
			// Joining: its members' classes go to the duplicate class alert.
			if (!party.yours) PartyKick.rememberListing(party.members.associate { it.name to it.clazz })
			clickSlot(chest, party.slot)
		}
		return true
	}

	private fun clickSlot(chest: AbstractContainerScreen<*>, slot: Int) {
		val player = client.player ?: return
		client.gameMode?.handleContainerInput(chest.menu.containerId, slot, 0, ContainerInput.PICKUP, player)
		waitingSince = System.currentTimeMillis()
	}

	/** Typing in the search box; true when the key was its. */
	private fun key(key: Int): Boolean {
		when (key) {
			GLFW.GLFW_KEY_BACKSPACE -> if (search.isNotEmpty()) search = search.dropLast(1)
			GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER -> searchFocused = false
			// Every other key stays in the box, so typing an E doesn't close the menu.
			else -> return true
		}
		resetScroll()
		return true
	}
}
