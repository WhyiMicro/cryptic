package imicro.cryptic.gui

/**
 * Common Font Awesome Free Solid glyphs available through
 * [ImGuiRuntime.font]. Add more constants here as the UI needs them.
 */
object FontAwesomeIcons {
    /**
     * A tick and a cross.
     *
     * Font Awesome rather than the Unicode characters that look like them:
     * the bundled Inter is baked with Basic Latin and Latin-1 only, so \u2713 and \u2715
     * arrive as the missing-glyph box.
     */
    const val CHECK = "\uF00C"
    const val XMARK = "\uF00D"

    /** A caret pointing down, for a dropdown that is shut. */
    const val CARET_DOWN = "\uF0D7"

    /** The crosshair editor's tools. */
    const val PENCIL = "\uF303"
    const val FILL = "\uF576"
    const val ERASER = "\uF12D"
    const val UNDO = "\uF0E2"
    const val MIRROR_X = "\uF07E"
    const val MIRROR_Y = "\uF07D"
    const val EDIT = "\uF044"

    const val SEARCH = "\uF002"
    const val DOWNLOAD = "\uF019"
    const val PLUS = "\uF067"
    const val FOLDER = "\uF07B"
    const val UPLOAD = "\uF093"

    /** Four arrows from a centre point: "up-down-left-right", for moving things. */
    const val MOVE = "\uF0B2"
    const val TRASH = "\uF1F8"
    const val PLAY = "\uF04B"
}
