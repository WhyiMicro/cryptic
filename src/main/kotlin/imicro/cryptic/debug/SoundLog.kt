package imicro.cryptic.debug

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.SoundEngine
import net.minecraft.sounds.SoundEvent

/**
 * The last sounds the server played, with id, pitch and volume, and what became of each: played,
 * or dropped by one of Cryptic's features, for `/cryptic debug sounds`.
 */
object SoundLog {
	private class Entry(val id: String, val pitch: Float, val volume: Float, val onEntity: Boolean, val at: Long) {
		@Volatile
		var outcome = "no play seen"
	}

	private const val KEPT = 40

	/** A play this long after the packet is taken to be its sound. */
	private const val MATCH_MILLIS = 1000L

	private val entries = ArrayDeque<Entry>()

	@JvmStatic
	fun note(sound: SoundEvent, pitch: Float, volume: Float, onEntity: Boolean) {
		if (!Minecraft.getInstance().isSameThread) return
		add(Entry(sound.location().toString(), pitch, volume, onEntity, System.currentTimeMillis()))
	}

	/** A server sound a feature swallowed before it reached the client thread. Any thread. */
	@JvmStatic
	fun dropped(sound: SoundEvent, pitch: Float, volume: Float, by: String) {
		add(Entry(sound.location().toString(), pitch, volume, false, System.currentTimeMillis()).also { it.outcome = "dropped by $by" })
	}

	/** A sound a feature stopped at the sound manager. */
	@JvmStatic
	fun muted(instance: SoundInstance, by: String) {
		matching(instance)?.outcome = "muted by $by"
	}

	/** Called inside every sound play, so it must never throw. */
	@JvmStatic
	fun played(instance: SoundInstance, result: SoundEngine.PlayResult?) {
		try {
			val entry = matching(instance) ?: return
			val options = Minecraft.getInstance().options
			val category = (options.getFinalSoundSourceVolume(instance.source) * 100).toInt()
			val volume = String.format(java.util.Locale.ROOT, "%.2f", instance.volume)
			val what = when (result) {
				SoundEngine.PlayResult.STARTED -> "played"
				SoundEngine.PlayResult.STARTED_SILENTLY -> "started silently"
				else -> "not started"
			}
			entry.outcome = "$what (${instance.source.name.lowercase()} at $category percent, volume $volume)"
		} catch (_: Exception) {
		}
	}

	private fun add(entry: Entry) = synchronized(entries) {
		entries.addLast(entry)
		while (entries.size > KEPT) entries.removeFirst()
	}

	private fun matching(instance: SoundInstance): Entry? {
		val id = instance.identifier.toString()
		val now = System.currentTimeMillis()
		return synchronized(entries) {
			entries.lastOrNull { it.id == id && it.outcome == "no play seen" && now - it.at <= MATCH_MILLIS }
		}
	}

	fun describe(): List<String> {
		val list = synchronized(entries) { entries.toList() }
		if (list.isEmpty()) return listOf("No sounds from the server yet.")
		val now = System.currentTimeMillis()
		return listOf("Last ${minOf(list.size, 20)} sounds from the server, newest first:") +
			list.asReversed().take(20).map {
				"%.1fs ago %s pitch %.2f volume %.2f%s: %s".format(
					(now - it.at) / 1000.0, it.id, it.pitch, it.volume, if (it.onEntity) " (on an entity)" else "", it.outcome,
				)
			}
	}
}
