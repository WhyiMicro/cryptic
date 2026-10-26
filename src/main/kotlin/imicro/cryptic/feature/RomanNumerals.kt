package imicro.cryptic.feature

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import java.util.Optional

/** Roman numerals in tooltips turned into numbers, for [TooltipFeatures]. */
object RomanNumerals {
	/**
	 * A numeral up to 39 right after a word ("Sharpness VII", "Tier §dIV"), ending the line or
	 * followed by a comma, a colour code or a gap. A lone "I" before a lowercase word is English.
	 */
	private val NUMERAL = Regex("""(?<=[A-Za-z] |[A-Za-z] §[0-9a-fk-or])(?=[IVX])(X{0,3}(?:IX|IV|V?I{0,3}))(?=$|,|§|\s(?![a-z]))""")

	private val VALUES = mapOf('I' to 1, 'V' to 5, 'X' to 10)

	private fun value(numeral: String): Int {
		var total = 0
		for (i in numeral.indices) {
			val v = VALUES.getValue(numeral[i])
			val next = numeral.getOrNull(i + 1)?.let(VALUES::getValue) ?: 0
			total += if (v < next) -v else v
		}
		return total
	}

	/**
	 * The line with its numerals replaced. Matched on the whole line, since a mod can split
	 * "Sharpness" and "VII" into pieces of their own; each piece keeps its style.
	 */
	fun inLine(line: Component): Component {
		val pieces = ArrayList<Pair<Style, String>>()
		line.visit({ style: Style, text: String ->
			pieces += style to text
			Optional.empty<Unit>()
		}, Style.EMPTY)
		val whole = pieces.joinToString("") { it.second }
		val matches = NUMERAL.findAll(whole).toList()
		if (matches.isEmpty()) return line

		val out: MutableComponent = Component.empty()
		var at = 0
		var next = 0
		for ((style, text) in pieces) {
			val built = StringBuilder()
			for (ch in text) {
				while (next < matches.size && at > matches[next].range.last) next++
				val match = matches.getOrNull(next)
				when {
					match == null || at < match.range.first -> built.append(ch)
					at == match.range.first -> built.append(value(match.value))
					// The rest of the numeral, already written as its number.
				}
				at++
			}
			if (built.isNotEmpty()) out.append(Component.literal(built.toString()).withStyle(style))
		}
		return out
	}
}
