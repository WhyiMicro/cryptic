package imicro.cryptic.feature

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import imicro.cryptic.Cryptic
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.ToggleModuleSetting
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
 * Checks GitHub for a newer Cryptic once a session, on the first server joined. Compares release
 * tags (`v0.20.0-26.2`) with the installed version, counting only releases with a jar for this
 * Minecraft version, and announces one with a notification and a clickable chat line.
 */
object UpdateChecker {
	/** Recent releases rather than just the latest, which may be for another Minecraft version. */
	private const val API = "https://api.github.com/repos/WhyiMicro/cryptic/releases?per_page=30"
	private const val SOURCE = "Update"
	private const val TOAST_SECONDS = 30.0

	@JvmField
	val autoUpdate = ToggleModuleSetting(
		id = "auto_update",
		label = "Install updates automatically",
		defaultValue = false,
		description = "Downloads a new release, then installs it when you close the game.",
	)

	private val checkNow = ButtonModuleSetting("check_now", "Check now", action = { check(manual = true) })

	@JvmField
	val module = Module(
		id = "update_checker",
		name = "Update Checker",
		description = "Says when an update is out",
		category = ModuleCategory.MISC,
		enabled = true,
		supportsKeybind = false,
		settings = listOf(autoUpdate, checkNow),
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
		AutoUpdater.initialize()
		ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
			if (module.enabled && !checkedThisSession) {
				checkedThisSession = true
				check(manual = false)
			}
		}
	}

	val installed: String
		get() = FabricLoader.getInstance().getModContainer(Cryptic.MOD_ID)
			.map { it.metadata.version.friendlyString }.orElse("0.0.0")

	/** Asks GitHub off the game thread. [manual] also reports when nothing is new. */
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
			val releases = runCatching {
				JsonParser.parseString(response.body()).asJsonArray.map { it.asJsonObject }
					.filter { it.get("draft")?.asBoolean != true && it.get("prerelease")?.asBoolean != true && it.get("tag_name") != null }
			}.getOrNull()
			client.execute {
				if (releases == null) {
					if (manual) tell("GitHub did not say what the latest release is.")
					return@execute
				}
				decide(releases, manual)
			}
		}
	}

	/** This build's Minecraft version, the part after the dash: "26.2". */
	val minecraftTag: String get() = installed.substringAfter('-', "")

	fun forThisMinecraft(release: JsonObject): Boolean =
		AutoUpdater.assetFor(release) != null

	/**
	 * Picks what to say, newest first. Only a release with a jar for this Minecraft version is
	 * offered; a newer one for another version is mentioned but never downloaded.
	 */
	private fun decide(releases: List<JsonObject>, manual: Boolean) {
		val tag = { release: JsonObject -> release.get("tag_name").asString }
		val newer = releases.filter { isNewer(tag(it), installed) }.sortedWith { a, b -> compare(tag(b), tag(a)) }
		val compatible = newer.firstOrNull(::forThisMinecraft)
		if (compatible != null) {
			val version = tag(compatible).removePrefix("v")
			val page = compatible.get("html_url")?.asString ?: RELEASES_PAGE
			if (autoUpdate.value && AutoUpdater.supported) update(compatible, version, page) else announce(version, page)
			return
		}
		val elsewhere = newer.firstOrNull()
		when {
			elsewhere != null -> {
				val version = tag(elsewhere).removePrefix("v")
				val minecraft = version.substringAfter('-', "a newer Minecraft")
				Toasts.show(SOURCE, "§fCryptic $version §7is out for Minecraft §f$minecraft§7, not your $minecraftTag", life = TOAST_SECONDS, color = Toasts.Colors.GOOD)
				Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(
					Component.literal("§8[Cryptic] §7Cryptic §f$version§7 is out for Minecraft §f$minecraft§7. Nothing newer for $minecraftTag, so nothing was downloaded."),
				)
			}
			manual -> tell("Cryptic is up to date ($installed).")
		}
	}

	private const val RELEASES_PAGE = "https://github.com/WhyiMicro/cryptic/releases"

	private fun compare(a: String, b: String): Int {
		val x = numbers(a) ?: return -1
		val y = numbers(b) ?: return 1
		for (i in 0 until 3) if (x[i] != y[i]) return x[i].compareTo(y[i])
		return 0
	}

	private fun announce(latest: String, page: String) {
		Toasts.show(SOURCE, "§fCryptic $latest §7is out §8(you have $installed)", life = TOAST_SECONDS, color = Toasts.Colors.GOOD)
		val link = Component.literal("Open the release page")
			.withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
			.withStyle { it.withClickEvent(ClickEvent.OpenUrl(URI.create(page))).withHoverEvent(HoverEvent.ShowText(Component.literal(page))) }
		val line = Component.literal("§8[Cryptic] §7Cryptic §f$latest§7 is out, you have §f$installed§7. ").append(link)
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(line)
	}

	/**
	 * With automatic updates on: downloaded in the background and installed when the game closes. A
	 * failed download falls back to the announcement with the link.
	 */
	private fun update(release: JsonObject, latest: String, page: String) {
		if (AutoUpdater.waiting == latest) {
			Toasts.show(SOURCE, "§fCryptic $latest §7installs when you close the game", life = TOAST_SECONDS, color = Toasts.Colors.GOOD)
			return
		}
		Toasts.show(SOURCE, "Downloading §fCryptic $latest§7...", color = Toasts.Colors.GOOD)
		AutoUpdater.download(release, latest) { result ->
			Minecraft.getInstance().execute {
				result.onSuccess {
					Toasts.show(SOURCE, "§fCryptic $latest §7installs when you close the game", life = TOAST_SECONDS, color = Toasts.Colors.GOOD)
					Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(
						Component.literal("§8[Cryptic] §7Downloaded Cryptic §f$latest§7. It installs when you close the game."),
					)
				}.onFailure { error ->
					Cryptic.LOGGER.warn("Update download failed", error)
					Toasts.show(SOURCE, "Couldn't download §fCryptic $latest§7: ${error.message}", error = true)
					announce(latest, page)
				}
			}
		}
	}

	private fun tell(message: String) {
		Toasts.show(SOURCE, message, color = Toasts.Colors.GOOD)
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §7$message"))
	}

	/**
	 * Whether [tag] is newer than [current], by the first three numbers. What follows is the
	 * Minecraft version, not a newer Cryptic.
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
