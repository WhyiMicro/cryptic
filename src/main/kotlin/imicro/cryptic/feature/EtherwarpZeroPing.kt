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
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
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
 * Makes a teleport arrive before the server says so.
 *
 * Ported from NoammAddons' NoRotate (CC0), which covers the same three
 * teleports with the same two switches each: an **etherwarp**, the **Instant
 * Transmission** on an Aspect of the End or Void, and the **Wither Impact**
 * teleport on a Hyperion and its siblings. A teleport costs a round trip — the
 * click goes up, and only when the position packet comes back does anything
 * move — and both halves of this fill that gap from the client's own prediction
 * of where it lands.
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
 * client has answered.
 *
 * A prediction is only ever a guess, so it is kept for the resync timeout and
 * no longer; if the teleport never happens the camera falls back to the player.
 */
object EtherwarpZeroPing {
	/** The three teleports, each switched on its own in the Etherwarp card. */
	enum class Kind { ETHERWARP, TRANSMISSION, IMPACT }

	private const val TRANSMISSION_RANGE = 8.0
	private const val IMPACT_RANGE = 10.0

	/** Hypixel takes a Wither Impact about eight times a second and no faster. */
	private const val IMPACT_GAP_MILLIS = 125L

	/** Below a tenth of the bar there is not the mana for any of them. NoammAddons' rule. */
	private const val MANA_NEEDED = 0.1
	private const val MANA_STALE_MILLIS = 3_000L

	/** The three scrolls that make a sword's right click Wither Impact. */
	private val IMPACT_SCROLLS = listOf("SHADOW_WARP_SCROLL", "IMPLOSION_SCROLL", "WITHER_SHIELD_SCROLL")

	/** Rooms Hypixel does not let you teleport about in. */
	private val NO_TELEPORT_ROOMS = setOf("New Trap", "Old Trap", "Teleport Maze", "Boulder")

	private val FORMATTING = Regex("§.")
	private val MANA = Regex("""([\d,]+)/([\d,]+)✎""")
	private val OVERFLOW = Regex("""([\d,]+)ʬ""")

	/** One predicted landing: where the feet end up, and when to stop believing it. */
	private class Pending(val kind: Kind, val feet: Vec3, var expiresAt: Long, val hardExpiresAt: Long)

	/**
	 * Predictions still waiting for their teleport, oldest first.
	 *
	 * A list rather than a single value because teleports chain: a second click
	 * can be sent before the first has come back, and each one is aimed from
	 * where the one before it is taking you.
	 *
	 * Only ever touched from the client thread — every entry point checks.
	 */
	private val pending = ArrayDeque<Pending>()

	/** When the last prediction was made, which is how a click is told from two. */
	private var lastPredictedAt = 0L
	private var lastImpactAt = 0L

	/**
	 * How close together two predictions have to be to be one click.
	 *
	 * The game sends both of a click's packets in the same tick, microseconds
	 * apart; nobody chains two real teleports inside a twentieth of a second.
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

		// A prediction belongs to the world it was made in, and a warp is a
		// perfectly ordinary way to leave one.
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> pending.clear() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> pending.clear() }
		// Mana is only ever written on the action bar.
		ClientReceiveMessageEvents.GAME.register { message, overlay -> if (overlay && armed) readMana(message.string) }
	}

	/** True while either half wants to know which dungeon floor this is. */
	val needsFloorTracking: Boolean
		get() = armed

	private fun showsEarly(kind: Kind): Boolean = when (kind) {
		Kind.ETHERWARP -> Etherwarp.fakeZpew.value
		Kind.TRANSMISSION -> Etherwarp.fakeZpewTransmission.value
		Kind.IMPACT -> Etherwarp.fakeZpewImpact.value
	}

