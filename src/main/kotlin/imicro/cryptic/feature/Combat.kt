package imicro.cryptic.feature

import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.render.WorldRender
import imicro.cryptic.skyblock.SkyblockItem
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.util.Mth
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import java.util.EnumMap

/**
 * Combat helpers: the flare and power orb you stand in, a HUD row each with the time it has left and
 * a notification shortly before it runs out; and the pearl trajectory, drawn by [Trajectories].
 *
 * Flares are armour stands wearing the flare's head; they last three minutes from when they appear.
 * Power orbs carry their seconds in their name ("Plasmaflux 29s"). Only the best of each within its
 * buff range is shown, since that is the one that applies. Textures, ranges and the three minutes
 * are facts from SkyHanni's repo (MIT) and the NEU item repo.
 *
 * Stands don't say whose they are, so only the warning is limited to your own: the stand of that kind
 * that appears next to you just after you use a flare or orb item (or after Hypixel says your previous
 * one was removed) is taken as yours.
 *
 * The Fire Veil overlay is IQ's (Apache 2.0): the wand's 3.5-block ring for the five seconds it
 * burns, and its flames hidden for nine seconds from the cast.
 */
object Combat {
	@JvmField
	val showFlares = ToggleModuleSetting(
		id = "show_flares",
		label = "Flares",
		defaultValue = true,
		description = "SOS, Alert and Warning flares within 40 blocks.",
	)

	@JvmField
	val showOrbs = ToggleModuleSetting(
		id = "show_orbs",
		label = "Power orbs",
		defaultValue = true,
		description = "Radiant, Mana Flux, Overflux and Plasmaflux while you're in their range.",
	)

	@JvmField
	val timerHud = ToggleModuleSetting(
		id = "timer_hud",
		label = "Flare & orb timer",
		defaultValue = true,
		description = "Shows the flare and power orb you are standing in, with the time each has left. Move it with Edit HUD.",
	)

	@JvmField
	val warnSeconds = SliderModuleSetting(
		id = "warn_seconds",
		label = "Warn ahead",
		unit = "s",
		defaultValue = 10.0,
		min = 0.0,
		max = 60.0,
		step = 1.0,
		description = "How long before it runs out to say so. At 0 it says when it has run out.",
	)

	@JvmField
	val expiryWarning = ToggleModuleSetting(
		id = "expiry_warning",
		label = "Warn when it runs out",
		defaultValue = true,
		description = "A notification (or a chat line, with notifications off) when your flare or orb is about to run out.",
	)

	@JvmField
	val pearlColor = ColorModuleSetting("pearl_color", "Line", defaultRgb = 0xC77DFF)

	@JvmField
	val pearlHitColor = ColorModuleSetting("pearl_hit_color", "Mob it hits", defaultRgb = 0xFF5555)

	@JvmField
	val pearlTrajectory = ToggleModuleSetting(
		id = "pearl_trajectory",
		label = "Pearl trajectory",
		defaultValue = true,
		description = "While you hold an ender pearl, shows where it would land. Stops at the first mob it would hit.",
	)

	@JvmField
	val fireVeil = ToggleModuleSetting(
		id = "fire_veil",
		label = "Fire Veil overlay",
		defaultValue = false,
		description = "Draws the Fire Veil Wand's reach around you while it burns.",
	)

	@JvmField
	val veilStyle = DropdownModuleSetting(
		id = "veil_style",
		label = "Style",
		options = listOf("Circle", "Wall"),
		defaultIndex = VEIL_CIRCLE,
		visibleIf = { fireVeil.value },
	)

	@JvmField
	val veilColor = ColorModuleSetting(
		id = "veil_color",
		label = "Colour",
		defaultRgb = 0xFFAA00,
		supportsAlpha = true,
		defaultAlpha = 0xC8,
		visibleIf = { fireVeil.value },
	)

	@JvmField
	val veilWidth = SliderModuleSetting(
		id = "veil_width",
		label = "Line width",
		defaultValue = 3.0,
		min = 1.0,
		max = 10.0,
		step = 0.5,
		visibleIf = { fireVeil.value },
	)

	@JvmField
	val veilThroughWalls = ToggleModuleSetting(
		id = "veil_through_walls",
		label = "Through walls",
		defaultValue = false,
		visibleIf = { fireVeil.value },
	)

	@JvmField
	val hideVeilFlames = ToggleModuleSetting(
		id = "hide_veil_flames",
		label = "Hide Fire Veil flames",
		defaultValue = true,
		description = "Hides the flame particles the wand puts around you.",
	)

