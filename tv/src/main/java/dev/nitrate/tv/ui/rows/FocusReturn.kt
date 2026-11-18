package dev.nitrate.tv.ui.rows

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/**
 * Coming back from the menu to the page: the menu (`MainMenu`) asks the page to put focus back where
 * it was ([restore], true when it did), else focus goes to the page's start. Focus can leave for the
 * menu by moving off the page's edge or by the Menu key, and the card left may sit deep in a row of
 * rows, so the page keeps its own note of it rather than relying on the focus system's memory.
 */
class FocusReturn {
    var restore: (() -> Boolean)? = null
}

val LocalFocusReturn = staticCompositionLocalOf<FocusReturn?> { null }

/** The card to come back to: its item id, and the requester it takes. */
class ReturnTarget(val id: String, val requester: FocusRequester)

val LocalReturnTarget = compositionLocalOf<ReturnTarget?> { null }

/** On a card's focusable: lets the page bring focus back to it (only the card left takes the requester). */
fun Modifier.returnFocus(id: String): Modifier = composed {
    val target = LocalReturnTarget.current
    if (target != null && target.id == id) focusRequester(target.requester) else this
}
