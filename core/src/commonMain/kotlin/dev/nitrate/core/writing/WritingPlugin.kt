package dev.nitrate.core.writing

import dev.nitrate.core.plugins.NitratePlugin
import kotlinx.serialization.Serializable

@Serializable
data class WritingSettings(val backupToServer: Boolean = true)

/** A Fountain screenplay editor for a keyboard; each app provides the editor screens. */
object WritingPlugin : NitratePlugin {
    override val id = "writing"
    override val name = "Writing pane"
    override val description =
        "A screenplay editor for a keyboard, beside the video while it plays or on its own page. " +
            "Saves Fountain and Fade In (.fadein) files."
}
