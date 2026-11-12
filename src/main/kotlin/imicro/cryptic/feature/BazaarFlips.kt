package imicro.cryptic.feature

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import imicro.cryptic.Cryptic
import imicro.cryptic.hud.Hud
import imicro.cryptic.hud.HudElement
import imicro.cryptic.skyblock.SkyblockLocation
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bazaar flips: what your trades made, and what comes next (the Flipping guide's step, or with it
 * stopped what your orders want from you), in one panel on the HUD; and the week's summary the
 * Flipping panel opens with: the total, a chart, the best item and your goal. Profit counts only
 * what you sell of something you bought on the Bazaar, against what you paid for it, so selling
 * your own drops doesn't count. What you paid and the week's sales are kept across restarts. The
 * look follows FlipperX's panels; the code is Cryptic's own.
 */
object BazaarFlips {
	/** The module the HUD editor files the panel under. */
	@JvmField
	val module = Flipping.module

	/** How many of an item you bought on the Bazaar and still hold, and what they cost all together. */
	private class Held(var units: Long = 0, var cost: Double = 0.0)

	private val held = HashMap<String, Held>()
	private val gson = GsonBuilder().setPrettyPrinting().create()
	private val file get() = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("bazaar-flips.json")

	private var profit = 0.0
	/** When the session's first counted trade happened; zero before one. */
	private var since = 0L
	/** The latest gain, with gains close together added up, shown beside the profit as it fades. */
	private var gain = 0.0
	private var gainAt = 0L

	/** Each item's icon as the orders menu shows it, by name. */
	private val icons = HashMap<String, ItemStack>()

	private val claimedBuy = Regex("""^\[Bazaar] Claimed ([\d,]+)x (.+?) worth ([\d,.]+) coins bought for""")
	// The coins claimed are already after the sell tax.
	private val claimedSell = Regex("""^\[Bazaar] Claimed ([\d,.]+) coins from selling ([\d,]+)x (.+?) at [\d,.]+ each""")
	private val bought = Regex("""^\[Bazaar] Bought ([\d,]+)x (.+?) for ([\d,.]+) coins!""")
	private val sold = Regex("""^\[Bazaar] Sold ([\d,]+)x (.+?) for ([\d,.]+) coins!""")

	private const val GAIN_MILLIS = 1700L
	private const val MERGE_MILLIS = 1600L

	fun initialize() {
		load()
		loadHistory()
		Hud.register(FlipsElement())
	}

	fun reset() {
		profit = 0.0
		since = 0L
		gain = 0.0
		gainAt = 0L
	}

	/** A "[Bazaar]" chat line, formatting stripped. Runs on the client thread. */
	fun onBazaarLine(line: String) {
		claimedBuy.find(line)?.let { m ->
			buy(name(m.groupValues[2]), count(m.groupValues[1]), coins(m.groupValues[3]))
			return
		}
		claimedSell.find(line)?.let { m ->
			sell(name(m.groupValues[3]), count(m.groupValues[2]), coins(m.groupValues[1]))
			return
		}
		bought.find(line)?.let { m ->
			buy(name(m.groupValues[2]), count(m.groupValues[1]), coins(m.groupValues[3]))
			return
		}
		sold.find(line)?.let { m ->
			// Taxed the way the daily limit counts instant sells.
			sell(name(m.groupValues[2]), count(m.groupValues[1]), coins(m.groupValues[3]) * (1 - Bazaar.tax.value / 100))
		}
	}

	/** An order's icon from the orders menu, kept for the panel. */
	fun noteIcon(name: String, stack: ItemStack) {
		if (!stack.isEmpty && name !in icons) icons[name] = stack.copyWithCount(1)
	}

	// Gemstones carry a symbol before their name ("64x ⸕ Fine Ruby Gemstone").
	private fun name(raw: String) = raw.trim().trimStart { !it.isLetterOrDigit() }.trim()
	private fun count(raw: String) = raw.replace(",", "").toLongOrNull() ?: 0L
	private fun coins(raw: String) = raw.replace(",", "").toDoubleOrNull() ?: 0.0

	private fun buy(name: String, units: Long, cost: Double) {
		if (units <= 0 || cost <= 0) return
		val item = held.getOrPut(name) { Held() }
		item.units += units
		item.cost += cost
		save()
	}

