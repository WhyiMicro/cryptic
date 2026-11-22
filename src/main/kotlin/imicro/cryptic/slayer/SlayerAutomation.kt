package imicro.cryptic.slayer

import imicro.cryptic.feature.SlayerFeatures
import imicro.cryptic.mixin.KeyMappingAccessor
import imicro.cryptic.skyblock.SkyblockItem
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Input
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.EntityHitResult
import java.util.ArrayDeque

/**
 * Slayer Features' automation (all of it Client Restrictions' "cheat"): the Steak Stake on whatever
 * can be staked, Holy Ice for Twinclaws, Healing Melon when low, Impel's jumps and sneaks, the Killer
 * Spring's clicks, and the tuba while a boss is up.
 *
 * Items are used from the hotbar: the slot is selected, used in the same tick (the game sends the
 * slot change first) and the old slot is selected again on the next.
 *
 * Facts, not code: the boss's skill stand ("TWINCLAWS 0.4s", "KILLER SPRING"), Impel's prompts as
 * titles ("Impel" with JUMP, SNEAK, CLICK UP or CLICK DOWN) and the Healing Melon come from skydiao
 * (MIT); the Killer Spring's head texture from SkyHanni's repo; the items' timings from their lore in
 * the NEU repo; staking at 20% and Killer Spring's alternating clicks from the community wiki.
 */
object SlayerAutomation {
	private val STAKES = listOf("SHARP_STEAK_STAKE", "STEAK_STAKE")
	private val HOLY_ICE = listOf("HOLY_ICE")
	private val HEALS = listOf("HEALING_MELON")
	private val TUBAS = listOf("WEIRDER_TUBA", "WEIRD_TUBA")

	/** Vampires besides the boss that the stake works on, by a word in their name. */
	private val STAKEABLE = listOf("Thrall", "Splatter", "Fledgling")

	/** A Vampire can be staked from this share of its health. */
	private const val STAKE_SHARE = 0.2

	/** How close to your boss a hit still counts as on it, since the name stand and the body are apart. */
	private const val STAKE_REACH = 2.0

	/** A stake hit's target dying this soon after was the stake's kill. */
	private const val STAKE_KILL_MILLIS = 2_000L

	/** Bloodcrave is kept for the next hit this long, then forgotten. */
	private const val BLOODCRAVE_MILLIS = 20_000L

	/** Healing Melon's 7❤ shows on the health bar after a moment; another bite waits this long. */
	private const val HEAL_GAP_MILLIS = 750L

	/** An Impel order not re-sent for this long is over; the same order after it is a new one. */
	private const val IMPEL_GAP_MILLIS = 600L

	/**
	 * A double stake hit's second counts from this long after the first, once the mob's half second
	 * of invulnerability is nearly over; the stake goes back after [STAKE_DOUBLE_MAX_MILLIS] anyway.
	 */
	private const val STAKE_SECOND_HIT_MILLIS = 450L
	private const val STAKE_DOUBLE_MAX_MILLIS = 1_200L

	/** A subtitle this soon after a title belongs to it. */
	private const val SUBTITLE_JOIN_MILLIS = 250L

	private const val PROMPTS_SHOW = 0
	private const val PROMPTS_HIDE_ALL = 2


	/** A Twinclaws countdown this much above the last reading is the next Twinclaws. */
	private const val CLAWS_RESTART = 0.3

	/** A heal that can't be made is noted in the debug log this often at most. */
	private const val NOTE_GAP_MILLIS = 2_000L

	/** How long a jump or a sneak is held for. */
	private const val JUMP_TICKS = 3
	private const val SNEAK_TICKS = 6

	/** One Killer Spring click per this long. */
	private const val SPRING_CLICK_MILLIS = 150L

	/** The Killer Spring's head (SkyHanni's repo, as a fact). */
	private const val SPRING_SKIN = "77f7a7bc8ac86f23ca7bf98afeb76960227e1832fe209a3026f6ceb8bde74f54"

	/** A tuba's buff and cooldown when its lore can't be read. */

