package imicro.cryptic.feature

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import imicro.cryptic.Cryptic
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.RewardScreen
import imicro.cryptic.gui.withMore
import imicro.cryptic.rewards.DailyRewardSite
import imicro.cryptic.rewards.RewardCard
import imicro.cryptic.rewards.RewardOffer
import imicro.cryptic.rewards.RewardRarity
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.inventory.BookViewScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Hypixel's daily reward, picked in game.
 *
 * Talking to the Delivery Man in a lobby gets a link to Hypixel's reward
 * site in chat. This reads the page behind it and shows its three cards in a
 * screen of Cryptic's own, where one is turned over and claimed without
 * leaving the game. It also reminds you when the day's reward has not been
 * claimed yet.
 *
 * Ported from DailyRewards (LGPL-3.0, Copyright (c) Cqseur): the link, the
 * page, the claim and Hypixel's midnight-Eastern day. RewardClaim by
 * ThatGravyBoat was read for its flow — closing Hypixel's book while the
 * picker opens, and waiting out the ad — but none of its code is used.
 */
object DailyRewards {
	private const val SOURCE = "Daily Rewards"

	/** Indices into [reveal]. */
	const val REVEAL_ONE = 0
	const val REVEAL_ALL = 1
	const val REVEAL_ON_OPEN = 2

	@JvmField
	val autoOpen = ToggleModuleSetting(
		id = "auto_open",
		label = "Open the picker",
		defaultValue = true,
		description = "Opens the reward picker as soon as Hypixel sends the link. Off, a notification says it is ready and /cryptic reward opens it.",
	)

	@JvmField
	val hideBook = ToggleModuleSetting(
		id = "hide_book",
		label = "Hide Hypixel's book",
		defaultValue = true,
		description = "Keeps the book Hypixel opens with the link from covering the picker.",
	)

	@JvmField
	val reveal = DropdownModuleSetting(
		id = "reveal",
		label = "Turning cards",
		options = listOf("One at a time", "All at once", "Already turned over"),
		defaultIndex = REVEAL_ONE,
		description = "Whether a click turns over the card you picked or all three, or whether they turn over by themselves as the picker opens.",
	)

	@JvmField
	val highlightBest = ToggleModuleSetting(
		id = "highlight_best",
		label = "Highlight the rarest",
		defaultValue = true,
		description = "A glow around the rarest card turned over.",
	)

	@JvmField
	val sounds = ToggleModuleSetting(
		id = "sounds",
		label = "Sounds",
		defaultValue = true,
		description = "A sound as each card turns over, higher for rarer ones, and one when the claim goes through.",
	)

	@JvmField
	val closeOnClaim = ToggleModuleSetting(
		id = "close_on_claim",
		label = "Close menu on reward claim",
		defaultValue = true,
		description = "Closes the picker a moment after the claim goes through. The notification still says what you got.",
	)

	private val reminderSection = SectionModuleSetting("reminder_section", "Reminder")

	@JvmField
	val reminder = ToggleModuleSetting(
		id = "reminder",
		label = "Remind me",
		defaultValue = true,
		description = "A notification on Hypixel while today's reward has not been claimed through Cryptic.",
	)

	@JvmField
	val remindOnce = ToggleModuleSetting(
		id = "remind_once",
		label = "Remind me once",
		defaultValue = true,
		description = "Only one reminder, when you join Hypixel, rather than one every so often.",
		visibleIf = { reminder.value },
	)

	@JvmField
	val reminderMinutes = SliderModuleSetting(
		id = "reminder_minutes",
		label = "Remind again every",
		unit = "min",
		defaultValue = 30.0,
		min = 5.0,
		max = 120.0,
		step = 5.0,
		visibleIf = { reminder.value && !remindOnce.value },
	)

	private val openLast = ButtonModuleSetting("open_last", "Open last reward", action = { requestOpen(lastOffer) })

	private val preview = ButtonModuleSetting("preview", "Preview the picker", action = { requestOpen(sampleOffer()) })

