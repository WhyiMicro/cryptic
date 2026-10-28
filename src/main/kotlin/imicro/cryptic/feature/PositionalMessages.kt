package imicro.cryptic.feature

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import imicro.cryptic.Cryptic
import imicro.cryptic.debug.DebugOverrides
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.nio.file.Files
import java.util.Locale
import kotlin.math.floor

/**
 * Says something in party chat when you reach a place: "at" a point within a radius, or "in" a box
 * between two corners, each with a delay, colour and message. From Odin's Positional Messages.
 * Suggested coordinates round x and z to the nearest half block of where you stand (Odin used the
 * block corner), so circles centre on you. A message can also count, while you're in its spot, how
 * many of the party have leaped to you (Blade Addons' leap count, credited to valley). Saved in `config/cryptic/positional-messages.json`,
 * outside profiles.
 */
object PositionalMessages {
	@JvmField
	val onlyBoss = ToggleModuleSetting(
		id = "only_boss",
		label = "Only in boss",
		defaultValue = true,
		description = "Only sends and draws them inside a dungeon boss fight, which is where they are for.",
	)

	@JvmField
	val showPositions = ToggleModuleSetting(
		id = "show_positions",
		label = "Show positions",
		defaultValue = true,
		description = "Draws each circle or box where it triggers.",
	)

	@JvmField
	val height = SliderModuleSetting(
		id = "height",
		label = "Circle height",
		defaultValue = 0.2,
		min = 0.0,
		max = 5.0,
		step = 0.1,
		description = "How tall the band drawn for an \"at\" message is, Odin's cylinder.",
		visibleIf = { showPositions.value },
	)

	@JvmField
	val showMessage = ToggleModuleSetting(
		id = "show_message",
		label = "Show message",
		defaultValue = true,
		visibleIf = { showPositions.value },
	)

	@JvmField
	val messageSize = SliderModuleSetting(
		id = "message_size",
		label = "Message size",
		defaultValue = 1.0,
		min = 0.1,
		max = 4.0,
		step = 0.1,
		visibleIf = { showPositions.value && showMessage.value },
	)

	@JvmField
	val module = Module(
		id = "positional_messages",
		name = "Positional Messages",
		description = "Party messages at spots",
		category = ModuleCategory.DUNGEON,
		supportsKeybind = false,
		settings = listOf(onlyBoss, showPositions, height, showMessage, messageSize),
	)

	/**
	 * One message. "At" has a [distance] and no second corner; "in" has the corner and no distance.
	 * [send] false only draws it, to mark a spot. [leapCount] is how many teammates to count in, or
	 * null for no count.
	 */
	data class PosMessage(
		val x: Double,
		val y: Double,
		val z: Double,
		val x2: Double? = null,
		val y2: Double? = null,
		val z2: Double? = null,
		val delay: Int,
		val distance: Double? = null,
		val color: String,
		val message: String,
		val send: Boolean = true,
		val leapCount: Int? = null,
	) {
		val box: AABB? get() = if (x2 != null && y2 != null && z2 != null) AABB(x, y, z, x2, y2, z2) else null

		fun contains(at: Vec3): Boolean =
			box?.contains(at) ?: distance?.let { at.distanceToSqr(x, y, z) <= it * it } ?: false
		val center: Vec3 get() = box?.center ?: Vec3(x, y, z)
		val argb: Int get() = 0xFF000000.toInt() or (COLORS[color.lowercase(Locale.ROOT)] ?: 0xFFFFFF)
	}

	/** Minecraft's sixteen colours, by Odin's names. */
	private val COLORS = linkedMapOf(
		"darkblue" to 0x0000AA, "darkgreen" to 0x00AA00, "darkaqua" to 0x00AAAA, "darkred" to 0xAA0000,
		"darkpurple" to 0xAA00AA, "gold" to 0xFFAA00, "gray" to 0xAAAAAA, "darkgray" to 0x555555,
		"blue" to 0x5555FF, "green" to 0x55FF55, "aqua" to 0x55FFFF, "red" to 0xFF5555,
		"lightpurple" to 0xFF55FF, "yellow" to 0xFFFF55, "white" to 0xFFFFFF, "black" to 0x000000,
	)

	private val messages = mutableListOf<PosMessage>()

	/** Messages already sent in this world; each is sent once. */
	private val sent = HashSet<PosMessage>()

	/** Messages waiting out their delay, with the client tick to send on. */
	private val pending = mutableListOf<Pair<PosMessage, Long>>()
	private var ticks = 0L

