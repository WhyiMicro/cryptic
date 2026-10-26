package imicro.cryptic.feature

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import imicro.cryptic.Cryptic
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.metadata.version.VersionPredicate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.zip.ZipFile

/**
 * Downloads a new Cryptic and installs it when the game closes. Update Checker finds the release;
 * this downloads its jar to `config/cryptic/update`, checks its SHA-256 against GitHub's and that
 * it really is Cryptic, and keeps it. Windows won't replace a jar the game has open, so
 * [imicro.cryptic.update.UpdateSwapper] does the swap in a separate process after the game exits.
 * The same approach as SkyHanni's libautoupdate.
 */
object AutoUpdater {
	private val directory: Path = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("update")
	private val helperDirectory: Path = directory.resolve("helper")
	private val log: Path = directory.resolve("update.log")

	private const val HELPER_CLASS = "imicro.cryptic.update.UpdateSwapper"

	private val http: HttpClient by lazy {
		// GitHub redirects downloads to its file host.
		HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build()
	}

	/** The downloaded jar waiting for the game to close, and its version. */
	@Volatile
	private var pending: Pair<Path, String>? = null

	@Volatile
	private var downloading = false

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		ClientLifecycleEvents.CLIENT_STOPPING.register { install() }
		// A download from a session that never installed it (crash, kill) is still good if it's
		// still newer.
		runCatching {
			if (!Files.isDirectory(directory)) return@runCatching
			Files.list(directory).use { files ->
				files.filter { it.fileName.toString().endsWith(".jar") }.forEach { jar ->
					val version = jar.fileName.toString().removePrefix("cryptic-").removeSuffix(".jar")
					// Checked against this Minecraft again, since the game may have updated.
					if (UpdateChecker.isNewer(version, UpdateChecker.installed) && isCryptic(jar) && runsOnThisMinecraft(jar)) pending = jar to version
					else Files.deleteIfExists(jar)
				}
			}
		}.onFailure { Cryptic.LOGGER.warn("Could not look for a waiting update", it) }
	}

	/** Whether this game can update itself: a jar in a mods folder, not a dev run. */
	val supported: Boolean get() = runningJar() != null

	val waiting: String? get() = pending?.second

	/** Downloads [release]'s jar for [version] off the game thread, reporting through [done]. */
	fun download(release: JsonObject, version: String, done: (Result<String>) -> Unit) {
		if (pending?.second == version) return done(Result.success(version))
		if (downloading) return
		val asset = assetFor(release) ?: return done(Result.failure(IllegalStateException("The release has no jar for Minecraft ${UpdateChecker.minecraftTag}.")))
		val url = asset.get("browser_download_url")?.asString ?: return done(Result.failure(IllegalStateException("The release's jar has no download link.")))
		val name = asset.get("name").asString
		val digest = asset.get("digest")?.takeIf { !it.isJsonNull }?.asString?.removePrefix("sha256:")
		downloading = true

		val part = directory.resolve("$name.part")
		val request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofMinutes(5))
			.header("User-Agent", "Cryptic/${UpdateChecker.installed}")
			.GET()
			.build()
		runCatching { Files.createDirectories(directory) }
		http.sendAsync(request, HttpResponse.BodyHandlers.ofFile(part)).whenComplete { response, error ->
			downloading = false
			val result = runCatching {
				if (error != null) throw error
				check(response.statusCode() == 200) { "GitHub answered ${response.statusCode()}." }
				if (digest != null) check(sha256(part).equals(digest, ignoreCase = true)) { "The download was damaged (its checksum is wrong)." }
				check(isCryptic(part)) { "The download is not a Cryptic jar." }
				check(runsOnThisMinecraft(part)) { "The download is for a different Minecraft version." }
				val jar = directory.resolve(name)
				Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING)
				// Only the newest is kept.
				pending?.first?.takeIf { it != jar }?.let(Files::deleteIfExists)
				pending = jar to version
				version
			}
			if (result.isFailure) runCatching { Files.deleteIfExists(part) }
			done(result)
		}
	}

	/** The release's jar for this Minecraft version (`cryptic-0.30.0-26.2.jar`), not a sources jar. */
	fun assetFor(release: JsonObject): JsonObject? {
		val minecraft = UpdateChecker.minecraftTag
		if (minecraft.isEmpty()) return null
		val assets = release.getAsJsonArray("assets")?.map { it.asJsonObject } ?: return null
		return assets.firstOrNull { asset ->
			val name = asset.get("name")?.asString.orEmpty()
			name.endsWith("-$minecraft.jar") && !name.contains("sources")
		}
	}

	/**
	 * Whether [jar]'s `fabric.mod.json` asks for this Minecraft version, checked the way Fabric
	 * checks it.
	 */
	private fun runsOnThisMinecraft(jar: Path): Boolean = runCatching {
		val running = FabricLoader.getInstance().getModContainer("minecraft").orElse(null)?.metadata?.version ?: return false
		ZipFile(jar.toFile()).use { zip ->
			val entry = zip.getEntry("fabric.mod.json") ?: return false
			val metadata = zip.getInputStream(entry).bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
			val wanted = metadata.getAsJsonObject("depends")?.get("minecraft") ?: return false
			val ranges = if (wanted.isJsonArray) wanted.asJsonArray.map { it.asString } else listOf(wanted.asString)
			ranges.any { VersionPredicate.parse(it).test(running) }
		}
	}.getOrDefault(false)

	private fun sha256(file: Path): String {
		val digest = MessageDigest.getInstance("SHA-256")
		Files.newInputStream(file).use { input ->
			val buffer = ByteArray(64 * 1024)
			while (true) {
				val read = input.read(buffer)
				if (read < 0) break
				digest.update(buffer, 0, read)
			}
		}
		return HexFormat.of().formatHex(digest.digest())
	}

	private fun isCryptic(jar: Path): Boolean = runCatching {
		ZipFile(jar.toFile()).use { zip ->
			val entry = zip.getEntry("fabric.mod.json") ?: return false
			val metadata = zip.getInputStream(entry).bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
			metadata.get("id")?.asString == Cryptic.MOD_ID
		}
	}.getOrDefault(false)

	/** The jar Cryptic was loaded from, or null in a dev run. */
	private fun runningJar(): Path? {
		if (FabricLoader.getInstance().isDevelopmentEnvironment) return null
		val container = FabricLoader.getInstance().getModContainer(Cryptic.MOD_ID).orElse(null) ?: return null
		return container.origin.paths.firstOrNull { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
	}

	/**
	 * Starts the swap as the game shuts down: copies the helper class out of the jar (it can't run
	 * from the jar it replaces) and launches it with this game's Java.
	 */
	private fun install() {
		val (jar, version) = pending ?: return
		if (!UpdateChecker.module.enabled || !UpdateChecker.autoUpdate.value) return
		val running = runningJar() ?: return
		runCatching {
			val classFile = helperDirectory.resolve(HELPER_CLASS.replace('.', '/') + ".class")
			Files.createDirectories(classFile.parent)
			val resource = "/" + HELPER_CLASS.replace('.', '/') + ".class"
			(AutoUpdater::class.java.getResourceAsStream(resource) ?: error("The helper is missing from the jar")).use { input ->
				Files.copy(input, classFile, StandardCopyOption.REPLACE_EXISTING)
			}
			val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
			val bin = Path.of(System.getProperty("java.home"), "bin")
			// javaw on Windows, so no console flashes up.
			val java = listOf(if (windows) "javaw.exe" else "java", if (windows) "java.exe" else "java")
				.map(bin::resolve).firstOrNull(Files::isRegularFile) ?: error("No Java found in ${bin}")
			ProcessBuilder(
				java.toString(), "-cp", helperDirectory.toString(), HELPER_CLASS,
				ProcessHandle.current().pid().toString(), running.toString(), jar.toString(), log.toString(),
			).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start()
			Cryptic.LOGGER.info("Cryptic {} will be installed once the game has closed", version)
		}.onFailure { Cryptic.LOGGER.error("Could not start the update", it) }
	}
}
