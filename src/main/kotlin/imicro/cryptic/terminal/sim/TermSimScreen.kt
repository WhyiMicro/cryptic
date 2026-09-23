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
 * A chest that behaves like one of Hypixel's terminals, run entirely on the
 * client.
 *
 * Ported from Odin's term-sim screens (BSD 3-Clause, Copyright (c) 2025
 * odtheking). The point of it is that [imicro.cryptic.feature.TerminalSolver]
 * cannot tell the difference: the screen feeds the same tracker the real
 * terminals feed, so the solver draws over it and clicks it exactly as it
 * would in a run, and the whole thing can be practised out of a dungeon.
 *
 * [ping] delays each click by the round trip it stands for, because a terminal
 * that answers instantly teaches the wrong rhythm.
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
	/** The filler that surrounds every terminal, and is never clickable. */
	protected val fillerPane: ItemStack = named(ItemStack(Items.STAINED_GLASS_PANE.black()), "")

	protected val gridSlots: List<Slot> get() = menu.slots.subList(0, size)

	protected var ping = 0L
		private set

	/** Stands in for the container id a real chest arrives with. */
	private val windowId = nextWindowId++

	/** The start menu answers straight away; a terminal answers after [ping]. */
	protected open val delaysClicks: Boolean get() = true

	private var pendingSlot: Slot? = null
	private var pendingButton = 0
	private var pendingTicks = 0

	/** Fills the chest in. Called once, after the screen is on screen. */
	protected abstract fun create()

	/** Handles one click that has already waited out [ping]. */
	protected open fun slotClick(slot: Slot, button: Int) {
		playClickSound()
	}

	fun open(pingMillis: Long) {
		ping = pingMillis
		Minecraft.getInstance().gui.setScreen(this)
		// Announced before the chest is filled, exactly as the server does it:
		// the window first, then the slots that go in it. The id is the
		// simulator's own, so two runs of the same terminal are told apart and
		// the second gets a click-protection clock of its own.
		Terminals.windowOpened(simName, windowId)
		create()
	}

	/**
	 * Fills the chest in, slot by slot, which is how Hypixel answers a click:
	 * the window stays open and only what changed is sent.
	 */
	protected fun rebuild(block: (Slot) -> ItemStack) {
		gridSlots.forEach { it.setSlot(block(it)) }
	}

	protected fun Slot.setSlot(stack: ItemStack) {
		set(stack)
		Terminals.slotUpdated(index, menu.items)
	}

	/** Called by the solver, which clicks by slot index rather than by mouse. */
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

	/**
	 * A click only lands once the round trip it stands for has gone by, and
	 * only one is in flight at a time — clicking again while the server has yet
	 * to answer is exactly what does not work in a real terminal.
	 */
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

	/**
	 * Hands the run back to the start menu, the way solving one really does.
	 *
	 * The click that finished the terminal still gets its sound: the terminals
	 * return early once they are solved, so without this the last click of
	 * every run would be the one that felt like nothing happened.
	 */
	protected fun completed() {
		playClickSound()
		// A simulated terminal has no chat line to be timed by, so it says so
		// itself — before the tracker is cleared, while the clock it started is
		// still the one being read.
		TerminalTimes.onSolved()
		Terminals.closed()
		Minecraft.getInstance().execute { StartSim().open(ping) }
	}

	protected fun playClickSound() {
		Minecraft.getInstance().soundManager.play(
			SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 1f),
		)
	}

	protected fun message(text: String) {
		Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal("§8[Cryptic] §f$text"))
	}

	private companion object {
		const val TICK_MILLIS = 50L

		/** Counted down from the top of the integers a real chest never reaches. */
		var nextWindowId = Int.MAX_VALUE / 2
	}
}

/** Names a stack, which is also what stops the game showing its own name. */
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
 * An inventory of the simulator's own, so a stray click can never touch the
 * player's real one. Two are needed because the menu and the screen are each
 * handed one before the screen exists to share it.
 */
private fun freshInventory(): Inventory {
	val player = requireNotNull(Minecraft.getInstance().player) {
		"The terminal simulator needs a player to build an inventory for"
	}
	return Inventory(player, PlayerEquipment(player))
}
