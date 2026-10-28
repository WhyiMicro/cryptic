package imicro.cryptic.skyblock

import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A trail of particles Hypixel draws from your eyes toward something (a vacuum to a pest, a hotspot
 * radar to a hotspot), read as it arrives and followed to its end before it gets there.
 *
 * Hypixel draws it as a cubic Bézier curve, one point per step, whose first control point lies 4
 * blocks from a spot 3 blocks above the start. A cubic fitted to the points gives the start's
 * direction per step; the step count at which that control point lands on the sphere is the
 * curve's length, and the fitted cubic there is its end.
 */
class ParticleTrail(
	/** How near your eyes a trail's first point is. */
	private val startReach: Double = 4.0,
	/** How far one point is from the last. */
	private val stepReach: Double = 3.5,
	/** A pause this long ends the trail. */
	private val gapMillis: Long = 1_000L,
	/** Points needed before the end is worked out. */
	private val minPoints: Int = 5,
) {
	private val points = ArrayList<Vec3>()
	private var lastAt = 0L

	val size: Int get() = points.size

	fun clear() = points.clear()

	/** Adds a particle at [at]; the trail's end when there are enough points to tell, else null. */
	fun offer(at: Vec3, eye: Vec3, now: Long = System.currentTimeMillis()): Vec3? {
		if (points.isEmpty() || now - lastAt > gapMillis) {
			// A new trail starts at your eyes.
			if (at.distanceTo(eye) > startReach) return null
			points.clear()
			points += at
			lastAt = now
			return null
		}
		if (at.distanceTo(points.last()) > stepReach) return null
		// Hypixel sends some points twice; a repeat would stall the curve and throw its end far off.
		if (points.any { it.distanceToSqr(at) < 1e-4 }) return null
		points += at
		lastAt = now
		return if (points.size >= minPoints) end(points) else null
	}

	companion object {
		/** Where a trail through [points] ends, or null when they don't make a curve. */
		fun end(points: List<Vec3>): Vec3? {
			val x = fitCubic(points.map { it.x }) ?: return null
			val y = fitCubic(points.map { it.y }) ?: return null
			val z = fitCubic(points.map { it.z }) ?: return null
			// The control point's offset per step: a cubic Bézier's starting slope is three times it.
			val vx = x[1] / 3
			val vy = y[1] / 3
			val vz = z[1] / 3
			val length2 = vx * vx + vy * vy + vz * vz
			if (length2 < 1e-9) return null
			// |n·v - (0, 3, 0)| = 4, solved for the number of steps n.
			val steps = (3 * vy + sqrt(9 * vy * vy + 7 * length2)) / length2
			if (!steps.isFinite() || steps <= 0) return null
			fun at(c: DoubleArray) = c[0] + steps * (c[1] + steps * (c[2] + steps * c[3]))
			return Vec3(at(x), at(y), at(z))
		}

		/** Least squares for a + b·i + c·i² + d·i³ through [values] at i = 0, 1, 2... */
		private fun fitCubic(values: List<Double>): DoubleArray? {
			val matrix = Array(4) { DoubleArray(5) }
			values.forEachIndexed { i, value ->
				val powers = DoubleArray(4).also { p -> var term = 1.0; for (k in 0..3) { p[k] = term; term *= i } }
				for (row in 0..3) {
					for (col in 0..3) matrix[row][col] += powers[row] * powers[col]
					matrix[row][4] += powers[row] * value
				}
			}
			// Gaussian elimination with partial pivoting.
			for (col in 0..3) {
				val pivot = (col..3).maxBy { abs(matrix[it][col]) }
				if (abs(matrix[pivot][col]) < 1e-12) return null
				val swap = matrix[col]; matrix[col] = matrix[pivot]; matrix[pivot] = swap
				for (row in 0..3) {
					if (row == col) continue
					val factor = matrix[row][col] / matrix[col][col]
					for (k in col..4) matrix[row][k] -= factor * matrix[col][k]
				}
			}
			return DoubleArray(4) { matrix[it][4] / matrix[it][it] }
		}
	}
}
