package imicro.cryptic.chunkcache

import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Chunk columns by position, read without a lock: the chunk builders ask for a block or a light
 * level thousands of times a section. A ring of 128 by 128 slots, so two columns only share a slot
 * 128 chunks apart, further than any view reaches. Written from the client thread only.
 */
class ColumnTable<T : ColumnTable.Column> {
	interface Column {
		val columnX: Int
		val columnZ: Int
	}

	private val slots = AtomicReferenceArray<T?>(SIZE * SIZE)

	@Volatile
	var count = 0
		private set

	fun get(x: Int, z: Int): T? {
		val column = slots.get(index(x, z)) ?: return null
		return if (column.columnX == x && column.columnZ == z) column else null
	}

	fun put(column: T) {
		if (slots.getAndSet(index(column.columnX, column.columnZ), column) == null) count++
	}

	fun remove(x: Int, z: Int): T? {
		val column = get(x, z) ?: return null
		slots.set(index(x, z), null)
		count--
		return column
	}

	fun clear() {
		for (i in 0 until slots.length()) slots.set(i, null)
		count = 0
	}

	private fun index(x: Int, z: Int): Int = (x and MASK) or ((z and MASK) shl BITS)

	companion object {
		private const val BITS = 7
		private const val SIZE = 1 shl BITS
		private const val MASK = SIZE - 1

		/** The widest view the ring holds without two columns meeting. */
		const val MAX_RADIUS = SIZE / 2 - 2
	}
}
