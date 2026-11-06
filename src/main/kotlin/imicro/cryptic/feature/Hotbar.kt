package imicro.cryptic.feature

import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.gui.withMore
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.abs
import kotlin.math.exp

/**
 * Hotbar animations: the selection box glides between slots and the held item can rise out of its
 * slot. The idea is VanillaHUD's (GPL-3.0); this is Cryptic's own. Driven from `HudMixin`, which
 * moves vanilla's own selection sprite and items, so resource packs still apply.
 */
object Hotbar {
	@JvmField
	val smooth = ToggleModuleSetting(
		id = "smooth",
		label = "Smooth selection",
		defaultValue = true,
		description = "The selection box slides to the slot you pick instead of jumping.",
	)

	@JvmField
	val speed = SliderModuleSetting(
		id = "speed",
		label = "Speed",
		defaultValue = 14.0,
		min = 4.0,
		max = 40.0,
		step = 1.0,
		visibleIf = { smooth.value },
	)

	@JvmField
	val lift = ToggleModuleSetting(
		id = "lift",
		label = "Raise held item",
		defaultValue = false,
		description = "The item in your hand rises a little out of its slot, and sinks back when you scroll away.",
	)

	@JvmField
	val liftAmount = SliderModuleSetting(
		id = "lift_amount",
		label = "Height",
		unit = "px",
		defaultValue = 2.0,
		min = 1.0,
		max = 6.0,
		step = 0.5,
		visibleIf = { lift.value },
	)

	@JvmField
	val numbers = ToggleModuleSetting(
		id = "numbers",
		label = "Slot numbers",
		defaultValue = false,
		description = "A small 1 to 9 in the corner of each slot.",
	)

	@JvmField
	val stopAtEnds = ToggleModuleSetting(
		id = "stop_at_ends",
		label = "Stop at the ends",
		defaultValue = false,
		description = "Scrolling past slot 9 stays on 9, and past slot 1 stays on 1, instead of wrapping round.",
	)

	@JvmField
	val module = Module(
		id = "hotbar",
		name = "Hotbar",
		description = "Hotbar animations",
		category = ModuleCategory.VISUAL,
		settings = listOf(smooth.withMore(speed), lift.withMore(liftAmount), numbers, stopAtEnds),
	)

	/** From `MouseHandlerMixin`, when the wheel changes the held slot. */
	@JvmStatic
	fun stopsAtEnds(): Boolean = module.enabled && stopAtEnds.value

	/** Where the box is drawn, in slots; follows the real slot. */
	private var shownSlot = Float.NaN
	private val lifted = FloatArray(9)
	private var lastFrame = 0L
	private var frameDt = 0f

	/** Once per frame, before the hotbar is drawn: moves everything toward the held slot. */
	private fun step(selected: Int) {
		val now = System.nanoTime()
		if (lastFrame == 0L || now - lastFrame > 500_000_000L) {
			frameDt = 0f
		} else {
			frameDt = (now - lastFrame) / 1_000_000_000f
		}
		lastFrame = now

		if (shownSlot.isNaN() || !smooth.value) {
			shownSlot = selected.toFloat()
		} else {
			val t = 1f - exp(-speed.value.toFloat() * frameDt)
			shownSlot += (selected - shownSlot) * t
			if (abs(selected - shownSlot) < 0.01f) shownSlot = selected.toFloat()
		}

		val liftT = 1f - exp(-18f * frameDt)
		val height = if (lift.value) liftAmount.value.toFloat() else 0f
		for (i in 0 until 9) {
			val goal = if (i == selected) height else 0f
			lifted[i] = if (frameDt == 0f) goal else lifted[i] + (goal - lifted[i]) * liftT
		}
	}

	/** Pixels to move vanilla's selection box by, from where vanilla puts it. Steps the animation. */
	@JvmStatic
	fun selectionOffset(): Float {
		if (!module.enabled) {
			shownSlot = Float.NaN
			return 0f
		}
		val selected = Minecraft.getInstance().player?.inventory?.selectedSlot ?: return 0f
		step(selected)
		return (shownSlot - selected) * 20f
	}

	/** How far to raise the item in hotbar slot [seed] (1 to 9; higher is the off hand). */
	@JvmStatic
	fun lift(seed: Int): Float {
		if (!module.enabled || seed < 1 || seed > 9) return 0f
		return lifted[seed - 1]
	}

	/** The slot's number, over its item. */
	@JvmStatic
	fun drawNumber(graphics: GuiGraphicsExtractor, x: Int, y: Int, seed: Int) {
		if (!module.enabled || !numbers.value || seed < 1 || seed > 9) return
		val font = Minecraft.getInstance().font
		val pose = graphics.pose()
		pose.pushMatrix()
		pose.translate(x - 1f, y - 1f)
		pose.scale(0.5f, 0.5f)
		graphics.text(font, seed.toString(), 0, 0, 0xFFD0D0D0.toInt(), true)
		pose.popMatrix()
	}
}
