package imicro.cryptic.feature

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import imicro.cryptic.Cryptic
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Checkbox
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Named sets of waypoints, any number switched on, one of them edited.
 *
 * Odin's waypoint packs (BSD 3-Clause, Copyright (c) 2025 odtheking): a pack
 * for a route, a pack a friend sent you, a pack of your own — shown together,
 * edited one at a time. Within a pack, waypoints are kept under a key: a room's
 * name for dungeon rooms, a floor for boss rooms.
 *
 * Shared by Dungeon Waypoints and Boss Waypoints, which differ only in what a
 * waypoint is and how a pack is written out to share.
 */
abstract class PackStore<T : Any>(
	/** The heading of the pack manager. */
	val title: String,
	private val fileName: String,
	private val itemClass: Class<T>,
	private val firstPackName: String,
) {
	class Pack<T>(var name: String, var enabled: Boolean, val rooms: LinkedHashMap<String, MutableList<T>> = LinkedHashMap()) {
		val count: Int get() = rooms.values.sumOf { it.size }
	}

	/** A read-only pack listed first, with a switch and nothing else. */
	class DefaultRow(val label: String, val count: () -> Int, val enabled: () -> Boolean, val setEnabled: (Boolean) -> Unit)

	open val defaultRow: DefaultRow? = null

	val packs = mutableListOf<Pack<T>>()

	/** The pack Edit mode writes into, by name. */
	var editName: String? = null
		private set

	val editPack: Pack<T>? get() = packs.firstOrNull { it.name == editName }

	protected val gson: Gson = GsonBuilder().setPrettyPrinting().create()
	protected val dir: Path get() = FabricLoader.getInstance().configDir.resolve("cryptic")
	private val file: Path get() = dir.resolve(fileName)

	/** Anything to read when the store's own file does not exist yet. */
	protected open fun loadLegacy() = Unit

	fun load() {
		packs.clear()
		editName = null
		runCatching {
			if (Files.exists(file)) {
				val root = JsonParser.parseString(Files.readString(file)).asJsonObject
				root.getAsJsonArray("packs")?.forEach { element ->
					val obj = element.asJsonObject
					val rooms = LinkedHashMap<String, MutableList<T>>()
					obj.getAsJsonObject("rooms")?.entrySet()?.forEach { (key, list) ->
						rooms[key] = list.asJsonArray.mapNotNull { runCatching { gson.fromJson(it, itemClass) }.getOrNull() }.toMutableList()
					}
					packs += Pack(obj.get("name").asString, obj.get("enabled")?.asBoolean ?: true, rooms)
				}
				editName = root.get("edit")?.takeIf { !it.isJsonNull }?.asString
			} else {
				loadLegacy()
			}
		}.onFailure { Cryptic.LOGGER.error("Could not read {}", fileName, it) }
		if (editPack == null) editName = packs.firstOrNull()?.name
	}

	fun save() {
		runCatching {
			Files.createDirectories(dir)
			val root = JsonObject()
			editName?.let { root.addProperty("edit", it) }
			val array = JsonArray()
			packs.forEach { pack ->
				array.add(JsonObject().apply {
					addProperty("name", pack.name)
					addProperty("enabled", pack.enabled)
					add("rooms", JsonObject().apply {
						pack.rooms.filterValues { it.isNotEmpty() }.forEach { (key, list) ->
							add(key, JsonArray().apply { list.forEach { add(gson.toJsonTree(it)) } })
						}
					})
				})
			}
			root.add("packs", array)
			Files.writeString(file, gson.toJson(root))
		}.onFailure { Cryptic.LOGGER.error("Could not save {}", fileName, it) }
	}

	/** Every waypoint under [key] from the packs switched on, with the pack it is in. */
	fun waypointsIn(key: String): Sequence<Pair<Pack<T>, T>> =
		packs.asSequence().filter { it.enabled }.flatMap { pack ->
			pack.rooms[key].orEmpty().asSequence().map { pack to it }
		}

	/** The list Edit mode writes into for [key], making a first pack if there is none. */
	fun editList(key: String): MutableList<T> {
		val pack = editPack ?: create(firstPackName) ?: packs.first()
		if (editName != pack.name) setEdit(pack)
		pack.enabled = true
		return pack.rooms.getOrPut(key) { mutableListOf() }
	}

	fun setEdit(pack: Pack<T>) {
		editName = pack.name
		pack.enabled = true
		save()
	}

	private fun nameTaken(name: String): Boolean =
		(defaultRow != null && name.equals("Default", ignoreCase = true)) || packs.any { it.name.equals(name, ignoreCase = true) }

	/** A free name built on [base]: itself, then "base 2", "base 3". */
	fun freeName(base: String): String {
		val clean = base.trim().ifBlank { "Pack" }.take(32)
		if (!nameTaken(clean)) return clean
		return generateSequence(2) { it + 1 }.map { "$clean $it" }.first { !nameTaken(it) }
	}

	fun create(name: String): Pack<T>? {
		val clean = name.trim().take(32)
		if (clean.isEmpty() || nameTaken(clean)) return null
		val pack = Pack<T>(clean, true)
		packs += pack
		if (editName == null) editName = clean
		save()
		return pack
	}

	fun rename(pack: Pack<T>, name: String): Boolean {
		val clean = name.trim().take(32)
		if (clean.isEmpty() || (nameTaken(clean) && !clean.equals(pack.name, ignoreCase = true))) return false
		if (editName == pack.name) editName = clean
		pack.name = clean
		save()
		return true
	}

	fun delete(pack: Pack<T>) {
		packs.remove(pack)
		if (editName == pack.name) editName = packs.firstOrNull()?.name
		save()
	}

	/** Adds a pack decoded from [text]. Null when it is not something this store can read. */
	fun import(name: String, text: String): Pack<T>? {
		val rooms = decode(text) ?: return null
		val pack = Pack(freeName(name), true, LinkedHashMap(rooms.mapValues { it.value.toMutableList() }))
		packs += pack
		if (editName == null) editName = pack.name
		save()
		return pack
	}

	/** A pack as a string to share: gzip, then base64, of [toJson]. */
	fun export(pack: Pack<T>): String {
		val bytes = ByteArrayOutputStream().use { out ->
			GZIPOutputStream(out).use { it.write(toJson(pack).toString().toByteArray()) }
			out.toByteArray()
		}
		return Base64.getEncoder().encodeToString(bytes)
	}

	/** The JSON a shared pack is written as. */
	protected abstract fun toJson(pack: Pack<T>): JsonElement

	/** Waypoints by key, read from shared JSON, or null when it is not what this store reads. */
	protected abstract fun fromJson(root: JsonObject): Map<String, List<T>>?

	fun decode(raw: String): Map<String, List<T>>? {
		val text = raw.trim()
		if (text.isEmpty()) return null
		val json = runCatching {
			if (text.startsWith("{")) text else GZIPInputStream(Base64.getDecoder().decode(text).inputStream()).bufferedReader().use { it.readText() }
		}.getOrNull() ?: return null
		val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
		return runCatching { fromJson(root) }.getOrNull()
	}
}

