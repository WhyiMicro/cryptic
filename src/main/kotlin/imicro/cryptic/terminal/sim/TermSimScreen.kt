package imicro.cryptic.terminal.sim

import imicro.cryptic.feature.TerminalTimes
import imicro.cryptic.terminal.Terminals
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.PlayerEquipment
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.client.gui.screens.inventory.ContainerScreen

/**
 * A chest that behaves like one of Hypixel's terminals, entirely on the client. Ported from Odin's
 * term-sim. It feeds the same tracker, so the solver works on it exactly as in a run. [ping] delays
 * each click to practise the real rhythm.
 */
abstract class TermSimScreen(
	private val simName: String,
	val size: Int,
	private val container: SimpleContainer = SimpleContainer(size),
) : ContainerScreen(
	ChestMenu(menuTypeFor(size), 0, freshInventory(), container, size / 9),
	freshInventory(),
	Component.literal(simName),
) {
	protected val fillerPane: ItemStack = named(ItemStack(Items.STAINED_GLASS_PANE.black()), "")

	protected val gridSlots: List<Slot> get() = menu.slots.subList(0, size)

	protected var ping = 0L
		private set

	private val windowId = nextWindowId++

	protected open val delaysClicks: Boolean get() = true

	private var pendingSlot: Slot? = null
	private var pendingButton = 0
	private var pendingTicks = 0

	protected abstract fun create()

	protected open fun slotClick(slot: Slot, button: Int) {
		playClickSound()
	}

	fun open(pingMillis: Long) {
		ping = pingMillis
		Minecraft.getInstance().gui.setScreen(this)
		// Announced before filling, as the server does. The simulator's own id gives each run its
		// own click-protection clock.
		Terminals.windowOpened(simName, windowId)
		create()
	}

	/** Fills the chest slot by slot, the way Hypixel answers a click. */
	protected fun rebuild(block: (Slot) -> ItemStack) {
		gridSlots.forEach { it.setSlot(block(it)) }
	}

	protected fun Slot.setSlot(stack: ItemStack) {
		set(stack)
		Terminals.slotUpdated(index, menu.items)
	}

	fun clickIndex(index: Int, button: Int) {
		gridSlots.getOrNull(index)?.let { queueClick(it, button) }
	}

	override fun slotClicked(slot: Slot, slotId: Int, button: Int, containerInput: ContainerInput) {
		queueClick(slot, button)
	}

	override fun containerTick() {
		super.containerTick()
		val slot = pendingSlot ?: return
		if (--pendingTicks > 0) return
		pendingSlot = null
		slotClick(slot, pendingButton)
	}

	override fun removed() {
		pendingSlot = null
		Terminals.closed()
		super.removed()
	}

	/** Escape from a terminal returns to the simulator's menu; escape from the menu leaves. */
	override fun onClose() {
		if (this is StartSim) return super.onClose()
		pendingSlot = null
		Terminals.closed()
		Minecraft.getInstance().execute { StartSim().open(ping) }
	}

	/** A click lands after its simulated round trip, one at a time, as in a real terminal. */
	private fun queueClick(slot: Slot, button: Int) {
		if (slot.container !== container) return
		if (slot.item.item == Items.STAINED_GLASS_PANE.black()) return
		if (pendingSlot != null) return

		val ticks = if (delaysClicks) (ping / TICK_MILLIS).toInt() else 0
		if (ticks <= 0) {
			slotClick(slot, button)
			return
		}

		pendingSlot = slot
		pendingButton = button
		pendingTicks = ticks
	}

	/** Hands back to the start menu. The finishing click still plays its sound. */
	protected fun completed() {
		playClickSound()
		// The simulator has no chat line to be timed by, so it reports the solve itself before the
		// tracker is cleared.
		TerminalTimes.onSolved()
		Terminals.closed()
		Minecraft.getInstance().execute { StartSim().open(ping) }
	}

	protected fun playClickSound() {
		if (imicro.cryptic.feature.TerminalSolver.replacesClickSound) return
		Minecraft.getInstance().soundManager.play(
			SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 1f),
		)
	}

	protected fun message(text: String) {
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §f$text"))
	}

	private companion object {
		const val TICK_MILLIS = 50L

		/** Counted down from a value real chest ids never reach. */
		var nextWindowId = Int.MAX_VALUE / 2
	}
}

internal fun named(stack: ItemStack, name: String): ItemStack =
	stack.apply { set(DataComponents.CUSTOM_NAME, Component.literal(name)) }

private fun menuTypeFor(size: Int): MenuType<ChestMenu> = when {
	size <= 9 -> MenuType.GENERIC_9x1
	size <= 18 -> MenuType.GENERIC_9x2
	size <= 27 -> MenuType.GENERIC_9x3
	size <= 36 -> MenuType.GENERIC_9x4
	size <= 45 -> MenuType.GENERIC_9x5
	else -> MenuType.GENERIC_9x6
}

/**
 * An inventory of the simulator's own, so a stray click never touches the player's. Two, since the
 * menu and screen each need one before the screen exists.
 */
private fun freshInventory(): Inventory {
	val player = requireNotNull(Minecraft.getInstance().player) {
		"The terminal simulator needs a player to build an inventory for"
	}
	return Inventory(player, PlayerEquipment(player))
}
