package imicro.cryptic.feature

import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.mixin.LocalPlayerAccessor
import imicro.cryptic.skyblock.SkyblockItem
import imicro.cryptic.terminal.ServerTicks
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.block.AbstractCauldronBlock
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.EnderChestBlock
import net.minecraft.world.level.block.HopperBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Makes a teleport arrive before the server says so: etherwarp, Instant Transmission (Aspect of the
 * End or Void), and Wither Impact, each with two switches. Ported from NoammAddons' NoRotate.
 *
 * **Fake zpew** puts the camera at the predicted landing as soon as the click is sent; the player
 * stays where the server has them, and when the teleport lands nothing visibly changes.
 *
 * **No rotate**: Hypixel's teleport confirmation can carry a rotation, which would undo however far
 * you turned while waiting, so your rotation is put back after the client accepts.
 *
 * A prediction is kept for the resync timeout only; if no teleport comes, the camera returns to the
 * player.
 */
object EtherwarpZeroPing {
	/**
	 * The teleports a click can be. The first three have their own Fake zpew and No rotate
	 * switches; Sinrecall and the Leech's are only predicted for Smooth teleport.
	 */
	enum class Kind { ETHERWARP, TRANSMISSION, IMPACT, SINRECALL, WEIRD }

	private const val TRANSMISSION_RANGE = 8.0
	private const val IMPACT_RANGE = 10.0
	private const val SINRECALL_RANGE = 4.0
	private val LEECH_RANGES = mapOf("ASPECT_OF_THE_LEECH_1" to 3.0, "ASPECT_OF_THE_LEECH_2" to 4.0)

	/** Hypixel takes a Wither Impact about eight times a second at most. */
	private const val IMPACT_GAP_MILLIS = 125L

	/** Below a tenth of the bar there isn't mana for any of them (NoammAddons' rule). */
	private const val MANA_NEEDED = 0.1
	private const val MANA_STALE_MILLIS = 3_000L

	/** The three scrolls that make a sword's right click Wither Impact. */
	private val IMPACT_SCROLLS = listOf("SHADOW_WARP_SCROLL", "IMPLOSION_SCROLL", "WITHER_SHIELD_SCROLL")

	/** Rooms Hypixel doesn't allow teleports in. */
	private val NO_TELEPORT_ROOMS = setOf("New Trap", "Old Trap", "Teleport Maze", "Boulder")

	/** Where outside dungeons Hypixel refuses teleports: the Glacite mineshafts. */
	private const val NO_TELEPORT_AREA = "Mineshaft"

	/** Zones, from the sidebar, where Hypixel refuses teleports. */
	private const val JUNGLE_TEMPLE = "Jungle Temple"
	private const val DOJO = "Dojo"

	/** On someone else's island the teleport abilities don't work. */
	private const val PRIVATE_ISLAND = "Private Island"
	private const val YOUR_ISLAND = "Your Island"

	private val FORMATTING = Regex("§.")
	private val MANA = Regex("""([\d,]+)/([\d,]+)✎""")
	private val OVERFLOW = Regex("""([\d,]+)ʬ""")

	/** One predicted landing and when to stop believing it. */
	private class Pending(val kind: Kind, val feet: Vec3, var expiresAt: Long, val hardExpiresAt: Long)

	/**
	 * Predictions waiting for their teleport, oldest first. Teleports chain: a click can be sent
	 * before the last came back, each aimed from where the one before lands. Client thread only.
	 */
	private val pending = ArrayDeque<Pending>()

	/**
	 * Where the server has you and which way you face: the last position and rotation the client
	 * sent. Not the player's own record, which only catches up on its next tick after a teleport is
	 * accepted, so a click in between was predicted from before the last teleport.
	 */
	private var sentFeet: Vec3? = null
	private var sentYaw: Float? = null
	private var sentPitch: Float? = null

	private fun forgetSent() {
		sentFeet = null
		sentYaw = null
		sentPitch = null
	}

	/** Where the server has your feet, for the teleport log. */
	val serverFeet: Vec3? get() = sentFeet

	/** When the last prediction was made, to tell one click from two. */
	private var lastPredictedAt = 0L
	private var lastImpactAt = 0L

	/**
	 * How close two predictions must be to be one click: both of a click's packets go out in the
	 * same tick, and nobody chains real teleports within a twentieth of a second.
	 */
	private const val SAME_CLICK_MILLIS = 50L

	/** What the action bar last said about mana, and when. */
	private var mana = 0
	private var maxMana = 0
	private var manaSeenAt = 0L

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

