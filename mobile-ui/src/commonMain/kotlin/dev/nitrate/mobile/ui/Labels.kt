package dev.nitrate.mobile.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * What a screen reader says for a control whose words sit beside it rather than inside it (a
 * switch at the end of a line, a colour swatch): "Favourites only, switch, on".
 */
fun Modifier.labelled(words: String): Modifier = semantics { contentDescription = words }
