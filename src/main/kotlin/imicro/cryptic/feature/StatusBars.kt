package imicro.cryptic.feature

import imicro.cryptic.gui.ButtonModuleSetting
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.skyblock.SkyblockLocation
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import java.text.NumberFormat
import java.util.Locale

/**
 * SkyBlock's health, mana, Rift vitality, defense, speed and experience as HUD elements of their
 * own. Each can show as Cryptic's, as the game's own (hearts, armour, the experience bar), or not
 * at all; hunger can be hidden too. Health, mana, vitality and defense come from Hypixel's action
 * bar, read from the packet before any mod changes it; speed from your movement speed; experience
 * from the game. Placed in their own editor (`/cryptic hotbar`).
 *
 * The idea is Skyblocker's fancy status bars (LGPL-3.0); none of its code is here. As facts only:
 * Hypixel's stat icons (its own font glyphs beside the old symbols), the parts of the action bar
 * being two or more spaces apart, and speed being movement speed, without sprinting, times 1000.
 */
object StatusBars {
	private val FORMATTING = Regex("§.")

	// Hypixel writes each stat with either its old symbol or its own font's glyph.
	private const val HEALTH_ICONS = "❤\uE010"
	private const val DEFENSE_ICONS = "❈\uE008"
	private const val MANA_ICONS = "✎\uE003"
	private const val OVERFLOW_ICONS = "ʬ\uE017"
	private const val VITALITY_ICONS = "\uE028"

	private val HEALTH = Regex("""([\d,]+)/([\d,]+)[$HEALTH_ICONS]""")
	private val DEFENSE = Regex("""([\d,]+)[$DEFENSE_ICONS](?: Defense)?""")
	private val MANA = Regex("""([\d,]+)/([\d,]+)[$MANA_ICONS] ?(?:Mana|([\d,]+)[$OVERFLOW_ICONS])?""")
	private val VITALITY = Regex("""([\d,]+)/([\d,]+)(?:[$VITALITY_ICONS]| ?Vitality)""")

	/** The action bar's parts are two or more spaces apart. */
	private val PARTS = Regex("""\s{2,}""")

	/** An action bar older than this is stale: the bars hide and the game's come back. */
	private const val STALE_MILLIS = 5_000L

	/** Health from the action bar is trusted this long; after it, it follows your hearts. */
	private const val HEALTH_FRESH_MILLIS = 1_500L

	private const val BAR_WIDTH = 80
	private const val BAR_HEIGHT = 10

	// What each stat shows as.
	private const val OURS = 0
	private const val GAMES = 1

	private fun showSetting(id: String, label: String, ours: String, games: String?, description: String) = DropdownModuleSetting(
		id = id,
		label = label,
		options = listOfNotNull(ours, games, "Neither"),
		defaultIndex = OURS,
		description = description,
	)

	@JvmField
	val healthShow = showSetting("health_show", "Health", "Cryptic's bar", "The game's hearts", "Past full (absorption) the bar turns gold.")

	@JvmField
	val healthColor = ColorModuleSetting("health_color", "Color", 0xFF5555, visibleIf = { healthShow.selectedIndex == OURS })

	@JvmField
	val manaShow = showSetting("mana_show", "Mana", "Cryptic's bar", null, "Overflow mana shows in cyan.")

	@JvmField
	val manaColor = ColorModuleSetting("mana_color", "Color", 0x5555FF, visibleIf = { manaShow.selectedIndex == OURS })

	@JvmField
	val vitalityShow = showSetting("vitality_show", "Vitality", "Cryptic's bar", null, "The Rift's vitality, while you're in it.")

	@JvmField
	val vitalityColor = ColorModuleSetting("vitality_color", "Color", 0x55FF55, visibleIf = { vitalityShow.selectedIndex == OURS })

	@JvmField
	val defenseShow = showSetting("defense_show", "Defense", "Cryptic's number", "The game's armour", "Your defense as a number, or the game's armour bar.")

	@JvmField
	val speedShow = showSetting("speed_show", "Speed", "Cryptic's number", null, "Your speed as a number; 100 is base.")

	@JvmField
	val experienceShow = showSetting("experience_show", "Experience", "Cryptic's number", "The game's bar", "Your level as a number, or the game's experience bar.")

	@JvmField
	val experienceBar = ToggleModuleSetting(
		id = "experience_bar",
		label = "As a bar",
		defaultValue = false,
		description = "A thin bar of the way to the next level under the number.",
		visibleIf = { experienceShow.selectedIndex == OURS },
	)

	@JvmField
	val levelPrefix = ToggleModuleSetting(
		id = "level_prefix",
		label = "\"Lv\" before the level",
		defaultValue = true,
		visibleIf = { experienceShow.selectedIndex == OURS },
	)