	private fun sell(name: String, units: Long, coins: Double) {
		val item = held[name] ?: return
		val counted = minOf(units, item.units)
		if (counted <= 0 || units <= 0) return
		val cost = item.cost * counted / item.units
		item.units -= counted
		item.cost -= cost
		if (item.units <= 0) held.remove(name)
		save()
		add(coins * counted / units - cost, name, counted)
	}

	private fun add(amount: Double, name: String, units: Long) {
		if (!amount.isFinite()) return
		val now = System.currentTimeMillis()
		if (since == 0L) since = now
		profit += amount
		gain = if (now - gainAt < MERGE_MILLIS) gain + amount else amount
		gainAt = now
		gains += Gain(now, amount, name, units)
		goalProgress += amount
		trim(now)
		weekAt = 0L
		saveHistory()
	}

	private fun signed(value: Double) = (if (value < 0) "-" else "") + Bazaar.short(abs(value))

	/** "+1.25M", "-300k", "+0". */
	private fun plus(value: Double) = (if (value < 0) "" else "+") + signed(value)

	/** A headline number, to one decimal: "+229.9M". */
	private fun headline(value: Double): String {
		val size = abs(value)
		val (number, unit) = when {
			size >= 1e9 -> size / 1e9 to "B"
			size >= 1e6 -> size / 1e6 to "M"
			size >= 1e3 -> size / 1e3 to "k"
			else -> size to ""
		}
		return (if (value < 0) "-" else "+") + String.format(Locale.US, "%.1f", number).removeSuffix(".0") + unit
	}

	private fun duration(millis: Long): String {
		val minutes = millis / 60_000
		return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
	}

	private fun perHour(): String? {
		if (since == 0L) return null
		val elapsed = System.currentTimeMillis() - since
		if (elapsed < 60_000) return null
		return signed(profit / elapsed * 3_600_000)
	}

	// ---- The week ------------------------------------------------------------------------------

	/** One counted sale: when, what it made, and of what. Kept by Gson, so all defaulted. */
	private class Gain(var at: Long = 0, var amount: Double = 0.0, var name: String = "", var units: Long = 0)

	/** The history file: the week's sales, and what has been made towards the goal. */
	private class Saved(var gains: List<Gain>? = null, var goalProgress: Double = 0.0)

	private val gains = ArrayList<Gain>()
	private var goalProgress = 0.0
	private val historyFile get() = FabricLoader.getInstance().configDir.resolve("cryptic").resolve("bazaar-flips-history.json")

	private const val DAY = 24 * 3_600_000L
	private const val WEEK = 7 * DAY
	private const val MAX_GAINS = 4_000

	private fun trim(now: Long) {
		gains.removeIf { now - it.at > WEEK }
		while (gains.size > MAX_GAINS) gains.removeAt(0)
	}

	fun resetGoal() {
		goalProgress = 0.0
		saveHistory()
	}

	/** A goal as typed ("500m", "1.5b Hyperion"), what it comes to, and how far along it is. */
	class Goal(val label: String, val target: Double, val progress: Double) {
		val share: Double get() = (progress / target).coerceIn(0.0, 1.0)
	}

	private val GOAL = Regex("""^([\d.,]+)\s*([kmb])?""", RegexOption.IGNORE_CASE)
	private var goalText: String? = null
	private var goalTarget = 0.0

	fun goal(): Goal? {
		val text = Flipping.goal.value.trim()
		if (text != goalText) {
			goalText = text
			goalTarget = GOAL.find(text)?.let { match ->
				val number = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return@let 0.0
				number * when (match.groupValues[2].lowercase(Locale.ROOT)) {
					"k" -> 1e3
					"m" -> 1e6
					"b" -> 1e9
					else -> 1.0
				}
			} ?: 0.0
		}
		return if (goalTarget > 0) Goal(text, goalTarget, goalProgress) else null
	}

	private class Best(val name: String, val units: Long, val amount: Double)

	/**
	 * The week as the chart draws it: each column's height from the chart's top ([levels]), where
	 * nothing made sits ([zero]), and the days under it by column.
	 */
	private class Week(
		val total: Double, val sales: Int, val span: Long,
		val levels: IntArray, val zero: Int, val labels: List<Pair<Int, String>>, val best: Best?,
	)

