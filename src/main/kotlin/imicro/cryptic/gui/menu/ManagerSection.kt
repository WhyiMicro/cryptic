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
 * What a module has besides its settings: its aliases, its sounds, its carries.
 *
 * Shown on the module's page in place of the settings, with a switch between
 * the two in the page's title row. Drawn with a [UiKit], which takes the
 * clicks the widgets asked for; everything else a section needs to hear about
 * comes through the methods here.
 */
abstract class ManagerSection(
	/** The section's name on the switch beside "Settings". */
	val tabLabel: String,
) {
	val ui = UiKit()

	/** The page this is on, for the colour picker it can lend. */
	lateinit var page: ModulePage

	/** Draws the section from [y] down, [width] wide, and returns how tall it was. */
	abstract fun draw(x: Float, y: Float, width: Float): Float

	/** Anything that floats over the rest, drawn after it. */
	open fun drawOverlays() {}

	open fun mouseClicked(mx: Float, my: Float, button: Int, doubleClick: Boolean): Boolean = ui.mouseClicked(mx, my, button, doubleClick)
	open fun mouseDragged(mx: Float, my: Float): Boolean = ui.mouseDragged(mx, my)
	open fun mouseReleased(): Boolean = ui.mouseReleased()

	/** A key; false lets the menu have it (Escape with nothing to cancel shuts it). */
	open fun keyPressed(event: KeyEvent): Boolean = ui.keyPressed(event)
	open fun keyReleased(event: KeyEvent): Boolean = false
	open fun charTyped(codePoint: Int): Boolean = ui.charTyped(codePoint)

	/** True while keys belong to the section: typing, or waiting for a bind. */
	open val capturingKeys: Boolean get() = ui.typing

	/** The page is going away: save what was changed. */
	open fun onClose() {}

	companion object {
		/** The section a module's page carries, if it has one. */
		fun forModule(module: Module): ManagerSection? = when (module) {
			AliasManager.module -> AliasSection()
			SoundVolumes.module -> SoundsSection()
			KeybindManager.module -> KeybindsSection()
			CarryManager.module -> CarrySection()
			CrosshairEditor.module -> CrosshairSection()
			imicro.cryptic.gui.menu.managers.ProfilesSection.module -> imicro.cryptic.gui.menu.managers.ProfilesSection()
			else -> null
		}
	}
}
