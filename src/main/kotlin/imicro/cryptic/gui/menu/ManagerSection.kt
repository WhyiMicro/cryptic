package imicro.cryptic.gui.menu

import imicro.cryptic.feature.AliasManager
import imicro.cryptic.feature.CarryManager
import imicro.cryptic.feature.CrosshairEditor
import imicro.cryptic.feature.KeybindManager
import imicro.cryptic.feature.SoundVolumes
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.menu.managers.AliasSection
import imicro.cryptic.gui.menu.managers.CarrySection
import imicro.cryptic.gui.menu.managers.CrosshairSection
import imicro.cryptic.gui.menu.managers.KeybindsSection
import imicro.cryptic.gui.menu.managers.SoundsSection
import net.minecraft.client.input.KeyEvent

/**
 * A module's extras besides settings (aliases, sounds, carries), shown on its page with a switch in
 * the title row. Drawn with a [UiKit], which takes the clicks its widgets asked for.
 */
abstract class ManagerSection(
	val tabLabel: String,
) {
	val ui = UiKit()

	lateinit var page: ModulePage

	/** Draws the section from [y] down, [width] wide, and returns its height. */
	abstract fun draw(x: Float, y: Float, width: Float): Float

	open fun drawOverlays() {}

	open fun mouseClicked(mx: Float, my: Float, button: Int, doubleClick: Boolean): Boolean = ui.mouseClicked(mx, my, button, doubleClick)
	open fun mouseDragged(mx: Float, my: Float): Boolean = ui.mouseDragged(mx, my)
	open fun mouseReleased(): Boolean = ui.mouseReleased()

	/** A key; false lets the menu have it (Escape with nothing to cancel closes it). */
	open fun keyPressed(event: KeyEvent): Boolean = ui.keyPressed(event)
	open fun keyReleased(event: KeyEvent): Boolean = false
	open fun charTyped(codePoint: Int): Boolean = ui.charTyped(codePoint)

	open val capturingKeys: Boolean get() = ui.typing

	/** The page is closing: save changes. */
	open fun onClose() {}

	companion object {
		fun forModule(module: Module): ManagerSection? = when (module) {
			AliasManager.module -> AliasSection()
			SoundVolumes.module -> SoundsSection()
			KeybindManager.module -> KeybindsSection()
			CarryManager.module -> CarrySection()
			imicro.cryptic.feature.PartyBlacklist.module -> imicro.cryptic.gui.menu.managers.BlacklistSection()
			CrosshairEditor.module -> CrosshairSection()
			imicro.cryptic.gui.menu.managers.ProfilesSection.module -> imicro.cryptic.gui.menu.managers.ProfilesSection()
			imicro.cryptic.gui.menu.managers.CreditsSection.module -> imicro.cryptic.gui.menu.managers.CreditsSection()
			else -> null
		}
	}
}