	@JvmField
	val module = Module(
		id = "daily_rewards",
		name = "Daily Rewards",
		description = "Claim daily rewards in game",
		category = ModuleCategory.GENERAL,
		settings = listOf(
			autoOpen, hideBook, reveal, highlightBest, sounds, closeOnClaim,
			reminderSection, reminder.withMore(remindOnce, reminderMinutes),
			openLast, preview,
		),
	)

	/** The offer last read from Hypixel, for reopening it. */
	private var lastOffer: RewardOffer? = null

	/** When a reward link last arrived, so Hypixel's book can be kept away for a moment. */
	private var linkAt = 0L

	/** A screen to open on the next tick without a chat screen in the way. */
	private var pendingScreen: Screen? = null

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		ClientTickEvents.END_CLIENT_TICK.register(::tick)
		ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
			ticksOnServer = 0
			remindedAt = 0L
		}
	}

	// --- The link ---------------------------------------------------------

	private val LINK = Regex("""rewards\.hypixel\.net/claim-reward/([A-Za-z0-9]{8})""")
	private val FORMATTING = Regex("§.")

	/** Chat lines a player wrote, where somebody else's link must not be followed. */
	private val PLAYER_CHAT = Regex(
		"""^(?:(?:Party|Guild|Officer|Co-op) >|From |To |\[\d+\]|\w{3,16}: |.*\[(?:VIP|MVP|YOUTUBE|ADMIN|MOD|HELPER|GM|OWNER)[^\]]*\] ?\w+:)""",
	)

	/**
	 * Hypixel's "Click the link to visit our website and claim your reward",
	 * in whatever language, read off the chat packet. A link someone pasted
	 * in chat is not yours to claim and is left alone.
	 */
	@JvmStatic
	fun onSystemChat(content: Component, overlay: Boolean) {
		if (overlay || !module.enabled) return
		val text = content.string.replace(FORMATTING, "").replace("\n", " ").trim()
		val id = LINK.find(text)?.groupValues?.get(1) ?: return
		if (PLAYER_CHAT.containsMatchIn(text)) return
		openLink(id)
	}

	/** Reads the reward behind [id] and shows it, or says it is ready. */
	fun openLink(id: String) {
		linkAt = System.currentTimeMillis()
		val screen = if (autoOpen.value) RewardScreen.loading().also { show(it) } else null
		DailyRewardSite.fetch(id).whenComplete { offer, error ->
			Minecraft.getInstance().execute {
				if (error != null) {
					Cryptic.LOGGER.warn("Could not read daily reward {}", id, error)
					val reason = (error.cause ?: error).message ?: "unknown error"
					if (screen != null && Minecraft.getInstance().gui.screen() === screen) {
						screen.fail(reason, "https://rewards.hypixel.net/claim-reward/$id")
					} else {
						Toasts.show(SOURCE, "Couldn't read the reward: $reason", true)
					}
					return@execute
				}
				lastOffer = offer
				if (screen != null && Minecraft.getInstance().gui.screen() === screen) {
					screen.show(offer)
				} else {
					Toasts.show(SOURCE, "Your daily reward is ready, §f/cryptic reward §7opens it", color = Toasts.Colors.REWARD)
				}
			}
		}
	}

	/**
	 * Whether a screen about to open is Hypixel's reward book, while the picker
	 * is opening. Asked by the screen setter.
	 */
	@JvmStatic
	fun blocksScreen(screen: Screen?): Boolean =
		module.enabled && hideBook.value && screen is BookViewScreen && System.currentTimeMillis() - linkAt < 5_000

	private fun show(screen: Screen) {
		Minecraft.getInstance().gui.setScreen(screen)
	}

	/** Opens [offer] on the next tick, out of a command or a button. */
	fun requestOpen(offer: RewardOffer?) {
		if (offer == null) {
			Toasts.show(SOURCE, "No reward to open yet. Talk to the §fDelivery Man §7in a lobby", true)
			return
		}
		pendingScreen = RewardScreen.ready(offer)
	}

	/** `/cryptic reward`: the last reward read, again. */
	fun reopen() = requestOpen(lastOffer)

	// --- Claiming ---------------------------------------------------------

	/** Claims card [option]; [done] hears whether it went through, on the game thread. */
	fun claim(offer: RewardOffer, option: Int, done: (String?) -> Unit) {
		val card = offer.cards[option]
		if (offer.sample) {
			done(null)
			Toasts.show(SOURCE, "Preview: would have claimed ${describe(card)}", color = Toasts.Colors.REWARD)
			return
		}
		DailyRewardSite.claim(offer, option).whenComplete { _, error ->
			Minecraft.getInstance().execute {
				if (error != null) {
					Cryptic.LOGGER.warn("Could not claim daily reward {}", offer.id, error)
					done((error.cause ?: error).message ?: "unknown error")
					return@execute
				}
				record(offer, card)
				done(null)
				Toasts.show(SOURCE, "Claimed ${describe(card)}", color = Toasts.Colors.REWARD)
			}
		}
	}

	/** "§6Legendary §f2,000 Bed Wars Tokens", for the notifications. */
	fun describe(card: RewardCard): String {
		val code = when (card.rarity) {
			RewardRarity.COMMON -> "§7"
			RewardRarity.RARE -> "§b"
			RewardRarity.EPIC -> "§5"
			RewardRarity.LEGENDARY -> "§6"
		}
		val amount = if (card.amount > 1) "%,d ".format(card.amount) else ""
		// "Legendary Mystery Box" says its rarity already.
		if (card.name.startsWith(card.rarity.label, ignoreCase = true)) return "$code$amount${card.name}"
		return "$code${card.rarity.label} §f$amount${card.name}"
	}

	// --- The day, and what was claimed --------------------------------------

	/** Hypixel's day turns at midnight in New York. */
	private val HYPIXEL_ZONE: ZoneId = ZoneId.of("America/New_York")

	private fun today(): LocalDate = ZonedDateTime.now(HYPIXEL_ZONE).toLocalDate()

	/** The time until the next day's reward. */
	fun untilReset(): Duration {
		val now = ZonedDateTime.now(HYPIXEL_ZONE)
		return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(HYPIXEL_ZONE))
	}

	private val gson = GsonBuilder().setPrettyPrinting().create()
	private val file: Path = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("daily-rewards.json")

	private var loaded = false
	private var lastClaimDay: LocalDate? = null
	private var totalClaims = 0
	private val history = ArrayList<JsonObject>()

	val claimedToday: Boolean get() = load().let { lastClaimDay == today() }

	private fun record(offer: RewardOffer, card: RewardCard) {
		load()
		lastClaimDay = today()
		totalClaims++
		history.add(0, JsonObject().apply {
			addProperty("day", today().toString())
			addProperty("reward", card.name)
			addProperty("type", card.type)
			addProperty("rarity", card.rarity.name)
			addProperty("amount", card.amount)
			addProperty("streak", offer.streak + 1)
		})
		while (history.size > 30) history.removeAt(history.size - 1)
		save()
	}

	private fun load() {
		if (loaded) return
		loaded = true
		runCatching {
			if (!Files.exists(file)) return
			val root = JsonParser.parseString(Files.readString(file)).asJsonObject
			lastClaimDay = root.get("lastClaimDay")?.asString?.let(LocalDate::parse)
			totalClaims = root.get("totalClaims")?.asInt ?: 0
			root.getAsJsonArray("history")?.forEach { history += it.asJsonObject }
		}.onFailure { Cryptic.LOGGER.error("Could not read daily-rewards.json", it) }
	}

	private fun save() {
		runCatching {
			val root = JsonObject()
			lastClaimDay?.let { root.addProperty("lastClaimDay", it.toString()) }
			root.addProperty("totalClaims", totalClaims)
			root.add("history", JsonArray().apply { history.forEach(::add) })
			Files.createDirectories(file.parent)
			val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
			Files.writeString(temp, gson.toJson(root))
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
		}.onFailure { Cryptic.LOGGER.error("Could not save daily-rewards.json", it) }
	}

	// --- Reminder -----------------------------------------------------------

	private var ticksOnServer = 0
	private var remindedAt = 0L

	/** A reminder waits this long after joining, so it does not drown in the join messages. */
	private const val REMINDER_DELAY_TICKS = 20 * 8

	private fun onHypixel(client: Minecraft): Boolean =
		client.currentServer?.ip?.lowercase()?.contains("hypixel") == true

	private fun tick(client: Minecraft) {
		pendingScreen?.let { screen ->
			if (client.gui.screen() !is ChatScreen) {
				pendingScreen = null
				client.gui.setScreen(screen)
			}
		}
		if (!module.enabled || !reminder.value || client.player == null || !onHypixel(client)) return
		if (++ticksOnServer < REMINDER_DELAY_TICKS || claimedToday) return
		val now = System.currentTimeMillis()
		val every = reminderMinutes.value.toLong() * 60_000
		if (remindedAt != 0L && (remindOnce.value || now - remindedAt < every)) return
		remindedAt = now
		remind()
	}

	fun remind() {
		Toasts.show(
			SOURCE,
			"Today's reward isn't claimed yet. Talk to the §fDelivery Man §7in a lobby",
			life = 10.0,
			color = Toasts.Colors.REWARD,
		)
	}

	// --- Debugging ----------------------------------------------------------

	/** A made-up offer that claims nothing, for seeing the picker any day. */
	fun sampleOffer(adSeconds: Int = 0, streak: Int = 5): RewardOffer = RewardOffer(
		id = "SAMPLE00",
		cards = listOf(
			RewardCard("tokens", RewardRarity.RARE, 2000, "Bed Wars Tokens", "In-game currency for Bed Wars"),
			RewardCard("mystery_box", RewardRarity.LEGENDARY, 1, "Legendary Mystery Box", "Holds housing items or cosmetics"),
			RewardCard("experience", RewardRarity.COMMON, 5000, "Hypixel Experience", "Grants Hypixel experience"),
		),
		streakStep = streak.coerceIn(0, 9),
		streak = streak,
		bestStreak = maxOf(streak, 23),
		activeAd = 0,
		adSeconds = adSeconds,
		token = "",
		sample = true,
	)

	/** For `/cryptic debug reward status`. */
	fun describeStatus(): List<String> {
		load()
		val until = untilReset()
		val nextLocal = ZonedDateTime.now(HYPIXEL_ZONE).toLocalDate().plusDays(1).atStartOfDay(HYPIXEL_ZONE)
			.withZoneSameInstant(ZoneId.systemDefault())
		return listOf(
			"Hypixel's day: ${today()}, claimed through Cryptic: $claimedToday (last ${lastClaimDay ?: "never"}, $totalClaims in all)",
			"Next reward in ${until.toHours()}h ${until.toMinutesPart()}m, at ${nextLocal.format(DateTimeFormatter.ofPattern("HH:mm z"))}",
			"On Hypixel: ${onHypixel(Minecraft.getInstance())}, reminder ${when { !reminder.value -> "off"; remindOnce.value -> "on, once per join"; else -> "on, every ${reminderMinutes.value.toInt()} min" }}",
			"Last reward read: ${lastOffer?.let { offer -> offer.cards.joinToString { "${it.rarity.label} ${it.name}" } } ?: "none"}",
		) + history.take(5).map { "  ${it.get("day").asString}: ${it.get("rarity").asString.lowercase()} ${it.get("reward").asString}" }
	}

	/** For `/cryptic debug reward reset`: forgets today's claim, so the reminder can be seen again. */
	fun forgetToday() {
		load()
		lastClaimDay = null
		remindedAt = 0L
		save()
	}
}
