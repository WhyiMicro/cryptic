package imicro.cryptic.debug

import imicro.cryptic.Cryptic
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import java.nio.file.Files
import java.util.Optional

/**
 * `/cryptic debug guilog`: records Hypixel's menus while on, for building screens over them (the
 * Party Finder's create and search pages). Every screen that opens, every chest's contents once
 * they settle (slot, item, name and lore with colour codes), what a sign opens with, and every
 * click, anvil rename and sign answer sent. Written to `logs/cryptic-gui.log` when turned off.
 */
object GuiLog {
	@Volatile
	var recording = false
		private set

	private val lines = ArrayList<String>()
	private var startedAt = 0L
	private val file get() = FabricLoader.getInstance().gameDir.resolve("logs").resolve("cryptic-gui.log")

	private const val MAX_LINES = 50_000

	/** A chest's contents are written once they've stopped changing for this many ticks. */
	private const val SETTLE_TICKS = 6

	private var watched: AbstractContainerScreen<*>? = null
	private var stamp = 0
	private var stableTicks = 0
	private var written = false

	fun toggle(): String {
		if (!recording) {
			lines.clear()
			startedAt = System.currentTimeMillis()
			recording = true
			add("start", "Recording menus.")
			return "Recording Hypixel's menus. Go through what you want captured, then run /cryptic debug guilog again."
		}
		recording = false
		add("stop", "")
		return runCatching {
			Files.createDirectories(file.parent)
			Files.write(file, lines)
			"Saved ${lines.size} lines to logs/cryptic-gui.log."
		}.getOrElse {
			Cryptic.LOGGER.warn("Writing the GUI log failed", it)
			"Couldn't save the GUI log: ${it.message}"
		}
	}

	private fun add(what: String, detail: String) {
		if (!recording || lines.size >= MAX_LINES) return
		lines += "${System.currentTimeMillis() - startedAt}\t$what\t$detail"
	}

	/** A screen being set, before it opens. */
	@JvmStatic
	fun onScreen(screen: Screen?) {
		if (!recording) return
		add("screen", if (screen == null) "(closed)" else "${screen.javaClass.name} title=${legacy(screen.title)}")
		if (screen is AbstractSignEditScreen) Minecraft.getInstance().execute { writeSign(screen) }
	}

	private fun writeSign(screen: AbstractSignEditScreen) {
		val sign = (screen as imicro.cryptic.mixin.SignEditScreenAccessor).`cryptic$sign`()
		add("sign", "at ${sign.blockPos.toShortString()}")
		for (front in listOf(true, false)) {
			sign.getText(front).getMessages(false).forEachIndexed { index, message ->
				add("sign.${if (front) "front" else "back"}", "line $index: ${legacy(message)}")
			}
		}
	}

	fun tick() {
		if (!recording) return
		val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*>
		if (screen !== watched) {
			watched = screen
			stamp = 0
			stableTicks = 0
			written = false
		}
		screen ?: return
		val slots = screen.menu.slots.filter { it.container !is Inventory }
		val now = slots.sumOf { (it.item.hoverName.string.hashCode() * 31 + it.item.count + (it.item.get(DataComponents.LORE)?.lines()?.size ?: 0)) * (it.index + 1) }
		if (now != stamp) {
			stamp = now
			stableTicks = 0
			written = false
			return
		}
		if (written || ++stableTicks < SETTLE_TICKS) return
		written = true
		add("chest", "title=${legacy(screen.title)} type=${BuiltInRegistries.MENU.getKey(screen.menu.type)} id=${screen.menu.containerId} slots=${slots.size}")
		for (slot in slots) {
			val stack = slot.item
			if (stack.isEmpty) continue
			add("slot", describe(slot.index, stack))
			stack.get(DataComponents.LORE)?.lines()?.forEach { add("lore", "  ${legacy(it)}") }
		}
	}

	private fun describe(index: Int, stack: ItemStack): String {
		val id = BuiltInRegistries.ITEM.getKey(stack.item)
		val skyblock = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getString("id").orElse("")
		return "#$index $id x${stack.count}${if (skyblock.isNotEmpty()) " sb=$skyblock" else ""} name=${legacy(stack.hoverName)}"
	}

	/** Outgoing clicks, renames and sign answers. Any thread. */
	@JvmStatic
	fun onPacketSent(packet: Packet<*>) {
		if (!recording) return
		when (packet) {
			is ServerboundContainerClickPacket ->
				add("click", "container=${packet.containerId()} slot=${packet.slotNum()} button=${packet.buttonNum()} input=${packet.containerInput()}")
			is net.minecraft.network.protocol.game.ServerboundRenameItemPacket -> add("rename", packet.name)
			is ServerboundSignUpdatePacket ->
				add("sign.sent", "at ${packet.pos.toShortString()} lines=${packet.lines.joinToString(" | ")}")
			else -> Unit
		}
	}

	/** A component with § codes back in, so colours (Hypixel's red "can't join" lines) show. */
	private fun legacy(component: Component): String {
		val out = StringBuilder()
		component.visit({ style, piece ->
			style.color?.let { color -> out.append(codeFor(color)) }
			if (style.isBold) out.append("§l")
			if (style.isItalic) out.append("§o")
			out.append(piece)
			Optional.empty<Unit>()
		}, Style.EMPTY)
		return out.toString()
	}

	private fun codeFor(color: TextColor): String {
		val code = net.minecraft.ChatFormatting.entries.firstOrNull { TextColor.fromLegacyFormat(it) == color }
		return code?.toString() ?: "§#${String.format("%06X", color.value)}"
	}
}