	@JvmField
	val hunger = DropdownModuleSetting(
		id = "hunger",
		label = "Hunger",
		options = listOf("The game's", "Hidden"),
		defaultIndex = 1,
		description = "SkyBlock has no use for it.",
	)

	/** A bar's own options: its numbers, and staying hidden until it drops below a share. */
	class BarOptions(prefix: String, shown: () -> Boolean) {
		val numbers = ToggleModuleSetting(
			id = "${prefix}_numbers",
			label = "Numbers",
			defaultValue = true,
			description = "What's left, written on the bar. Off, it's just the bar.",
			visibleIf = shown,
		)

		val hideUntil = ToggleModuleSetting(
			id = "${prefix}_hide_until",
			label = "Hide until it drops",
			defaultValue = false,
			description = "The bar stays hidden until it falls below the threshold.",
			visibleIf = shown,
		)

		val threshold = SliderModuleSetting(
			id = "${prefix}_threshold",
			label = "Show below",
			unit = "%",
			defaultValue = 90.0,
			min = 5.0,
			max = 100.0,
			step = 5.0,
			visibleIf = { shown() && hideUntil.value },
		)

		fun shows(current: Int, max: Int): Boolean =
			!hideUntil.value || max <= 0 || current * 100.0 / max < threshold.value
	}

	private val healthOptions = BarOptions("health") { healthShow.selectedIndex == OURS }
	private val manaOptions = BarOptions("mana") { manaShow.selectedIndex == OURS }
	private val vitalityOptions = BarOptions("vitality") { vitalityShow.selectedIndex == OURS }

	@JvmField
	val tidyActionBar = ToggleModuleSetting(
		id = "tidy_action_bar",
		label = "Take them off the action bar",
		defaultValue = true,
		description = "Drops health, defense, mana and vitality from Hypixel's action bar, leaving anything else on it.",
	)

	@JvmField
	val edit = ButtonModuleSetting(
		id = "edit",
		label = "Edit bars",
		action = {
			val parent = Minecraft.getInstance().gui.screen()
			Minecraft.getInstance().execute { openEditor(parent) }
		},
	)

	@JvmField
	val module = Module(
		id = "status_bars",
		name = "Status Bars",
		description = "Health, mana and more as bars",
		category = ModuleCategory.VISUAL,
		settings = listOf(
			healthShow.withMore(healthColor, healthOptions.numbers, healthOptions.hideUntil, healthOptions.threshold),
			manaShow.withMore(manaColor, manaOptions.numbers, manaOptions.hideUntil, manaOptions.threshold),
			vitalityShow.withMore(vitalityColor, vitalityOptions.numbers, vitalityOptions.hideUntil, vitalityOptions.threshold),
			defenseShow, speedShow, experienceShow.withMore(experienceBar, levelPrefix), hunger,
			tidyActionBar, edit,
		),
	)

	// ---- Reading -------------------------------------------------------------------------------

	private class Stat(val current: Int, val max: Int)

	private var healthStat: Stat? = null
	private var healthReadAt = 0L
	private var manaStat: Stat? = null
	private var overflowMana = 0
	private var vitalityStat: Stat? = null
	private var defenseValue: Int? = null
	private var lastRead = 0L
	private var lastVitality = 0L

	private fun number(text: String) = text.replace(",", "").toIntOrNull() ?: 0

	/** Whether there's a recent action bar to go by: on SkyBlock, and Hypixel sending its stats. */
	private fun fresh(): Boolean = SkyblockLocation.onSkyblock && System.currentTimeMillis() - lastRead < STALE_MILLIS

	/**
	 * An action bar as it arrived, before any mod has changed it; from the chat and action bar
	 * packet hooks.
	 */
	@JvmStatic
	fun read(message: Component) {
		if (!module.enabled) return
		val plain = message.string.replace(FORMATTING, "")
		val now = System.currentTimeMillis()
		var found = false
		HEALTH.find(plain)?.let {
			healthStat = Stat(number(it.groupValues[1]), number(it.groupValues[2]))
			healthReadAt = now
			found = true
		}
		MANA.find(plain)?.let {
			manaStat = Stat(number(it.groupValues[1]), number(it.groupValues[2]))
			overflowMana = it.groupValues[3].takeIf { value -> value.isNotEmpty() }?.let(::number) ?: 0
			found = true
		}
		DEFENSE.find(plain)?.let {
			defenseValue = number(it.groupValues[1])
			found = true
		}
		VITALITY.find(plain)?.let {
			vitalityStat = Stat(number(it.groupValues[1]), number(it.groupValues[2]))
			lastVitality = now
			found = true
		}
		if (found) lastRead = now
	}

	/** Whether a part of the action bar is one of the stats shown elsewhere. */
	private fun isStat(part: String): Boolean {
		val plain = part.replace(FORMATTING, "").trim()
		return HEALTH.containsMatchIn(plain) || MANA.containsMatchIn(plain) ||
			DEFENSE.containsMatchIn(plain) || VITALITY.containsMatchIn(plain)
	}