/**
 * The pack manager, `/cryptic dwp` and `/cryptic bwp`: switch packs on and off,
 * pick the one Edit mode writes into, and create, import, export, rename or
 * delete them. Odin's pack selector, on Minecraft's own widgets.
 */
class PackScreen<T : Any>(private val store: PackStore<T>) : Screen(Component.literal(store.title)) {
	/** A question waiting for a line of text: its title and what to do with the answer. */
	private class Prompt(val title: String, val initial: String, val done: (String) -> Unit)

	private var prompt: Prompt? = null
	private var promptBox: EditBox? = null
	private var scroll = 0
	private var armedDelete: PackStore.Pack<T>? = null
	private var status: String? = null

	private val panelWidth = 360
	private val rowHeight = 24
	private val left get() = (width - panelWidth) / 2
	private val listTop = 64
	private val visibleRows get() = ((height - listTop - 50) / rowHeight).coerceAtLeast(1)

	/** One line of the list: the read-only default, or a pack. */
	private val rows: List<PackStore.Pack<T>?>
		get() = (if (store.defaultRow != null) listOf<PackStore.Pack<T>?>(null) else emptyList()) + store.packs

	override fun init() {
		armedDelete = armedDelete?.takeIf { it in store.packs }
		val current = prompt
		if (current != null) {
			initPrompt(current)
			return
		}

		addRenderableWidget(button("Create", left, 36, 84) {
			prompt = Prompt("New pack name", store.freeName("Pack")) { name ->
				status = store.create(name)?.let { "Created ${it.name}." } ?: "§cThat name is taken."
			}
			rebuildWidgets()
		})
		addRenderableWidget(button("Import", left + 90, 36, 84) {
			val clipboard = Minecraft.getInstance().keyboardHandler.clipboard
			if (store.decode(clipboard) == null) {
				status = "§cCopy an exported waypoint string first."
				rebuildWidgets()
				return@button
			}
			prompt = Prompt("Name for the imported pack", store.freeName("Imported")) { name ->
				status = store.import(name, clipboard)?.let { "Imported ${it.count} waypoint(s) as ${it.name}." }
					?: "§cThat is not a waypoint string."
			}
			rebuildWidgets()
		})

		val all = rows
		scroll = scroll.coerceIn(0, (all.size - visibleRows).coerceAtLeast(0))
		all.drop(scroll).take(visibleRows).forEachIndexed { index, pack ->
			val y = listTop + index * rowHeight
			if (pack == null) initDefaultRow(y) else initRow(pack, y)
		}

		addRenderableWidget(button("Done", width / 2 - 40, height - 30, 80) { onClose() })
	}