		// Predictions belong to the world they were made in.
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> pending.clear(); forgetSent() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> pending.clear(); forgetSent() }
		// Mana is only shown on the action bar.
		ClientReceiveMessageEvents.GAME.register { message, overlay -> if (overlay && (armed || SmoothTeleport.predictive)) readMana(message.string) }
	}

	val needsFloorTracking: Boolean
		get() = armed

	private fun showsEarly(kind: Kind): Boolean = when (kind) {
		Kind.ETHERWARP -> Etherwarp.fakeZpew.value
		Kind.TRANSMISSION -> Etherwarp.fakeZpewTransmission.value
		Kind.IMPACT -> Etherwarp.fakeZpewImpact.value
		Kind.SINRECALL, Kind.WEIRD -> false
	}

	private fun keepsRotation(kind: Kind): Boolean = when (kind) {
		Kind.ETHERWARP -> Etherwarp.noRotate.value
		Kind.TRANSMISSION -> Etherwarp.noRotateTransmission.value
		Kind.IMPACT -> Etherwarp.noRotateImpact.value
		Kind.SINRECALL, Kind.WEIRD -> false
	}

	private val armed: Boolean
		get() = Etherwarp.module.enabled && Kind.entries.any { showsEarly(it) || keepsRotation(it) }

	private fun readMana(raw: String) {
		val text = raw.replace(FORMATTING, "")
		val match = MANA.find(text) ?: return
		val overflow = OVERFLOW.find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: 0
		mana = (match.groupValues[1].replace(",", "").toIntOrNull() ?: return) + overflow
		maxMana = match.groupValues[2].replace(",", "").toIntOrNull() ?: return
		manaSeenAt = System.currentTimeMillis()
	}

	/** True when the mana bar is known and too low; unknown is given the benefit of the doubt. */
	internal fun outOfMana(): Boolean =
		System.currentTimeMillis() - manaSeenAt < MANA_STALE_MILLIS && maxMana > 0 && mana < maxMana * MANA_NEEDED

	/**
	 * Which teleport a right click with [stack] is, and its range. A merged Aspect etherwarps while
	 * sneaking and transmits otherwise.
	 */
	internal fun classify(stack: ItemStack, sneaking: Boolean): Pair<Kind, Double>? {
		if (stack.isEmpty) return null
		val id = SkyblockItem.id(stack)
		val data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()

		if (id == "ASPECT_OF_THE_VOID" || id == "ASPECT_OF_THE_END") {
			if (sneaking) EtherwarpHelper.etherwarpRange(stack)?.let { return Kind.ETHERWARP to it }
			return Kind.TRANSMISSION to TRANSMISSION_RANGE + data.getByteOr("tuned_transmission", 0).toInt()
		}

		if (id == "SINSEEKER_SCYTHE") return Kind.SINRECALL to SINRECALL_RANGE + data.getByteOr("tuned_transmission", 0).toInt()
		LEECH_RANGES[id]?.let { return Kind.WEIRD to it }

		val scrolls = data.get("ability_scroll")?.toString() ?: return null
		return if (IMPACT_SCROLLS.all { it in scrolls }) Kind.IMPACT to IMPACT_RANGE else null
	}

	/**
	 * Every packet the client sends: a teleport is first knowable when its click goes out. Sending
	 * can happen on any thread, so world reads are limited to the client thread (where uses are
	 * always sent).
	 */
	@JvmStatic
	fun onPacketSent(packet: Packet<*>) {
		val client = Minecraft.getInstance()
		if (imicro.cryptic.debug.TeleportLog.recording && client.isSameThread) {
			when (packet) {
				is ServerboundUseItemPacket -> imicro.cryptic.debug.TeleportLog.click("use", packet.yRot, packet.xRot)
				is ServerboundUseItemOnPacket -> (client.player as? LocalPlayerAccessor)?.let {
					imicro.cryptic.debug.TeleportLog.click("useOn", it.`cryptic$serverYRot`(), it.`cryptic$serverXRot`())
				}
				else -> Unit
			}
		}
		if (packet is ServerboundMovePlayerPacket) {
			if (packet.hasPosition()) sentFeet = Vec3(packet.getX(0.0), packet.getY(0.0), packet.getZ(0.0))
			if (packet.hasRotation()) {
				sentYaw = packet.getYRot(0f)
				sentPitch = packet.getXRot(0f)
			}
		}
		if (!armed) return
		if (!client.isSameThread) return

		when (packet) {
			// The packet's rotation is what the server warps from, which can differ from the
			// screen's if the mouse moved since the tick.
			is ServerboundUseItemPacket -> predict(packet.yRot, packet.xRot)
			// A click on a block in reach sends this instead, with no rotation, so the last
			// rotation sent is what the server uses.
			is ServerboundUseItemOnPacket -> client.player?.let { player ->
				val known = player as LocalPlayerAccessor
				predict(sentYaw ?: known.`cryptic$serverYRot`(), sentPitch ?: known.`cryptic$serverXRot`())
			}
			else -> Unit
		}
	}

	private fun predict(yaw: Float, pitch: Float) {
		val client = Minecraft.getInstance()
		val player = client.player ?: return
		if (player.isPassenger) return

		// Sneaking arms a merged Aspect's etherwarp; otherwise it's Instant Transmission.
		val (kind, range) = classify(player.mainHandItem, client.options.keyShift.isDown) ?: return imicro.cryptic.debug.TeleportLog.skipped("not a teleport item")
		if (!showsEarly(kind) && !keepsRotation(kind)) return imicro.cryptic.debug.TeleportLog.skipped("$kind: nothing wants it predicted")

		// Hypixel refuses teleports in Goldor's tower.
		if (DungeonLocation.inFloor7 && DungeonRun.inBoss) return imicro.cryptic.debug.TeleportLog.skipped("Floor 7 boss")
		if (DungeonMap.currentRoom()?.data?.name in NO_TELEPORT_ROOMS) return imicro.cryptic.debug.TeleportLog.skipped("no-teleport room")
		if (imicro.cryptic.skyblock.SkyblockLocation.area == NO_TELEPORT_AREA) return imicro.cryptic.debug.TeleportLog.skipped("mineshaft")
		noTeleportZone()?.let { return imicro.cryptic.debug.TeleportLog.skipped(it) }
		if (outOfMana()) return imicro.cryptic.debug.TeleportLog.skipped("out of mana: $mana/$maxMana")
		if (opensSomething() || client.hitResult is EntityHitResult) return imicro.cryptic.debug.TeleportLog.skipped("aiming at something clickable")

		purge()

		// A click on a block in reach sends two packets (the block first, then the plain use). Both
		// are one teleport, so the second replaces the first instead of chaining, which used to
		// send the camera an extra warp along the same line.
		val now = System.currentTimeMillis()
		val sameClick = now - lastPredictedAt < SAME_CLICK_MILLIS && pending.isNotEmpty()

		if (kind == Kind.IMPACT && !sameClick) {
			if (now - lastImpactAt <= IMPACT_GAP_MILLIS) return imicro.cryptic.debug.TeleportLog.skipped("Wither Impact cooldown")
			lastImpactAt = now
		}

		// Cast from where the server has you, which differs slightly from the screen while moving.
		val known = player as LocalPlayerAccessor
		val chainedFrom = if (sameClick) pending.elementAtOrNull(pending.size - 2) else pending.lastOrNull()
		val from = chainedFrom?.feet
			?: sentFeet
			?: Vec3(known.`cryptic$serverX`(), known.`cryptic$serverY`(), known.`cryptic$serverZ`())

		// A second reading that lands nowhere leaves the first one standing.
		val look = lookVector(yaw, pitch)
		val feet = when (kind) {
			Kind.ETHERWARP -> {
				val target = EtherwarpHelper.target(from, look, range)
				if (!target.valid) return imicro.cryptic.debug.TeleportLog.skipped("etherwarp target not valid")
				EtherwarpHelper.landing(target.pos ?: return)
			}
			else -> InstantTransmissionHelper.predict(range, from, look) ?: return imicro.cryptic.debug.TeleportLog.skipped("no landing found")
		}
		imicro.cryptic.debug.TeleportLog.predicted(kind, from, feet, sameClick, pending.size)

		if (sameClick) pending.removeLast()
		lastPredictedAt = now
		val timeout = Etherwarp.resyncTimeout.value.toLong()
		pending.addLast(Pending(kind, feet, now + timeout, now + timeout + Etherwarp.maxLagWait.value.toLong()))
	}

	/** The reason teleports don't work here, from the sidebar zone, or null. */
	internal fun noTeleportZone(): String? {
		val area = imicro.cryptic.skyblock.SkyblockLocation.area
		val zone = imicro.cryptic.skyblock.SkyblockLocation.zone() ?: return null
		return when {
			zone == JUNGLE_TEMPLE -> "Jungle Temple"
			zone.startsWith(DOJO) -> "Dojo"
			area == PRIVATE_ISLAND && zone != YOUR_ISLAND -> "visiting an island"
			else -> null
		}
	}

	/**
	 * Whether the click will open something instead of teleporting (Hypixel lets clicks reach
	 * chests and levers). NoammAddons' list.
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
	 * Where the camera belongs this frame, or null. Eyes rather than feet, since this replaces an
	 * eye position. The camera's own eased eye height is passed in, so crouching lowers the view
	 * smoothly.
	 */
	@JvmStatic
	fun cameraPosition(eyeHeight: Float): Vec3? {
		if (!Etherwarp.module.enabled) return null
		purge()
		// The last one: with two in flight, the camera belongs where both end up.
		val destination = pending.lastOrNull() ?: return null
		if (!showsEarly(destination.kind)) return null
		return destination.feet.add(0.0, eyeHeight.toDouble(), 0.0)
	}

	/**
	 * The server's teleport arriving: retires the prediction it answers and saves the head's
	 * rotation for [afterServerTeleport]. Handlers run twice; only the client-thread pass counts.
	 */
	@JvmStatic
	fun beforeServerTeleport(packet: ClientboundPlayerPositionPacket) {
		val client = Minecraft.getInstance()
		if (!client.isSameThread) return
		val player = client.player ?: return
		restoreRotation = false

		val answered = answered(player, packet)
		lastWasTeleport = answered != NOT_A_TELEPORT
		if (!lastWasTeleport) return

		// The prediction it answers, and any earlier ones that never got an answer.
		var ours: Pending? = null
		repeat(if (answered == UNMATCHED) 1 else answered + 1) { ours = pending.removeFirstOrNull() }
		restoreRotation = ours != null && Etherwarp.module.enabled && keepsRotation(ours.kind)
		if (!restoreRotation) return

		savedYRot = player.yRot
		savedXRot = player.xRot
		savedYRotO = player.yRotO
		savedXRotO = player.xRotO
		savedHeadRot = player.yHeadRot
		savedHeadRotO = player.yHeadRotO
	}

	/**
	 * Which waiting prediction [packet] answers, by queue position: the first whose landing it puts
	 * you on, however short (a transmission into a wall moves one block). [UNMATCHED] when it lands
	 * nowhere predicted but moves further than any correction (a mispredicted teleport);
	 * [NOT_A_TELEPORT] otherwise.
	 */
	private fun answered(player: net.minecraft.world.entity.player.Player, packet: ClientboundPlayerPositionPacket): Int {
		val now = PositionMoveRotation.of(player)
		val then = PositionMoveRotation.calculateAbsolute(now, packet.change(), packet.relatives()).position()
		val match = pending.indexOfFirst { then.distanceTo(it.feet) <= LANDING_MATCH }
		if (match >= 0) return match
		return if (then.distanceTo(now.position()) >= TELEPORT_DISTANCE) UNMATCHED else NOT_A_TELEPORT
	}

	private const val UNMATCHED = -1
	private const val NOT_A_TELEPORT = -2

	private const val LANDING_MATCH = 1.0

	private const val TELEPORT_DISTANCE = 1.5

	@JvmStatic
	var lastWasTeleport = false
		private set

	fun unanswered(): Int {
		purge()
		return pending.size
	}

	/**
	 * Restores the head after the client has accepted the teleport. The acceptance carried the
	 * server's rotation, so only the view changes; the next movement packet reports where you
	 * really look.
	 */
	@JvmStatic
	fun afterServerTeleport() {
		val client = Minecraft.getInstance()
		if (!client.isSameThread) return
		// Accepting just told the server where you are, facing the way it asked.
		client.player?.let { player ->
			sentFeet = player.position()
			sentYaw = player.yRot
			sentPitch = player.xRot
		}
		if (!restoreRotation) return
		restoreRotation = false

		val player = Minecraft.getInstance().player ?: return
		player.yRot = savedYRot
		player.xRot = savedXRot
		// Last tick's angles too, or the frame between shows a turn that never happened.
		player.yRotO = savedYRotO
		player.xRotO = savedXRotO
		player.setYHeadRot(savedHeadRot)
		player.yHeadRotO = savedHeadRotO
	}

	/**
	 * Drops predictions the server has had long enough to answer. While the server isn't ticking at
	 * all, the countdown restarts rather than pausing, since the answer is stuck in the freeze;
	 * otherwise the camera was pulled back mid-freeze and thrown forward when the teleport arrived.
	 * [Etherwarp.maxLagWait] still caps it, so an unanswered prediction can't strand the camera.
	 */
	private fun purge() {
		val now = System.currentTimeMillis()

		if (Etherwarp.waitOutLag.value && pending.isNotEmpty()) {
			val silence = ServerTicks.sinceLastTick ?: 0L
			if (silence > STALL_MILLIS) {
				val timeout = Etherwarp.resyncTimeout.value.toLong()
				pending.forEach { it.expiresAt = minOf(now + timeout, it.hardExpiresAt) }
			}
		}

		while (pending.isNotEmpty() && (pending.first().expiresAt <= now || pending.first().hardExpiresAt <= now)) {
			pending.removeFirst()
		}
	}

	/** How long the server may be silent before it counts as a freeze. */
	private const val STALL_MILLIS = 300L

	/** The view vector [net.minecraft.world.entity.Entity] builds from its angles. */
	private fun lookVector(yaw: Float, pitch: Float): Vec3 {
		val pitchRadians = pitch * PI / 180.0
		val yawRadians = -yaw * PI / 180.0
		val cosPitch = cos(pitchRadians)
		return Vec3(sin(yawRadians) * cosPitch, -sin(pitchRadians), cos(yawRadians) * cosPitch)
	}
}