	private val file get() = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("positional-messages.json")
	private val gson = GsonBuilder().setPrettyPrinting().create()

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		load()
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
		imicro.cryptic.hud.Hud.register(LeapCountElement())
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> forget() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> forget() }
	}

	private fun forget() {
		sent.clear()
		pending.clear()
	}

	private fun load() {
		runCatching {
			if (!Files.exists(file)) return
			val type = object : TypeToken<List<PosMessage>>() {}.type
			val loaded: List<PosMessage>? = gson.fromJson(Files.readString(file), type)
			messages.clear()
			loaded?.let(messages::addAll)
		}.onFailure { Cryptic.LOGGER.error("Could not read positional messages", it) }
	}

	private fun save() {
		runCatching {
			Files.createDirectories(file.parent)
			Files.writeString(file, gson.toJson(messages))
		}.onFailure { Cryptic.LOGGER.error("Could not save positional messages", it) }
	}

	val needsDungeon: Boolean get() = module.enabled && onlyBoss.value

	private fun active(): Boolean =
		module.enabled && (!onlyBoss.value || (DungeonLocation.inDungeon && DungeonRun.inBoss))

	fun tick(client: Minecraft) {
		ticks++
		if (!active()) return
		val player = client.player ?: return

		countLeaps(client)
		for (message in messages) {
			if (!message.send || message in sent) continue
			if (!message.contains(player.position())) continue
			sent += message
			pending += message to ticks + message.delay
		}

		val due = pending.filter { it.second <= ticks }
		if (due.isEmpty()) return
		pending.removeAll(due)
		due.forEach { (message, _) -> say(client, message.message) }
	}

	// ---- Leap count ---------------------------------------------------------------------------

	/** The message whose spot you're in that counts leaps, and how many of the party are there. */
	private var counting: PosMessage? = null
	private var leaped = 0

	private fun countLeaps(client: Minecraft) {
		val player = client.player ?: return
		val level = client.level ?: return
		val message = messages.firstOrNull { it.leapCount != null && it.contains(player.position()) }
		counting = message
		leaped = if (message == null) 0 else level.players().count { it !== player && isRealPlayer(it) && message.contains(it.position()) }
	}

	/**
	 * Whether a player is a real one: Hypixel's NPCs have no proper tab entry, while real players'
	 * names have no spaces. Blade's test.
	 */
	private fun isRealPlayer(entity: net.minecraft.world.entity.player.Player): Boolean {
		val info = Minecraft.getInstance().connection?.getPlayerInfo(entity.uuid) ?: return false
		val name = info.profile.name()
		return name.isNotEmpty() && ' ' !in name
	}

	/** Blade's format: blue once one short of full or better, dark red before. */
	private fun leapCountText(count: Int, max: Int): String =
		(if (max - count <= 1) "§9" else "§4") + count + "§9/" + max + " Players Leaped"

	/** The count while you're in such a spot; same id as Spirit Leap's old one, so its place carries over. */
	private class LeapCountElement : imicro.cryptic.hud.HudElement("leap_count", "Leap Count", 0.45, 0.6) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int = 110
		override val height: Int = 10

		override fun isVisible(): Boolean = active() && counting != null

		override fun showInEditor(): Boolean = module.enabled && messages.any { it.leapCount != null }

		override fun render(context: net.minecraft.client.gui.GuiGraphicsExtractor) = draw(context, leapCountText(leaped, counting?.leapCount ?: 0))

		override fun renderExample(context: net.minecraft.client.gui.GuiGraphicsExtractor) = draw(context, leapCountText(2, 4))

		private fun draw(context: net.minecraft.client.gui.GuiGraphicsExtractor, text: String) {
			context.text(font, text, (width - font.width(text)) / 2, (height - font.lineHeight) / 2 + 1, -1)
		}
	}

	private fun say(client: Minecraft, text: String) {
		if (DebugOverrides.previewPartyCommands) {
			client.gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §7Would send: §f/pc $text"))
			return
		}
		client.connection?.sendCommand("pc $text")
	}

	private fun render(context: LevelRenderContext) {
		if (!showPositions.value || !active() || messages.isEmpty()) return
		val client = Minecraft.getInstance()
		val orientation = client.gameRenderer.mainCamera().rotation()

		for (message in messages) {
			val box = message.box
			if (box != null) {
				WorldRender.drawBox(
					poseStack = context.poseStack(),
					collector = context.submitNodeCollector(),
					minX = box.minX, minY = box.minY, minZ = box.minZ,
					maxX = box.maxX, maxY = box.maxY, maxZ = box.maxZ,
					outlineArgb = message.argb,
					fillArgb = 0,
					outline = true,
					fill = false,
					phase = false,
					lineWidth = 2f,
				)
			} else {
				val radius = message.distance ?: continue
				// Odin's band: two rings and the uprights between, depth-tested.
				WorldRender.drawCylinder(
					context.poseStack(), context.submitNodeCollector(),
					Vec3(message.x, message.y, message.z), radius, height.value, message.argb, 5f, false,
				)
			}

			if (showMessage.value) {
				val at = if (box != null) message.center.add(0.0, box.ysize / 2 + 0.5, 0.0) else Vec3(message.x, message.y + 1.0, message.z)
				WorldRender.drawText(
					poseStack = context.poseStack(),
					collector = context.submitNodeCollector(),
					orientation = orientation,
					// Always white; a dark circle's colour is unreadable as text (Odin does the
					// same).
					text = Component.literal(message.message),
					x = at.x,
					y = at.y,
					z = at.z,
					scale = messageSize.value.toFloat(),
					seeThrough = true,
				)
			}
		}
	}

	private fun feedback(context: CommandContext<FabricClientCommandSource>, text: String) {
		context.source.sendFeedback(Component.literal("§8[Cryptic] §7$text"))
	}

	/** Where you stand, x and z rounded to the nearest half block (139.2 to 139, 139.42 to 139.5). */
	private fun here(): Vec3? {
		val player = Minecraft.getInstance().player ?: return null
		return Vec3(halfStep(player.x), floor(player.y), halfStep(player.z))
	}

	private fun halfStep(value: Double): Double = Math.round(value * 2.0) / 2.0

	private fun suggestCoordinate(axis: Char): (CommandContext<FabricClientCommandSource>, SuggestionsBuilder) -> java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> =
		{ _, builder ->
			here()?.let {
				val value = when (axis) {
					'x' -> it.x
					'y' -> it.y
					else -> it.z
				}
				builder.suggest(trim(value))
			}
			builder.buildFuture()
		}

	private fun trim(value: Double): String = if (value == floor(value)) value.toInt().toString() else value.toString()

	private fun add(context: CommandContext<FabricClientCommandSource>, message: PosMessage): Int {
		if (message.color.lowercase(Locale.ROOT) !in COLORS) {
			feedback(context, "§cUnknown colour ${message.color}. §7Try ${COLORS.keys.joinToString(", ")}.")
			return 0
		}
		if (messages.any { it.message == message.message }) {
			feedback(context, "§cThere is already a message \"${message.message}\".")
			return 0
		}
		messages += message
		save()
		val where = if (message.box != null) {
			"in ${trim(message.x)}, ${trim(message.y)}, ${trim(message.z)} to " +
				"${trim(message.x2!!)}, ${trim(message.y2!!)}, ${trim(message.z2!!)}"
		} else {
			"at ${trim(message.x)}, ${trim(message.y)}, ${trim(message.z)} within ${trim(message.distance ?: 0.0)} blocks"
		}
		feedback(context, "Added \"${message.message}\" $where, after ${message.delay}t${if (message.send) "" else ", drawn only"}.")
		return 1
	}

	/** delay, colour, send, message: the tail every form of `add` shares. */
	private fun <T : ArgumentBuilder<FabricClientCommandSource, T>> T.tail(
		build: (CommandContext<FabricClientCommandSource>) -> PosMessage?,
	): T = then(
		ClientCommands.argument("color", StringArgumentType.word())
			.suggests { _, builder ->
				COLORS.keys.filter { it.startsWith(builder.remainingLowerCase) }.forEach(builder::suggest)
				builder.buildFuture()
			}
			.then(
				ClientCommands.argument("send", BoolArgumentType.bool())
					.then(
						ClientCommands.argument("message", StringArgumentType.greedyString())
							.executes { context -> build(context)?.let { add(context, it) } ?: 0 },
					),
			),
	)

	private fun color(context: CommandContext<FabricClientCommandSource>) = StringArgumentType.getString(context, "color")
	private fun send(context: CommandContext<FabricClientCommandSource>) = BoolArgumentType.getBool(context, "send")
	private fun text(context: CommandContext<FabricClientCommandSource>) = StringArgumentType.getString(context, "message")
	private fun double(context: CommandContext<FabricClientCommandSource>, name: String) = DoubleArgumentType.getDouble(context, name)
	private fun delay(context: CommandContext<FabricClientCommandSource>) = IntegerArgumentType.getInteger(context, "delay")

	private fun coordinate(name: String, axis: Char) =
		ClientCommands.argument(name, DoubleArgumentType.doubleArg()).suggests(suggestCoordinate(axis))

	fun command(): LiteralArgumentBuilder<FabricClientCommandSource> =
		ClientCommands.literal("posmsg")
			.executes { context ->
				feedback(context, "/cryptic posmsg add at|in|here ..., leapcount <off|1-4> <message>, remove <message>, list, clear")
				1
			}
			.then(
				ClientCommands.literal("add")
					.then(
						ClientCommands.literal("at").then(
							coordinate("x", 'x').then(coordinate("y", 'y').then(coordinate("z", 'z').then(
								ClientCommands.argument("delay", IntegerArgumentType.integer(0)).then(
									ClientCommands.argument("distance", DoubleArgumentType.doubleArg(0.1)).tail { context ->
										PosMessage(
											x = double(context, "x"), y = double(context, "y"), z = double(context, "z"),
											delay = delay(context), distance = double(context, "distance"),
											color = color(context), message = text(context), send = send(context),
										)
									},
								),
							))),
						),
					)
					.then(
						ClientCommands.literal("here").then(
							ClientCommands.argument("delay", IntegerArgumentType.integer(0)).then(
								ClientCommands.argument("distance", DoubleArgumentType.doubleArg(0.1)).tail { context ->
									val at = here() ?: return@tail null
									PosMessage(
										x = at.x, y = at.y, z = at.z,
										delay = delay(context), distance = double(context, "distance"),
										color = color(context), message = text(context), send = send(context),
									)
								},
							),
						),
					)
					.then(
						ClientCommands.literal("in").then(
							coordinate("x", 'x').then(coordinate("y", 'y').then(coordinate("z", 'z').then(
								coordinate("x2", 'x').then(coordinate("y2", 'y').then(coordinate("z2", 'z').then(
									ClientCommands.argument("delay", IntegerArgumentType.integer(0)).tail { context ->
										PosMessage(
											x = double(context, "x"), y = double(context, "y"), z = double(context, "z"),
											x2 = double(context, "x2"), y2 = double(context, "y2"), z2 = double(context, "z2"),
											delay = delay(context),
											color = color(context), message = text(context), send = send(context),
										)
									},
								))),
							))),
						),
					),
			)
			.then(
				// How many teammates to count in a message's spot, after it's made.
				ClientCommands.literal("leapcount").then(
					ClientCommands.argument("count", StringArgumentType.word())
						.suggests { _, builder ->
							listOf("off", "1", "2", "3", "4").filter { it.startsWith(builder.remainingLowerCase) }.forEach(builder::suggest)
							builder.buildFuture()
						}
						.then(
							ClientCommands.argument("message", StringArgumentType.greedyString())
								.suggests { _, builder ->
									messages.map { it.message }.filter { it.lowercase().startsWith(builder.remainingLowerCase) }
										.forEach(builder::suggest)
									builder.buildFuture()
								}
								.executes { context ->
									val raw = StringArgumentType.getString(context, "count").lowercase(Locale.ROOT)
									val count = if (raw == "off") null else raw.toIntOrNull()?.takeIf { it in 1..4 }
									if (raw != "off" && count == null) {
										feedback(context, "§cThe count is off or 1 to 4: how many teammates should reach you.")
										return@executes 0
									}
									val wanted = text(context).trim()
									val index = messages.indexOfFirst { it.message.equals(wanted, ignoreCase = true) }
									if (index < 0) {
										feedback(context, "§cNo message \"$wanted\".")
										return@executes 0
									}
									messages[index] = messages[index].copy(leapCount = count)
									save()
									feedback(context, if (count == null) "No leap count on \"$wanted\"." else "\"$wanted\" now counts leaps, out of $count.")
									1
								},
						),
				),
			)
			.then(
				ClientCommands.literal("remove").then(
					ClientCommands.argument("message", StringArgumentType.greedyString())
						.suggests { _, builder ->
							messages.map { it.message }.filter { it.lowercase().startsWith(builder.remainingLowerCase) }
								.forEach(builder::suggest)
							builder.buildFuture()
						}
						.executes { context ->
							val wanted = text(context).trim()
							val removed = messages.count { it.message.equals(wanted, ignoreCase = true) }
							messages.removeAll { it.message.equals(wanted, ignoreCase = true) }
							save()
							feedback(context, if (removed > 0) "Removed \"$wanted\"." else "§cNo message \"$wanted\".")
							1
						},
				),
			)
			.then(
				ClientCommands.literal("clear").executes { context ->
					messages.clear()
					save()
					feedback(context, "Cleared every positional message.")
					1
				},
			)
			.then(
				ClientCommands.literal("list").executes { context ->
					if (messages.isEmpty()) {
						feedback(context, "No positional messages yet.")
					} else {
						messages.forEachIndexed { index, it ->
							val where = if (it.box != null) {
								"in ${trim(it.x)}, ${trim(it.y)}, ${trim(it.z)} to ${trim(it.x2!!)}, ${trim(it.y2!!)}, ${trim(it.z2!!)}"
							} else {
								"at ${trim(it.x)}, ${trim(it.y)}, ${trim(it.z)} r${trim(it.distance ?: 0.0)}"
							}
							val leaps = it.leapCount?.let { count -> ", leap count of $count" } ?: ""
							feedback(context, "${index + 1}. §f\"${it.message}\" §7$where, ${it.delay}t, ${it.color}${if (it.send) "" else ", drawn only"}$leaps")
						}
					}
					1
				},
			)
}