	/** The action bar as it's about to show, without the stats; from `HudMixin`. */
	@JvmStatic
	fun onActionBar(message: Component): Component {
		if (!module.enabled || !tidyActionBar.value || !SkyblockLocation.onSkyblock) return message
		val coded = message.string
		val parts = coded.split(PARTS)
		if (parts.none(::isStat)) return message
		// Colour codes carry over a removed part, so each kept part starts from the code before it.
		val kept = ArrayList<String>()
		var lastCode = ""
		for (part in parts) {
			if (!isStat(part)) kept += if (part.startsWith("§")) part else lastCode + part
			Regex("§.").findAll(part).lastOrNull()?.let { lastCode = it.value }
		}
		val left = kept.joinToString("     ").trim()
		return if (left.replace(FORMATTING, "").isBlank()) Component.empty() else Component.literal(left)
	}

	/** The game's hearts, armour and hunger, each left out as set; from `HudMixin`. */
	@JvmStatic
	fun hidesHearts(): Boolean = module.enabled && healthShow.selectedIndex != GAMES && fresh()

	@JvmStatic
	fun hidesArmour(): Boolean = module.enabled && defenseShow.selectedIndex != GAMES && fresh()

	@JvmStatic
	fun hidesHunger(): Boolean = module.enabled && hunger.selectedIndex == 1 && SkyblockLocation.onSkyblock

	/** Whether the game's experience bar and level are left out; from `HudMixin`. */
	@JvmStatic
	fun hidesVanillaXp(): Boolean = module.enabled && experienceShow.selectedIndex != GAMES && SkyblockLocation.onSkyblock

