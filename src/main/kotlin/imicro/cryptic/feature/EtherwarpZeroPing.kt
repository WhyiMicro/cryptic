package imicro.cryptic.feature

import imicro.cryptic.debug.TeleportLog
import imicro.cryptic.dungeon.DungeonLocation
import imicro.cryptic.dungeon.DungeonRun
import imicro.cryptic.dungeon.map.DungeonFloor
import imicro.cryptic.mixin.LocalPlayerAccessor
import imicro.cryptic.skyblock.SkyblockItem
import imicro.cryptic.terminal.ServerTicks
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.block.AbstractCauldronBlock
import net.minecraft.world.level.block.AbstractSkullBlock
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.EnderChestBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.HopperBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.SignBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Makes a teleport arrive before the server says so: etherwarp, Instant Transmission (Aspect of the
 * End or Void), and Wither Impact, each with two switches. Ported from NoammAddons' NoRotate; the
 * re-chaining, refusal lines and ping-timed expiry follow ideas from ADM's Legit Zpew.
 *
 * **Fake zpew** puts the camera at the predicted landing as soon as the click is sent; the player
 * stays where the server has them, and when the teleport lands nothing visibly changes.
 *
 * **No rotate**: Hypixel's teleport confirmation can carry a rotation, which would undo however far
 * you turned while waiting, so your rotation is put back after the client accepts.
 *
 * Clicks waiting on the server are kept with their look, not just their landing: the server casts
 * each from wherever the one before put you, so whenever a real landing (or a refusal) comes in,
 * the rest of the chain is cast again from there.
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
	private val LEECH_RANGES = mapOf(
		"ASPECT_OF_THE_LEECH_1" to 3.0,
		"ASPECT_OF_THE_LEECH_2" to 4.0,
		"ASPECT_OF_THE_LEECH_3" to 5.0,
	)

	/** Hypixel takes one Wither Impact per tenth of a second; faster clicks are dropped. */
	private const val IMPACT_GAP_MILLIS = 100L

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

	/** Chat lines that mean the oldest click was refused outright. */
	private val REFUSALS = listOf(
		"You cannot use abilities in this room!",
		"You cannot do that in this room!",
		"A mystical force in this room prevents you from using that ability!",
	)

	/**
	 * Also sent just before a blink a wall cut short, which still moves you (up to four ticks
	 * later for Wither Impact), so it only marks the click until no move follows.
	 */
	private const val BLOCKED = "There are blocks in the way!"
	private const val BLOCKED_GRACE_TICKS = 1L
	private const val BLOCKED_GRACE_TICKS_IMPACT = 5L

	/** One click the server hasn't answered yet. */
	private class Click(
		val kind: Kind,
		val range: Double,
		val yaw: Float,
		val pitch: Float,
		val sneaking: Boolean,
		/** Where the server has you for this click when nothing before it moves you. */
		var from: Vec3,
		val sentAt: Long,
		var expiresAt: Long,
		val hardExpiresAt: Long,
	) {
		/** Where it lands, cast from the end of the chain before it; null when it won't move you. */
		var landing: Vec3? = null

		/**
		 * Still expected to be answered. One that ran out of time stops moving the camera but can
		 * still be matched for a while, in case the answer was only late.
		 */
		var counts = true

		/** The server tick a "blocks in the way" line came for it, or -1. */
		var blockedAt = -1L

		/** Its etherwarp sound was played on the click. */
		var heard = false
	}

	/** Clicks waiting for their teleport, oldest first. Client thread only. */
	private val pending = ArrayDeque<Click>()

	/** How long a click that stopped counting is kept, to match a late answer. */
	private const val KEEP_LATE_MILLIS = 3_000L

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

	private fun forgetAll() {
		pending.clear()
		owedSounds.clear()
		forgetSent()
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

	// ---- Round trip ---------------------------------------------------------------------------
	//
	// Measured from answered clicks the way TCP times its packets: a smoothed round trip and how
	// much it varies. A click unanswered for well past that was dropped by the server.

	private var roundTrip = Double.NaN
	private var roundTripSpread = 0.0
	private var lastAnswerTick = -1L

	private const val EXPIRY_MARGIN_MILLIS = 150.0
	private const val EXPIRY_MIN_MILLIS = 250.0
	private const val EXPIRY_MAX_MILLIS = 2_000.0

	private fun sampleRoundTrip(millis: Double) {
		if (roundTrip.isNaN()) {
			roundTrip = millis
			roundTripSpread = millis / 2
			return
		}
		roundTripSpread = roundTripSpread * 0.75 + abs(millis - roundTrip) * 0.25
		roundTrip = roundTrip * 0.875 + millis * 0.125
	}

	/** How long a click is believed: the timed round trip once there is one, else the setting. */
	private fun timeoutMillis(): Long =
		if (roundTrip.isNaN()) Etherwarp.resyncTimeout.value.toLong()
		else (roundTrip + maxOf(EXPIRY_MARGIN_MILLIS, 4 * roundTripSpread)).coerceIn(EXPIRY_MIN_MILLIS, EXPIRY_MAX_MILLIS).toLong()

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true

		// Predictions belong to the world they were made in.
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> forgetAll() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> forgetAll() }
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

	/** Whether anything wants this kind of click followed. */
	private fun wanted(kind: Kind): Boolean =
		showsEarly(kind) || keepsRotation(kind) || (kind == Kind.ETHERWARP && soundOnClick)

	private val soundOnClick: Boolean
		get() = Etherwarp.soundOnClick.value &&
			imicro.cryptic.gui.Restrictions.allows(imicro.cryptic.gui.Restriction.SEMI_LEGIT)

	private val armed: Boolean
		get() = Etherwarp.module.enabled && Kind.entries.any(::wanted)

	/** Chat and the action bar: mana, and the lines that answer a click without a teleport. */
	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (!armed && !SmoothTeleport.predictive) return
		val text = message.string.replace(FORMATTING, "")
		if (overlay) {
			readMana(text)
			return
		}
		if (pending.isEmpty()) return
		if (REFUSALS.any { text.contains(it) }) {
			val refused = pending.firstOrNull { it.counts } ?: return
			pending.remove(refused)
			TeleportLog.note("refused: $text")
			rechain()
		} else if (text.contains(BLOCKED)) {
			// The cut-short blink this line came with has already landed.
			if (lastAnswerTick == ServerTicks.total) return
			pending.firstOrNull { it.counts && it.blockedAt < 0 }?.blockedAt = ServerTicks.total
		}
	}

	private fun readMana(text: String) {
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
		if (TeleportLog.recording && client.isSameThread) {
			when (packet) {
				is ServerboundUseItemPacket -> TeleportLog.click("use", packet.yRot, packet.xRot)
				is ServerboundUseItemOnPacket -> (client.player as? LocalPlayerAccessor)?.let {
					TeleportLog.click("useOn", it.`cryptic$serverYRot`(), it.`cryptic$serverXRot`())
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
		val known = player as LocalPlayerAccessor

		// The sneak the server has heard of, not the key: they differ on the tick you press it.
		val sneaking = known.`cryptic$lastSentInput`().shift()
		val (kind, range) = classify(player.mainHandItem, sneaking) ?: return TeleportLog.skipped("not a teleport item")
		if (!wanted(kind)) return TeleportLog.skipped("$kind: nothing wants it predicted")

		// Hypixel refuses teleports in Goldor's tower.
		if (DungeonLocation.inFloor7 && DungeonRun.inBoss) return TeleportLog.skipped("Floor 7 boss")
		if (imicro.cryptic.skyblock.SkyblockLocation.area == NO_TELEPORT_AREA) return TeleportLog.skipped("mineshaft")
		noTeleportZone()?.let { return TeleportLog.skipped(it) }
		if (outOfMana()) return TeleportLog.skipped("out of mana: $mana/$maxMana")
		if (opensSomething() || client.hitResult is EntityHitResult) return TeleportLog.skipped("aiming at something clickable")

		purge()

		// A click on a block in reach sends two packets (the block first, then the plain use). Both
		// are one teleport, so the second replaces the first instead of chaining.
		val now = System.currentTimeMillis()
		val sameClick = now - lastPredictedAt < SAME_CLICK_MILLIS && pending.isNotEmpty()

		if (kind == Kind.IMPACT && !sameClick) {
			if (now - lastImpactAt < IMPACT_GAP_MILLIS) return TeleportLog.skipped("Wither Impact cooldown")
			lastImpactAt = now
		}

		val from = sentFeet ?: Vec3(known.`cryptic$serverX`(), known.`cryptic$serverY`(), known.`cryptic$serverZ`())
		val replaced = if (sameClick) pending.removeLast() else null
		lastPredictedAt = now
		val timeout = timeoutMillis()
		val click = Click(kind, range, yaw, pitch, sneaking, from, now, now + timeout, now + timeout + Etherwarp.maxLagWait.value.toLong())
		click.heard = replaced?.heard == true
		pending.addLast(click)
		rechain()

		val landing = click.landing
		if (landing == null) TeleportLog.skipped("$kind lands nowhere from ${chainStart(click)}")
		else TeleportLog.predicted(kind, chainStart(click), landing, sameClick, pending.size)

		if (kind == Kind.ETHERWARP && landing != null && !click.heard && soundOnClick) {
			click.heard = true
			Etherwarp.playTeleportSoundNow()
			owedSounds.addLast(now)
		}
	}

	/** Where [click] is cast from now: the landing before it in the chain, or its own start. */
	private fun chainStart(click: Click): Vec3 {
		var at: Vec3? = null
		for (c in pending) {
			if (c === click) break
			if (c.counts) c.landing?.let { at = it }
		}
		return at ?: click.from
	}

	/** Where [click] lands cast from [from], or null if the server won't move you. */
	private fun land(from: Vec3, click: Click): Vec3? {
		if (blocksTeleports(from)) return null
		val look = lookVector(click.yaw, click.pitch)
		val to = when (click.kind) {
			Kind.ETHERWARP -> {
				val target = EtherwarpHelper.target(from, look, click.range, EtherwarpHelper.SNEAK_EYE_HEIGHT)
				if (!target.valid) return null
				EtherwarpHelper.landing(target.pos ?: return null)
			}
			else -> InstantTransmissionHelper.predict(click.range, from, look, InstantTransmissionHelper.eyeHeight(click.sneaking))
		} ?: return null
		return to.takeUnless { blocksTeleports(it) }
	}

	/** Casts every counting click again, each from where the one before it lands. */
	private fun rechain() {
		var at: Vec3? = null
		for (click in pending) {
			if (!click.counts) continue
			click.landing = land(at ?: click.from, click)
			click.landing?.let { at = it }
		}
	}

	/**
	 * In, or a block from, a room that refuses teleports: the server's room reaches into the
	 * doorway.
	 */
	private fun blocksTeleports(at: Vec3): Boolean {
		if (!DungeonLocation.inDungeon) return false
		for (dx in -1..1) for (dz in -1..1) {
			val room = DungeonFloor.roomAt(DungeonFloor.tileOf(at.x + dx, at.z + dz)) ?: continue
			if (room.data?.name in NO_TELEPORT_ROOMS) return true
		}
		return false
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
	 * Whether the click will open or press something instead of teleporting (Hypixel lets clicks
	 * reach chests, levers and wither essence). NoammAddons' list, with ADM's signs, gates and
	 * heads.
	 */
	private fun opensSomething(): Boolean {
		val client = Minecraft.getInstance()
		val level = client.level ?: return false
		val hit = client.hitResult as? BlockHitResult ?: return false
		if (hit.type != HitResult.Type.BLOCK) return false

		return when (level.getBlockState(hit.blockPos).block) {
			is ChestBlock, is EnderChestBlock, is HopperBlock, is AbstractCauldronBlock,
			is LeverBlock, is ButtonBlock, is TrapDoorBlock,
			is SignBlock, is FenceGateBlock, is AbstractSkullBlock,
			-> true
			else -> false
		}
	}

	/** The end of the chain of counting clicks: where your next teleport is cast from, or null. */
	private fun chainEnd(): Vec3? {
		var at: Vec3? = null
		for (click in pending) if (click.counts) click.landing?.let { at = it }
		return at
	}

	/**
	 * Where the next etherwarp is cast from, for the overlay: the end of the clicks still on their
	 * way while Fake zpew shows them, else null (the player).
	 */
	fun castFrom(): Vec3? {
		if (!Etherwarp.module.enabled) return null
		purge()
		val last = pending.lastOrNull { it.counts && it.landing != null } ?: return null
		return if (showsEarly(last.kind)) chainEnd() else null
	}

	/**
	 * Where the camera belongs this frame, or null. Eyes rather than feet, since this replaces an
	 * eye position. The camera's own eased eye height is passed in, so crouching lowers the view
	 * smoothly.
	 */
	@JvmStatic
	fun cameraPosition(eyeHeight: Float): Vec3? = feetPosition()?.add(0.0, eyeHeight.toDouble(), 0.0)

	/** Where your model stands while Fake zpew shows a landing, for third person; else null. */
	@JvmStatic
	fun feetPosition(): Vec3? {
		if (!Etherwarp.module.enabled) return null
		purge()
		// The last one: with two in flight, the camera belongs where both end up.
		val last = pending.lastOrNull { it.counts && it.landing != null } ?: return null
		if (!showsEarly(last.kind)) return null
		return last.landing
	}

	/**
	 * The server's teleport arriving: retires the click it answers and saves the head's rotation
	 * for [afterServerTeleport]. Handlers run twice; only the client-thread pass counts.
	 */
	@JvmStatic
	fun beforeServerTeleport(packet: ClientboundPlayerPositionPacket) {
		val client = Minecraft.getInstance()
		if (!client.isSameThread) return
		val player = client.player ?: return
		restoreRotation = false

		val now = PositionMoveRotation.of(player)
		val to = PositionMoveRotation.calculateAbsolute(now, packet.change(), packet.relatives()).position()
		val answered = answers(to)
		lastWasTeleport = answered >= 0 || to.distanceTo(now.position()) >= TELEPORT_DISTANCE

		val ours: Click?
		if (answered >= 0) {
			ours = pending[answered]
			sampleRoundTrip((System.currentTimeMillis() - ours.sentAt).toDouble())
			repeat(answered + 1) { pending.removeFirst() }
		} else if (lastWasTeleport) {
			// A teleport no click predicts: taken for the oldest that counts, which mispredicted.
			ours = pending.firstOrNull { it.counts }
			if (ours != null) {
				TeleportLog.note("unmatched landing ${to}; taken for the oldest click")
				repeat(pending.indexOf(ours) + 1) { pending.removeFirst() }
			}
		} else {
			// The server putting you about where you are: what it casts the waiting clicks from.
			ours = null
			if (pending.isNotEmpty()) TeleportLog.note("server resync to $to")
		}

		// The clicks still waiting went out before this reached us, so the server casts them from it.
		if (ours != null || pending.isNotEmpty()) {
			pending.forEach { it.from = to }
			rechain()
		}
		if (ours != null) lastAnswerTick = ServerTicks.total

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
	 * The first waiting click that lands at [to], cast either from its own start (the ones before
	 * it were dropped) or from the chain's (they moved you), or -1.
	 */
	private fun answers(to: Vec3): Int {
		var at: Vec3? = null
		for ((index, click) in pending.withIndex()) {
			val own = land(click.from, click)
			val chained = at?.let { land(it, click) }
			if (own.isNear(to) || chained.isNear(to)) return index
			if (click.counts) (if (at == null) own else chained)?.let { at = it }
		}
		return -1
	}

	private fun Vec3?.isNear(to: Vec3): Boolean = this != null && distanceTo(to) <= LANDING_MATCH

	private const val LANDING_MATCH = 0.6

	private const val TELEPORT_DISTANCE = 1.5

	@JvmStatic
	var lastWasTeleport = false
		private set

	fun unanswered(): Int {
		purge()
		return pending.count { it.counts }
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

	/** A blocked line not followed by its move within the grace is a refusal. */
	fun tick() {
		if (pending.isEmpty()) return
		val oldest = pending.firstOrNull { it.counts } ?: return
		if (oldest.blockedAt < 0) return
		val grace = if (oldest.kind == Kind.IMPACT) BLOCKED_GRACE_TICKS_IMPACT else BLOCKED_GRACE_TICKS
		if (ServerTicks.total - oldest.blockedAt <= grace) return
		pending.remove(oldest)
		TeleportLog.note("blocks in the way: ${oldest.kind} refused")
		rechain()
	}

	/**
	 * Clicks the server has had long enough to answer stop counting. While the server isn't
	 * ticking at all, the countdown restarts rather than pausing, since the answer is stuck in the
	 * freeze. [Etherwarp.maxLagWait] still caps it.
	 */
	private fun purge() {
		if (pending.isEmpty()) return
		val now = System.currentTimeMillis()

		if (Etherwarp.waitOutLag.value) {
			val silence = ServerTicks.sinceLastTick ?: 0L
			if (silence > STALL_MILLIS) {
				val timeout = timeoutMillis()
				pending.forEach { it.expiresAt = minOf(now + timeout, it.hardExpiresAt) }
			}
		}

		var changed = false
		for (click in pending) {
			if (click.counts && (click.expiresAt <= now || click.hardExpiresAt <= now)) {
				click.counts = false
				changed = true
				TeleportLog.note("${click.kind} unanswered after ${now - click.sentAt}ms")
			}
		}
		while (pending.isNotEmpty() && !pending.first().counts && now - pending.first().sentAt > KEEP_LATE_MILLIS) {
			pending.removeFirst()
		}
		if (changed) rechain()
	}

	/** How long the server may be silent before it counts as a freeze. */
	private const val STALL_MILLIS = 300L

	// ---- Sound on click -------------------------------------------------------------------------

	/** Etherwarps whose sound was played on the click, each owed one sound from the server. */
	private val owedSounds = ArrayDeque<Long>()

	private const val OWED_SOUND_MILLIS = 3_000L

	/** True when the server's etherwarp sound was already played on the click, so it's swallowed. */
	fun owesSound(): Boolean {
		val now = System.currentTimeMillis()
		while (owedSounds.isNotEmpty() && now - owedSounds.first() > OWED_SOUND_MILLIS) owedSounds.removeFirst()
		return owedSounds.removeFirstOrNull() != null
	}

	/** The view vector [net.minecraft.world.entity.Entity] builds from its angles. */
	private fun lookVector(yaw: Float, pitch: Float): Vec3 {
		val pitchRadians = pitch * PI / 180.0
		val yawRadians = -yaw * PI / 180.0
		val cosPitch = cos(pitchRadians)
		return Vec3(sin(yawRadians) * cosPitch, -sin(pitchRadians), cos(yawRadians) * cosPitch)
	}
}
