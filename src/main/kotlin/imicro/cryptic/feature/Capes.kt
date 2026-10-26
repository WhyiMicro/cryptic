package imicro.cryptic.feature

import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.NativeImage
import imicro.cryptic.Cryptic
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SectionModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.ClientAsset
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.player.PlayerSkin
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Capes from the other cape mods (OptiFine, LabyMod, MinecraftCapes), and one of your own from an
 * image file. Each player is looked up once a session, in that order; a player's official Minecraft
 * cape wins unless told otherwise. Only you see what this shows.
 */
object Capes {
	private val ownSection = SectionModuleSetting("own_section", "Your cape")

	@JvmField
	val ownCape = ToggleModuleSetting(
		id = "own_cape",
		label = "Custom cape",
		defaultValue = false,
		description = "Wears an image of your choice as your cape (and elytra). Only you see it.",
	)

	@JvmField
	val chooseImage = ButtonModuleSetting("choose_image", "Choose image…", { chooseImage() }, visibleIf = { ownCape.value })

	private val othersSection = SectionModuleSetting("others_section", "Cape mods")

	@JvmField
	val optifine = ToggleModuleSetting("optifine", "OptiFine", true, "Capes from OptiFine's cape server.")

	@JvmField
	val labymod = ToggleModuleSetting("labymod", "LabyMod", true, "Capes from LabyMod's cape server.")

	@JvmField
	val minecraftCapes = ToggleModuleSetting("minecraftcapes", "MinecraftCapes", true, "Capes from the MinecraftCapes mod, animated ones included.")

	@JvmField
	val preferOfficial = ToggleModuleSetting(
		id = "prefer_official",
		label = "Keep official capes",
		defaultValue = true,
		description = "A player's Minecraft cape (Migrator, Vanilla, events) stays over one from a cape mod.",
	)

	@JvmField
	val module = Module(
		id = "capes",
		name = "Capes",
		description = "Capes from cape mods",
		category = ModuleCategory.VISUAL,
		supportsKeybind = false,
		settings = listOf(ownSection, ownCape, chooseImage, othersSection, optifine, labymod, minecraftCapes, preferOfficial),
	)

	private val ownFile get() = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("cape.png")
	private val OWN_ID: Identifier = Cryptic.id("capes/own")

	/** A player's cape: still being looked up, none anywhere, or ready to wear. */
	private sealed class Entry {
		data object Loading : Entry()
		data object None : Entry()
		class Ready(val texture: ClientAsset.Texture, val frames: List<NativeImage>, val dynamic: DynamicTexture) : Entry()
	}

	private val players = ConcurrentHashMap<UUID, Entry>()
	private val inFlight = AtomicInteger()
	private const val MAX_IN_FLIGHT = 4

	private var own: Entry.Ready? = null
	private var ownLoaded = false

	private val http: HttpClient by lazy {
		HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build()
	}

	/** [skin] with the cape this module gives [entity], if any. Called for every player drawn. */
	@JvmStatic
	fun skinFor(entity: Any, skin: PlayerSkin): PlayerSkin {
		if (!module.enabled || entity !is Player) return skin
		val profile = entity.gameProfile
		val client = Minecraft.getInstance()
		if (ownCape.value && profile.id == client.player?.uuid) {
			ownTexture()?.let { return withCape(skin, it.texture) }
		}
		if (preferOfficial.value && skin.cape != null) return skin
		// Hypixel's NPCs and other server-made players aren't anyone's account.
		if (profile.id.version() != 4) return skin
		when (val entry = players[profile.id]) {
			is Entry.Ready -> return withCape(skin, entry.texture)
			null -> lookUp(profile.id, profile.name)
			else -> {}
		}
		return skin
	}

	private fun withCape(skin: PlayerSkin, cape: ClientAsset.Texture) = PlayerSkin(skin.body, cape, skin.elytra, skin.model, skin.secure)

	// ---- Your own -----------------------------------------------------------------------------

	private fun ownTexture(): Entry.Ready? {
		if (ownLoaded) return own
		ownLoaded = true
		own = runCatching {
			if (!Files.exists(ownFile)) return null
			val image = Files.newInputStream(ownFile).use { NativeImage.read(it) }
			register(OWN_ID, listOf(toCapeLayout(image)))
		}.onFailure { Cryptic.LOGGER.error("Could not read your cape image", it) }.getOrNull()
		return own
	}

	/** A file dialog off the render thread; the image is copied in as `config/cryptic/cape.png`. */
	private fun chooseImage() {
		Thread({
			val picked = runCatching {
				org.lwjgl.system.MemoryStack.stackPush().use { stack ->
					val filters = stack.mallocPointer(1)
					filters.put(stack.UTF8("*.png")).flip()
					org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog("Choose a cape image", null, filters, "PNG images", false)
				}
			}.getOrNull() ?: return@Thread
			Minecraft.getInstance().execute { useImage(java.nio.file.Path.of(picked)) }
		}, "Cryptic cape picker").apply { isDaemon = true }.start()
	}

