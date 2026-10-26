package imicro.cryptic.device

import imicro.cryptic.feature.DeviceSolver
import net.minecraft.client.Minecraft

/**
 * The berserk's early Sharp Shooter ("i4"): standing on the device's plate when Goldor starts
 * talking means the device is being done before the first section. Done is your own "completed a
 * device!" line; leaving the plate first, or running out of time, is a fail.
 */
object I4 {
	/** A step off the plate this long ends the attempt, so a stumble doesn't count. */
	private const val OFF_PLATE_TICKS = 20

	/** Well past any real i4; an attempt still open then has failed. */
	private const val TIMEOUT_TICKS = 20 * 45

	private var attempting = false
	private var ticks = 0
	private var offTicks = 0

	fun onChatMessage(line: String) {
		val plain = line.replace(FORMATTING, "").trim()
		if (plain == GOLDOR_START) {
			attempting = DeviceSolver.i4MessagesEnabled && SharpShooter.onPlatform()
			ticks = 0
			offTicks = 0
			return
		}
		if (!attempting) return
		val name = DEVICE_DONE.matchEntire(plain)?.groupValues?.get(1) ?: return
		if (name != Minecraft.getInstance().player?.gameProfile?.name) return
		attempting = false
		DeviceSolver.announceI4(success = true)
	}

	fun tick() {
		if (!attempting) return
		offTicks = if (SharpShooter.onPlatform()) 0 else offTicks + 1
		if (offTicks >= OFF_PLATE_TICKS || ++ticks >= TIMEOUT_TICKS) {
			attempting = false
			DeviceSolver.announceI4(success = false)
		}
	}

	fun forget() {
		attempting = false
	}

	private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
	private val DEVICE_DONE = Regex("""^(?:\[[^\]]+] )?(\w{1,16}) completed a device! \(\d/\d\)$""")
	private val FORMATTING = Regex("§.")
}
