package imicro.cryptic.dungeon.map

/**
 * A pair of whole-number coordinates on the dungeon grid or the map item.
 *
 * Ported from dtMap (BSD 3-Clause, Copyright (c) 2026 rice.who). The full
 * licence is in `licenses/dtMap-LICENSE.txt`.
 */
data class Vec2i(val x: Int, val z: Int) {
	fun add(other: Vec2i): Vec2i = Vec2i(x + other.x, z + other.z)
	fun add(x0: Int, z0: Int): Vec2i = Vec2i(x + x0, z + z0)
	fun multiply(number: Int): Vec2i = Vec2i(x * number, z * number)
	fun multiply(number: Double): Vec2i = Vec2i((x * number).toInt(), (z * number).toInt())
	fun divide(number: Double): Vec2i = Vec2i((x / number).toInt(), (z / number).toInt())

	/** The index of this pixel in a map item's colour array. */
	fun mapIndex(): Int = z * 128 + x
}
