package imicro.cryptic.dungeon

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import imicro.cryptic.Cryptic
import imicro.cryptic.terminal.ServerTicks
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.phys.Vec3
import java.nio.file.Files
import java.util.Locale

/**
 * The boss timings the Smart Tick Timer counts with. SkyBlock 0.27.2 sped up the Floor 7 fight, so
 * each timing can be changed in game with `/cryptic debug timing` (saved in
 * `config/cryptic/timings.json`). `/cryptic debug bosslog` prints every boss line, lightning volley
 * and wither move, in server ticks and real seconds since that boss's first line.
 */
object BossTimings {
	enum class Timing(val key: String, val defaultTicks: Int, val what: String) {
		/** Measured 2026-10-06: 519 ticks after Storm's first line (Odin has 520). */
		LIGHTNING("lightning", 519, "Storm's first line until the big lightning volley"),

		/**
		 * When to lower the purple pillar, counted from Storm's PY call. He moved 99 ticks after it
		 * in both logged runs; 52 was settled on in game (Odin has 55).
		 */
		PY("py", 52, "Storm's PY call until the purple pillar should be lowered"),

		GOLDOR_START("goldor_start", 64, "Storm dying until terminals can be done"),

		GOLDOR_CORE("goldor_core", 60, "Goldor's repeating cycle"),

		FIRE_FREEZE("fire_freeze", 206, "The Professor's line until Fire Freeze is used, plus its 100-tick cast"),

		/** A fixed 20 s since the update. */
		WATCHER_MOVE("watcher_move", 400, "The Watcher's greeting until he moves his first wave on"),

		/** 390 before the update. */
		LIVID("livid", 340, "Livid's greeting until he can be hurt"),
		;

		val ticks: Int get() = overrides[key] ?: defaultTicks
	}

	private val overrides = HashMap<String, Int>()

	private val file get() = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("timings.json")

	private val gson = GsonBuilder().setPrettyPrinting().create()

	@Volatile
	var logging = false
		private set

	private data class Moment(val tick: Long, val millis: Long) {
		companion object {
			fun now() = Moment(ServerTicks.total, System.currentTimeMillis())
		}
	}

	/** When each boss first spoke on this server; every log line is measured from it. */
	private val firstLine = LinkedHashMap<String, Moment>()

	private var currentBoss: String? = null
	private var lastLineTick = -1L

	/** Each wither's last position, and how long it has kept still or moving. */
	private class Track(var at: Vec3, var moving: Boolean = false, var still: Int = 0, var lastMoved: Moment = Moment.now())

