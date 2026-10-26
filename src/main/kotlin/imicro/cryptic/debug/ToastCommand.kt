package imicro.cryptic.debug

import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import imicro.cryptic.feature.Toasts
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * `/cryptic debug toast`: raises a notification to check how it looks.
 *
 * - `toast <seconds> <title> <message>` (quote a title with spaces; `&` colour codes work)
 * - `toast head <player> <seconds> <title> <message>`, with a face
 * - `toast sample <kind>`, a real kind with made-up details
 */
object ToastCommand {
	private val SAMPLES = listOf("invite", "carry", "cookie", "downtime", "update", "error", "all")

	fun build(): LiteralArgumentBuilder<FabricClientCommandSource> =
		ClientCommands.literal("toast")
			.then(message(ClientCommands.argument("seconds", DoubleArgumentType.doubleArg(0.5, 60.0))) { null })
			.then(
				ClientCommands.literal("head").then(
					ClientCommands.argument("player", StringArgumentType.word())
						.suggests { _, builder -> SharedSuggestionProvider.suggest(onlinePlayers(), builder) }
						.then(message(ClientCommands.argument("seconds", DoubleArgumentType.doubleArg(0.5, 60.0))) { StringArgumentType.getString(it, "player") }),
				),
			)
			.then(
				ClientCommands.literal("sample").then(
					ClientCommands.argument("kind", StringArgumentType.word())
						.suggests { _, builder -> SharedSuggestionProvider.suggest(SAMPLES, builder) }
						.executes { context ->
							val kind = StringArgumentType.getString(context, "kind").lowercase()
							if (kind !in SAMPLES) {
								context.source.sendError(Component.literal("Unknown sample '$kind'. Try: ${SAMPLES.joinToString(", ")}"))
								return@executes 0
							}
							if (!Toasts.module.enabled) context.source.sendFeedback(Component.literal("§8[Cryptic] §7Toast Notifications is off, so nothing will show."))
							sample(kind)
							1
						},
				),
			)

	private fun message(
		seconds: com.mojang.brigadier.builder.RequiredArgumentBuilder<FabricClientCommandSource, Double>,
		head: (CommandContext<FabricClientCommandSource>) -> String?,
	) = seconds.then(
		ClientCommands.argument("title", StringArgumentType.string()).then(
			ClientCommands.argument("message", StringArgumentType.greedyString()).executes { context ->
				val title = StringArgumentType.getString(context, "title")
				val color = leadingColor(title)
				Toasts.show(
					source = colored(title).replace(Regex("^(§.)+"), ""),
					message = colored(StringArgumentType.getString(context, "message")),
					life = DoubleArgumentType.getDouble(context, "seconds"),
					color = color,
					head = head(context),
				)
				if (!Toasts.module.enabled) context.source.sendFeedback(Component.literal("§8[Cryptic] §7Toast Notifications is off, so nothing will show."))
				1
			},
		),
	)

	private fun colored(text: String): String = text.replace(Regex("&([0-9a-fk-orA-FK-OR])")) { "§" + it.groupValues[1] }

	private fun leadingColor(title: String): Int? {
		val code = Regex("^[&§]([0-9a-fA-F])").find(title)?.groupValues?.get(1)?.single() ?: return null
		val format = ChatFormatting.getByCode(code) ?: return null
		val rgb = net.minecraft.network.chat.TextColor.fromLegacyFormat(format)?.value ?: return null
		return 0xFF000000.toInt() or rgb
	}

	private fun onlinePlayers(): List<String> =
		Minecraft.getInstance().connection?.onlinePlayers?.map { it.profile.name } ?: emptyList()

	private fun self(): String = Minecraft.getInstance().player?.name?.string ?: "Steve"

	private fun sample(kind: String) {
		val me = self()
		when (kind) {
			"invite" -> Toasts.ask(
				source = "Party", id = "debug_invite", life = 60.0,
				keys = mapOf(GLFW.GLFW_KEY_Y to {}, GLFW.GLFW_KEY_N to {}),
				color = Toasts.Colors.PARTY, head = me, footer = "§a[Y] §fAccept   §c[N] §fIgnore",
			) { "§f$me §7invited you to their party" }
			"carry" -> Toasts.show("", "§d$me §e3§7/§e10", head = me)
			"cookie" -> Toasts.show("Booster Cookie", "Runs out in §f2h 15m", color = Toasts.Colors.COOKIE)
			"downtime" -> Toasts.show("Party", "Downtime for §f$me §8(§7food§8)", color = Toasts.Colors.PARTY)
			"update" -> Toasts.show("Update", "§fCryptic 1.0.0 §7is out §8(you have 0.29.0)", color = Toasts.Colors.GOOD)
			"error" -> Toasts.show("Slot Binds", "A slot cannot be bound to itself", error = true)
			"all" -> SAMPLES.filter { it != "all" }.forEach(::sample)
		}
	}
}
