package imicro.cryptic.feature

import com.google.gson.GsonBuilder
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import imicro.cryptic.Cryptic
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.nio.file.Files

/**
 * Players you won't run with, each with a reason and the day they were added. While on, one who
 * joins your party is kicked (when you lead) and named in a toast. Edited on the module's page or
 * with `/cryptic blacklist`. Saved in `config/cryptic/blacklist.json`.
 */
object PartyBlacklist {
	data class Entry(
		var name: String = "",
		var reason: String = "",
		/** When it was added, in epoch milliseconds. */
		var added: Long = 0L,
	)

	val entries = mutableListOf<Entry>()

	@JvmField
	val kick = ToggleModuleSetting(
		id = "kick",
		label = "Kick them",
		defaultValue = true,
		description = "Kicks a blacklisted player as soon as they join, while you lead the party.",
	)

	@JvmField
	val announce = ToggleModuleSetting(
		id = "announce",
		label = "Say why in party chat",
		defaultValue = false,
		description = "Before kicking, says in party chat that they're blacklisted, with your reason.",
		visibleIf = { kick.value },
	)

	@JvmField
	val module = Module(
		id = "party_blacklist",
		name = "Party Blacklist",
		description = "Players you won't play with",
		category = ModuleCategory.DUNGEON,
		supportsKeybind = false,
		settings = listOf(kick, announce),
	)

	private val file get() = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("blacklist.json")
	private val gson = GsonBuilder().setPrettyPrinting().create()

	val NAME = Regex("""^\w{1,16}$""")

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		load()
	}

	fun find(name: String): Entry? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

	/** Adds [entry], or replaces [replacing]; an entry for the same name is replaced too. */
	fun put(entry: Entry, replacing: Entry? = null) {
		entry.name = entry.name.trim()
		entry.reason = entry.reason.trim()
		if (entry.added == 0L) entry.added = System.currentTimeMillis()
		val index = entries.indexOfFirst { it === replacing || it.name.equals(entry.name, ignoreCase = true) }
		if (index >= 0) {
			entries[index] = entry
			entries.removeIf { it !== entry && it.name.equals(entry.name, ignoreCase = true) }
		} else {
			entries += entry
		}
		save()
	}

	fun remove(entry: Entry) {
		entries.removeIf { it === entry }
		save()
	}

	/** Someone joined the party: tell, and kick when you lead. */
	fun onJoined(name: String) {
		if (!module.enabled) return
		val entry = find(name) ?: return
		val why = entry.reason.ifEmpty { "no reason given" }
		val kicking = kick.value && PartyFeatures.mayLeadParty()
		val text = if (kicking) "Kicking $name, who is blacklisted: $why" else "$name is blacklisted: $why"
		if (Toasts.module.enabled) {
			Toasts.show("Party Blacklist", text, error = true, head = name)
		} else {
			Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §c$text"))
		}
		if (!kicking) return
		if (announce.value) PartyFeatures.enqueue("pc Kicking $name: blacklisted ($why)")
		PartyFeatures.enqueue("party kick $name")
	}

	/**
	 * `/cryptic blacklist` opens the list; `add <name> [reason]`, `remove <name>` and `list` work
	 * from chat.
	 */
	fun command(): LiteralArgumentBuilder<FabricClientCommandSource> =
		ClientCommands.literal("blacklist")
			.executes {
				imicro.cryptic.gui.menu.MenuScreen.request(module)
				1
			}
			.then(
				ClientCommands.literal("add").then(
					ClientCommands.argument("name", StringArgumentType.word())
						.suggests { _, builder -> SharedSuggestionProvider.suggest(onlineNames(), builder) }
						.executes { add(it, "") }
						.then(
							ClientCommands.argument("reason", StringArgumentType.greedyString())
								.executes { add(it, StringArgumentType.getString(it, "reason")) },
						),
				),
			)
			.then(
				ClientCommands.literal("remove").then(
					ClientCommands.argument("name", StringArgumentType.word())
						.suggests { _, builder -> SharedSuggestionProvider.suggest(entries.map { it.name }, builder) }
						.executes { context ->
							val name = StringArgumentType.getString(context, "name")
							val entry = find(name)
							if (entry == null) {
								reply(context, "§c$name isn't on your blacklist.")
							} else {
								remove(entry)
								reply(context, "§7Took §f${entry.name} §7off your blacklist.")
							}
							1
						},
				),
			)
			.then(
				ClientCommands.literal("list").executes { context ->
					if (entries.isEmpty()) {
						reply(context, "§7Your blacklist is empty.")
					} else {
						reply(context, "§7Blacklisted (${entries.size}):")
						entries.forEach { reply(context, " §f${it.name} §8- §7${it.reason.ifEmpty { "no reason" }} §8(${date(it.added)})") }
					}
					1
				},
			)

	private fun add(context: CommandContext<FabricClientCommandSource>, reason: String): Int {
		val name = StringArgumentType.getString(context, "name")
		if (!NAME.matches(name)) {
			reply(context, "§c$name isn't a Minecraft name.")
			return 0
		}
		val existing = find(name)
		put(Entry(existing?.name?.takeIf { it.equals(name, ignoreCase = true) } ?: name, reason, existing?.added ?: 0L), existing)
		reply(context, if (existing != null) "§7Updated §f$name§7's reason." else "§7Blacklisted §f$name§7.")
		if (!module.enabled) reply(context, "§8Party Blacklist is off, so they won't be kicked until you turn it on.")
		return 1
	}

	private fun onlineNames(): List<String> =
		Minecraft.getInstance().connection?.onlinePlayers?.map { it.profile.name }?.filter { NAME.matches(it) }.orEmpty()

	private fun reply(context: CommandContext<FabricClientCommandSource>, text: String) =
		context.source.sendFeedback(Component.literal("§8[Cryptic] $text"))

	/** "23 Oct 2026", as the list shows it. */
	fun date(millis: Long): String =
		if (millis <= 0) "unknown date"
		else java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)
			.format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()))

	private fun load() {
		runCatching {
			if (!Files.exists(file)) return
			val loaded = gson.fromJson(Files.readString(file), Array<Entry>::class.java) ?: return
			entries.clear()
			@Suppress("SENSELESS_COMPARISON")
			loaded.filter { it != null && it.name != null && NAME.matches(it.name) }
				.forEach { entries += Entry(it.name, it.reason ?: "", it.added) }
		}.onFailure { Cryptic.LOGGER.error("Could not read the party blacklist", it) }
	}

	fun save() {
		runCatching {
			Files.createDirectories(file.parent)
			Files.writeString(file, gson.toJson(entries))
		}.onFailure { Cryptic.LOGGER.error("Could not save the party blacklist", it) }
	}
}
