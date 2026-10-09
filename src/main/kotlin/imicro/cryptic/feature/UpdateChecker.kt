package imicro.cryptic.feature

import com.google.gson.JsonParser
import imicro.cryptic.Cryptic
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Asks GitHub whether a newer Cryptic has been released.
 *
 * Once a session, on the first server joined, it reads the latest release of
 * the repository and compares its tag (`v0.20.0-26.2`) with what is installed.
 * A newer one is announced with a notification that stays up for thirty
 * seconds, and a line in chat that opens the release page when clicked.
 */
object UpdateChecker {
	private const val API = "https://api.github.com/repos/WhyiMicro/cryptic/releases/latest"
	private const val SOURCE = "Update"
	private const val TOAST_SECONDS = 30.0

	private val checkNow = ButtonModuleSetting("check_now", "Check now", action = { check(manual = true) })

	@JvmField
	val module = Module(
		id = "update_checker",
		name = "Update Checker",
		description = "Tells you when a new Cryptic is out",
		category = ModuleCategory.MISC,
		enabled = true,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(checkNow),
	)

	private val http: HttpClient by lazy {
		HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
	}

	private var checkedThisSession = false

	@Volatile
	private var checking = false

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
			if (module.enabled && !checkedThisSession) {
				checkedThisSession = true
				check(manual = false)
			}
		}
	}

	/** The installed version, "0.20.0-26.2". */
	val installed: String
		get() = FabricLoader.getInstance().getModContainer(Cryptic.MOD_ID)
			.map { it.metadata.version.friendlyString }.orElse("0.0.0")

	/** Asks GitHub, off the game thread. [manual] also says so when there is nothing new. */
	fun check(manual: Boolean) {
		if (checking) return
		checking = true
		val request = HttpRequest.newBuilder(URI.create(API))
			.timeout(Duration.ofSeconds(15))
			.header("Accept", "application/vnd.github+json")
			.header("User-Agent", "Cryptic/$installed")
			.GET()
			.build()
		http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete { response, error ->
			checking = false
			val client = Minecraft.getInstance()
			if (error != null || response == null || response.statusCode() != 200) {
				Cryptic.LOGGER.warn("Update check failed: {}", error?.message ?: "HTTP ${response?.statusCode()}")
				if (manual) client.execute { tell("Could not reach GitHub to check for updates.") }
				return@whenComplete
			}
			val release = runCatching { JsonParser.parseString(response.body()).asJsonObject }.getOrNull()
			val tag = release?.get("tag_name")?.asString
			val page = release?.get("html_url")?.asString ?: "https://github.com/WhyiMicro/cryptic/releases"
			client.execute {
				when {
					tag == null -> if (manual) tell("GitHub did not say what the latest release is.")
					isNewer(tag, installed) -> announce(tag.removePrefix("v"), page)
					manual -> tell("Cryptic is up to date ($installed).")
				}
			}
		}
	}

	private fun announce(latest: String, page: String) {
		Toasts.show(SOURCE, "Cryptic $latest is out. You have $installed.", life = TOAST_SECONDS)
		val link = Component.literal("Open the release page")
			.withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
			.withStyle { it.withClickEvent(ClickEvent.OpenUrl(URI.create(page))).withHoverEvent(HoverEvent.ShowText(Component.literal(page))) }
		val line = Component.literal("§8[Cryptic] §7Cryptic §f$latest§7 is out, you have §f$installed§7. ").append(link)
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(line)
	}

	private fun tell(message: String) {
		Toasts.show(SOURCE, message)
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §7$message"))
	}

	/**
	 * Whether [tag] is a later release than [current], by their first three
	 * numbers. What follows them is the Minecraft version, which is not newer
	 * or older Cryptic, only a different build of it.
	 */
	fun isNewer(tag: String, current: String): Boolean {
		val latest = numbers(tag) ?: return false
		val have = numbers(current) ?: return true
		for (i in 0 until 3) {
			if (latest[i] != have[i]) return latest[i] > have[i]
		}
		return false
	}

	private val VERSION = Regex("""(\d+)\.(\d+)\.(\d+)""")

	private fun numbers(version: String): List<Int>? =
		VERSION.find(version)?.groupValues?.drop(1)?.map { it.toInt() }
}
