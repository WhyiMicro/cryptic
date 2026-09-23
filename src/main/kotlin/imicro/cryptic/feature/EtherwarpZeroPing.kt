package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.mixin.LocalPlayerAccessor
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.level.block.AbstractCauldronBlock
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.EnderChestBlock
import net.minecraft.world.level.block.HopperBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Makes an etherwarp arrive before the server says so.
 *
 * Ported from NoammAddons' NoRotate (CC0). A teleport costs a round trip: the
 * click goes up, and only when the position packet comes back does anything
 * move. Both halves of this fill that gap from the client's own prediction of
 * where the warp lands — which [EtherwarpHelper] already works out for the
 * overlay, so the landing spot is the one being drawn under the crosshair.
 *
 * **Fake zpew** puts the camera at the landing spot the moment the click is
 * sent. Nothing else moves: the player is still where the server thinks,
 * physics included, and when the real teleport lands the camera is already
 * there and nothing visibly changes.
 *
 * **No rotate** is the other half of the same round trip. A Hypixel teleport
 * confirmation carries a rotation as well as a position, so the server hands
 * your head back the way it was pointing when the click was sent — undoing
 * however far you turned while waiting. The rotation is put back after the
 * client has answered, which is what Noamm's version does too.
 *
 * A prediction is only ever a guess, so it is kept for the resync timeout and
 * no longer; if the warp never happens the camera falls back to the player.
 */
object EtherwarpZeroPing {
	/** One predicted landing: where the feet end up, and when to stop believing it. */
	private class Pending(val feet: Vec3, val expiresAt: Long)

	/**
	 * Predictions still waiting for their teleport, oldest first.
	 *
	 * A list rather than a single value because etherwarps chain: a second
	 * click can be sent before the first teleport has come back, and each one
	 * is aimed from where the one before it is taking you.
	 *
	 * Only ever touched from the client thread — every entry point checks.
	 */
	private val pending = ArrayDeque<Pending>()

	/** When the last prediction was made, which is how a click is told from two. */
	private var lastPredictedAt = 0L

	/**
	 * How close together two predictions have to be to be one click.
	 *
	 * The game sends both of a click's packets in the same tick, microseconds
	 * apart; nobody chains two real etherwarps inside a twentieth of a second.
	 */
	private const val SAME_CLICK_MILLIS = 50L

