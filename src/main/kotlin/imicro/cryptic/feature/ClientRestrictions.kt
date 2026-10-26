package imicro.cryptic.feature

import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleCategory
import imicro.cryptic.gui.Restriction
import imicro.cryptic.gui.Restrictions

/**
 * How far into the grey area the client goes. Legit keeps only features that read or draw;
 * Semi-legit adds quality of life that acts for you and could be flagged (No rotate, Fake zpew,
 * the structure scanner); Cheats allows everything (Auto Clicker, Freecam).
 *
 * Features above the level are hidden from the menu and read as off, but their own switches and
 * settings are untouched, so going back to a higher level brings them back as they were. Each
 * feature's level is its module's or setting's [Restriction].
 */
object ClientRestrictions {
	@JvmField
	val mode = DropdownModuleSetting(
		id = "mode",
		label = "Mode",
		options = listOf("Legit", "Semi-legit", "Cheats"),
		defaultIndex = Restriction.CHEAT.ordinal,
		description = "Legit: only features that show you things. Semi-legit: also quality of life that acts for you and could be flagged, such as No Rotate. Cheats: everything. Switching keeps every feature's own settings.",
	)

	@JvmField
	val module = Module(
		id = "client_restrictions",
		name = "Client Restrictions",
		description = "Hide grey-area features",
		category = ModuleCategory.MISC,
		supportsToggle = false,
		settings = listOf(mode),
	)

	fun initialize() {
		Restrictions.allowedLevel = { mode.selectedIndex }
	}
}
