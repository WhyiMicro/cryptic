package imicro.cryptic.debug

import net.minecraft.client.Minecraft
import net.minecraft.sounds.SoundEvent

/**
 * The last sounds the server played, for `/cryptic debug sounds`.
 *
 * Which sound Hypixel plays for something — a treasure chest found, a lock
 * picked — can only be heard in a lobby, and muting one needs its exact id,
 * pitch and volume. So the most recent are kept with all three.
 */
object SoundLog {
	private class Entry(val id: String, val pitch: Float, val volume: Float, val onEntity: Boolean, val at: Long)

	private const val KEPT = 40

	private val entries = ArrayDeque<Entry>()

	/** A sound packet, on the client thread. */
	@JvmStatic
	fun note(sound: SoundEvent, pitch: Float, volume: Float, onEntity: Boolean) {
		if (!Minecraft.getInstance().isSameThread) return
		entries.addLast(Entry(sound.location().toString(), pitch, volume, onEntity, System.currentTimeMillis()))
		while (entries.size > KEPT) entries.removeFirst()
	}

	fun describe(): List<String> {
		if (entries.isEmpty()) return listOf("No sounds from the server yet.")
		val now = System.currentTimeMillis()
		return listOf("Last ${minOf(entries.size, 20)} sounds from the server, newest first:") +
			entries.toList().asReversed().take(20).map {
				"%.1fs ago %s pitch %.2f volume %.2f%s".format(
					(now - it.at) / 1000.0, it.id, it.pitch, it.volume, if (it.onEntity) " (on an entity)" else "",
				)
			}
	}
}