	private val numberFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)

	private fun format(value: Int): String = numberFormat.format(value)

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		Hud.register(BarElement("status_health", "Health bar", 0.31, 0.855, { healthShow.selectedIndex == OURS }, { healthOptions.numbers.value }, ::healthState))
		Hud.register(BarElement("status_mana", "Mana bar", 0.523, 0.855, { manaShow.selectedIndex == OURS }, { manaOptions.numbers.value }, ::manaState))
		Hud.register(BarElement("status_vitality", "Vitality bar", 0.31, 0.815, { vitalityShow.selectedIndex == OURS }, { vitalityOptions.numbers.value }, ::vitalityState))
		Hud.register(TextElement("status_defense", "Defense", 0.235, 0.86, { defenseShow.selectedIndex == OURS }) { defenseValue?.let { "§a❈ ${format(it)}" } })
		Hud.register(TextElement("status_speed", "Speed", 0.70, 0.86, { speedShow.selectedIndex == OURS }) { speedText() })
		Hud.register(ExperienceElement())
	}

	private val ours = setOf("status_health", "status_mana", "status_vitality", "status_defense", "status_speed", "status_experience")

	/** The HUD editor with only these bars in it; also `/cryptic hotbar`. */
	fun openEditor(parent: net.minecraft.client.gui.screens.Screen?) = Hud.openEditor(parent) { it.id in ours }

	private fun speedText(): String? {
		val player = Minecraft.getInstance().player ?: return null
		// Sprinting adds 30%, which the stat doesn't count.
		val speed = if (player.isSprinting) player.speed / 1.3f else player.speed
		return "§f✦ ${format(Math.round(speed * 1000.0).toInt())}"
	}

	// ---- Elements ------------------------------------------------------------------------------

	/** What a bar shows: how full, any past full, its colour and its text. */
	private class BarState(val fill: Float, val extra: Float, val color: Int, val extraColor: Int, val text: String)

	/**
	 * Health: the action bar's while it's fresh, then your hearts scaled to it, since Hypixel keeps
	 * them in step and they change the moment you're hit.
	 */
	private fun healthState(): BarState? {
		val stat = healthStat ?: return null
		val player = Minecraft.getInstance().player
		val current = if (System.currentTimeMillis() - healthReadAt > HEALTH_FRESH_MILLIS && player != null && player.maxHealth > 0f) {
			Math.round(player.health / player.maxHealth * stat.max)
		} else {
			stat.current
		}
		if (!healthOptions.shows(current, stat.max)) return null
		val max = stat.max.coerceAtLeast(1)
		return BarState(
			(current.toFloat() / max).coerceIn(0f, 1f),
			((current - max).toFloat() / max).coerceIn(0f, 1f),
			healthColor.argb or (0xFF shl 24), 0xFFFFC94A.toInt(),
			"${format(current)}/${format(stat.max)}",
		)
	}

	private fun manaState(): BarState? {
		val stat = manaStat ?: return null
		if (!manaOptions.shows(stat.current, stat.max) && overflowMana == 0) return null
		val max = stat.max.coerceAtLeast(1)
		val text = "${format(stat.current)}/${format(stat.max)}" + if (overflowMana > 0) " +${format(overflowMana)}" else ""
		return BarState(
			(stat.current.toFloat() / max).coerceIn(0f, 1f),
			(overflowMana.toFloat() / max).coerceIn(0f, 1f),
			manaColor.argb or (0xFF shl 24), 0xFF55FFFF.toInt(),
			text,
		)
	}

	private fun vitalityState(): BarState? {
		val stat = vitalityStat ?: return null
		// Only while the Rift keeps sending it.
		if (System.currentTimeMillis() - lastVitality > STALE_MILLIS) return null
		if (!vitalityOptions.shows(stat.current, stat.max)) return null
		val max = stat.max.coerceAtLeast(1)
		return BarState((stat.current.toFloat() / max).coerceIn(0f, 1f), 0f, vitalityColor.argb or (0xFF shl 24), 0, "${format(stat.current)}/${format(stat.max)}")
	}

	private class BarElement(
		id: String,
		name: String,
		x: Double,
		y: Double,
		private val wanted: () -> Boolean,
		private val numbers: () -> Boolean,
		private val state: () -> BarState?,
	) : HudElement(id, name, x, y) {
		private val font get() = Minecraft.getInstance().font

		override val width = BAR_WIDTH
		override val height = BAR_HEIGHT

		override fun isVisible(): Boolean = module.enabled && wanted() && fresh() && state() != null

		override fun showInEditor(): Boolean = module.enabled && wanted()

		override fun render(context: GuiGraphicsExtractor) {
			state()?.let { draw(context, it) }
		}

		override fun renderExample(context: GuiGraphicsExtractor) {
			val shown = state() ?: BarState(0.7f, 0.1f, 0xFF888888.toInt(), 0xFFAAAAAA.toInt(), name)
			draw(context, shown)
		}

		private fun draw(context: GuiGraphicsExtractor, bar: BarState) {
			context.fill(0, 0, width, height, 0xC0101010.toInt())
			val inner = width - 2
			val filled = (inner * bar.fill).toInt()
			context.fill(1, 1, 1 + filled, height - 1, bar.color)
			if (bar.extra > 0f) {
				val over = (inner * bar.extra).toInt().coerceAtMost(inner)
				context.fill(1 + inner - over, 1, 1 + inner, height - 1, bar.extraColor)
			}
			if (numbers()) {
				val text = bar.text
				context.text(font, text, (width - font.width(text)) / 2, (height - font.lineHeight) / 2 + 1, 0xFFFFFFFF.toInt(), true)
			}
		}
	}

	private class TextElement(
		id: String,
		name: String,
		x: Double,
		y: Double,
		private val wanted: () -> Boolean,
		private val text: () -> String?,
	) : HudElement(id, name, x, y) {
		private val font get() = Minecraft.getInstance().font

		override val width: Int get() = maxOf(30, font.width(text() ?: name))
		override val height: Int get() = font.lineHeight

		override fun isVisible(): Boolean = module.enabled && wanted() && fresh() && text() != null

		override fun showInEditor(): Boolean = module.enabled && wanted()

		override fun render(context: GuiGraphicsExtractor) {
			text()?.let { context.text(font, it, 0, 0, -1, true) }
		}

		override fun renderExample(context: GuiGraphicsExtractor) {
			context.text(font, text() ?: "§7$name", 0, 0, -1, true)
		}
	}

	private class ExperienceElement : HudElement("status_experience", "Experience", 0.47, 0.87) {
		private val font get() = Minecraft.getInstance().font

		private fun label(): String {
			val level = Minecraft.getInstance().player?.experienceLevel ?: 0
			return if (levelPrefix.value) "§aLv $level" else "§a$level"
		}

		override val width: Int get() = if (experienceBar.value) BAR_WIDTH else maxOf(20, font.width(label()))
		override val height: Int get() = font.lineHeight + if (experienceBar.value) 4 else 0

		override fun isVisible(): Boolean =
			module.enabled && experienceShow.selectedIndex == OURS && SkyblockLocation.onSkyblock && Minecraft.getInstance().player != null

		override fun showInEditor(): Boolean = module.enabled && experienceShow.selectedIndex == OURS

		override fun render(context: GuiGraphicsExtractor) {
			val player = Minecraft.getInstance().player
			val text = label()
			context.text(font, text, (width - font.width(text)) / 2, 0, -1, true)
			if (experienceBar.value) {
				val y = font.lineHeight + 1
				context.fill(0, y, width, y + 3, 0xC0101010.toInt())
				val progress = (player?.experienceProgress ?: 0f).coerceIn(0f, 1f)
				context.fill(0, y, (width * progress).toInt(), y + 3, 0xFF80FF20.toInt())
			}
		}
	}
}
