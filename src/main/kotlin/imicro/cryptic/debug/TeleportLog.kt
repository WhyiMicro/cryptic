package imicro.cryptic.debug

import imicro.cryptic.Cryptic
import imicro.cryptic.feature.Etherwarp
import imicro.cryptic.feature.EtherwarpZeroPing
import imicro.cryptic.feature.SmoothTeleport
import imicro.cryptic.mixin.LocalPlayerAccessor
import imicro.cryptic.skyblock.ServerStats
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.world.phys.Vec3
import java.nio.file.Files
import java.nio.file.Path

/**
 * `/cryptic debug teleportlog`: records teleports on a real server for tuning Smooth teleport and
 * Fake zpew. Saved to `logs/cryptic-teleport.log`; nothing is sent anywhere.
 */
object TeleportLog {
	@Volatile
	var recording = false
		private set

	private val lines = ArrayList<String>()
	private var startedAt = 0L

	/** Frames are recorded until this long after the last click or teleport. */
	private const val FRAMES_FOR_MS = 3_000L
	private var framesUntil = 0L

	/** Caps a forgotten recording. */
	private const val MAX_LINES = 200_000

	private var initialized = false

	private val file: Path get() = FabricLoader.getInstance().gameDir.resolve("logs").resolve("cryptic-teleport.log")

	fun initialize() {
		if (initialized) return
		initialized = true
		LevelRenderEvents.COLLECT_SUBMITS.register { frame() }
	}

	fun toggle(): String {
		if (!recording) {
			lines.clear()
			startedAt = System.currentTimeMillis()
			recording = true
			add("start", settings())
			return "Recording teleports. Teleport around as you normally would, then run /cryptic debug teleportlog again."
		}
		recording = false
		add("stop", "")
		return runCatching {
			Files.createDirectories(file.parent)
			Files.write(file, lines)
			"Saved ${lines.size} lines to logs/cryptic-teleport.log."
		}.getOrElse {
			Cryptic.LOGGER.warn("Writing the teleport log failed", it)
			"Couldn't save the teleport log: ${it.message}"
		}
	}

	private fun settings(): String =
		"smooth=${Etherwarp.smoothTeleport.value} predictive=${Etherwarp.smoothPredictive.value} smoothing=${Etherwarp.smoothTime.value.toInt()}ms " +
			"kinds=${SmoothTeleport.KINDS.filter { Etherwarp.smoothKinds.isSelected(it) }} " +
			"fakeZpew=${Etherwarp.fakeZpew.value}/${Etherwarp.fakeZpewTransmission.value}/${Etherwarp.fakeZpewImpact.value} " +
			"noRotate=${Etherwarp.noRotate.value}/${Etherwarp.noRotateTransmission.value}/${Etherwarp.noRotateImpact.value} " +
			"ping=${ServerStats.ping} camera=${Minecraft.getInstance().options.cameraType}"

	private fun add(what: String, detail: String) {
		if (!recording || lines.size >= MAX_LINES) return
		lines += "${System.currentTimeMillis() - startedAt}\t$what\t$detail"
	}

	private fun v(p: Vec3?): String = p?.let { "%.3f %.3f %.3f".format(it.x, it.y, it.z) } ?: "-"

	private fun serverPos(): String {
		val player = Minecraft.getInstance().player ?: return "-"
		val known = player as LocalPlayerAccessor
		return "%.3f %.3f %.3f".format(known.`cryptic$serverX`(), known.`cryptic$serverY`(), known.`cryptic$serverZ`())
	}

	@JvmStatic
	fun click(packet: String, yaw: Float, pitch: Float) {
		if (!recording) return
		val player = Minecraft.getInstance().player ?: return
		framesUntil = System.currentTimeMillis() + FRAMES_FOR_MS
		add(
			"click",
			"$packet item=${imicro.cryptic.skyblock.SkyblockItem.id(player.mainHandItem)} sneak=${Minecraft.getInstance().options.keyShift.isDown} " +
				"packetRot=%.1f %.1f viewRot=%.1f %.1f feet=${v(player.position())} server=${serverPos()} sent=${v(EtherwarpZeroPing.serverFeet)} ping=${ServerStats.ping}".format(yaw, pitch, player.yRot, player.xRot),
		)
	}

	@JvmStatic
	fun skipped(reason: String) = add("skip", reason)

	@JvmStatic
	fun predicted(kind: EtherwarpZeroPing.Kind, from: Vec3, feet: Vec3, sameClick: Boolean, waiting: Int) =
		add("predict", "$kind from=${v(from)} to=${v(feet)} sameClick=$sameClick waiting=$waiting")

	@JvmStatic
	fun glide(from: Vec3, to: Vec3, durationMs: Long) = add("glide", "from=${v(from)} to=${v(to)} over=${durationMs}ms")

	@JvmStatic
	fun packet(packet: ClientboundPlayerPositionPacket) {
		if (!recording || !Minecraft.getInstance().isSameThread) return
		val player = Minecraft.getInstance().player ?: return
		framesUntil = System.currentTimeMillis() + FRAMES_FOR_MS
		val change = packet.change()
		add(
			"packet",
			"id=${packet.id()} pos=${v(change.position())} rot=%.1f %.1f relatives=${packet.relatives()} before=${v(player.position())} viewRot=%.1f %.1f waiting=${EtherwarpZeroPing.unanswered()}"
				.format(change.yRot(), change.xRot(), player.yRot, player.xRot),
		)
	}

	@JvmStatic
	fun packetApplied() {
		if (!recording || !Minecraft.getInstance().isSameThread) return
		val player = Minecraft.getInstance().player ?: return
		add("applied", "after=${v(player.position())} viewRot=%.1f %.1f waiting=${EtherwarpZeroPing.unanswered()}".format(player.yRot, player.xRot))
	}

	private fun frame() {
		if (!recording || System.currentTimeMillis() > framesUntil) return
		val client = Minecraft.getInstance()
		val player = client.player ?: return
		val partial = client.deltaTracker.getGameTimeDeltaPartialTick(true)
		val real = player.getPosition(partial)
		val camera = client.gameRenderer.mainCamera()
		val eyes = camera.position()
		add("frame", "real=${v(real)} camera=${v(eyes)} viewRot=%.1f %.1f".format(camera.yRot(), camera.xRot()))
	}
}
