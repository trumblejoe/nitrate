package dev.nitrate.testing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.isRoot

/**
 * Accessibility check for screenshot tests: everything a person can land on (focus with a D-pad,
 * tap, or reach with a screen reader) has something to say, its own words or the words inside it.
 * Returns a line per thing that would be read out as nothing ("unlabelled button").
 */
object A11y {
    fun silent(compose: SemanticsNodeInteractionsProvider): List<String> {
        val out = mutableListOf<String>()
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach { root -> walk(root, out) }
        return out
    }

    private fun actionable(n: SemanticsNode) =
        n.config.contains(SemanticsActions.OnClick) || n.config.contains(SemanticsProperties.Focused)

    private fun words(n: SemanticsNode): Boolean {
        val c = n.config
        if (c.getOrNull(SemanticsProperties.ContentDescription)?.any { it.isNotBlank() } == true) return true
        if (c.getOrNull(SemanticsProperties.Text)?.any { it.text.isNotBlank() } == true) return true
        if (c.getOrNull(SemanticsProperties.EditableText)?.text?.isNotBlank() == true) return true
        if (c.getOrNull(SemanticsProperties.StateDescription)?.isNotBlank() == true) return true
        if (c.contains(SemanticsProperties.EditableText)) return true // a text field, even when empty (its label is separate)
        return n.children.any { words(it) }
    }

    private fun walk(n: SemanticsNode, out: MutableList<String>) {
        if (actionable(n) && !words(n) && n.boundsInRoot.width > 1f && n.boundsInRoot.height > 1f) {
            val b = n.boundsInRoot
            out += "unlabelled ${if (n.config.contains(SemanticsActions.OnClick)) "button" else "focusable"} at (${b.left.toInt()}, ${b.top.toInt()}) ${b.width.toInt()}×${b.height.toInt()}"
        }
        n.children.forEach { walk(it, out) }
    }
}