	@JvmField
	val module = Module(
		id = "combat",
		name = "Combat",
		description = "Timers, pearls and Fire Veil",
		category = ModuleCategory.GENERAL,
		enabled = false,
		supportsKeybind = false,
		settings = listOf(
			timerHud.withMore(showFlares, showOrbs),
			expiryWarning.withMore(warnSeconds),
			pearlTrajectory.withMore(pearlColor, pearlHitColor),
			fireVeil.withMore(veilStyle, veilColor, veilWidth, veilThroughWalls),
			hideVeilFlames,
		),
	)

	private enum class Kind { FLARE, ORB }

	/** In rising order within each kind, so a later one beats an earlier one in range. */
	private enum class Deployable(
		val label: String,
		val colour: Int,
		val kind: Kind,
		val range: Double,
		val textureHash: String,
	) {
		WARNING("Warning Flare", 0xFF55FF55.toInt(), Kind.FLARE, FLARE_RANGE, "22e2bf6c1ec330247927ba63479e5872ac66b06903c86c82b52dac9f1c971458"),
		ALERT("Alert Flare", 0xFF5555FF.toInt(), Kind.FLARE, FLARE_RANGE, "9d2bf9864720d87fd06b84efa80b795c48ed539b16523c3b1f1990b40c003f6b"),
		SOS("SOS Flare", 0xFFAA00AA.toInt(), Kind.FLARE, FLARE_RANGE, "c0062cc98ebda72a6a4b89783adcef2815b483a01d73ea87b3df76072a89d13b"),
		RADIANT("Radiant", 0xFF55FF55.toInt(), Kind.ORB, 18.0, "c94d0a068ea50a9de2cef23a92bca4b3678d12ca8a1815d13beac94dfd557125"),
		MANA_FLUX("Mana Flux", 0xFF5555FF.toInt(), Kind.ORB, 18.0, "82ada1c7fcc8cf35defeb944a4f8ffa9a9d260560fc7f5f5826de8085435967c"),
		OVERFLUX("Overflux", 0xFFAA00AA.toInt(), Kind.ORB, 18.0, "20de5e8974940375934d32f71c91ad2d5728d38e51647dcc8f39206c099a54c2"),
		PLASMAFLUX("Plasmaflux", 0xFFFF55FF.toInt(), Kind.ORB, 20.0, "83ed4ce23933e66e04df16070644f7599eeb55307f7eafe8d92f40fb3520863c");

		val icon: ItemStack by lazy { SkyblockItem.head(textureHash) }
	}

	/** One in range: which, the armour stand it was read from, and when it runs out. */
	private class Active(val type: Deployable, val entityId: Int, val endsAt: Long) {
		fun millisLeft(now: Long): Long = endsAt - now
	}

	private val ORB_NAME = Regex("""^(Radiant|Mana Flux|Overflux|Plasmaflux) (\d+)s$""")
	private val FORMATTING = Regex("§.")

	private val orbByName = Deployable.entries.filter { it.kind == Kind.ORB }.associateBy { it.label }
	private val flareByHash = Deployable.entries.filter { it.kind == Kind.FLARE }.associateBy { it.textureHash }

	/** Each head's flare, or null, by the base64 Hypixel sends; decoding it every tick would add up. */
	private val flareByTexture = HashMap<String, Deployable?>()

	/** When each flare was first seen, back-dated by its age, so leaving and coming back keeps its time. */
	private val flareStarted = HashMap<Int, Long>()

	/** Each orb's last read seconds and the end they gave; only a new number moves the end. */
	private val orbReading = HashMap<Int, Pair<Int, Long>>()

	/** Stands already warned about (or replaced), so each gets one notification at most. */
	private val warned = HashSet<Int>()

	/** When you last used a flare or orb item, until the stand it made is found. */
	private val usedAt = EnumMap<Kind, Long>(Kind::class.java)

	/** The flare and orb you placed; only these warn. */
	private val mine = EnumMap<Kind, Mine>(Kind::class.java)

	/** The closest fresh stand next to you this tick, per kind, while one of yours is expected. */
	private val candidates = EnumMap<Kind, Active>(Kind::class.java)
	private val candidateDistance = EnumMap<Kind, Double>(Kind::class.java)

	private class Mine(var active: Active, val claimedAt: Long, var x: Double, var y: Double, var z: Double) {
		var seen = true
	}

	private var flare: Active? = null
	private var orb: Active? = null
	private var lastLevel: ClientLevel? = null