	private fun initDefaultRow(y: Int) {
		val default = store.defaultRow ?: return
		addRenderableWidget(
			Checkbox.builder(Component.empty(), font)
				.pos(left + 4, y + 2)
				.selected(default.enabled())
				.onValueChange { _, value -> default.setEnabled(value) }
				.build(),
		)
	}

	private fun initRow(pack: PackStore.Pack<T>, y: Int) {
		addRenderableWidget(
			Checkbox.builder(Component.empty(), font)
				.pos(left + 4, y + 2)
				.selected(pack.enabled)
				.onValueChange { _, value ->
					pack.enabled = value
					store.save()
				}
				.build(),
		)
		val editing = pack.name == store.editName
		addRenderableWidget(button(if (editing) "Editing" else "Edit", left + 196, y + 2, 46) {
			store.setEdit(pack)
			rebuildWidgets()
		}).active = !editing
		addRenderableWidget(button("Export", left + 244, y + 2, 42) {
			Minecraft.getInstance().keyboardHandler.clipboard = store.export(pack)
			status = "Copied ${pack.name} (${pack.count} waypoints) to the clipboard."
			rebuildWidgets()
		})
		addRenderableWidget(button("✎", left + 288, y + 2, 20) {
			prompt = Prompt("Rename ${pack.name}", pack.name) { name ->
				status = if (store.rename(pack, name)) "Renamed to ${pack.name}." else "§cThat name is taken."
			}
			rebuildWidgets()
		})
		val armed = armedDelete === pack
		addRenderableWidget(button(if (armed) "§cSure?" else "§cX", left + 310, y + 2, if (armed) 46 else 20) {
			if (armed) {
				store.delete(pack)
				status = "Deleted ${pack.name}."
				armedDelete = null
			} else {
				armedDelete = pack
			}
			rebuildWidgets()
		})
	}