	private var week: Week? = null
	private var weekAt = 0L
	private var weekWidth = 0

	private fun week(width: Int): Week? {
		val now = System.currentTimeMillis()
		if (now - weekAt < 1_000L && width == weekWidth) return week
		weekAt = now
		weekWidth = width
		val recent = gains.filter { now - it.at <= WEEK }
		week = if (recent.isEmpty() || width <= 0) null else build(recent, now, width)
		return week
	}

	private fun build(recent: List<Gain>, now: Long, width: Int): Week {
		val first = recent.first().at
		// Opens a little before the first sale, so the line starts from nothing.
		val start = maxOf(now - WEEK, minOf(first - (now - first) / 20, now - 10 * 60_000L))
		val span = now - start
		val sums = DoubleArray(width)
		var total = 0.0
		var index = 0
		var low = 0.0
		var high = 0.0
		for (column in 0 until width) {
			val until = start + span * (column + 1) / width
			while (index < recent.size && recent[index].at <= until) total += recent[index++].amount
			sums[column] = total
			low = minOf(low, total)
			high = maxOf(high, total)
		}
		if (high <= low) high = low + 1
		fun level(value: Double) = ((high - value) / (high - low) * (CHART - 1)).roundToInt()

		val zone = ZoneId.systemDefault()
		val labels = ArrayList<Pair<Int, String>>()
		var day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate().plusDays(1)
		while (true) {
			val at = day.atStartOfDay(zone).toInstant().toEpochMilli()
			if (at >= now) break
			labels += ((at - start) * width / span).toInt() to day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
			day = day.plusDays(1)
		}
		// Within one day there's no midnight to name: the time it starts at instead.
		if (labels.isEmpty()) labels += 0 to CLOCK.format(Instant.ofEpochMilli(start).atZone(zone))

		val best = recent.groupBy { it.name }
			.map { (name, sales) -> Best(name, sales.sumOf { it.units }, sales.sumOf { it.amount }) }
			.maxByOrNull { it.amount }?.takeIf { it.amount > 0 }
		return Week(total, recent.size, span, IntArray(width) { level(sums[it]) }, level(0.0), labels, best)
	}

	private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

	// ---- What's next --------------------------------------------------------------------------

	private class Step(val text: String, val urgent: Boolean, val icon: ItemStack, val detail: String? = null)

	private val idleIcon by lazy { ItemStack(Items.CLOCK) }
	private val emptyIcon by lazy { ItemStack(Items.CHEST) }
	private val claimIcon by lazy { ItemStack(Items.GOLD_INGOT) }

	private fun iconOf(name: String, fallback: ItemStack) = icons[name] ?: fallback

	/** An item's icon as the orders menu last showed it. */
	fun iconOf(name: String): ItemStack? = icons[name]

	/** How many of the items [matches] names you bought on the Bazaar and still hold. */
	fun heldUnits(matches: (String) -> Boolean): Long = held.entries.sumOf { if (matches(it.key)) it.value.units else 0L }

	private fun more(count: Int) = if (count > 1) " §7(+${count - 1})" else ""

	/** The guide's step while it runs; otherwise the first thing your orders want, or that they're fine. */
	private fun step(): Step {
		Flipping.hudStep()?.let { return Step(it.title, it.urgent, it.icon ?: if (it.urgent) claimIcon else idleIcon, it.detail) }
		val orders = Bazaar.openOrders.filter { !it.coopMember || Bazaar.coopAlerts.value }
		if (orders.isEmpty()) return Step("No orders out", false, emptyIcon)
		val by = orders.groupBy { Bazaar.statusFor(it) }
		by[Bazaar.Status.FILLED]?.let { filled ->
			val first = filled.first()
			val text = if (filled.size == 1) "Claim ${first.amount}x ${first.name}" else "Claim ${filled.size} filled orders"
			return Step(text, true, if (filled.size == 1) iconOf(first.name, claimIcon) else claimIcon)
		}
		by[Bazaar.Status.UNDERCUT]?.let { list ->
			val first = list.first()
			return Step("Relist ${first.name}, undercut${more(list.size)}", true, iconOf(first.name, idleIcon))
		}
		by[Bazaar.Status.EXPIRED]?.let { list ->
			val first = list.first()
			return Step("${first.name} expired, cancel it${more(list.size)}", true, iconOf(first.name, idleIcon))
		}
		by[Bazaar.Status.MATCHED]?.let { list ->
			val first = list.first()
			return Step("${first.name} is matched${more(list.size)}", false, iconOf(first.name, idleIcon))
		}
		val top = by[Bazaar.Status.TOP]?.size ?: 0
		// Orders without market data yet count as out, not as on top.
		val text = when {
			top < orders.size -> if (orders.size == 1) "1 order out" else "${orders.size} orders out"
			top == 1 -> "Your order is on top"
			else -> "$top orders on top"
		}
		return Step(text, false, idleIcon)
	}