	private val FORMATTING = Regex("§.")
	private val HEALTH = Regex("""([\d.,]+[kM]?)/([\d.,]+[kM]?)❤""")
	private val TWINCLAWS = Regex("""TWINCLAWS (\d+(?:\.\d+)?)s""")
	private val IMPEL = Regex("""Impel:\s*(.+?)(?:\s+(\d+(?:\.\d+)?)s)?$""", RegexOption.IGNORE_CASE)

	// ---- Hotbar use ----------------------------------------------------------------------------

	/** The slot to go back to after a use or the stake, or -1. */
	private var restoreSlot = -1
	private var swappedTo = -1

	/** An item used while the stake is held goes back to the stake, not past it; -1 otherwise. */
	private var useReturn = -1
	private var usedSlot = -1

	/** The first hotbar slot with one of [ids] (in order of preference), or else a name containing one of [names]. */
	private fun hotbarSlot(player: Player, ids: List<String>, names: List<String> = emptyList()): Int? {
		val inventory = player.inventory
		for (id in ids) {
			for (slot in 0 until Inventory.getSelectionSize()) {
				if (SkyblockItem.id(inventory.getItem(slot)) == id) return slot
			}
		}
		for (name in names) {
			for (slot in 0 until Inventory.getSelectionSize()) {
				val stack = inventory.getItem(slot)
				if (!stack.isEmpty && name in stack.hoverName.string.replace(FORMATTING, "")) return slot
			}
		}
		return null
	}

	/**
	 * Uses the first of [ids] (or [names]) in the hotbar and selects the old slot next tick. False if
	 * there's none, or an item is already out for a use.
	 */
	private fun use(client: Minecraft, player: LocalPlayer, ids: List<String>, names: List<String> = emptyList()): Boolean {
		if (useReturn >= 0 || client.gui.screen() != null) return false
		if (restoreSlot >= 0 && stakeTarget == null) return false
		val slot = hotbarSlot(player, ids, names) ?: return false
		val inventory = player.inventory
		val held = inventory.selectedSlot
		if (slot != held) {
			inventory.selectedSlot = slot
			if (stakeTarget != null) {
				useReturn = held
				usedSlot = slot
			} else {
				restoreSlot = held
				swappedTo = slot
			}
		}
		client.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
		recordEvent("used ${inventory.getItem(slot).hoverName.string.replace(FORMATTING, "")} from slot ${slot + 1}")
		return true
	}

	/** Back to the slot held before; left alone if you've picked another one since. */
	private fun restore(player: LocalPlayer) {
		if (useReturn >= 0) {
			if (player.inventory.selectedSlot == usedSlot) player.inventory.selectedSlot = useReturn
			useReturn = -1
			usedSlot = -1
		}
		if (restoreSlot < 0 || stakeTarget != null) return
		if (player.inventory.selectedSlot == swappedTo) player.inventory.selectedSlot = restoreSlot
		restoreSlot = -1
		swappedTo = -1
	}

	// ---- State ---------------------------------------------------------------------------------

	private var stakedId = -1
	private var stakedAt = 0L
	private var bloodcraveAt = 0L

	/** The mob the stake came out for, and when; it's put away [SlayerFeatures.stakeSwapBack] after. */
	private var stakeTarget: Entity? = null
	private var stakeOutAt = 0L

	/** Whether this stake needs a second hit (no Bloodcrave was stored), and whether it's had it. */
	private var stakeDouble = false
	private var stakeSecondHit = false

	private var lastClaws = Double.MAX_VALUE
	private var iceUsed = false

	private var nextHealAt = 0L
	private var healNotedAt = 0L

	/** The Impel order showing ("SNEAK"), when it first showed and was last re-sent, and whether it's done. */
	private var impelCommand = ""
	private var impelFirst = 0L
	private var impelLast = 0L
	private var impelDone = false
	private var impelSeconds = 0.0
	private var impelSecondsAt = 0L
	private var lastTitle = ""
	private var lastTitleAt = 0L
	private var jumpTicks = 0
	private var sneakTicks = 0

	private var nextSpringClickAt = 0L
	private var springLeftNext = true
	private val springHeads = HashMap<String, Boolean>()

