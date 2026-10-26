package imicro.cryptic.dungeon.map

import com.mojang.blaze3d.platform.NativeImage
import imicro.cryptic.Cryptic
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import net.minecraft.world.level.material.MapColor

/**
 * The score screen Hypixel draws on the map when a run ends, uploaded once as a texture and drawn
 * in one call.
 */
object ScoreArt {
	const val SIZE = 128

	val TEXTURE: Identifier = Cryptic.id("map/score_art")

	var ready: Boolean = false
		private set

	private var texture: DynamicTexture? = null

	/** Takes a finished run's map colours (palette indices) and uploads them. */
	fun capture(colors: ByteArray) {
		if (colors.size < SIZE * SIZE) return

		val image = NativeImage(SIZE, SIZE, false)
		for (y in 0 until SIZE) {
			for (x in 0 until SIZE) {
				val packed = colors[y * SIZE + x].toInt() and 0xFF
				// Unpainted stays transparent, not black.
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

	private fun toAbgr(argb: Int): Int {
		val alpha = argb ushr 24 and 0xFF
		val red = argb ushr 16 and 0xFF
		val green = argb ushr 8 and 0xFF
		val blue = argb and 0xFF
		return (alpha shl 24) or (blue shl 16) or (green shl 8) or red
	}
}