	private var savedYRot = 0f
	private var savedXRot = 0f
	private var savedYRotO = 0f
	private var savedXRotO = 0f
	private var savedHeadRot = 0f
	private var savedHeadRotO = 0f
	private var restoreRotation = false

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		// A prediction belongs to the world it was made in, and a warp is a
		// perfectly ordinary way to leave one.
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> pending.clear() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> pending.clear() }
	}

	/** True while either half wants to know which dungeon floor this is. */
	val needsFloorTracking: Boolean
		get() = armed

	private val armed: Boolean
		get() = Etherwarp.module.enabled && (Etherwarp.fakeZpew.value || Etherwarp.noRotate.value)

	/**
	 * Called for every packet the client sends, which is where a teleport is
	 * first knowable: the click has gone, and the server will answer it.
	 *
	 * Sending happens on whichever thread asked, so anything that reads the
	 * world has to be turned away unless this is the client thread. A use is
	 * always sent from there, so nothing is lost by the check.
	 */
	@JvmStatic
	fun onPacketSent(packet: Packet<*>) {
		if (!armed) return
		val client = Minecraft.getInstance()
		if (!client.isSameThread) return

		when (packet) {
			// The rotation the packet carries is the one the server will warp
			// from, which is not quite the one on screen if the mouse has moved
			// since the tick.
			is ServerboundUseItemPacket -> predict(packet.yRot, packet.xRot)
			// A click on a block close enough to reach sends this one instead,
			// and it carries no rotation of its own — so the last one the
			// server was told is the one it will use.
			is ServerboundUseItemOnPacket -> client.player?.let { player ->
				val known = player as LocalPlayerAccessor
				predict(known.`cryptic$serverYRot`(), known.`cryptic$serverXRot`())
			}
			else -> Unit
		}
	}

	private fun predict(yaw: Float, pitch: Float) {
		val client = Minecraft.getInstance()
		val player = client.player ?: return
		if (player.isPassenger) return
		// Sneaking is what arms a merged Aspect of the End, so a click without
		// it is an ordinary use of the item.
		if (!client.options.keyShift.isDown) return

		val held = player.mainHandItem.takeUnless { it.isEmpty } ?: return
		val range = EtherwarpHelper.etherwarpRange(held) ?: return

		// Hypixel refuses every teleport in Goldor's tower, so a click there is
		// not one however good the target looks.
		if (DungeonLocation.inFloor7 && DungeonRun.inBoss) return
		if (opensSomething()) return

		purge()

		// One click on a block within arm's reach sends two packets: the game
		// offers the click to the block first, and when sneaking makes the
		// block refuse it — which it always does — sends the plain use as well.
		// Both are the same etherwarp, so the second replaces the first instead
		// of being chained onto it. Chaining them is what sent the camera a
		// whole extra warp down the same line and left it there until the
		// resync pulled it back, and only ever within reach, because past that
		// there is no block to offer the click to.
		val now = System.currentTimeMillis()
		val sameClick = now - lastPredictedAt < SAME_CLICK_MILLIS && pending.isNotEmpty()

		// From where the server has you, not from where you are on screen. The
		// two are a fraction of a block apart the whole time you are moving,
		// and the etherwarp is cast from the server's one.
		val known = player as LocalPlayerAccessor
		val chainedFrom = if (sameClick) pending.elementAtOrNull(pending.size - 2) else pending.lastOrNull()
		val from = chainedFrom?.feet
			?: Vec3(known.`cryptic$serverX`(), known.`cryptic$serverY`(), known.`cryptic$serverZ`())

		val target = EtherwarpHelper.target(from, lookVector(yaw, pitch), range)
		// A second reading that lands nowhere leaves the first one standing:
		// the click was still made, and the earlier guess is the better of the
		// two answers available.
		if (!target.valid) return
		val pos = target.pos ?: return

		if (sameClick) pending.removeLast()
		lastPredictedAt = now
		pending.addLast(
			Pending(
				EtherwarpHelper.landing(pos),
				now + Etherwarp.resyncTimeout.value.toLong(),
			),
		)
	}

	/**
	 * Whether the click is going to open something rather than teleport.
	 *
	 * Hypixel lets a crouched click reach a chest or a lever, and when it does
	 * the etherwarp does not happen. The same list NoammAddons checks.
	 */
	private fun opensSomething(): Boolean {
		val client = Minecraft.getInstance()
		val level = client.level ?: return false
		val hit = client.hitResult as? BlockHitResult ?: return false
		if (hit.type != HitResult.Type.BLOCK) return false

		return when (level.getBlockState(hit.blockPos).block) {
			is ChestBlock, is EnderChestBlock, is HopperBlock, is AbstractCauldronBlock,
			is LeverBlock, is ButtonBlock, is TrapDoorBlock,
			-> true
			else -> false
		}
	}

	/**
	 * Where the camera belongs this frame, or null to leave it on the player.
	 *
	 * The eyes rather than the feet, because this replaces the position the
	 * camera was about to be given, which is already an eye position.
	 */
	@JvmStatic
	fun cameraPosition(): Vec3? {
		if (!Etherwarp.module.enabled || !Etherwarp.fakeZpew.value) return null
		purge()
		val player = Minecraft.getInstance().player ?: return null
		// The last one, not the first: with two warps in flight the camera
		// belongs where the pair of them ends up.
		val destination = pending.lastOrNull() ?: return null
		return destination.feet.add(0.0, player.eyeHeight.toDouble(), 0.0)
	}

	/**
	 * The server's teleport, arriving. Retires the prediction it confirms, and
	 * remembers where the head was pointing so [afterServerTeleport] can put it
	 * back.
	 *
	 * Packet handlers run twice — once on the network thread, which throws
	 * itself out and reschedules, and once on the client thread — so this only
	 * answers the second.
	 */
	@JvmStatic
	fun beforeServerTeleport() {
		val client = Minecraft.getInstance()
		if (!client.isSameThread) return
		val player = client.player ?: return

		val ours = pending.removeFirstOrNull() != null
		restoreRotation = ours && Etherwarp.module.enabled && Etherwarp.noRotate.value
		if (!restoreRotation) return

		savedYRot = player.yRot
		savedXRot = player.xRot
		savedYRotO = player.yRotO
		savedXRotO = player.xRotO
		savedHeadRot = player.yHeadRot
		savedHeadRotO = player.yHeadRotO
	}

	/**
	 * Puts the head back, after the client has accepted the teleport.
	 *
	 * The acceptance the client just sent carries the server's own rotation, so
	 * the server is answered with what it asked for and only the view is kept;
	 * the next movement packet tells it where you are really looking.
	 */
	@JvmStatic
	fun afterServerTeleport() {
		if (!restoreRotation) return
		restoreRotation = false

		val player = Minecraft.getInstance().player ?: return
		player.yRot = savedYRot
		player.xRot = savedXRot
		// The previous tick's angles as well, or the frame in between is drawn
		// halfway through a turn that never happened.
		player.yRotO = savedYRotO
		player.xRotO = savedXRotO
		player.setYHeadRot(savedHeadRot)
		player.yHeadRotO = savedHeadRotO
	}

	private fun purge() {
		val now = System.currentTimeMillis()
		while (pending.isNotEmpty() && pending.first().expiresAt <= now) pending.removeFirst()
	}

	/** The same view vector [net.minecraft.world.entity.Entity] builds from its angles. */
	private fun lookVector(yaw: Float, pitch: Float): Vec3 {
		val pitchRadians = pitch * PI / 180.0
		val yawRadians = -yaw * PI / 180.0
		val cosPitch = cos(pitchRadians)
		return Vec3(sin(yawRadians) * cosPitch, -sin(pitchRadians), cos(yawRadians) * cosPitch)
	}
}
