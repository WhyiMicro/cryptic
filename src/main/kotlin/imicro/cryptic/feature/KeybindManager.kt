package imicro.cryptic.feature

import com.google.gson.GsonBuilder
import com.mojang.blaze3d.platform.InputConstants
import imicro.cryptic.Cryptic
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonTeam
import imicro.cryptic.dungeon.Floor7
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.KeybindModuleSetting
import imicro.cryptic.gui.KeybindsScreen
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import java.nio.file.Files
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Keys that run a command or say something in chat.
 *
 * Modelled on Athen's Keybinds (BSD 3-Clause, Copyright (c) 2025-2026 Starred):
 * a binding is one or more keys or mouse buttons held together, the line it
 * sends, and when it is allowed to — outside a menu, inside a chest, on certain
 * islands, floors, classes or Floor 7 phases. A line starting with "/" is a
 * command, which goes through the client's own commands first, so a binding
 * can run `/cryptic` or an alias; anything else is said in chat.
 *
 * The bindings live in `config/cryptic/keybinds.json`, apart from the profile,
 * like the waypoint packs: they are the player's, not a look.
 */
object KeybindManager {
	enum class WorkIn(val title: String) {
		OUTSIDE_GUI("Outside GUI"),
		GUI("In a GUI"),
		EVERYWHERE("Everywhere"),
	}

	/**
	 * One binding. Every field has a default so Gson, which builds these
	 * without calling a constructor, never leaves one null.
	 */
	data class Binding(
		var keys: MutableList<Int> = mutableListOf(),
		var command: String = "",
		var enabled: Boolean = true,
		var category: String = "",
		var workIn: WorkIn = WorkIn.OUTSIDE_GUI,
		var islands: MutableSet<String> = linkedSetOf(),
		/** "E", "F1" to "F7", "M1" to "M7". */
		var floors: MutableSet<String> = linkedSetOf(),
		var classes: MutableSet<DungeonTeam.DungeonClass> = linkedSetOf(),
		/** Floor 7's boss phases, 1 to 5. */
		var phases: MutableSet<Int> = linkedSetOf(),
	) {
		fun copy(): Binding = Binding(
			keys.toMutableList(), command, enabled, category, workIn,
			islands.toCollection(linkedSetOf()), floors.toCollection(linkedSetOf()),
			classes.toCollection(linkedSetOf()), phases.toCollection(linkedSetOf()),
		)
	}

	data class Category(var name: String = "", var enabled: Boolean = true)

	private data class Saved(
		val bindings: MutableList<Binding> = mutableListOf(),
		val categories: MutableList<Category> = mutableListOf(),
	)

	/** Islands as the tab list names them, which is what [SkyblockLocation.area] holds. */
	val ISLANDS = listOf(
		"Private Island", "Garden", "Hub", "Dungeon Hub", "Catacombs", "The Park", "Spider's Den",
		"The End", "Crimson Isle", "Kuudra", "Gold Mine", "Deep Caverns", "Dwarven Mines",
		"Mineshaft", "Crystal Hollows", "The Farming Islands", "Backwater Bayou", "Galatea",
		"The Rift", "Jerry's Workshop",
	)

	val FLOORS = listOf("E") + (1..7).map { "F$it" } + (1..7).map { "M$it" }

	val CLASSES = DungeonTeam.DungeonClass.entries.filter { it != DungeonTeam.DungeonClass.UNKNOWN }

	val bindings = mutableListOf<Binding>()
	val categories = mutableListOf<Category>()

	private val open = ButtonModuleSetting("open", "Open manager", action = { KeybindsScreen.request() })

	@JvmField
	val module = Module(
		id = "keybinds_manager",
		name = "Keybinds Manager",
		description = "Keys that run a command or say something in chat",
		category = ModuleCategory.MISC,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(open),
	)

	/** Keys and buttons held right now, keys by GLFW code and buttons as [KeybindModuleSetting.mouse] makes them. */
	private val held = HashSet<Int>()

	/** Bindings that have fired and wait for one of their keys to come up before they can again. */
	private val fired: MutableSet<Binding> = Collections.newSetFromMap(IdentityHashMap())