	private val withers = HashMap<Int, Track>()

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		load()
		ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
			firstLine.clear()
			currentBoss = null
			lastLineTick = -1L
			withers.clear()
		}
	}

	private fun load() {
		runCatching {
			if (!Files.exists(file)) return
			JsonParser.parseString(Files.readString(file)).asJsonObject.entrySet().forEach { (key, value) ->
				if (Timing.entries.any { it.key == key }) overrides[key] = value.asInt
			}
		}.onFailure { Cryptic.LOGGER.error("Could not read timings.json", it) }
	}

	private fun save() {
		runCatching {
			Files.createDirectories(file.parent)
			val root = JsonObject()
			overrides.toSortedMap().forEach { (key, ticks) -> root.addProperty(key, ticks) }
			Files.writeString(file, gson.toJson(root))
		}.onFailure { Cryptic.LOGGER.error("Could not write timings.json", it) }
	}

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay) return
		val line = message.string.replace(FORMATTING, "").trim()
		val speaker = BOSS_LINE.matchEntire(line)?.groupValues?.get(1) ?: return
		val now = Moment.now()
		firstLine.putIfAbsent(speaker, now)
		val sincePrevious = if (lastLineTick >= 0) now.tick - lastLineTick else null
		currentBoss = speaker
		lastLineTick = now.tick
		if (!logging) return
		print("§b$speaker ${stamp(now, firstLine[speaker] ?: now)}" + (sincePrevious?.let { " §8(+${it}t)" } ?: "") + " §7" + line.removePrefix("[BOSS] $speaker: "))
	}

	/**
	 * An entity arriving, on the network thread: only lightning matters, handed to the client
	 * thread. Must not throw, or the connection drops.
	 */
	@JvmStatic
	fun onEntityAdded(type: EntityType<*>) {
		if (!logging || type != EntityTypes.LIGHTNING_BOLT) return
		val now = Moment.now()
		// Storm strikes a few bolts all phase; the volley the timer is for is ten or more in one
		// tick, printed once.
		if (now.tick != boltTick) {
			boltTick = now.tick
			boltsThisTick = 0
		}
		if (++boltsThisTick != VOLLEY_BOLTS) return
		Minecraft.getInstance().execute { print("§eLightning volley ${sinceBoss(now)}") }
	}

	/** The tick bolts are being counted for, and how many. Network thread only. */
	private var boltTick = -1L
	private var boltsThisTick = 0

	private const val VOLLEY_BOLTS = 10

	/** While logging, watches the withers for Storm moving and Necron dropping. */
	fun tick(client: Minecraft) {
		if (!logging) {
			if (withers.isNotEmpty()) withers.clear()
			return
		}
		val level = client.level ?: return
		val seen = HashSet<Int>()
		for (entity in level.entitiesForRendering()) {
			if (entity !is WitherBoss) continue
			seen += entity.id
			val track = withers.getOrPut(entity.id) {
				print("§dWither #${entity.id} appeared ${sinceBoss(Moment.now())} §8at ${where(entity.position())}")
				Track(entity.position())
			}
			val step = entity.position().distanceTo(track.at)
			val moved = step > MOVE_PER_TICK
			// A jump of several blocks in one tick is a boss being placed (Necron dropping), not
			// flying.
			if (step > JUMP_BLOCKS) {
				print("§dWither #${entity.id} jumped ${String.format(Locale.ROOT, "%.1f", step)} blocks ${sinceBoss(Moment.now())} §8from ${where(track.at)} to ${where(entity.position())}")
			}
			track.at = entity.position()
			if (moved) {
				track.still = 0
				track.lastMoved = Moment.now()
				if (!track.moving) {
					track.moving = true
					print("§dWither #${entity.id} started moving ${sinceBoss(track.lastMoved)} §8at ${where(entity.position())}")
				}
			} else if (track.moving && ++track.still >= STILL_TICKS) {
				track.moving = false
				print("§dWither #${entity.id} stopped ${sinceBoss(track.lastMoved)} §8at ${where(entity.position())}")
			}
		}
		withers.keys.retainAll(seen)
	}

	private fun sinceBoss(now: Moment): String {
		val boss = currentBoss ?: return "§8(no boss line yet)"
		// Also since the last line, which the Necron drop is timed from.
		val sinceLast = if (lastLineTick >= 0) " §8(+${now.tick - lastLineTick}t after the last line)" else ""
		return "${stamp(now, firstLine[boss] ?: now)} §8after $boss's first line$sinceLast"
	}

	/** "+541t (27.05s, 27.31s real)". */
	private fun stamp(now: Moment, from: Moment): String {
		val ticks = now.tick - from.tick
		val real = String.format(Locale.ROOT, "%.2fs", (now.millis - from.millis) / 1000.0)
		return "§f+${ticks}t §7(${seconds(ticks)}, $real real)"
	}

	private fun seconds(ticks: Long): String = String.format(Locale.ROOT, "%.2fs", ticks / 20.0)

	private fun where(at: Vec3): String = String.format(Locale.ROOT, "%.1f, %.1f, %.1f", at.x, at.y, at.z)

	private fun print(text: String) {
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal("§8[Boss] $text"))
	}

	fun timingCommand(): LiteralArgumentBuilder<FabricClientCommandSource> =
		ClientCommands.literal("timing")
			.executes { context -> list(context); 1 }
			.then(ClientCommands.literal("reset").executes { context ->
				overrides.clear()
				save()
				context.source.sendFeedback(Component.literal("§8[Cryptic] §7Every timing is back to its default."))
				1
			})
			.then(
				ClientCommands.argument("name", StringArgumentType.word())
					.suggests { _, builder -> SharedSuggestionProvider.suggest(Timing.entries.map { it.key }, builder) }
					.executes { context ->
						val timing = timingArg(context) ?: return@executes 0
						context.source.sendFeedback(Component.literal(describe(timing)))
						1
					}
					.then(
						ClientCommands.argument("value", StringArgumentType.word())
							.suggests { _, builder -> SharedSuggestionProvider.suggest(listOf("reset", "27s", "540"), builder) }
							.executes { context ->
								val timing = timingArg(context) ?: return@executes 0
								val value = StringArgumentType.getString(context, "value")
								if (value.equals("reset", ignoreCase = true)) {
									overrides.remove(timing.key)
								} else {
									val ticks = parseTicks(value) ?: run {
										context.source.sendError(Component.literal("Write ticks (540 or 540t) or seconds (27s or 27.05s)."))
										return@executes 0
									}
									overrides[timing.key] = ticks
								}
								save()
								context.source.sendFeedback(Component.literal(describe(timing)))
								1
							},
					),
			)

	fun bossLogCommand(): LiteralArgumentBuilder<FabricClientCommandSource> =
		ClientCommands.literal("bosslog").executes { context ->
			logging = !logging
			context.source.sendFeedback(
				Component.literal(
					"§8[Cryptic] §7Boss log ${if (logging) "§aon" else "§coff"}§7. " +
						"Every boss line, lightning strike and wither starting or stopping is printed with the ticks " +
						"since that boss's first line.",
				),
			)
			1
		}

	private fun timingArg(context: CommandContext<FabricClientCommandSource>): Timing? {
		val name = StringArgumentType.getString(context, "name")
		return Timing.entries.firstOrNull { it.key.equals(name, ignoreCase = true) } ?: run {
			context.source.sendError(Component.literal("No timing called $name. Try ${Timing.entries.joinToString { it.key }}."))
			null
		}
	}

	/** "540", "540t", "27s" or "27.05s", as server ticks. */
	private fun parseTicks(text: String): Int? {
		val lower = text.lowercase(Locale.ROOT)
		return when {
			lower.endsWith("s") -> lower.dropLast(1).toDoubleOrNull()?.let { Math.round(it * 20).toInt() }
			lower.endsWith("t") -> lower.dropLast(1).toIntOrNull()
			else -> lower.toIntOrNull()
		}?.takeIf { it in 0..20_000 }
	}

	private fun list(context: CommandContext<FabricClientCommandSource>) {
		context.source.sendFeedback(Component.literal("§8[Cryptic] §7Boss timings, in server ticks:"))
		Timing.entries.forEach { context.source.sendFeedback(Component.literal(describe(it))) }
	}

	private fun describe(timing: Timing): String {
		val changed = timing.key in overrides
		return "§8[Cryptic] §b${timing.key}§7: §f${timing.ticks}t §7(${seconds(timing.ticks.toLong())})" +
			(if (changed) " §e(set, default ${timing.defaultTicks}t)" else " §8(default)") +
			" §8— ${timing.what}"
	}

	private val BOSS_LINE = Regex("""^\[BOSS] ([^:]+): .*$""")
	private val FORMATTING = Regex("§.")

	/** More than this between ticks is moving rather than bobbing. */
	private const val MOVE_PER_TICK = 0.05

	private const val STILL_TICKS = 10

	private const val JUMP_BLOCKS = 2.0
}
