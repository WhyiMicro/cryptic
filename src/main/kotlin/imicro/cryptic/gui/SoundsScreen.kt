package imicro.cryptic.gui

import imgui.ImDrawList
import imgui.type.ImString
import imicro.cryptic.feature.SoundVolumes
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Sound Manager's window: every sound the game knows, searchable, each
 * with its own volume and a button to hear it.
 *
 * NoammAddons' layout — a search box, a row of categories by what the id
 * starts with, and a Recent list of what has just played, which is how a sound
 * heard in game is found without knowing its name.
 */
class SoundsScreen: ManagerScreen("Sound Manager", "sounds") {
	private enum class Tab(val title: String) {
		ALL("All"), RECENT("Recent"), CHANGED("Changed"),
		BLOCKS("Blocks"), ENTITIES("Mobs"), ITEMS("Items"), MUSIC("Music"), AMBIENT("Ambient"), UI("UI"), OTHER("Other"),
	}

	private val search = ImString(64)
	private var tab = Tab.ALL

	/** Every registered sound, sorted, worked out once per opening. */
	private val everySound: List<String> = BuiltInRegistries.SOUND_EVENT.keySet().map { it.toString() }.sorted()

	/** The list as last filtered, kept until the search or the tab changes. */
	private var shown: List<String> = emptyList()
	private var shownFor = ""

	private var changed = false
	private var pending: (() -> Unit)? = null

	override fun onClosed() {
		if (changed) SoundVolumes.save()
	}

	override fun afterFrame() {
		pending?.invoke()
		pending = null
	}

	override fun drawContent(draw: ImDrawList, x: Float, top: Float, width: Float): Float {
		var y = top
		val pad = dp(16f)
		val inner = width - pad * 2f

		y += measuredCard(draw, x, y, width) {
			var cy = y + dp(12f)
			label(draw, "Sounds", x + pad, cy, MUTED_TEXT, dp(9f))
			cy += dp(16f)
			textField(draw, "##sound_search", "Search, e.g. enderman", search, x + pad, cy, inner, dp(22f))
			cy += dp(30f)
			cy += chips(draw, "sound_tab", Tab.entries, { it.title }, { it == tab }, x + pad, cy, inner) {
				tab = it
				scrollToTop()
			} + dp(8f)
			label(draw, "0% is silent, up to 200%. Over 100% only helps sounds that play quietly to begin with.", x + pad, cy, MUTED_TEXT, dp(8.5f))
			cy += dp(14f)
			if (!SoundVolumes.module.enabled) {
				cy += dp(4f)
				label(draw, "Sound Manager is off in Settings, so these are kept but not applied.", x + pad, cy + dp(3f), DELETE_TEXT, dp(9f))
				val (_, enable) = buttonLeftOf(draw, "##sound_enable", "Turn on", x + width - pad, cy, dp(18f))
				if (enable) pending = { SoundVolumes.module.enabled = true }
				cy += dp(20f)
			}
			cy + dp(8f) - y
		} + dp(CARD_GAP)

		val list = filtered()
		if (list.isEmpty()) {
			val emptyHeight = dp(46f)
			card(draw, x, y, width, emptyHeight)
			val text = if (tab == Tab.RECENT && search.get().isBlank()) "Nothing has played yet" else "No sounds match"
			centered(draw, text, x, y, width, emptyHeight, MUTED_TEXT, dp(11f))
			return y + emptyHeight - top
		}

		val rowHeight = dp(ROW_HEIGHT)
		val step = rowHeight + dp(4f)
		list.forEachIndexed { index, id ->
			if (inView(y, rowHeight)) drawRow(draw, x, y, width, id, index)
			y += step
		}
		return y - top
	}

