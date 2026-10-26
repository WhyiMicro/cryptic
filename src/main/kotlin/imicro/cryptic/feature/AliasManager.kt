package imicro.cryptic.feature

import com.google.gson.GsonBuilder
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import imicro.cryptic.Cryptic
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket
import java.nio.file.Files
import java.util.Locale

/**
 * Commands of your own that stand for others, from NoammAddons' Command Shortcuts: `/pk` for
 * `/party kick`, `/dh` for `/warp dungeon_hub`. Extra words go on the end, or where `{args}` says;
 * `{1}`, `{2}` take single words. `;` separates commands, sent half a second apart so Hypixel
 * accepts them.
 *
 * Each alias is a real client command, so chat suggests it. A removed alias can't leave the command
 * tree until the next server, so until then it's sent as typed. Saved in
 * `config/cryptic/aliases.json`.
 */
object AliasManager {
	data class Alias(
		var name: String = "",
		var command: String = "",
		var enabled: Boolean = true,
	)

	val aliases = mutableListOf<Alias>()

	@JvmField
	val module = Module(
		id = "alias_manager",
		name = "Alias Manager",
		description = "Shortcuts for commands",
		category = ModuleCategory.GENERAL,
		supportsKeybind = false,
	)

	/** A queued command: what to send, the client tick to send it on, and its alias depth. */
	private class Queued(val command: String, val at: Long, val depth: Int)

	private val queue = ArrayDeque<Queued>()
	private var ticks = 0L

	/** The alias depth of the command being sent, so loops end. */
	private var depth = 0

	/** Names registered on this server's command tree, which can't be removed. */
	private val registered = HashSet<String>()

	private val file get() = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("aliases.json")
	private val gson = GsonBuilder().setPrettyPrinting().create()

	private val NAME = Regex("""^[a-z0-9_\-]{1,24}$""")

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		load()
		ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			// A new server rebuilds the commands; removed aliases just aren't registered.
			registered.clear()
			aliases.forEach { register(dispatcher, it.name) }
		}
	}

	private fun register(dispatcher: CommandDispatcher<FabricClientCommandSource>, name: String) {
		if (!registered.add(name)) return
		dispatcher.register(
			ClientCommands.literal(name)
				.executes {
					run(name, "")
					1
				}
				.then(
					ClientCommands.argument("args", StringArgumentType.greedyString()).executes {
						run(name, StringArgumentType.getString(it, "args"))
						1
					},
				),
		)
	}

	fun tick(@Suppress("UNUSED_PARAMETER") client: Minecraft) {
		ticks++
		while (queue.isNotEmpty() && queue.first().at <= ticks) {
			val next = queue.removeFirst()
			send(next.command, next.depth)
		}
	}

	private fun run(name: String, args: String) {
		val alias = aliases.firstOrNull { it.name == name }
		if (alias == null || !alias.enabled || !module.enabled) {
			// Removed or off: the server gets what was typed.
			forward(if (args.isBlank()) name else "$name $args")
			return
		}
		if (depth >= MAX_DEPTH) {
			note("Stopped /$name: aliases calling each other went more than $MAX_DEPTH deep.")
			return
		}

		val commands = expand(alias.command, args)
		commands.forEachIndexed { index, command ->
			if (index == 0) {
				send(command, depth + 1)
			} else {
				queue.addLast(Queued(command, ticks + index * SPACING_TICKS, depth + 1))
			}
		}
	}

	/**
	 * [template] with [args] filled in: `{args}` takes all, `{1}` to `{9}` one word each, otherwise
	 * they go on the end of the first command.
	 */
	fun expand(template: String, args: String): List<String> {
		val words = args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
		val placeholders = template.contains("{args}") || Regex("""\{\d}""").containsMatchIn(template)
		return template.split(';').map { it.trim().removePrefix("/") }.filter { it.isNotEmpty() }
			.mapIndexed { index, part ->
				var command = part.replace("{args}", args.trim())
				for (i in 1..9) command = command.replace("{$i}", words.getOrNull(i - 1).orEmpty())
				command = command.replace(Regex("\\s+"), " ").trim()
				if (!placeholders && index == 0 && args.isNotBlank()) "$command ${args.trim()}" else command
			}
	}

	/** Sent through the client's commands first, so it can be another alias or `/cryptic`. */
	private fun send(command: String, level: Int) {
		val connection = Minecraft.getInstance().connection ?: return
		val previous = depth
		depth = level
		try {
			connection.sendCommand(command)
		} catch (error: RuntimeException) {
			Cryptic.LOGGER.error("Alias Manager could not send /$command", error)
		} finally {
			depth = previous
		}
	}

	/** Straight to the server, past the client's commands. */
	private fun forward(command: String) {
		Minecraft.getInstance().connection?.send(ServerboundChatCommandPacket(command))
	}

	/** Why [name] can't be an alias, or null. [except] is the alias being renamed. */
	fun problemWith(name: String, except: Alias?): String? {
		val clean = clean(name)
		if (clean.isEmpty()) return "Give it a name."
		if (!NAME.matches(clean)) return "Letters, digits, - and _ only."
		if (clean == "cryptic") return "/cryptic is taken."
		if (aliases.any { it !== except && it.name == clean }) return "/$clean is already an alias."
		// Another mod's client command; replacing it would break it.
		val existing = ClientCommands.getActiveDispatcher()?.root?.getChild(clean)
		if (existing != null && clean !in registered) return "/$clean is another mod's command."
		return null
	}

	fun clean(name: String): String = name.trim().removePrefix("/").lowercase(Locale.ROOT)

	/** Adds [alias], or replaces [replacing], and makes it typeable now. */
	fun put(alias: Alias, replacing: Alias?) {
		alias.name = clean(alias.name)
		alias.command = alias.command.trim()
		val index = replacing?.let { old -> aliases.indexOfFirst { it === old } } ?: -1
		if (index >= 0) aliases[index] = alias else aliases += alias
		save()

		val dispatcher = ClientCommands.getActiveDispatcher() ?: return
		if (alias.name !in registered) {
			register(dispatcher, alias.name)
			ClientCommands.refreshCommandCompletions()
		}
	}

	fun remove(alias: Alias) {
		aliases.removeIf { it === alias }
		save()
	}

	fun toggle(alias: Alias) {
		alias.enabled = !alias.enabled
		save()
	}

	private fun note(message: String) {
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §7$message"))
	}

	private fun load() {
		runCatching {
			if (!Files.exists(file)) return
			val loaded = gson.fromJson(Files.readString(file), Array<Alias>::class.java) ?: return
			aliases.clear()
			@Suppress("SENSELESS_COMPARISON")
			loaded.filter { it != null && it.name != null && it.command != null }
				.forEach { aliases += Alias(clean(it.name), it.command, it.enabled) }
		}.onFailure { Cryptic.LOGGER.error("Could not read aliases", it) }
	}

	fun save() {
		runCatching {
			Files.createDirectories(file.parent)
			Files.writeString(file, gson.toJson(aliases))
		}.onFailure { Cryptic.LOGGER.error("Could not save aliases", it) }
	}

	/** Past this alias depth, it's a loop. */
	private const val MAX_DEPTH = 5

	private const val SPACING_TICKS = 10L
}
