import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/*
 * Trims the menu's fonts to the characters Cryptic draws, on every build.
 *
 * The full fonts live in src/fonts. Font Awesome keeps only the icons the code
 * uses (found by scanning the Kotlin sources, so a new icon is picked up on its
 * own); Poppins keeps everything but its Devanagari. A dropped glyph keeps its
 * id and metrics with no outline, so nothing else in the font has to change.
 * Hinting is kept, so text renders exactly as before. The layout tables
 * (GSUB, GPOS, kern) go: the menu draws one character at a time.
 */

val fontSources = file("src/fonts")
val kotlinSources = file("src/main/kotlin")
val trimmedFonts = layout.buildDirectory.dir("generated/fonts")

val trimFonts = tasks.register("trimFonts") {
	inputs.dir(fontSources)
	inputs.dir(kotlinSources)
	outputs.dir(trimmedFonts)
	doLast {
		val icons = iconCodePoints(kotlinSources)
		val out = trimmedFonts.get().asFile.resolve("assets/cryptic/font")
		out.deleteRecursively()
		out.mkdirs()
		for (font in fontSources.listFiles { f -> f.extension == "ttf" }!!.sortedBy { it.name }) {
			val keep: (Int) -> Boolean = if (font.name.startsWith("fa-")) icons::contains else ::isTextCodePoint
			val trimmed = trimFont(font.readBytes(), keep)
			out.resolve(font.name).writeBytes(trimmed)
			logger.info("Trimmed ${font.name}: ${font.length() / 1024} KB to ${trimmed.size / 1024} KB")
		}
	}
}

extensions.getByType<SourceSetContainer>()["main"].resources.srcDir(trimFonts)

/** Every Font Awesome code point the sources mention: as a character, a `\uF0xx` escape or `Char(0xF0xx)`. */
fun iconCodePoints(root: File): Set<Int> {
	val escape = Regex("""\\u([eEfF][0-9a-fA-F]{3})|Char\(0x([eEfF][0-9a-fA-F]{3})\)""")
	val found = HashSet<Int>()
	root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
		val text = file.readText()
		text.codePoints().forEach { if (it in 0xE000..0xF8FF) found += it }
		escape.findAll(text).forEach { found += (it.groupValues[1].ifEmpty { it.groupValues[2] }).toInt(16) }
	}
	return found
}

/** Poppins keeps everything except its Devanagari, which nothing in a SkyBlock menu is written in. */
fun isTextCodePoint(c: Int): Boolean = c !in 0x0900..0x097F && c !in 0x1CD0..0x1CFF && c !in 0xA8E0..0xA8FF

fun u16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
fun s16(b: ByteArray, at: Int): Int = u16(b, at).toShort().toInt()
fun u32(b: ByteArray, at: Int): Long = (u16(b, at).toLong() shl 16) or u16(b, at + 2).toLong()

/** Code point to glyph id, from every format 4 and 12 subtable of [cmap]. */
fun cmapEntries(cmap: ByteArray): Map<Int, Int> {
	val map = HashMap<Int, Int>()
	for (i in 0 until u16(cmap, 2)) {
		val sub = u32(cmap, 4 + i * 8 + 4).toInt()
		when (u16(cmap, sub)) {
			4 -> {
				val segs = u16(cmap, sub + 6) / 2
				val ends = sub + 14
				val starts = ends + segs * 2 + 2
				val deltas = starts + segs * 2
				val ranges = deltas + segs * 2
				for (s in 0 until segs) {
					val start = u16(cmap, starts + s * 2)
					val end = u16(cmap, ends + s * 2)
					val delta = s16(cmap, deltas + s * 2)
					val rangeOffset = u16(cmap, ranges + s * 2)
					for (c in start..end) {
						if (c == 0xFFFF) continue
						val gid = if (rangeOffset == 0) (c + delta) and 0xFFFF else {
							val g = u16(cmap, ranges + s * 2 + rangeOffset + 2 * (c - start))
							if (g == 0) 0 else (g + delta) and 0xFFFF
						}
						if (gid != 0) map[c] = gid
					}
				}
			}
			12 -> {
				val groups = u32(cmap, sub + 12).toInt()
				for (g in 0 until groups) {
					val at = sub + 16 + g * 12
					val start = u32(cmap, at).toInt()
					val end = u32(cmap, at + 4).toInt()
					val glyph = u32(cmap, at + 8).toInt()
					for (c in start..end) map[c] = glyph + (c - start)
				}
			}
		}
	}
	return map
}