	/** When the Fire Veil was last cast, and where you were last tick; both read by the particle hook. */
	@Volatile
	private var veilCastAt = 0L

	@Volatile
	private var playerAt: Vec3 = Vec3.ZERO

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		Hud.register(TimerElement(), module, timerHud)
		LevelRenderEvents.COLLECT_SUBMITS.register(::renderVeil)
	}

	fun tick(client: Minecraft) {
		val level = client.level
		val player = client.player
		tickVeil(player)
		if (!module.enabled || level == null || player == null || !SkyblockLocation.onSkyblock) {
			forget()
			return
		}
		// Entity ids start over in every world.
		if (level !== lastLevel) {
			forget()
			lastLevel = level
		}

		val now = System.currentTimeMillis()
		var bestFlare: Active? = null
		var bestOrb: Active? = null
		for (entity in level.entitiesForRendering()) {
			val stand = entity as? ArmorStand ?: continue
			val found = read(stand, now) ?: continue
			val kind = found.type.kind
			val distance = stand.distanceTo(player).toDouble()
			val own = mine[kind]
			if (own != null && own.active.entityId == found.entityId) {
				own.active = found
				own.x = stand.x
				own.y = stand.y
				own.z = stand.z
				own.seen = true
			} else {
				val used = usedAt[kind]
				// Spawned since the click (with some slack for ping), right beside you.
				if (used != null && found.entityId !in warned && distance <= CLAIM_RANGE &&
					stand.tickCount * 50L <= now - used + SPAWN_SLACK_MILLIS &&
					distance < (candidateDistance[kind] ?: Double.MAX_VALUE)
				) {
					candidates[kind] = found
					candidateDistance[kind] = distance
				}
			}
			if (distance > found.type.range) continue
			when (kind) {
				Kind.FLARE -> if (better(found, bestFlare)) bestFlare = found
				Kind.ORB -> if (better(found, bestOrb)) bestOrb = found
			}
		}

		for (kind in Kind.entries) {
			val candidate = candidates[kind]
			if (candidate != null) {
				val stand = level.getEntity(candidate.entityId)
				// Hypixel removes the old one when a new one goes out; that isn't running out.
				mine[kind]?.let { warned += it.active.entityId }
				mine[kind] = Mine(candidate, now, stand?.x ?: player.x, stand?.y ?: player.y, stand?.z ?: player.z)
				usedAt.remove(kind)
			} else {
				val used = usedAt[kind]
				if (used != null && now - used > CLAIM_MILLIS) usedAt.remove(kind)
			}
			checkMine(kind, player.x, player.y, player.z, now)
		}
		candidates.clear()
		candidateDistance.clear()

		flare = bestFlare
		orb = bestOrb
	}

	/** Called on the client thread when the player right-clicks with [stack] in the main hand. */
	@JvmStatic
	fun onItemUsed(stack: ItemStack) {
		if (!module.enabled || stack.isEmpty) return
		val id = SkyblockItem.id(stack)
		noteVeil(stack, id)
		val kind = when {
			id.endsWith("_POWER_ORB") -> Kind.ORB
			id.endsWith("_FLARE") -> Kind.FLARE
			else -> return
		}
		usedAt[kind] = System.currentTimeMillis()
	}

	@JvmStatic
	fun onSystemChat(message: Component, overlay: Boolean) {
		if (overlay || !module.enabled) return
		val text = message.string.replace(FORMATTING, "").trim()
		TOO_FAR.matchEntire(text)?.let { far ->
			val kind = if (far.groupValues[1].contains("orb", ignoreCase = true)) Kind.ORB else Kind.FLARE
			mine.remove(kind)?.let { warned += it.active.entityId }
			return
		}
		val match = REPLACED.matchEntire(text) ?: return
		val name = match.groupValues[1]
		val kind = when {
			name.endsWith("Power Orb") -> Kind.ORB
			name.endsWith("Flare") -> Kind.FLARE
			else -> return
		}
		val now = System.currentTimeMillis()
		val own = mine[kind]
		// The click may already have found the new one; then this line is about the one before it.
		if (own != null && now - own.claimedAt < CLAIM_MILLIS) return
		own?.let { warned += it.active.entityId }
		mine.remove(kind)
		if (usedAt[kind] == null) usedAt[kind] = now
	}

	/**
	 * Warns once when yours crosses the warning line. Gone while you were near it means it ran out (if
	 * it was due) or was picked up; out of sight, its time keeps counting.
	 */
	private fun checkMine(kind: Kind, px: Double, py: Double, pz: Double, now: Long) {
		val own = mine[kind] ?: return
		val active = own.active
		val left = active.millisLeft(now)
		if (!own.seen) {
			val dx = own.x - px
			val dy = own.y - py
			val dz = own.z - pz
			val near = dx * dx + dy * dy + dz * dz <= GONE_RANGE * GONE_RANGE
			if (near || left <= 0) {
				if (left <= VANISH_MILLIS && expiryWarning.value && active.entityId !in warned) notify(active.type, 0)
				warned += active.entityId
				mine.remove(kind)
				return
			}
		}
		own.seen = false
		if (expiryWarning.value && active.entityId !in warned && left <= (warnSeconds.value * 1000).toLong()) {
			warned += active.entityId
			notify(active.type, left)
		}
	}

	private fun read(stand: ArmorStand, now: Long): Active? {
		val name = stand.customName
		if (name != null) {
			val match = ORB_NAME.matchEntire(name.string.replace(FORMATTING, "").trim()) ?: return null
			val type = orbByName[match.groupValues[1]] ?: return null
			val seconds = match.groupValues[2].toIntOrNull() ?: return null
			val last = orbReading[stand.id]
			val endsAt = if (last != null && last.first == seconds) last.second else now + seconds * 1000L
			orbReading[stand.id] = seconds to endsAt
			return Active(type, stand.id, endsAt)
		}

		val head = stand.getItemBySlot(EquipmentSlot.HEAD)
		if (!head.`is`(Items.PLAYER_HEAD)) return null
		val texture = SkyblockItem.skullTexture(head) ?: return null
		val type = flareByTexture.getOrPut(texture) { SkyblockItem.textureHash(texture)?.let { flareByHash[it] } } ?: return null
		val started = flareStarted.getOrPut(stand.id) { now - stand.tickCount * 50L }
		return Active(type, stand.id, started + FLARE_MILLIS)
	}

	private fun better(candidate: Active, best: Active?): Boolean = when {
		best == null -> true
		candidate.type != best.type -> candidate.type.ordinal > best.type.ordinal
		else -> candidate.endsAt > best.endsAt
	}

	private fun notify(type: Deployable, millisLeft: Long) {
		val message = if (millisLeft <= 500) "§cRan out" else "Runs out in §f${(millisLeft + 999) / 1000}s"
		Toasts.show(type.label, message, color = type.colour)
	}

	/** Whether a particle is the Fire Veil's flames, to be dropped. Network thread: reads only volatile fields. */
	@JvmStatic
	fun hidesParticle(packet: ClientboundLevelParticlesPacket): Boolean {
		return try {
			packet.particle.type == ParticleTypes.FLAME && veilFlamesHidden() &&
				playerAt.distanceToSqr(packet.x, packet.y, packet.z) <= VEIL_FLAME_REACH * VEIL_FLAME_REACH
		} catch (_: RuntimeException) {
			false
		}
	}

	private fun veilFlamesHidden(): Boolean =
		module.enabled && hideVeilFlames.value && veilCastAt > 0 && System.currentTimeMillis() - veilCastAt <= VEIL_FLAME_MILLIS

	private fun noteVeil(stack: ItemStack, id: String) {
		if (!fireVeil.value && !hideVeilFlames.value) return
		val isVeil = id == VEIL_ID || stack.hoverName.string.replace(FORMATTING, "").contains("Fire Veil", ignoreCase = true)
		if (!isVeil) return
		val now = System.currentTimeMillis()
		if (now - veilCastAt < VEIL_DEBOUNCE_MILLIS) return
		veilCastAt = now
	}

	private fun tickVeil(player: LocalPlayer?) {
		if (player == null || !module.enabled) {
			veilCastAt = 0L
			return
		}
		playerAt = player.position()
	}

	/** The veil's ring (or low wall) around you while it burns, on every island. */
	private fun renderVeil(context: LevelRenderContext) {
		val now = System.currentTimeMillis()
		if (!module.enabled || !fireVeil.value || veilCastAt == 0L || now - veilCastAt > VEIL_MILLIS) return
		val client = Minecraft.getInstance()
		val player = client.player ?: return
		val partialTick = client.deltaTracker.getGameTimeDeltaPartialTick(false).toDouble()
		val centre = Vec3(
			Mth.lerp(partialTick, player.xo, player.x),
			Mth.lerp(partialTick, player.yo, player.y) + 0.02,
			Mth.lerp(partialTick, player.zo, player.z),
		)
		if (veilStyle.selectedIndex == VEIL_CIRCLE) {
			WorldRender.drawCircle(
				context.poseStack(), context.submitNodeCollector(), centre, VEIL_RADIUS,
				veilColor.argb, veilWidth.value.toFloat(), veilThroughWalls.value, segments = 60,
			)
		} else {
			WorldRender.drawCylinder(
				context.poseStack(), context.submitNodeCollector(), centre, VEIL_RADIUS, VEIL_WALL_HEIGHT,
				veilColor.argb, veilWidth.value.toFloat(), veilThroughWalls.value, segments = 60,
			)
		}
	}

	private fun forget() {
		flare = null
		orb = null
		lastLevel = null
		flareStarted.clear()
		orbReading.clear()
		warned.clear()
		usedAt.clear()
		mine.clear()
		candidates.clear()
		candidateDistance.clear()
	}

	/** "2:54" past a minute, "29s" under it. */
	private fun formatLeft(millis: Long): String {
		val seconds = ((millis + 999) / 1000).coerceAtLeast(0)
		return if (seconds >= 60) "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}" else "${seconds}s"
	}

	private const val FLARE_RANGE = 40.0
	private const val FLARE_MILLIS = 180_000L

	/** How close to its end one that disappears counts as run out, rather than picked up. */
	private const val VANISH_MILLIS = 3_000L

	/** How long after the click its stand may take to show up, and how far from you. */
	private const val CLAIM_MILLIS = 4_000L
	private const val CLAIM_RANGE = 10.0
	private const val SPAWN_SLACK_MILLIS = 1_500L

	/** Within this, yours disappearing means it is gone rather than out of sight. */
	private const val GONE_RANGE = 32.0

	private const val VEIL_CIRCLE = 0

	/** The Fire Veil Wand's ring, how long it burns, and how long its flames are hidden. */
	private const val VEIL_ID = "FIRE_VEIL_WAND"
	private const val VEIL_RADIUS = 3.5
	private const val VEIL_WALL_HEIGHT = 1.0
	private const val VEIL_MILLIS = 5_000L
	private const val VEIL_FLAME_MILLIS = 9_000L

	/** Flames this close to you while the veil burns are its own. */
	private const val VEIL_FLAME_REACH = 6.0

	/** A click and the use that follows it both fire; one cast is counted from both. */
	private const val VEIL_DEBOUNCE_MILLIS = 500L

	private val REPLACED = Regex("""^Your previous (.+) was removed!$""")
	private val TOO_FAR = Regex("""^Your (.+) disappeared because you were too far away!$""")

	private class TimerElement : HudElement("combat_timers", "Flare & Orb Timer", 0.02, 0.45) {
		private val font get() = Minecraft.getInstance().font

		private fun rows(): List<Active> = buildList {
			if (showFlares.value) flare?.let(::add)
			if (showOrbs.value) orb?.let(::add)
		}

		private fun exampleRows(): List<Active> {
			val now = System.currentTimeMillis()
			return listOf(Active(Deployable.SOS, -1, now + 174_000), Active(Deployable.PLASMAFLUX, -2, now + 29_000))
		}

		/** Sized around what is shown, or the example when nothing is. */
		private fun sized(): List<Active> = rows().ifEmpty { exampleRows() }

		private fun label(active: Active, now: Long): String = "${active.type.label} §f${formatLeft(active.millisLeft(now))}"

		override val width: Int
			get() {
				val now = System.currentTimeMillis()
				return ICON_ROOM + (sized().maxOfOrNull { font.width(label(it, now)) } ?: 0)
			}

		override val height: Int get() = maxOf(1, sized().size) * ROW

		override fun isVisible(): Boolean = module.enabled && timerHud.value && rows().isNotEmpty()

		override fun showInEditor(): Boolean = module.enabled && timerHud.value

		override fun render(context: GuiGraphicsExtractor) = draw(context, rows())

		override fun renderExample(context: GuiGraphicsExtractor) = draw(context, rows().ifEmpty { exampleRows() })

		private fun draw(context: GuiGraphicsExtractor, rows: List<Active>) {
			val now = System.currentTimeMillis()
			rows.forEachIndexed { index, active ->
				context.item(active.type.icon, 0, index * ROW - 1)
				context.text(font, label(active, now), ICON_ROOM, index * ROW + 3, active.type.colour)
			}
		}

		private companion object {
			const val ICON_ROOM = 18
			const val ROW = 14
		}
	}
}
