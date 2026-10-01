package imicro.cryptic.feature

import imicro.cryptic.dungeon.Floor7
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.ModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import imicro.cryptic.render.WorldRender
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB

/**
 * Boxes the gate out of the section you are in, in a colour that says whether
 * it still stands.
 *
 * Ported from NoammAddons' Gate Highlight (CC0, Noamm9). Each of the first three
 * sections of the Goldor phase ends in a gate that has to be blown open before
 * the party can move on, and from the far side of a section there is no telling
 * at a glance whether it has gone yet.
 *
 * NoammAddons answers that by drawing the box only while the gate stands, so
 * that no box means it is open. The trouble is that no box also means the module
 * is off, or the section was read wrong, or the chunk has not arrived — an
 * absence cannot tell those apart. So a blown gate is drawn too, in a colour of
 * its own: red says wait, green says go, and nothing at all says nothing.
 *
 * Whether it stands is read off one block of it. A standing gate is built of
 * cracked and infested stone bricks, and a blown one is not there at all, so a
 * single block of the wall says which. The fourth section has no gate — it opens
 * onto the core — so there is nothing to draw there.
 *
 * Part of the F7/M7 QOL card rather than a card of its own, and never drawn
 * through walls: a gate is only worth seeing from where it can be walked to.
 */
object GateHighlight {
	private val styles = listOf("Outline", "Fill", "Fill + outline")
	private const val OUTLINE = 0
	private const val FILL = 1

	@JvmField
	val enabled = ToggleModuleSetting(
		id = "gate_highlight",
		label = "Gate highlight",
		defaultValue = true,
		description = "Boxes the gate out of the section you are in: red while it stands, green once it is blown.",
	)

	@JvmField
	val style = DropdownModuleSetting(
		id = "gate_style",
		label = "Style",
		options = styles,
		defaultIndex = 2,
		visibleIf = { enabled.value },
	)

	@JvmField
	val lineWidth = SliderModuleSetting(
		id = "gate_line_width",
		label = "Line width",
		defaultValue = 2.5,
		min = 1.0,
		max = 10.0,
		step = 0.1,
		visibleIf = { enabled.value && style.selectedIndex != FILL },
	)

	@JvmField
	val fillColor = ColorModuleSetting(
		id = "gate_fill_color",
		label = "Standing fill",
		defaultRgb = 0xFF5555,
		supportsAlpha = true,
		defaultAlpha = 0x32,
		visibleIf = { enabled.value && style.selectedIndex != OUTLINE },
	)

	@JvmField
	val outlineColor = ColorModuleSetting(
		id = "gate_outline_color",
		label = "Standing outline",
		defaultRgb = 0xFF5555,
		visibleIf = { enabled.value && style.selectedIndex != FILL },
	)

	@JvmField
	val showDestroyed = ToggleModuleSetting(
		id = "gate_show_destroyed",
		label = "Show when destroyed",
		defaultValue = true,
		description = "Keeps the box up in its own colour once the gate is blown, instead of taking it away.",
		visibleIf = { enabled.value },
	)

	@JvmField
	val destroyedFillColor = ColorModuleSetting(
		id = "gate_destroyed_fill_color",
		label = "Destroyed fill",
		defaultRgb = 0x55FF55,
		supportsAlpha = true,
		defaultAlpha = 0x32,
		visibleIf = { enabled.value && showDestroyed.value && style.selectedIndex != OUTLINE },
	)

	@JvmField
	val destroyedOutlineColor = ColorModuleSetting(
		id = "gate_destroyed_outline_color",
		label = "Destroyed outline",
		defaultRgb = 0x55FF55,
		visibleIf = { enabled.value && showDestroyed.value && style.selectedIndex != FILL },
	)

	/** Listed on the F7/M7 QOL card. */
	val settings: List<ModuleSetting> = listOf(
		enabled, style, lineWidth, fillColor, outlineColor,
		showDestroyed, destroyedFillColor, destroyedOutlineColor,
	)

	/** True while the gate is to be drawn, for the floor scans too. */
	val active: Boolean get() = F7Qol.module.enabled && enabled.value

	/**
	 * One section's gate: a block of it to read, and the box around the whole.
	 *
	 * Keyed by the section it closes off, since a party only ever cares about the
	 * gate in front of it — the next section's gate is still standing as well,
	 * and boxing that one too would be one box saying nothing new.
	 */
	private class Gate(val probe: BlockPos, val box: AABB)

	private val gates = mapOf(
		1 to Gate(BlockPos(103, 134, 123), AABB(95.0, 114.0, 122.0, 106.0, 134.0, 124.0)),
		2 to Gate(BlockPos(17, 134, 135), AABB(18.0, 114.0, 127.0, 19.0, 134.0, 138.0)),
		3 to Gate(BlockPos(5, 134, 49), AABB(1.0, 114.0, 49.0, 14.0, 134.0, 51.0)),
	)

	private enum class State { STANDING, DESTROYED, UNKNOWN }

	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		LevelRenderEvents.COLLECT_SUBMITS.register(::render)
	}

	private fun render(context: LevelRenderContext) {
		if (!active || !Floor7.inGoldor) return
		val gate = gates[Floor7.p3Section] ?: return

		val standing = when (stateOf(gate)) {
			State.STANDING -> true
			State.DESTROYED -> if (showDestroyed.value) false else return
			State.UNKNOWN -> return
		}

		val mode = style.selectedIndex
		WorldRender.drawBox(
			poseStack = context.poseStack(),
			collector = context.submitNodeCollector(),
			minX = gate.box.minX,
			minY = gate.box.minY,
			minZ = gate.box.minZ,
			maxX = gate.box.maxX,
			maxY = gate.box.maxY,
			maxZ = gate.box.maxZ,
			outlineArgb = if (standing) outlineColor.argb else destroyedOutlineColor.argb,
			fillArgb = if (standing) fillColor.argb else destroyedFillColor.argb,
			outline = mode != FILL,
			fill = mode != OUTLINE,
			// Never through walls: the gate is only worth seeing from where it can be walked to.
			phase = false,
			lineWidth = lineWidth.value.toFloat(),
		)
	}

	/**
	 * What one block of the gate says about the whole of it.
	 *
	 * Three answers, not two. A chunk the client has not been sent reads as air
	 * just as a blown gate does, and calling that "destroyed" would paint a gate
	 * green from across the tower before anybody had seen it — so a block that
	 * is not loaded is no answer at all, and nothing is drawn for it.
	 */
	private fun stateOf(gate: Gate): State {
		val level = Minecraft.getInstance().level ?: return State.UNKNOWN
		if (!level.isLoaded(gate.probe)) return State.UNKNOWN

		val state = level.getBlockState(gate.probe)
		return when {
			state.block == Blocks.CRACKED_STONE_BRICKS || state.block == Blocks.INFESTED_STONE_BRICKS -> State.STANDING
			state.isAir -> State.DESTROYED
			// Something else entirely: not a gate this was written for.
			else -> State.UNKNOWN
		}
	}
}