	private fun drawRow(draw: ImDrawList, x: Float, y: Float, width: Float, id: String, index: Int) {
		val pad = dp(14f)
		card(draw, x, y, width, dp(ROW_HEIGHT))

		val percent = SoundVolumes.percent(id)
		val buttonSize = dp(18f)
		val buttonY = y + (dp(ROW_HEIGHT) - buttonSize) / 2f
		var right = x + width - pad - buttonSize
		if (iconButton(draw, "##sound_play_$index", FontAwesomeIcons.PLAY, right, buttonY, buttonSize)) preview(id, percent)
		right -= buttonSize + dp(4f)
		if (percent != 100) {
			if (iconButton(draw, "##sound_reset_$index", FontAwesomeIcons.UNDO, right, buttonY, buttonSize, MUTED_TEXT)) {
				SoundVolumes.set(id, 100)
				changed = true
			}
		}
		right -= dp(10f)

		val valueText = "$percent%"
		val valueWidth = textWidth("200%", dp(10f))
		label(draw, valueText, right - textWidth(valueText, dp(10f)), y + dp(10f), if (percent == 100) MUTED_TEXT else TEXT, dp(10f))
		right -= valueWidth + dp(10f)

		val sliderWidth = dp(110f)
		val sliderX = right - sliderWidth
		slider(draw, "##sound_slider_$index", sliderX, y + dp(14f), sliderWidth, percent / SoundVolumes.MAX_PERCENT.toFloat())?.let { ratio ->
			val value = ((ratio * SoundVolumes.MAX_PERCENT) / STEP).roundToInt() * STEP
			if (value != percent) {
				SoundVolumes.set(id, value)
				changed = true
			}
		}

		val name = id.removePrefix("minecraft:")
		label(draw, ellipsize(name, dp(10f), sliderX - dp(12f) - x - pad), x + pad, y + dp(10f), if (percent == 100) TEXT else ACCENT, dp(10f))
	}

	/** What the search and the tab leave, worked out again only when either changes. */
	private fun filtered(): List<String> {
		val query = search.get().trim().lowercase(Locale.ROOT)
		// Recent and Changed move on their own, so those are never kept.
		val key = "$tab\u0000$query"
		if (key == shownFor && tab != Tab.RECENT && tab != Tab.CHANGED) return shown

		val source = when (tab) {
			Tab.RECENT -> SoundVolumes.recent()
			Tab.CHANGED -> SoundVolumes.volumes.keys.sorted()
			else -> (everySound + SoundVolumes.recent().filter { it !in everySoundSet }).sorted().filter { tabOf(it) == tab || tab == Tab.ALL }
		}
		val terms = query.split(Regex("\\s+")).filter { it.isNotEmpty() }
		shown = source.filter { id -> terms.all { term -> id.contains(term) || id.replace('.', ' ').replace('_', ' ').contains(term) } }
		shownFor = key
		return shown
	}

	private val everySoundSet: Set<String> = everySound.toHashSet()

	private fun tabOf(id: String): Tab {
		val path = id.substringAfter(':')
		return when {
			path.startsWith("block.") -> Tab.BLOCKS
			path.startsWith("entity.") -> Tab.ENTITIES
			path.startsWith("item.") -> Tab.ITEMS
			path.startsWith("music") -> Tab.MUSIC
			path.startsWith("ambient.") || path.startsWith("weather.") -> Tab.AMBIENT
			path.startsWith("ui.") -> Tab.UI
			else -> Tab.OTHER
		}
	}

	/**
	 * Plays [id] the way it would sound at [percent]: from half volume, so twice
	 * as loud can be heard as twice as loud.
	 */
	private fun preview(id: String, percent: Int) {
		val location = Identifier.tryParse(id) ?: return
		// With the module on, the volume is scaled on the way out like any
		// other sound; with it off, the preview does it itself.
		val volume = if (SoundVolumes.module.enabled) PREVIEW_VOLUME else PREVIEW_VOLUME * percent / 100f
		Minecraft.getInstance().soundManager.play(
			SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(location), 1f, volume),
		)
	}

	companion object {
		private const val ROW_HEIGHT = 32f
		private const val STEP = 5
		private const val PREVIEW_VOLUME = 0.5f

		private var requested = false

		fun request() {
			requested = true
		}

		fun openIfRequested(client: Minecraft) {
			if (!requested) return
			requested = false
			ImGuiRuntime.open(client) { SoundsScreen() }
		}
	}
}