	private fun useImage(path: java.nio.file.Path) {
		try {
			// Read first, so a file that isn't an image never replaces the one in use.
			Files.newInputStream(path).use { NativeImage.read(it) }.close()
			Files.createDirectories(ownFile.parent)
			Files.copy(path, ownFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
			own?.let { release(OWN_ID, it) }
			own = null
			ownLoaded = false
			ownCape.value = true
			note("§7Your cape is now §f${path.fileName}§7.")
		} catch (error: Exception) {
			note("§cThat file couldn't be read as a PNG image.")
		}
	}

	// ---- Other players ------------------------------------------------------------------------

	private fun lookUp(uuid: UUID, name: String) {
		if (inFlight.get() >= MAX_IN_FLIGHT) return
		players[uuid] = Entry.Loading
		inFlight.incrementAndGet()
		val providers = buildList<() -> CompletableFuture<List<NativeImage>?>> {
			if (optifine.value) add { png("http://s.optifine.net/capes/$name.png") }
			if (labymod.value) add { png("https://dl.labymod.net/capes/$uuid") }
			if (minecraftCapes.value) add { minecraftCapes(uuid) }
		}
		firstOf(providers.iterator()).whenComplete { frames, _ ->
			inFlight.decrementAndGet()
			if (frames.isNullOrEmpty()) {
				players[uuid] = Entry.None
				return@whenComplete
			}
			Minecraft.getInstance().execute {
				players[uuid] = runCatching { register(Cryptic.id("capes/${uuid.toString().replace("-", "")}"), frames) }
					.getOrElse { Entry.None }
			}
		}
	}

	/** The first provider with a cape, asked one after another. */
	private fun firstOf(providers: Iterator<() -> CompletableFuture<List<NativeImage>?>>): CompletableFuture<List<NativeImage>?> {
		if (!providers.hasNext()) return CompletableFuture.completedFuture(null)
		return providers.next()().exceptionally { null }.thenCompose { found ->
			if (!found.isNullOrEmpty()) CompletableFuture.completedFuture(found) else firstOf(providers)
		}
	}

	private fun png(url: String): CompletableFuture<List<NativeImage>?> = bytes(url).thenApply { body ->
		body?.let { listOf(toCapeLayout(NativeImage.read(it))) }
	}

	/** MinecraftCapes answers with JSON naming a still or an animated cape. */
	private fun minecraftCapes(uuid: UUID): CompletableFuture<List<NativeImage>?> =
		bytes("https://api.minecraftcapes.net/profile/${uuid.toString().replace("-", "")}").thenCompose { body ->
			val json = body?.let { JsonParser.parseString(String(it)).asJsonObject } ?: return@thenCompose CompletableFuture.completedFuture(null)
			fun url(key: String) = json.get(key)?.takeIf { it.isJsonPrimitive }?.asString
			val animated = url("animated_cape_url")
			val still = url("cape_url")
			when {
				animated != null -> bytes(animated).thenApply { it?.let { bytes -> frames(NativeImage.read(bytes)) } }
				still != null -> png(still)
				else -> CompletableFuture.completedFuture(null)
			}
		}

	private fun bytes(url: String): CompletableFuture<ByteArray?> {
		val request = HttpRequest.newBuilder(URI.create(url))
			.header("User-Agent", "Cryptic")
			.timeout(Duration.ofSeconds(10))
			.GET()
			.build()
		return http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
			.thenApply { response -> response.body().takeIf { response.statusCode() == 200 && it.isNotEmpty() } }
	}

	// ---- Images -------------------------------------------------------------------------------

	/**
	 * Any cape image laid out as Minecraft's 64×32 cape sheet, scaled: LabyMod sends only the cape's
	 * 22×17 corner, OptiFine 46×22 (cape and elytra).
	 */
	private fun toCapeLayout(image: NativeImage): NativeImage {
		val w = image.width
		val h = image.height
		val scale = when {
			w == h * 2 -> return image
			w * 17 == h * 22 -> w / 22
			w * 22 == h * 46 -> w / 46
			else -> maxOf(1, w / 64)
		}.coerceAtLeast(1)
		val sheet = NativeImage(64 * scale, 32 * scale, true)
		for (y in 0 until minOf(h, sheet.height)) for (x in 0 until minOf(w, sheet.width)) sheet.setPixel(x, y, image.getPixel(x, y))
		image.close()
		return sheet
	}

	/** An animated cape's frames, stacked top to bottom, each half as tall as it is wide. */
	private fun frames(strip: NativeImage): List<NativeImage> {
		val frameHeight = strip.width / 2
		val count = if (frameHeight > 0) strip.height / frameHeight else 0
		if (count <= 1) return listOf(toCapeLayout(strip))
		val frames = (0 until count).map { i ->
			NativeImage(strip.width, frameHeight, true).also { frame ->
				for (y in 0 until frameHeight) for (x in 0 until strip.width) frame.setPixel(x, y, strip.getPixel(x, i * frameHeight + y))
			}
		}
		strip.close()
		return frames
	}

	private fun register(id: Identifier, frames: List<NativeImage>): Entry.Ready {
		val first = frames.first()
		val texture = DynamicTexture({ "Cryptic cape" }, NativeImage(first.width, first.height, true))
		texture.pixels?.copyFrom(first)
		texture.upload()
		Minecraft.getInstance().textureManager.register(id, texture)
		return Entry.Ready(ClientAsset.ResourceTexture(id, id), frames, texture)
	}

	private fun release(id: Identifier, entry: Entry.Ready) {
		Minecraft.getInstance().textureManager.release(id)
		entry.frames.forEach { it.close() }
	}

	/** Animated capes move on to their next frame ten times a second. */
	private var frameTicks = 0
	private var frame = 0

	fun tick() {
		if (!module.enabled || ++frameTicks < 2) return
		frameTicks = 0
		frame++
		for (entry in players.values) {
			if (entry !is Entry.Ready || entry.frames.size <= 1) continue
			entry.dynamic.pixels?.copyFrom(entry.frames[frame % entry.frames.size])
			entry.dynamic.upload()
		}
	}

	private fun note(text: String) {
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] $text"))
	}

	fun describe(): List<String> {
		val ready = players.values.count { it is Entry.Ready }
		return listOf("Capes: ${players.size} players looked up, $ready with a cape, ${inFlight.get()} looking now, your own: ${Files.exists(ownFile)}".lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() })
	}
}
