package imicro.cryptic.gui.menu.managers

import imicro.cryptic.feature.SoundVolumes
import imicro.cryptic.gui.menu.Box
import imicro.cryptic.gui.menu.ManagerSection
import imicro.cryptic.gui.menu.MenuStyle
import imicro.cryptic.gui.menu.TextField
import imicro.cryptic.gui.menu.UiText
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Sound Manager: every sound the game knows, searchable, each with its own volume and a play
 * button. NoammAddons' layout: search, category chips by id prefix, and a Recent list, which is how
 * a sound just heard is found.
 */
class SoundsSection : ManagerSection("Sounds") {
	private enum class Tab(val title: String) {
		ALL("All"), RECENT("Recent"), CHANGED("Changed"),
		BLOCKS("Blocks"), ENTITIES("Mobs"), ITEMS("Items"), MUSIC("Music"), AMBIENT("Ambient"), UI("UI"), OTHER("Other"),
	}

	private val search = TextField(64)
	private var tab = Tab.ALL

	private val everySound: List<String> = BuiltInRegistries.SOUND_EVENT.keySet().map { it.toString() }.sorted()
	private val everySoundSet: Set<String> = everySound.toHashSet()

	/** The list as last filtered, kept until the search or tab changes. */
	private var shown: List<String> = emptyList()
	private var shownFor = ""
	private var changed = false

	override fun onClose() {
		if (changed) SoundVolumes.save()
	}

	override fun draw(x: Float, y: Float, width: Float): Float {
		var cy = y
		val pad = 18f
		// The card's height depends on how the chips wrap, so they're measured first.
		val wrap = chipRows(width - pad * 2f)
		val cardH = 18f + 34f + 12f + wrap * (imicro.cryptic.gui.menu.UiKit.CHIP_HEIGHT + 6f) - 6f + 14f + 18f + 14f
		ui.card(Box(x, cy, width, cardH))
		var inner = cy + 18f
		ui.field(Box(x + pad, inner, width - pad * 2f, 34f), search, "Search sounds, e.g. enderman")
		inner += 34f + 12f
		inner += ui.chips(x + pad, inner, width - pad * 2f, Tab.entries, { it.title }, { it == tab }) { tab = it } + 14f
		ui.text("0% is silent, up to 200%. Over 100% only helps sounds that play quietly to begin with.", x + pad, inner, 12f, MenuStyle.MUTED)
		cy += cardH + 14f

		val list = filtered()
		if (list.isEmpty()) {
			val text = if (tab == Tab.RECENT && search.isEmpty) "Nothing has played yet." else "No sounds match."
			ui.text(text, x + 2f, cy + 4f, 13f, MenuStyle.MUTED)
			return cy + 24f - y
		}
		val rowH = 44f
		for (id in list) {
			if (cy + rowH >= ui.area.y && cy <= ui.area.bottom) drawRow(id, x, cy, width, rowH)
			cy += rowH + 2f
		}
		return cy - y
	}

	private fun chipRows(width: Float): Int {
		var rows = 1
		var cx = 0f
		for (tab in Tab.entries) {
			val w = ui.chipWidth(tab.title)
			if (cx > 0f && cx + w > width) {
				rows++
				cx = 0f
			}
			cx += w + 6f
		}
		return rows
	}

	private fun drawRow(id: String, x: Float, y: Float, width: Float, h: Float) {
		ui.card(Box(x, y, width, h))
		val pad = 18f
		val cy = y + h / 2f
		val percent = SoundVolumes.percent(id)

		var right = x + width - pad
		ui.iconButton(Box(right - 28f, cy - 14f, 28f, 28f), Sections.PLAY) { preview(id, percent) }
		right -= 28f + 4f
		if (percent != 100) {
			ui.iconButton(Box(right - 28f, cy - 14f, 28f, 28f), Sections.UNDO, MenuStyle.MUTED) {
				SoundVolumes.set(id, 100)
				changed = true
			}
		}
		right -= 28f + 12f

		val valueText = "$percent%"
		val valueW = ui.width("200%", 13f)
		ui.text(valueText, right - ui.width(valueText, 13f), cy - 6.5f, 13f, if (percent == 100) MenuStyle.MUTED else MenuStyle.TEXT)
		right -= valueW + 16f

		val sliderW = 220f
		ui.slider(Box(right - sliderW, cy - 10f, sliderW, 20f), percent / SoundVolumes.MAX_PERCENT.toFloat()) { ratio ->
			val value = ((ratio * SoundVolumes.MAX_PERCENT) / STEP).roundToInt() * STEP
			if (value != SoundVolumes.percent(id)) {
				SoundVolumes.set(id, value)
				changed = true
			}
		}
		val name = id.removePrefix("minecraft:")
		val room = right - sliderW - 20f - (x + pad)
		ui.text(ui.fit(name, 13f, room), x + pad, cy - 6.5f, 13f, if (percent == 100) MenuStyle.TEXT else MenuStyle.accent(), UiText.Face.REGULAR)
	}

	/** What the search and tab leave, recomputed only when either changes. */
	private fun filtered(): List<String> {
		val query = search.text.trim().lowercase(Locale.ROOT)
		// Recent and Changed move on their own, so they're never cached.
		val key = "$tab\u0000$query"
		if (key == shownFor && tab != Tab.RECENT && tab != Tab.CHANGED) return shown

		val source = when (tab) {
			Tab.RECENT -> SoundVolumes.recent()
			Tab.CHANGED -> SoundVolumes.volumes.keys.sorted()
			else -> (everySound + SoundVolumes.recent().filter { it !in everySoundSet }).sorted().filter { tab == Tab.ALL || tabOf(it) == tab }
		}
		val terms = query.split(Regex("\\s+")).filter { it.isNotEmpty() }
		shown = source.filter { id -> terms.all { term -> id.contains(term) || id.replace('.', ' ').replace('_', ' ').contains(term) } }
		shownFor = key
		return shown
	}

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

	/** Plays [id] at [percent], from half volume so double can be heard. */
	private fun preview(id: String, percent: Int) {
		val location = Identifier.tryParse(id) ?: return
		// With the module on, the volume is scaled on the way out like any sound; off, the preview
		// scales itself.
		val volume = if (SoundVolumes.module.enabled) PREVIEW_VOLUME else PREVIEW_VOLUME * percent / 100f
		Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(location), 1f, volume))
	}

	private companion object {
		const val STEP = 5
		const val PREVIEW_VOLUME = 0.5f
	}
}