	private val file get() = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("keybinds.json")
	private val gson = GsonBuilder().setPrettyPrinting().create()

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		load()
	}

	/** Whether anything here has to know the island, the floor, the class or the phase. */
	val needsSkyblock: Boolean get() = module.enabled && bindings.any { it.islands.isNotEmpty() }
	val needsDungeon: Boolean get() = module.enabled && bindings.any { it.floors.isNotEmpty() || it.phases.isNotEmpty() || it.classes.isNotEmpty() }
	val needsTeam: Boolean get() = module.enabled && bindings.any { it.classes.isNotEmpty() }
	val needsPhase: Boolean get() = module.enabled && bindings.any { it.phases.isNotEmpty() }

	// ---- Input ------------------------------------------------------------

	/** A key going down or up anywhere, from the keyboard mixin. Never kept from the game. */
	@JvmStatic
	fun onKey(key: Int, pressed: Boolean) {
		if (key == InputConstants.UNKNOWN.value) return
		if (pressed) press(key) else release(key)
	}

	/** A mouse button going down or up, from the mouse mixin. */
	@JvmStatic
	fun onMouse(button: Int, pressed: Boolean) {
		val code = KeybindModuleSetting.mouse(button)
		if (pressed) press(code) else release(code)
	}

	private fun press(code: Int) {
		if (!held.add(code)) return
		if (!module.enabled || bindings.isEmpty()) return
		// The manager itself is where keys are being bound, not used.
		val screen = Minecraft.getInstance().gui.screen()
		if (screen is KeybindsScreen) return

		val disabled = categories.filter { !it.enabled }.mapTo(HashSet()) { it.name }
		val matching = bindings.filter { binding ->
			binding.enabled &&
				binding.keys.isNotEmpty() &&
				code in binding.keys &&
				binding !in fired &&
				binding.category !in disabled &&
				held.containsAll(binding.keys) &&
				allowed(binding)
		}
		// Ctrl+K and K both held: only the one that asked for more keys, or K
		// would go off every time Ctrl+K was meant.
		val chosen = matching.filter { binding ->
			matching.none { other -> other !== binding && other.keys.size > binding.keys.size && other.keys.containsAll(binding.keys) }
		}
		chosen.forEach {
			fired += it
			send(it.command)
		}
	}

	private fun release(code: Int) {
		held.remove(code)
		fired.removeIf { binding -> binding.keys.any { it !in held } }
	}

	/** Forgets what is held, for when the window loses the keys it would have heard come up. */
	fun releaseAll() {
		held.clear()
		fired.clear()
	}

	/** Whether [binding]'s conditions hold right now. */
	private fun allowed(binding: Binding): Boolean {
		val client = Minecraft.getInstance()
		val screen = client.gui.screen()
		val inGui = screen is AbstractContainerScreen<*>
		val outside = screen == null
		when (binding.workIn) {
			WorkIn.OUTSIDE_GUI -> if (!outside) return false
			WorkIn.GUI -> if (!inGui) return false
			// Never while typing: chat, a sign, a search box are all screens
			// that are not containers.
			WorkIn.EVERYWHERE -> if (!outside && !inGui) return false
		}

		if (binding.islands.isNotEmpty() && SkyblockLocation.area !in binding.islands) return false
		if (binding.floors.isNotEmpty() && floorName() !in binding.floors) return false
		if (binding.classes.isNotEmpty()) {
			val name = client.player?.name?.string ?: return false
			if (DungeonTeam.classOf(name) !in binding.classes) return false
		}
		if (binding.phases.isNotEmpty()) {
			if (!DungeonLocation.inDungeon || DungeonLocation.floor != 7) return false
			if (Floor7.phase !in binding.phases) return false
		}
		return true
	}

	private fun floorName(): String? = when {
		!DungeonLocation.inDungeon -> null
		DungeonLocation.floor == 0 -> "E"
		DungeonLocation.masterMode -> "M${DungeonLocation.floor}"
		else -> "F${DungeonLocation.floor}"
	}

	/**
	 * Sends a binding's line: a command through the client's command dispatcher
	 * and on to the server, anything else as chat.
	 */
	private fun send(line: String) {
		val text = line.trim()
		if (text.isEmpty()) return
		val connection = Minecraft.getInstance().connection ?: return
		try {
			if (text.startsWith("/")) connection.sendCommand(text.drop(1)) else connection.sendChat(text)
		} catch (error: RuntimeException) {
			Cryptic.LOGGER.error("Keybinds Manager could not send \"$text\"", error)
		}
	}

	// ---- Editing ------------------------------------------------------------

	/** Adds [binding], or puts it in place of [replacing]. */
	fun put(binding: Binding, replacing: Binding?) {
		val index = replacing?.let { old -> bindings.indexOfFirst { it === old } } ?: -1
		if (index >= 0) bindings[index] = binding else bindings += binding
		if (binding.category.isNotBlank() && categories.none { it.name == binding.category }) {
			categories += Category(binding.category)
		}
		fired.clear()
		save()
	}

	fun remove(binding: Binding) {
		bindings.removeIf { it === binding }
		fired.remove(binding)
		// A category nothing is in any more goes with its last binding.
		categories.removeIf { category -> bindings.none { it.category == category.name } }
		save()
	}

	fun toggle(binding: Binding) {
		binding.enabled = !binding.enabled
		save()
	}

	fun toggleCategory(name: String) {
		categories.firstOrNull { it.name == name }?.let { it.enabled = !it.enabled }
		save()
	}

	/** How a set of keys reads: "LEFT.CONTROL + K". */
	fun describe(keys: List<Int>): String =
		if (keys.isEmpty()) "None" else keys.joinToString(" + ") { KeybindModuleSetting.nameOf(it) }

	// ---- Storage ------------------------------------------------------------

	private fun load() {
		runCatching {
			if (!Files.exists(file)) return
			val saved = gson.fromJson(Files.readString(file), Saved::class.java) ?: return
			bindings.clear()
			categories.clear()
			// Fields a hand-edited file left out come back as nulls through
			// Gson, so each binding is rebuilt on its defaults.
			saved.bindings.orEmpty().forEach { loaded ->
				@Suppress("SENSELESS_COMPARISON", "UselessCallOnNotNull")
				bindings += Binding(
					keys = loaded.keys.orEmpty().toMutableList(),
					command = loaded.command.orEmpty(),
					enabled = loaded.enabled,
					category = loaded.category.orEmpty(),
					workIn = loaded.workIn ?: WorkIn.OUTSIDE_GUI,
					islands = loaded.islands.orEmpty().toCollection(linkedSetOf()),
					floors = loaded.floors.orEmpty().toCollection(linkedSetOf()),
					classes = loaded.classes.orEmpty().filterNotNull().toCollection(linkedSetOf()),
					phases = loaded.phases.orEmpty().toCollection(linkedSetOf()),
				)
			}
			saved.categories.orEmpty().forEach { if (!it.name.isNullOrBlank()) categories += Category(it.name, it.enabled) }
		}.onFailure { Cryptic.LOGGER.error("Could not read keybinds", it) }
	}

	fun save() {
		runCatching {
			Files.createDirectories(file.parent)
			Files.writeString(file, gson.toJson(Saved(bindings, categories)))
		}.onFailure { Cryptic.LOGGER.error("Could not save keybinds", it) }
	}
}