	// ---- Drawing ------------------------------------------------------------------------------

	private const val PAD = 6
	private const val ICON = 16
	private const val ICON_GAP = 4
	private const val LINE = 11
	private const val ROW_GAP = 4
	private const val MIN_WIDTH = 150
	/** A headline number: the font at twice its size, and the gap under it. */
	private const val BIG = 19
	private const val CHART = 40
	private const val GOAL_HEIGHT = LINE + 5
	private const val AMBER = 0xFFE8A33D.toInt()
	private const val TEXT = 0xFFE8E8E8.toInt()
	private const val DIM = 0xFF8A8A8A.toInt()
	private const val LOSS = 0xD27C78
	private const val TRACK = 0xFF2A2A2A.toInt()
	private const val GRID = 0x12FFFFFF
	private const val AREA = 0x26E8A33D

	private val font get() = Minecraft.getInstance().font

	private fun tone(value: Double) = if (value < 0) (0xFF shl 24) or LOSS else AMBER

	/** [text] at twice the font's size. */
	private fun big(graphics: GuiGraphicsExtractor, text: String, x: Int, y: Int, color: Int) {
		val pose = graphics.pose()
		pose.pushMatrix()
		pose.translate(x.toFloat(), y.toFloat())
		pose.scale(2f, 2f)
		graphics.text(font, text, 0, 0, color, false)
		pose.popMatrix()
	}

	private fun fit(text: String, width: Int): String {
		if (font.width(text) <= width) return text
		return font.plainSubstrByWidth(text, width - font.width("...")) + "..."
	}

	private fun goalHeight() = if (goal() != null) GOAL_HEIGHT else 0

	/** The goal's name, how far along it is, and a bar. */
	private fun drawGoal(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int) {
		val goal = goal() ?: return
		val percent = "${(goal.share * 100).toInt()}%"
		graphics.text(font, fit(goal.label, width - font.width(percent) - 6), x, y, TEXT, false)
		graphics.text(font, percent, x + width - font.width(percent), y, DIM, false)
		graphics.fill(x, y + LINE, x + width, y + LINE + 2, TRACK)
		val filled = (width * goal.share).roundToInt()
		if (filled > 0) graphics.fill(x, y + LINE, x + filled, y + LINE + 2, AMBER)
	}

	/** The line and the area under it, a run of columns at one height at a time. */
	private fun drawChart(graphics: GuiGraphicsExtractor, x: Int, y: Int, week: Week) {
		val width = week.levels.size
		for (quarter in 1..3) graphics.fill(x, y + CHART * quarter / 4, x + width, y + CHART * quarter / 4 + 1, GRID)
		graphics.fill(x, y + week.zero, x + width, y + week.zero + 1, TRACK)
		var from = 0
		while (from < width) {
			val level = week.levels[from]
			var to = from + 1
			while (to < width && week.levels[to] == level) to++
			if (level < week.zero) graphics.fill(x + from, y + level + 1, x + to, y + week.zero, AREA)
			if (level > week.zero) graphics.fill(x + from, y + week.zero + 1, x + to, y + level, AREA)
			if (level != week.zero) graphics.fill(x + from, y + level, x + to, y + level + 1, AMBER)
			if (to < width) {
				val next = week.levels[to]
				graphics.fill(x + to, y + minOf(level, next), x + to + 1, y + maxOf(level, next) + 1, AMBER)
			}
			from = to
		}
	}

