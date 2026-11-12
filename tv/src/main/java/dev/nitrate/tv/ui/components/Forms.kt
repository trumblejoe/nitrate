package dev.nitrate.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * D-pad friendly text field. Focusing it doesn't open the keyboard: it waits as a button until
 * OK is pressed. While typing, Up or Down (or the keyboard's own Done/Search) puts the keyboard
 * away and moves on, so the field never holds the D-pad hostage.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val button = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val transformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None
    fun stop(then: (() -> Unit)? = null) {
        keyboard?.hide()
        editing = false
        then?.invoke()
    }
    // Back on the button after the keyboard's Done/Search (once it's composed again).
    var backToButton by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (editing) runCatching { field.requestFocus(); keyboard?.show() }
        else if (backToButton) { backToButton = false; runCatching { button.requestFocus() } }
    }

    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceVariant)
            .border(BorderStroke(2.dp, if (focused) colors.primary else colors.border), shape)
            .onFocusChanged { focused = it.hasFocus }
            .focusGroup(),
    ) {
        if (editing) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                visualTransformation = transformation,
                keyboardOptions = keyboardOptions,
                // The keyboard's action also ends typing.
                keyboardActions = KeyboardActions(
                    onDone = { keyboardActions.onDone?.invoke(this); backToButton = true; stop() },
                    onSearch = { keyboardActions.onSearch?.invoke(this); backToButton = true; stop() },
                    onGo = { keyboardActions.onGo?.invoke(this); backToButton = true; stop() },
                    onNext = { keyboardActions.onNext?.invoke(this); stop { focusManager.moveFocus(FocusDirection.Down) } },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(field)
                    // Stop typing when focus leaves (not on the first report, before it arrives).
                    .onFocusChanged { if (it.isFocused) hadFocus = true else if (hadFocus && editing) { hadFocus = false; editing = false } }
                    .onPreviewKeyEvent { e ->
                        val dir = when (e.key) {
                            Key.DirectionDown -> FocusDirection.Down
                            Key.DirectionUp -> FocusDirection.Up
                            else -> return@onPreviewKeyEvent false
                        }
                        if (e.type == KeyEventType.KeyDown) stop { focusManager.moveFocus(dir) }
                        true
                    }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                        inner()
                    }
                },
            )
        } else {
            // A plain focus stop until OK: shows what's typed (or the hint).
            val shown = transformation.filter(androidx.compose.ui.text.AnnotatedString(value)).text.text
            androidx.tv.material3.Surface(
                onClick = { editing = true },
                shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape),
                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = colors.onSurface,
                    focusedContentColor = colors.onSurface,
                ),
                scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
                modifier = Modifier.fillMaxWidth().focusRequester(button),
            ) {
                Text(
                    shown.ifEmpty { placeholder },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (value.isEmpty()) colors.onSurfaceVariant else colors.onSurface,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                )
            }
        }
    }
}