	private fun initPrompt(current: Prompt) {
		val box = EditBox(font, width / 2 - 100, height / 2 - 10, 200, 20, Component.literal(current.title))
		box.setMaxLength(32)
		box.value = current.initial
		promptBox = addRenderableWidget(box)
		setInitialFocus(box)
		addRenderableWidget(button("OK", width / 2 - 100, height / 2 + 16, 96) { confirmPrompt() })
		addRenderableWidget(button("Cancel", width / 2 + 4, height / 2 + 16, 96) {
			prompt = null
			rebuildWidgets()
		})
	}

	private fun confirmPrompt() {
		val current = prompt ?: return
		prompt = null
		current.done(promptBox?.value ?: "")
		rebuildWidgets()
	}

	private fun button(label: String, x: Int, y: Int, width: Int, onPress: (Button) -> Unit): Button =
		Button.builder(Component.literal(label)) { onPress(it) }.bounds(x, y, width, 20).build()

	override fun keyPressed(event: KeyEvent): Boolean {
		if (prompt != null && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
			confirmPrompt()
			return true
		}
		if (prompt != null && event.isEscape) {
			prompt = null
			rebuildWidgets()
			return true
		}
		return super.keyPressed(event)
	}

	override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
		if (prompt != null) return super.mouseScrolled(x, y, scrollX, scrollY)
		val before = scroll
		scroll = (scroll - scrollY.toInt()).coerceAtLeast(0)
		if (scroll != before) rebuildWidgets()
		return true
	}

	override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
		super.extractRenderState(graphics, mouseX, mouseY, a)
		val white = 0xFFFFFFFF.toInt()
		graphics.centeredText(font, store.title, width / 2, 14, white)

		val current = prompt
		if (current != null) {
			graphics.centeredText(font, current.title, width / 2, height / 2 - 26, white)
			return
		}

		rows.drop(scroll).take(visibleRows).forEachIndexed { index, pack ->
			val y = listTop + index * rowHeight
			val editing = pack != null && pack.name == store.editName
			graphics.fill(left, y, left + panelWidth, y + rowHeight - 2, if (editing) 0x60406080 else 0x40000000)
			val default = store.defaultRow
			val name = when {
				pack == null -> default?.label ?: ""
				editing -> "§e★ §f${pack.name}"
				else -> pack.name
			}
			// The count sits against the buttons, and a long name gives way to it.
			val count = "§a${pack?.count ?: default?.count?.invoke() ?: 0} §7wp"
			val countX = left + (if (pack == null) panelWidth - 8 else 192) - font.width(count)
			graphics.text(font, fit(name, countX - (left + 28) - 6), left + 28, y + 8, white)
			graphics.text(font, count, countX, y + 8, white)
		}

		status?.let { graphics.centeredText(font, it, width / 2, height - 44, 0xFFAAAAAA.toInt()) }
	}

	/** [text] cut down to [width] pixels, with an ellipsis when it had to be. */
	private fun fit(text: String, width: Int): String {
		if (font.width(text) <= width) return text
		var cut = text
		while (cut.isNotEmpty() && font.width("$cut…") > width) cut = cut.dropLast(1)
		return "$cut…"
	}

	override fun isPauseScreen(): Boolean = false
}

/** The one-line prompt for a waypoint's title: Enter keeps it, Escape leaves it. */
class WaypointTitleScreen(private val initial: String, private val done: (String) -> Unit) :
	Screen(Component.literal("Waypoint title")) {
	private var box: EditBox? = null

	override fun init() {
		val field = EditBox(font, width / 2 - 100, height / 2 - 10, 200, 20, Component.literal("Title"))
		field.setMaxLength(64)
		field.value = initial
		box = addRenderableWidget(field)
		setInitialFocus(field)
	}

	override fun keyPressed(event: KeyEvent): Boolean {
		if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
			done(box?.value ?: "")
			onClose()
			return true
		}
		return super.keyPressed(event)
	}

	override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
		super.extractRenderState(graphics, mouseX, mouseY, a)
		graphics.centeredText(font, "Waypoint title (Enter to save, empty for none)", width / 2, height / 2 - 24, 0xFFFFFFFF.toInt())
	}

	override fun isPauseScreen(): Boolean = false
}