	/**
	 * The week's card, [width] wide from [x], [y]: the total, this session, a chart of the week, the
	 * best item and the goal. Draws when given [graphics]; returns its height either way.
	 */
	fun summary(graphics: GuiGraphicsExtractor?, x: Int, y: Int, width: Int): Int {
		val week = week(width)
		val right = x + width
		var rowY = y
		if (graphics != null) {
			graphics.text(font, "Flips", x, rowY, DIM, false)
			val span = when {
				week == null -> "nothing sold yet"
				week.span >= 2 * DAY -> "this week"
				else -> "last ${duration(week.span)}"
			}
			graphics.text(font, span, right - font.width(span), rowY, DIM, false)
		}
		rowY += LINE

		if (week != null) {
			if (graphics != null) {
				val total = headline(week.total)
				big(graphics, total, x, rowY, tone(week.total))
				val sales = if (week.sales == 1) "in 1 sale" else "in ${week.sales} sales"
				val salesX = x + font.width(total) * 2 + 6
				if (salesX + font.width(sales) <= right) graphics.text(font, sales, salesX, rowY + 7, DIM, false)
			}
			rowY += BIG

			if (since != 0L) {
				if (graphics != null) {
					val session = plus(profit)
					graphics.text(font, session, x, rowY, tone(profit), false)
					graphics.text(font, "this session", x + font.width("$session "), rowY, DIM, false)
					perHour()?.let { graphics.text(font, "$it an hour", right - font.width("$it an hour"), rowY, DIM, false) }
				}
				rowY += LINE
			}

			rowY += 3
			if (graphics != null) {
				drawChart(graphics, x, rowY, week)
				val labelY = rowY + CHART + 3
				val nowX = right - font.width("now")
				graphics.text(font, "now", nowX, labelY, TEXT, false)
				var free = x
				for ((column, label) in week.labels) {
					val labelX = x + column
					// Days too close to the last one, or to "now", go unnamed.
					if (labelX < free || labelX + font.width(label) > nowX - 3) continue
					graphics.text(font, label, labelX, labelY, DIM, false)
					free = labelX + font.width(label) + 2
				}
			}
			rowY += CHART + 3 + LINE

			week.best?.let { best ->
				if (graphics != null) {
					val made = plus(best.amount)
					graphics.item(icons[best.name] ?: Farming.iconFor(best.name) ?: claimIcon, x, rowY)
					val units = " ${best.units}x"
					val room = right - font.width(made) - 6 - (x + ICON + ICON_GAP)
					val name = fit(best.name, room)
					val textY = rowY + (ICON - font.lineHeight) / 2 + 1
					graphics.text(font, name, x + ICON + ICON_GAP, textY, TEXT, false)
					if (font.width(name) + font.width(units) <= room) {
						graphics.text(font, units, x + ICON + ICON_GAP + font.width(name), textY, DIM, false)
					}
					graphics.text(font, made, right - font.width(made), textY, AMBER, false)
				}
				rowY += ICON + 2
			}
		}

		if (goal() != null) {
			rowY += 1
			if (graphics != null) drawGoal(graphics, x, rowY, width)
			rowY += GOAL_HEIGHT
		}
		return rowY - y
	}

	private class FlipsElement : HudElement("bazaar_flips", "Bazaar Flips", 0.02, 0.3) {
		/** The step's block: the icon, with a second line of text when the step has one. */
		private fun head(step: Step) = if (step.detail != null) LINE * 2 - 2 else ICON

		private fun widthOf(step: Step) =
			maxOf(MIN_WIDTH, PAD * 2 + ICON + ICON_GAP + maxOf(font.width(step.text), step.detail?.let { font.width(it) } ?: 0))

		private fun stepOf(example: Boolean) =
			if (example) Step("Claim 64x Enchanted Diamond", true, ItemStack(Items.DIAMOND)) else step()

		private fun heightOf(step: Step) = PAD * 2 + head(step) + ROW_GAP + BIG + LINE + goalHeight()

		override val width: Int
			get() = widthOf(stepOf(false))
		override val height: Int get() = heightOf(stepOf(false))

