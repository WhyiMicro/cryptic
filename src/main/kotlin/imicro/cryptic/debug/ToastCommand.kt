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
 * - `toast sample <kind>`, a real kind with made-up details; `all` goes through every kind
 */
object ToastCommand {
	/** One of each kind of notification Cryptic raises, with made-up details; "all" shows them in turn. */
	private val SAMPLES = listOf(
		"invite", "friend", "joined", "downtime", "blacklist", "dupe",
		"carry", "carry_done", "confirm_boss", "draft",
		"cookie", "no_cookie", "reward",
		"bazaar", "bazaar_filled", "auction_sold", "outbid", "protected", "slot_binds",
		"mining", "tools", "structure", "compass",
		"teleport", "copied", "update", "update_done", "update_failed", "error", "all",
	)

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

	private val YES_NO = mapOf(GLFW.GLFW_KEY_Y to {}, GLFW.GLFW_KEY_N to {})

	private fun sample(kind: String) {
		val me = self()
		when (kind) {
			"invite" -> Toasts.ask(
				source = "Party", id = "debug_invite", life = SAMPLE_ASK_SECONDS, keys = YES_NO,
				color = Toasts.Colors.PARTY, head = me, footer = "§a[Y] §fAccept   §c[N] §fIgnore",
			) { "§f$me §7invited you to their party" }
			"friend" -> Toasts.ask(
				source = "Friends", id = "debug_friend", life = SAMPLE_ASK_SECONDS, keys = YES_NO,
				color = Toasts.Colors.FRIEND, head = me, footer = "§a[Y] §fAccept   §c[N] §fDeny",
			) { "§f$me §7sent you a friend request" }
			"joined" -> Toasts.show("Party", "Joined §f$me§7's party", color = Toasts.Colors.PARTY, head = me)
			"downtime" -> Toasts.show("Party", "§f$me §7wants downtime after this run", color = Toasts.Colors.PARTY, head = me)
			"blacklist" -> Toasts.show("Party Blacklist", "Kicking $me, who is blacklisted: left mid-run", error = true, head = me)
			"dupe" -> Toasts.show("Duplicate class", "2 Mages ($me, Notch)", error = true)
			"carry" -> Toasts.show("", "§d$me §e3§7/§e10", head = me)
			"carry_done" -> Toasts.show("", "§d$me§7's carry is §adone", head = me)
			"confirm_boss" -> Toasts.ask(
				source = "Carry Manager", id = "debug_confirm_boss", life = SAMPLE_ASK_SECONDS, keys = YES_NO,
				head = me, footer = "§a[Y] §fYes   §c[N] §fNo",
			) { "Is this §d$me§7's boss?" }
			"draft" -> Toasts.ask(
				source = "Puzzle Solver", id = "debug_draft", life = SAMPLE_ASK_SECONDS, keys = YES_NO,
				footer = "§a[Y] §fYes   §c[N] §fNo",
			) { "Fetch an architect's draft?" }
			"cookie" -> Toasts.show("Booster Cookie", "Runs out in §f2h 15m", color = Toasts.Colors.COOKIE)
			"no_cookie" -> Toasts.show("Booster Cookie", "§cNo active booster cookie§7, be careful", color = Toasts.Colors.COOKIE)
			"reward" -> Toasts.show("Daily Rewards", "Your daily reward is ready, §f/cryptic reward §7opens it", color = Toasts.Colors.REWARD)
			"bazaar" -> Toasts.show("Bazaar", "§7Your buy order for §aEnchanted Diamond §7was §cundercut §8(§e1,265§8)§7.", color = Toasts.Colors.BAZAAR)
			"bazaar_filled" -> Toasts.show("Bazaar", "§7Your co-op's sell offer for §f1x §5Third Master Star §7was §6filled§7.", color = Toasts.Colors.BAZAAR)
			"auction_sold" -> Toasts.show("Auction House", "§f$me §7bought §6Hyperion §7for §61,234,000,000 coins§7.", color = Toasts.Colors.AUCTION)
			"outbid" -> Toasts.show("Auction House", "§fNotch §7outbid you on §5Wither Goggles §7by §6250,000 coins§7.", color = Toasts.Colors.AUCTION)
			"protected" -> Toasts.show("Item Protection", "§fHyperion §7is protected and wasn't dropped.", error = true)
			"slot_binds" -> Toasts.show("Slot Binds", "Bound slot §f36 §7to §f9", color = Toasts.Colors.INVENTORY)
			"mining" -> Toasts.show("Mining", "Add Mining Speed in §f/widget > Stats Widget §7for ping offset", color = Toasts.Colors.MINING)
			"tools" -> Toasts.show("Nucleus Features", "§fAll tools found", color = Toasts.Colors.MINING)
			"structure" -> Toasts.show("Nucleus Features", "Found §fLost Precursor City §7from afar (crystal scan)", color = Toasts.Colors.MINING)
			"compass" -> Toasts.show("Wishing Compass", "Move 8 blocks away and use another Wishing Compass to triangulate the location.", color = Toasts.Colors.MINING)
			"teleport" -> Toasts.show("Teleport Features", "§fFake zpew §7was turned back off: it can't be on with §fSmooth teleport§7.", color = Toasts.Colors.TELEPORT)
			"copied" -> Toasts.show("Better Chat", "Copied to the clipboard", color = Toasts.Colors.CRYPTIC)
			"update" -> Toasts.ask(
				source = "Update", id = "debug_update", life = SAMPLE_ASK_SECONDS, keys = YES_NO,
				color = Toasts.Colors.GOOD, footer = "§a[Y] §fInstall   §c[N] §fNot now",
			) { "A new update for Cryptic is available! §8(0.86.0 -> 0.87.0) §7Would you like to install it now?" }
			"update_done" -> Toasts.show("Update", "§aUpdate downloaded! §7Restart the game to apply §fCryptic 0.87.0§7.", color = Toasts.Colors.GOOD)
			"update_failed" -> Toasts.show("Update", "§cThe update failed: §7Could not reach GitHub", error = true)
			"error" -> Toasts.show("Slot Binds", "A slot cannot be bound to itself", error = true)
			"all" -> SAMPLES.filter { it != "all" }.forEachIndexed { index, next ->
				// A few at a time, so each can be read before the stack pushes it out.
				java.util.concurrent.CompletableFuture.delayedExecutor(index * ALL_SPACING_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
					.execute { Minecraft.getInstance().execute { sample(next) } }
			}
		}
	}

	private const val ALL_SPACING_MS = 1_500L

	/** Sample questions go quickly, or they would hold the stack while "all" runs. */
	private const val SAMPLE_ASK_SECONDS = 8.0
}
