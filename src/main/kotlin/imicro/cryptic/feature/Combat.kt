package imicro.cryptic.feature

import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.skyblock.SkyblockItem
import imicro.cryptic.skyblock.SkyblockLocation
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Combat helpers: the flare and power orb you stand in, a HUD row each with the time it has left and
 * a notification shortly before it runs out; and the pearl trajectory, drawn by [Trajectories].
 *
 * Flares are armour stands wearing the flare's head; they last three minutes from when they appear.
 * Power orbs carry their seconds in their name ("Plasmaflux 29s"). Only the best of each within its
 * buff range is shown, since that is the one that applies. Textures, ranges and the three minutes
 * are facts from SkyHanni's repo (MIT) and the NEU item repo.
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
	val module = Module(
		id = "combat",
		name = "Combat",
		description = "Timers and pearl trajectory",
		category = ModuleCategory.GENERAL,
		enabled = false,
		supportsKeybind = false,
		settings = listOf(
			timerHud.withMore(showFlares, showOrbs),
			expiryWarning.withMore(warnSeconds),
			pearlTrajectory.withMore(pearlColor, pearlHitColor),
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

	/** Stands already warned about, so each gets one notification. */
	private val warned = HashSet<Int>()

	private var flare: Active? = null
	private var orb: Active? = null
	private var lastLevel: ClientLevel? = null

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		Hud.register(TimerElement())
	}

	fun tick(client: Minecraft) {
		val level = client.level
		val player = client.player
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
			if (stand.distanceTo(player) > found.type.range) continue
			when (found.type.kind) {
				Kind.FLARE -> if (better(found, bestFlare)) bestFlare = found
				Kind.ORB -> if (better(found, bestOrb)) bestOrb = found
			}
		}

		warn(flare, bestFlare, now)
		warn(orb, bestOrb, now)
		flare = bestFlare
		orb = bestOrb
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

	/**
	 * Once per stand: when the one you're in crosses the warning line, or when it goes away in its
	 * last seconds without having crossed it (an orb vanishes before reading 0s).
	 */
	private fun warn(previous: Active?, current: Active?, now: Long) {
		if (!expiryWarning.value) return
		val ahead = (warnSeconds.value * 1000).toLong()
		if (current != null && current.entityId !in warned && current.millisLeft(now) <= ahead) {
			warned += current.entityId
			notify(current.type, current.millisLeft(now))
		}
		if (previous != null && previous.entityId != current?.entityId && previous.entityId !in warned &&
			previous.millisLeft(now) <= VANISH_MILLIS
		) {
			warned += previous.entityId
			notify(previous.type, 0)
		}
	}

	private fun notify(type: Deployable, millisLeft: Long) {
		val message = if (millisLeft <= 500) "§cRan out" else "Runs out in §f${(millisLeft + 999) / 1000}s"
		Toasts.show(type.label, message, color = type.colour)
	}

	private fun forget() {
		flare = null
		orb = null
		lastLevel = null
		flareStarted.clear()
		orbReading.clear()
		warned.clear()
	}

	/** "2:54" past a minute, "29s" under it. */
	private fun formatLeft(millis: Long): String {
		val seconds = ((millis + 999) / 1000).coerceAtLeast(0)
		return if (seconds >= 60) "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}" else "${seconds}s"
	}

	private const val FLARE_RANGE = 40.0
	private const val FLARE_MILLIS = 180_000L

	/** How close to its end one that disappears counts as run out, rather than walked away from. */
	private const val VANISH_MILLIS = 3_000L

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