		override fun isVisible(): Boolean =
			Flipping.module.enabled && Flipping.flipHud.value && SkyblockLocation.onSkyblock &&
				(Bazaar.openOrders.isNotEmpty() || since != 0L || Flipping.running)

		override fun showInEditor(): Boolean = Flipping.module.enabled && Flipping.flipHud.value

		override fun render(context: GuiGraphicsExtractor) {
			keepOnScreen(context)
			draw(context, false)
		}

		override fun renderExample(context: GuiGraphicsExtractor) = draw(context, true)

		private fun draw(context: GuiGraphicsExtractor, example: Boolean) {
			val step = stepOf(example)
			val w = widthOf(step)
			val head = head(step)
			BazaarHistory.panelBox(context, 0, 0, w, heightOf(step))

			context.item(step.icon, PAD, PAD + (head - ICON) / 2)
			val textX = PAD + ICON + ICON_GAP
			if (step.detail == null) {
				context.text(font, step.text, textX, PAD + (ICON - font.lineHeight) / 2 + 1, if (step.urgent) AMBER else TEXT, false)
			} else {
				context.text(font, step.text, textX, PAD + 1, if (step.urgent) AMBER else TEXT, false)
				context.text(font, step.detail, textX, PAD + LINE, DIM, false)
			}

			var y = PAD + head + ROW_GAP
			val right = w - PAD
			val shown = if (example) 1_250_000.0 else profit
			val value = headline(shown)
			big(context, value, PAD, y, tone(shown))
			drawGain(context, PAD + font.width(value) * 2 + 6, y + 7, example)
			y += BIG

			context.text(font, "this session", PAD, y, DIM, false)
			val hourly = if (example) "2.4M" else perHour()
			hourly?.let { context.text(font, "$it an hour", right - font.width("$it an hour"), y, DIM, false) }
			y += LINE
			drawGoal(context, PAD, y + 1, w - PAD * 2)
		}

		/** The latest gain beside the profit, fading out. */
		private fun drawGain(context: GuiGraphicsExtractor, x: Int, y: Int, example: Boolean) {
			if (example) return
			val age = System.currentTimeMillis() - gainAt
			if (age >= GAIN_MILLIS || gain == 0.0) return
			val alpha = ((1f - age.toFloat() / GAIN_MILLIS) * 255).toInt()
			// Text this faint draws fully opaque instead.
			if (alpha < 8) return
			val rgb = if (gain < 0) LOSS else TEXT and 0xFFFFFF
			context.text(font, plus(gain), x, y, (alpha shl 24) or rgb, false)
		}
	}

	// ---- Saving -------------------------------------------------------------------------------

	private fun load() {
		runCatching {
			if (!Files.exists(file)) return
			val type = object : TypeToken<Map<String, Held>>() {}.type
			gson.fromJson<Map<String, Held>>(Files.readString(file), type)?.let(held::putAll)
		}.onFailure { Cryptic.LOGGER.warn("Could not read Bazaar flips", it) }
	}

	private fun save() {
		runCatching {
			Files.createDirectories(file.parent)
			Files.writeString(file, gson.toJson(held))
		}.onFailure { Cryptic.LOGGER.warn("Could not save Bazaar flips", it) }
	}

	private fun loadHistory() {
		runCatching {
			if (!Files.exists(historyFile)) return
			val saved = gson.fromJson(Files.readString(historyFile), Saved::class.java) ?: return
			saved.gains?.sortedBy { it.at }?.let(gains::addAll)
			goalProgress = saved.goalProgress
			trim(System.currentTimeMillis())
		}.onFailure { Cryptic.LOGGER.warn("Could not read the week's flips", it) }
	}

	private fun saveHistory() {
		runCatching {
			Files.createDirectories(historyFile.parent)
			Files.writeString(historyFile, gson.toJson(Saved(gains, goalProgress)))
		}.onFailure { Cryptic.LOGGER.warn("Could not save the week's flips", it) }
	}

	fun describe(): List<String> = listOf(
		"Flips: profit ${signed(profit)} this session, ${gains.size} sales kept for the week, ${signed(goalProgress)} towards the goal; holding " +
			held.entries.joinToString { "${it.value.units}x ${it.key} (${Bazaar.short(it.value.cost)})" }.ifEmpty { "nothing" },
	)
}