	private var nextTubaAt = 0L

	fun clear() {
		stakeTarget = null
		val player = Minecraft.getInstance().player
		if (player != null) restore(player)
		restoreSlot = -1
		useReturn = -1
		stakedId = -1
		bloodcraveAt = 0L
		lastClaws = Double.MAX_VALUE
		iceUsed = false
		impelCommand = ""
		jumpTicks = 0
		sneakTicks = 0
	}

	// ---- Tick ----------------------------------------------------------------------------------

	/** End of tick, while Slayer Features is active. [mine] is your own boss, if one is up. */
	fun tick(client: Minecraft, player: LocalPlayer, mine: SlayerBosses.Seen?, now: Long) {
		tickStake(now)
		restore(player)
		if (jumpTicks > 0) jumpTicks--
		if (sneakTicks > 0) sneakTicks--
		val level = client.level ?: return
		if (bloodcraveAt != 0L && now - bloodcraveAt > BLOODCRAVE_MILLIS) bloodcraveAt = 0L

		val bloodfiend = mine?.takeIf { it.type == SlayerType.RIFTSTALKER }
		if (bloodfiend != null) {
			watch(level, bloodfiend)
			if (SlayerFeatures.autoHolyIce.value) holyIce(client, player, level, bloodfiend)
			if (SlayerFeatures.autoHeal.value) heal(client, player, now)
			if (SlayerFeatures.maniaHelper.value) impel(now)
		} else {
			lastClaws = Double.MAX_VALUE
			iceUsed = false
		}
		if (SlayerFeatures.autoKillerSpring.value && bloodfiend != null) killerSpring(client, level, now)
		if (SlayerFeatures.autoTuba.value && bloodfiend != null) tuba(client, player, now)
	}

	private fun standsNear(level: ClientLevel, boss: Entity, reach: Double): List<String> =
		level.getEntitiesOfClass(ArmorStand::class.java, boss.boundingBox.inflate(reach)) { it.customName != null }
			.mapNotNull { it.customName?.string?.replace(FORMATTING, "")?.trim()?.takeIf(String::isNotEmpty) }

