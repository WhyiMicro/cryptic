package imicro.cryptic.feature

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import imicro.cryptic.Cryptic
import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SoundsScreen
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.resources.sounds.SoundInstance
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * The Sound Manager: a volume for every sound, from silent to twice as loud.
 *
 * NoammAddons' Sound Manager (CC0, Copyright (c) Noamm9), with its window
 * opening whether or not the module is on — turning a module on only to reach
 * the place where it is set up is a step nobody needs. With the module off,
 * the volumes are kept and nothing is changed.
 *
 * Applied by scaling what a sound instance reports as its volume. Minecraft
 * caps that at full, so more than 100% only helps a sound that plays quieter
 * than full to begin with — which most of Hypixel's do.
 *
 * Kept in `config/cryptic/sound-volumes.json`, by sound id, in percent.
 */
object SoundVolumes {
	private val open = ButtonModuleSetting("open", "Open manager", action = { SoundsScreen.request() })

	@JvmField
	val module = Module(
		id = "sound_manager",
		name = "Sound Manager",
		description = "Turn any sound up, down or off",
		category = ModuleCategory.MISC,
		hasDemoSettings = false,
		supportsKeybind = false,
		settings = listOf(open),
	)

	/** Percent by sound id. Read from the sound thread as well as the game's, so concurrent. */
	val volumes = ConcurrentHashMap<String, Int>()

	/** The last sounds played, newest last, by id. */
	private val recent = LinkedHashSet<String>()
	private val recentLock = Any()

	private val file get() = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("sound-volumes.json")
	private val gson = GsonBuilder().setPrettyPrinting().create()

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		load()
	}

	/** What to multiply [instance]'s volume by: 1 for a sound left alone. */
	@JvmStatic
	fun factor(instance: SoundInstance): Float {
		if (!module.enabled || volumes.isEmpty()) return 1f
		val percent = volumes[instance.identifier.toString()] ?: return 1f
		return percent / 100f
	}

	/** A sound starting, from the sound manager, for the Recent list. */
	@JvmStatic
	fun onPlay(instance: SoundInstance) {
		val id = instance.identifier.toString()
		synchronized(recentLock) {
			recent.remove(id)
			recent.add(id)
			if (recent.size > MAX_RECENT) recent.remove(recent.first())
		}
	}

	/** The recent sounds, newest first. */
	fun recent(): List<String> = synchronized(recentLock) { recent.toList().asReversed() }

	fun percent(id: String): Int = volumes[id] ?: 100

	fun set(id: String, percent: Int) {
		val value = percent.coerceIn(0, MAX_PERCENT)
		if (value == 100) volumes.remove(id) else volumes[id] = value
	}

	private fun load() {
		runCatching {
			if (!Files.exists(file)) return
			val type = object : TypeToken<Map<String, Int>>() {}.type
			val loaded: Map<String, Int>? = gson.fromJson(Files.readString(file), type)
			volumes.clear()
			loaded?.forEach { (id, percent) -> if (percent != 100) volumes[id] = percent.coerceIn(0, MAX_PERCENT) }
		}.onFailure { Cryptic.LOGGER.error("Could not read sound volumes", it) }
	}

	fun save() {
		runCatching {
			Files.createDirectories(file.parent)
			Files.writeString(file, gson.toJson(volumes.toSortedMap()))
		}.onFailure { Cryptic.LOGGER.error("Could not save sound volumes", it) }
	}

	const val MAX_PERCENT = 200
	private const val MAX_RECENT = 100
}
