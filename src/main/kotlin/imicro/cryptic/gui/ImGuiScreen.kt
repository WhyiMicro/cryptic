package imicro.cryptic.gui

/**
 * A Minecraft screen that draws itself through Cryptic's Dear ImGui context.
 *
 * [ImGuiRuntime] runs one frame per rendered frame and hands it to whichever
 * screen is open. Screens implement this rather than being named individually
 * there, so a second window costs no changes to the runtime.
 */
interface ImGuiScreen {
	/** Called by the render-tail mixin while this is Minecraft's active screen. */
	fun drawImGui()
}