	private fun keepsRotation(kind: Kind): Boolean = when (kind) {
		Kind.ETHERWARP -> Etherwarp.noRotate.value
		Kind.TRANSMISSION -> Etherwarp.noRotateTransmission.value
		Kind.IMPACT -> Etherwarp.noRotateImpact.value
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

	/** True when the bar is known and too empty; an unknown bar is given the benefit of the doubt. */
	private fun outOfMana(): Boolean =
		System.currentTimeMillis() - manaSeenAt < MANA_STALE_MILLIS && maxMana > 0 && mana < maxMana * MANA_NEEDED

	/**
	 * Which teleport a right click with [stack] is, and how far it goes.
	 *
	 * The same item is two of them: a merged Aspect etherwarps while you sneak
	 * and transmits while you do not.
	 */
	private fun classify(stack: ItemStack, sneaking: Boolean): Pair<Kind, Double>? {
		if (stack.isEmpty) return null
		val id = SkyblockItem.id(stack)
		val data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()

		if (id == "ASPECT_OF_THE_VOID" || id == "ASPECT_OF_THE_END") {
			if (sneaking) EtherwarpHelper.etherwarpRange(stack)?.let { return Kind.ETHERWARP to it }
			return Kind.TRANSMISSION to TRANSMISSION_RANGE + data.getByteOr("tuned_transmission", 0).toInt()
		}

		val scrolls = data.get("ability_scroll")?.toString() ?: return null
		return if (IMPACT_SCROLLS.all { it in scrolls }) Kind.IMPACT to IMPACT_RANGE else null
	}

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

		// Sneaking is what arms a merged Aspect's etherwarp; without it the same
		// click is its Instant Transmission.
		val (kind, range) = classify(player.mainHandItem, client.options.keyShift.isDown) ?: return
		if (!showsEarly(kind) && !keepsRotation(kind)) return

		// Hypixel refuses every teleport in Goldor's tower, so a click there is
		// not one however good the target looks.
		if (DungeonLocation.inFloor7 && DungeonRun.inBoss) return
		if (DungeonMap.currentRoom()?.data?.name in NO_TELEPORT_ROOMS) return
		if (outOfMana()) return
		if (opensSomething() || client.hitResult is EntityHitResult) return

		purge()

		// One click on a block within arm's reach sends two packets: the game
		// offers the click to the block first, and when the block has no use
		// for it sends the plain use as well. Both are the same teleport, so the
		// second replaces the first instead of being chained onto it. Chaining
		// them is what sent the camera a whole extra warp down the same line and
		// left it there until the resync pulled it back, and only ever within
		// reach, because past that there is no block to offer the click to.
		val now = System.currentTimeMillis()
		val sameClick = now - lastPredictedAt < SAME_CLICK_MILLIS && pending.isNotEmpty()

		if (kind == Kind.IMPACT && !sameClick) {
			if (now - lastImpactAt <= IMPACT_GAP_MILLIS) return
			lastImpactAt = now
		}

		// From where the server has you, not from where you are on screen. The
		// two are a fraction of a block apart the whole time you are moving,
		// and the teleport is cast from the server's one.
		val known = player as LocalPlayerAccessor
		val chainedFrom = if (sameClick) pending.elementAtOrNull(pending.size - 2) else pending.lastOrNull()
		val from = chainedFrom?.feet
			?: Vec3(known.`cryptic$serverX`(), known.`cryptic$serverY`(), known.`cryptic$serverZ`())

		// A second reading that lands nowhere leaves the first one standing:
		// the click was still made, and the earlier guess is the better of the
		// two answers available.
		val look = lookVector(yaw, pitch)
		val feet = when (kind) {
			Kind.ETHERWARP -> {
				val target = EtherwarpHelper.target(from, look, range)
				if (!target.valid) return
				EtherwarpHelper.landing(target.pos ?: return)
			}
			else -> InstantTransmissionHelper.predict(range, from, look) ?: return
		}

		if (sameClick) pending.removeLast()
		lastPredictedAt = now
		val timeout = Etherwarp.resyncTimeout.value.toLong()
		pending.addLast(Pending(kind, feet, now + timeout, now + timeout + Etherwarp.maxLagWait.value.toLong()))
	}

	/**
	 * Whether the click is going to open something rather than teleport.
	 *
	 * Hypixel lets a click reach a chest or a lever, and when it does the
	 * teleport does not happen. The same list NoammAddons checks.
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
	 * camera was about to be given, which is already an eye position. The
	 * height is handed in rather than read off the player: the camera keeps its
	 * own, eased between ticks, so crouching lowers the view over a few frames
	 * instead of not at all.
	 */
	@JvmStatic
	fun cameraPosition(eyeHeight: Float): Vec3? {
		if (!Etherwarp.module.enabled) return null
		purge()
		// The last one, not the first: with two teleports in flight the camera
		// belongs where the pair of them ends up.
		val destination = pending.lastOrNull() ?: return null
		if (!showsEarly(destination.kind)) return null
		return destination.feet.add(0.0, eyeHeight.toDouble(), 0.0)
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

		val ours = pending.removeFirstOrNull()
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

	/**
	 * Drops predictions the server has had long enough to answer.
	 *
	 * While the server is not ticking at all, that time is not the prediction's
	 * fault: the warp has been sent and the answer is stuck in the same freeze
	 * as everything else. So the countdown does not merely pause, it starts
	 * over — the server owes an answer from the moment it is running again, not
	 * from whatever was left on the clock when it stopped. What happened
	 * without this is worth naming: the timeout ran out mid-freeze, the camera
	 * was pulled back, and then the real teleport arrived and threw it forward
	 * again, which is the one thing zero ping exists to avoid.
	 *
	 * There is still a limit, set by [Etherwarp.maxLagWait], because a server
	 * that never answers must not leave the camera somewhere the player is not.
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

	/**
	 * How long the server may be silent before it counts as a freeze rather
	 * than an ordinary gap between ticks.
	 */
	private const val STALL_MILLIS = 300L

	/** The same view vector [net.minecraft.world.entity.Entity] builds from its angles. */
	private fun lookVector(yaw: Float, pitch: Float): Vec3 {
		val pitchRadians = pitch * PI / 180.0
		val yawRadians = -yaw * PI / 180.0
		val cosPitch = cos(pitchRadians)
		return Vec3(sin(yawRadians) * cosPitch, -sin(pitchRadians), cos(yawRadians) * cosPitch)
	}
}