/** The glyphs a composite glyph is built from. */
fun components(glyf: ByteArray, start: Int, end: Int): List<Int> {
	if (end - start < 10 || s16(glyf, start) >= 0) return emptyList()
	val found = ArrayList<Int>()
	var at = start + 10
	while (true) {
		val flags = u16(glyf, at)
		found += u16(glyf, at + 2)
		at += 4 + (if (flags and 0x0001 != 0) 4 else 2)
		at += when {
			flags and 0x0008 != 0 -> 2
			flags and 0x0040 != 0 -> 4
			flags and 0x0080 != 0 -> 8
			else -> 0
		}
		if (flags and 0x0020 == 0) return found
	}
}

fun trimFont(data: ByteArray, keepCodePoint: (Int) -> Boolean): ByteArray {
	val tables = sortedMapOf<String, ByteArray>()
	for (i in 0 until u16(data, 4)) {
		val record = 12 + i * 16
		val tag = String(data, record, 4, Charsets.ISO_8859_1)
		val offset = u32(data, record + 8).toInt()
		tables[tag] = data.copyOfRange(offset, offset + u32(data, record + 12).toInt())
	}
	val head = tables.getValue("head").copyOf()
	val glyf = tables.getValue("glyf")
	val loca = tables.getValue("loca")
	val glyphs = u16(tables.getValue("maxp"), 4)
	val longLoca = s16(head, 50) == 1
	val offsets = IntArray(glyphs + 1) { if (longLoca) u32(loca, it * 4).toInt() else u16(loca, it * 2) * 2 }

	val keep = BooleanArray(glyphs)
	keep[0] = true
	for ((c, gid) in cmapEntries(tables.getValue("cmap"))) if (gid < glyphs && keepCodePoint(c)) keep[gid] = true
	val pending = ArrayDeque(keep.indices.filter { keep[it] })
	while (pending.isNotEmpty()) {
		val g = pending.removeLast()
		for (part in components(glyf, offsets[g], offsets[g + 1])) {
			if (part < glyphs && !keep[part]) { keep[part] = true; pending += part }
		}
	}

	val newGlyf = ByteArrayOutputStream()
	val newLoca = ByteBuffer.allocate((glyphs + 1) * 4)
	for (g in 0 until glyphs) {
		newLoca.putInt(newGlyf.size())
		if (!keep[g]) continue
		newGlyf.write(glyf, offsets[g], offsets[g + 1] - offsets[g])
		while (newGlyf.size() % 4 != 0) newGlyf.write(0)
	}
	newLoca.putInt(newGlyf.size())
	tables["glyf"] = newGlyf.toByteArray()
	tables["loca"] = newLoca.array()
	head[50] = 0
	head[51] = 1
	for (i in 8..11) head[i] = 0
	tables["head"] = head
	// Glyph names are only for tools; format 3 has none.
	tables["post"]?.let { tables["post"] = it.copyOf(32).also { p -> p[0] = 0; p[1] = 3; p[2] = 0; p[3] = 0 } }
	for (tag in listOf("GSUB", "GPOS", "GDEF", "kern", "DSIG", "hdmx", "LTSH", "VDMX")) tables.remove(tag)

	return assemble(u32(data, 0), tables)
}

fun checksum(b: ByteArray): Long {
	var sum = 0L
	for (i in b.indices step 4) {
		var word = 0L
		for (j in 0..3) word = (word shl 8) or (if (i + j < b.size) (b[i + j].toLong() and 0xFF) else 0L)
		sum = (sum + word) and 0xFFFFFFFFL
	}
	return sum
}

fun assemble(version: Long, tables: Map<String, ByteArray>): ByteArray {
	val count = tables.size
	var power = 1
	while (power * 2 <= count) power *= 2
	val directory = 12 + count * 16
	var size = directory
	val offsets = LinkedHashMap<String, Int>()
	for ((tag, bytes) in tables) {
		offsets[tag] = size
		size += (bytes.size + 3) / 4 * 4
	}
	val out = ByteBuffer.allocate(size)
	out.putInt(version.toInt())
	out.putShort(count.toShort())
	out.putShort((power * 16).toShort())
	out.putShort(Integer.numberOfTrailingZeros(power).toShort())
	out.putShort((count * 16 - power * 16).toShort())
	for ((tag, bytes) in tables) {
		out.put(tag.toByteArray(Charsets.ISO_8859_1))
		out.putInt(checksum(bytes).toInt())
		out.putInt(offsets.getValue(tag))
		out.putInt(bytes.size)
	}
	for ((tag, bytes) in tables) {
		out.position(offsets.getValue(tag))
		out.put(bytes)
	}
	val font = out.array()
	val adjustment = (0xB1B0AFBAL - checksum(font)) and 0xFFFFFFFFL
	ByteBuffer.wrap(font).putInt(offsets.getValue("head") + 8, adjustment.toInt())
	return font
}
