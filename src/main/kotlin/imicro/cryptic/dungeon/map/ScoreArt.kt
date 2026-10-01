package imicro.cryptic.dungeon.map

import com.mojang.blaze3d.platform.NativeImage
import imicro.cryptic.Cryptic
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import net.minecraft.world.level.material.MapColor

/**
 * The picture Hypixel puts on the map when a run is over.
 *
 * The end of a run replaces the floor with the score screen: four categories,
 * a grade and a number. There is nothing to read out of it, so it is kept as
 * it arrives and drawn as it is.
 *
 * As one texture, not as rectangles. The first version of this drew the
 * picture as runs of one colour — a few thousand of them for a screen with
 * lettering on it — and every one was a quad, every frame, which cost most of
 * the frame rate at the end of a run. A map is a picture, so it is uploaded
 * once as a picture and drawn with one call after that.
 */
object ScoreArt {
	/** One side of the map item, which is square. */
	const val SIZE = 128

	val TEXTURE: Identifier = Cryptic.id("map/score_art")

	var ready: Boolean = false
		private set

	private var texture: DynamicTexture? = null

	/**
	 * Takes the colours off a finished run's map and uploads them.
	 *
	 * The bytes are map colours rather than pixels — an index into Minecraft's
	 * own palette, which is what a map item stores — so each one is looked up
	 * before it goes into the image.
	 */
	fun capture(colors: ByteArray) {
		if (colors.size < SIZE * SIZE) return

		val image = NativeImage(SIZE, SIZE, false)
		for (y in 0 until SIZE) {
			for (x in 0 until SIZE) {
				val packed = colors[y * SIZE + x].toInt() and 0xFF
				// Nothing painted stays nothing: the map item is bare around
				// its edges, and a black border is not what the HUD wants.
				val argb = if (packed == 0) 0 else MapColor.getColorFromPackedId(packed)
				image.setPixelABGR(x, y, toAbgr(argb))
			}
		}

		val client = Minecraft.getInstance()
		texture?.close()
		val uploaded = DynamicTexture({ "Cryptic score art" }, image)
		client.textureManager.register(TEXTURE, uploaded)
		texture = uploaded
		ready = true
	}

	fun forget() {
		if (!ready) return
		ready = false
		Minecraft.getInstance().textureManager.release(TEXTURE)
		texture?.close()
		texture = null
	}

	/** Minecraft's map palette is ARGB; a native image wants the bytes the other way. */
	private fun toAbgr(argb: Int): Int {
		val alpha = argb ushr 24 and 0xFF
		val red = argb ushr 16 and 0xFF
		val green = argb ushr 8 and 0xFF
		val blue = argb and 0xFF
		return (alpha shl 24) or (blue shl 16) or (green shl 8) or red
	}
}
