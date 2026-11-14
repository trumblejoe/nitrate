package dev.nitrate.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

/** Holds a screen's loaded data for as long as the screen is in the back stack. */
class RetainedValues : ViewModel() {
    val values = mutableMapOf<String, MutableState<Any?>>()
}

/**
 * Like `remember { mutableStateOf(initial()) }`, but kept while the screen is in the back stack,
 * so coming back to it (Back from a title) shows what it had instead of loading from scratch.
 */
@Suppress("UNCHECKED_CAST")
@Composable
fun <T> retained(key: String, initial: () -> T): MutableState<T> {
    val holder: RetainedValues = viewModel(key = "retained-values")
    return remember(key) { holder.values.getOrPut(key) { mutableStateOf(initial()) } } as MutableState<T>
}