	/**
	 * Twinclaws counts down on the stand with the boss's despawn timer ("03:45 TWINCLAWS 1.2s"); the
	 * ice goes on when it reaches [SlayerFeatures.holyIceAt]. A count that goes back up is the next one.
	 */
	private fun holyIce(client: Minecraft, player: LocalPlayer, level: ClientLevel, boss: SlayerBosses.Seen) {
		val left = standsNear(level, boss.boss, 3.0).firstNotNullOfOrNull { TWINCLAWS.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
		if (left == null) {
			lastClaws = Double.MAX_VALUE
			iceUsed = false
			return
		}
		if (left > lastClaws + CLAWS_RESTART) iceUsed = false
		lastClaws = left
		if (!iceUsed && left <= SlayerFeatures.holyIceAt.value + 1e-6) {
			iceUsed = use(client, player, HOLY_ICE, listOf("Holy Ice"))
			if (!iceUsed) note("Twinclaws at ${left}s: no Holy Ice in the hotbar, or another item is out")
		}
	}

	private fun heal(client: Minecraft, player: LocalPlayer, now: Long) {
		if (now < nextHealAt || player.maxHealth <= 0f) return
		val share = player.health / player.maxHealth * 100
		if (share >= SlayerFeatures.healThreshold.value) return
		if (use(client, player, HEALS, listOf("Healing Melon"))) {
			nextHealAt = now + HEAL_GAP_MILLIS
		} else if (now - healNotedAt > NOTE_GAP_MILLIS) {
			healNotedAt = now
			note("heal wanted at ${"%.0f".format(share)}% health: no Healing Melon in the hotbar, or another item is out")
		}
	}

	private var lastNote = ""

	/** A reason something wasn't done, recorded once until it changes. */
	private fun note(text: String) {
		if (text == lastNote) return
		lastNote = text
		recordEvent(text)
	}

	// ---- Impel ---------------------------------------------------------------------------------

	/** "Impel: SNEAK 1.1s": the order, and the countdown Hypixel re-sends every tick. */
	private fun impelOrder(text: String): String? = IMPEL.find(text)?.groupValues?.get(1)?.trim()?.uppercase()?.takeIf(String::isNotEmpty)

	/**
	 * A title or subtitle, client thread. The order may also come split, "Impel:" in the title and
	 * the order in the subtitle, so one just after the other is read joined.
	 */
	@JvmStatic
	fun onTitle(text: Component, subtitle: Boolean) {
		if (!SlayerFeatures.module.enabled) return
		val plain = text.string.replace(FORMATTING, "").trim()
		if (plain.isEmpty()) return
		val now = System.currentTimeMillis()
		val joined = if (impelOrder(plain) == null && subtitle && now - lastTitleAt < SUBTITLE_JOIN_MILLIS) "$lastTitle $plain" else plain
		val match = IMPEL.find(joined)
		val order = match?.groupValues?.get(1)?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
		if (!subtitle) {
			lastTitle = plain
			lastTitleAt = now
		}
		if (order == null) {
			recordEvent("${if (subtitle) "subtitle" else "title"}: \"$plain\"")
			return
		}
		// The countdown re-sends it each tick; only a new order (or one after a gap) starts the delay.
		if (order != impelCommand || now - impelLast > IMPEL_GAP_MILLIS) {
			impelCommand = order
			impelFirst = now
			impelDone = false
			recordEvent("Impel: \"$joined\"")
		}
		impelLast = now
		match.groupValues[2].toDoubleOrNull()?.let {
			impelSeconds = it
			impelSecondsAt = now
		}
	}

	/**
	 * The Impel order to show and the seconds left on it, for the Impel display; null without one,
	 * or for a jump or sneak the Mania helper answers and hides.
	 */
	fun impelShown(now: Long): Pair<String, Double?>? {
		if (impelCommand.isEmpty() || now - impelLast > IMPEL_GAP_MILLIS) return null
		if (helperHides(impelCommand)) return null
		val left = if (impelSecondsAt >= impelFirst) (impelSeconds - (now - impelSecondsAt) / 1000.0).coerceAtLeast(0.0) else null
		return impelCommand to left
	}

	private fun impel(now: Long) {
		if (impelCommand.isEmpty() || impelDone || now - impelLast > IMPEL_GAP_MILLIS) return
		if (now - impelFirst < SlayerFeatures.maniaDelay.value.toLong()) return
		impelDone = true
		when {
			"JUMP" in impelCommand -> jumpTicks = JUMP_TICKS
			"SNEAK" in impelCommand || "CROUCH" in impelCommand -> sneakTicks = SNEAK_TICKS
			else -> return
		}
		recordEvent("answered Impel: $impelCommand")
	}

	/** Whether to drop an Impel title the helper is set to hide. Client thread, from the title hooks. */
	@JvmStatic
	fun hidesTitle(text: Component): Boolean {
		if (!SlayerFeatures.module.enabled) return false
		val plain = text.string.replace(FORMATTING, "").trim()
		val order = impelOrder(plain)
		if (order == null) {
			// "Impel:" alone, or the order's half of a split title just read with it.
			val half = plain.equals("Impel:", ignoreCase = true) ||
				(impelCommand.isNotEmpty() && System.currentTimeMillis() - impelLast < SUBTITLE_JOIN_MILLIS && plain.uppercase().startsWith(impelCommand))
			return half && (SlayerFeatures.impelDisplay.value || helperHides(impelCommand))
		}
		// The display draws its own, so Hypixel's goes whenever it's on.
		return SlayerFeatures.impelDisplay.value || helperHides(order)
	}

	/** Whether the Mania helper answers [order] and is set to hide it. */
	private fun helperHides(order: String): Boolean {
		if (!SlayerFeatures.maniaHelper.value) return false
		return when (SlayerFeatures.impelPrompts.selectedIndex) {
			PROMPTS_SHOW -> false
			PROMPTS_HIDE_ALL -> true
			else -> "JUMP" in order || "SNEAK" in order
		}
	}

	/** The keys as read this tick, with the helper's jump or sneak held. */
	@JvmStatic
	fun input(keys: Input): Input {
		if (jumpTicks <= 0 && sneakTicks <= 0) return keys
		return Input(
			keys.forward(), keys.backward(), keys.left(), keys.right(),
			keys.jump() || jumpTicks > 0,
			keys.shift() || sneakTicks > 0,
			keys.sprint(),
		)
	}

	// ---- Killer Spring -------------------------------------------------------------------------

	private fun isSpringHead(stand: ArmorStand): Boolean {
		val head = stand.getItemBySlot(EquipmentSlot.HEAD)
		if (!head.`is`(Items.PLAYER_HEAD)) return false
		val texture = SkyblockItem.skullTexture(head) ?: return false
		return springHeads.getOrPut(texture) { SkyblockItem.textureHash(texture) == SPRING_SKIN }
	}

	/** The spring's stands and names around [target], or null if [target] isn't part of one. */
	private fun springAround(level: ClientLevel, target: Entity): List<ArmorStand>? {
		val box = AABB(target.x - 1.0, target.y - 4.0, target.z - 1.0, target.x + 1.0, target.y + 4.0, target.z + 1.0)
		val stands = level.getEntitiesOfClass(ArmorStand::class.java, box) { true }
		val spring = (target as? ArmorStand)?.let(::isSpringHead) == true || stands.any(::isSpringHead) ||
			stands.any { it.customName?.string?.contains("BOOM") == true }
		return if (spring) stands else null
	}

	/** Clicks the spring you're looking at as its stands ask, or alternately when they don't say. */
	private fun killerSpring(client: Minecraft, level: ClientLevel, now: Long) {
		if (now < nextSpringClickAt || client.gui.screen() != null) return
		val target = (client.hitResult as? EntityHitResult)?.entity ?: return
		val stands = springAround(level, target) ?: return
		val texts = stands.mapNotNull { it.customName?.string?.replace(FORMATTING, "")?.uppercase() }
		val left = texts.any { "LEFT" in it }
		val right = texts.any { "RIGHT" in it }
		val clickLeft = when {
			left && !right -> true
			right && !left -> false
			else -> springLeftNext
		}
		val mapping = if (clickLeft) client.options.keyAttack else client.options.keyUse
		if (mapping.isUnbound) return
		KeyMapping.click((mapping as? KeyMappingAccessor)?.`cryptic$key`() ?: return)
		springLeftNext = !clickLeft
		nextSpringClickAt = now + SPRING_CLICK_MILLIS
	}

	// ---- Tuba ----------------------------------------------------------------------------------

	/** Blown as your Bloodfiend spawns, then every [SlayerFeatures.tubaEvery] while it's up. */
	private fun tuba(client: Minecraft, player: LocalPlayer, now: Long) {
		if (now < nextTubaAt) return
		if (!use(client, player, TUBAS)) return
		nextTubaAt = now + (SlayerFeatures.tubaEvery.value * 1000).toLong()
	}


	// ---- Steak Stake ---------------------------------------------------------------------------

	/** A hit about to go out; swaps to the stake when it should be the stake's. */
	fun beforeAttack(player: Player, target: Entity) {
		val now = System.currentTimeMillis()
		val victim = victimOf(target)
		// While it's out, every hit is the stake's; it goes back on time whatever lands.
		if (stakeTarget != null) {
			noteStakeHit(victim, now)
			if (stakeDouble && now - stakeOutAt >= STAKE_SECOND_HIT_MILLIS) stakeSecondHit = true
			return
		}
		val level = Minecraft.getInstance().level ?: return
		if (!wantsStake(level, target)) return
		val slot = hotbarSlot(player, STAKES) ?: return
		val inventory = player.inventory
		val held = inventory.selectedSlot
		if (slot != held) {
			inventory.selectedSlot = slot
			if (restoreSlot < 0) restoreSlot = held
			swappedTo = slot
		}
		stakeTarget = victim
		stakeOutAt = now
		// With no Bloodcrave stored, a second hit makes sure the kill is the stake's, which stores one.
		stakeDouble = SlayerFeatures.stakeDoubleHit.value && bloodcraveAt == 0L
		stakeSecondHit = false
		// This first hit is the one Bloodcrave is for.
		bloodcraveAt = 0L
		noteStakeHit(victim, now)
	}

	private fun noteStakeHit(victim: Entity, now: Long) {
		stakedId = victim.id
		stakedAt = now
	}

	/** A hit on your boss's name stand is a hit on your boss. */
	private fun victimOf(target: Entity): Entity {
		if (target !is ArmorStand) return target
		val mine = SlayerBosses.mine() ?: return target
		return if (target.distanceToSqr(mine.boss) <= STAKE_REACH * STAKE_REACH) mine.boss else target
	}

	/**
	 * The stake goes back [SlayerFeatures.stakeSwapBack] after it came out, counted from the swap and
	 * not from later hits, so an auto clicker can't keep it out. A mob still stakeable after that
	 * gets the stake again on the next hit, to finish it.
	 */
	private fun tickStake(now: Long) {
		if (stakeTarget == null) return
		val out = now - stakeOutAt
		// Waiting on the double hit's second, past the mob's invulnerability from the first.
		if (stakeDouble && !stakeSecondHit && out < STAKE_DOUBLE_MAX_MILLIS) return
		if (out >= SlayerFeatures.stakeSwapBack.value.toLong()) stakeTarget = null
	}

	private fun wantsStake(level: ClientLevel, target: Entity): Boolean {
		val bloodcrave = bloodcraveAt != 0L
		val mine = SlayerBosses.mine()?.takeIf { it.type == SlayerType.RIFTSTALKER }
		if (mine != null && (target.id == mine.boss.id || target.distanceToSqr(mine.boss) <= STAKE_REACH * STAKE_REACH)) {
			return bloodcrave || SlayerFeatures.STEAK_MARK in mine.name
		}
		val name = nameOf(level, target) ?: return false
		if (STAKEABLE.none { name.contains(it, ignoreCase = true) }) return false
		return bloodcrave || SlayerFeatures.STEAK_MARK in name || low(name)
	}

	/** A mob's name stand: the one spawned just after it, or the nearest named one over it. */
	private fun nameOf(level: ClientLevel, mob: Entity): String? {
		(level.getEntity(mob.id + 1) as? ArmorStand)?.customName?.string?.let { return it.replace(FORMATTING, "") }
		return level.getEntitiesOfClass(ArmorStand::class.java, mob.boundingBox.inflate(0.5, 2.5, 0.5)) {
			it !== mob && it.customName != null && it.y >= mob.y
		}.minByOrNull { it.distanceToSqr(mob) }?.customName?.string?.replace(FORMATTING, "")
	}

	/** Whether a name's "312/1,800❤" is at or under a fifth. */
	private fun low(name: String): Boolean {
		val match = HEALTH.find(name) ?: return false
		val current = number(match.groupValues[1]) ?: return false
		val max = number(match.groupValues[2]) ?: return false
		return max > 0 && current <= max * STAKE_SHARE
	}

	private fun number(text: String): Double? {
		val value = text.trimEnd('k', 'M').replace(",", "").toDoubleOrNull() ?: return null
		return when (text.last()) {
			'k' -> value * 1e3
			'M' -> value * 1e6
			else -> value
		}
	}

	/**
	 * A stake kill leaves Bloodcrave (the Sharp Steak Stake's double damage) for the next hit, which
	 * takes the stake out afresh; the stake out now goes back at once.
	 */
	fun onEntityDied(entityId: Int) {
		if (entityId != stakedId) return
		stakedId = -1
		val now = System.currentTimeMillis()
		if (stakeTarget?.id == entityId) stakeTarget = null
		if (now - stakedAt <= STAKE_KILL_MILLIS) {
			bloodcraveAt = now
			recordEvent("stake kill: Bloodcrave ready")
		}
	}

	// ---- Debug ---------------------------------------------------------------------------------

	/** What `/cryptic debug rift` prints of recent fights: titles, the boss's stands, what was done. */
	private val events = ArrayDeque<String>()
	private val standsSeen = LinkedHashSet<String>()
	private const val EVENT_LIMIT = 25
	private const val STANDS_LIMIT = 40

	fun recordEvent(text: String) {
		val stamp = String.format(java.util.Locale.ROOT, "%tT", System.currentTimeMillis())
		events.addLast("$stamp $text")
		while (events.size > EVENT_LIMIT) events.removeFirst()
	}

	/** Each new text on the stands around your Bloodfiend, numbers folded so timers count once. */
	private fun watch(level: ClientLevel, boss: SlayerBosses.Seen) {
		for (text in standsNear(level, boss.boss, 6.0)) {
			val shape = text.replace(Regex("""\d[\d.,]*"""), "#")
			if (standsSeen.add(shape)) {
				recordEvent("stand: \"$text\"")
				if (standsSeen.size > STANDS_LIMIT) standsSeen.remove(standsSeen.first())
			}
		}
	}

	fun describe(client: Minecraft): List<String> {
		val level = client.level ?: return listOf("Not in a world.")
		val player = client.player ?: return listOf("Not in a world.")
		val out = ArrayList<String>()
		val mine = SlayerBosses.mine()
		out += "Your boss: " + (mine?.let { "${it.type.title} ${it.tier} \"${it.name}\"" } ?: "none")
		out += "Hotbar: stake ${hotbarSlot(player, STAKES)?.plus(1) ?: "-"}, Holy Ice ${hotbarSlot(player, HOLY_ICE, listOf("Holy Ice"))?.plus(1) ?: "-"}, " +
			"Healing Melon ${hotbarSlot(player, HEALS, listOf("Healing Melon"))?.plus(1) ?: "-"}, tuba ${hotbarSlot(player, TUBAS)?.plus(1) ?: "-"}"
		out += "Health ${"%.1f".format(player.health)}/${"%.1f".format(player.maxHealth)} " +
			"(${"%.0f".format(if (player.maxHealth > 0f) player.health / player.maxHealth * 100 else 0f)}%, heals below ${SlayerFeatures.healThreshold.value.toInt()}%), " +
			"Bloodcrave ${if (bloodcraveAt != 0L) "ready" else "no"}, next tuba in ${((nextTubaAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)}s"
		out += "Stake: " + (stakeTarget?.let { "out for entity ${it.id} for ${System.currentTimeMillis() - stakeOutAt}ms" } ?: "put away") +
			"; Impel: " + (if (impelCommand.isEmpty()) "none yet" else "$impelCommand, ${if (impelDone) "answered" else "pending"}") +
			"; last Twinclaws reading: " + (if (lastClaws == Double.MAX_VALUE) "none" else "${lastClaws}s")
		val target = (client.hitResult as? EntityHitResult)?.entity
		if (target == null) {
			out += "Looking at: no entity"
		} else {
			out += "Looking at: ${target.type.toShortString()} ${target.id}, name \"${nameOf(level, target) ?: "-"}\", " +
				"stake ${if (wantsStake(level, target)) "yes" else "no"}, spring ${if (springAround(level, target) != null) "yes" else "no"}"
			val box = AABB(target.x - 1.5, target.y - 4.0, target.z - 1.5, target.x + 1.5, target.y + 4.0, target.z + 1.5)
			for (stand in level.getEntitiesOfClass(ArmorStand::class.java, box) { true }.take(12)) {
				val name = stand.customName?.string?.replace(FORMATTING, "") ?: "(no name)"
				val head = stand.getItemBySlot(EquipmentSlot.HEAD)
				val hash = SkyblockItem.skullTexture(head)?.let(SkyblockItem::textureHash)?.take(12)
				out += "  stand ${stand.id} \"$name\"" + (hash?.let { " head $it${if (isSpringHead(stand)) " (Killer Spring)" else ""}" } ?: "")
			}
		}
		out += "Indicators: " + RiftIndicators.describe()
		out += "Particles around your Bloodfiend since last time: " + SlayerFeatures.particleReport()
		out += "Recent (titles, stands near your Bloodfiend, actions):"
		out += events.ifEmpty { listOf("nothing yet") }.map { "  $it" }
		return out
	}
}
