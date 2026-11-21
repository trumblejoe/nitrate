package dev.nitrate.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import dev.nitrate.android.remote.RemoteControlHub
import dev.nitrate.core.remote.RemotePrompt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * While [prompt] is on screen, the user's phones are asked it too (and again every few seconds, for
 * a phone whose remote opens meanwhile); a phone's answer runs [onAnswer] with the choice's id, as
 * if its button were pressed here. Withdrawn from the phones when it goes.
 */
@Composable
fun AskPhones(remote: RemoteControlHub?, prompt: RemotePrompt?, onAnswer: (String) -> Unit) {
    if (remote == null || prompt == null) return
    val answer by rememberUpdatedState(onAnswer)
    LaunchedEffect(prompt.id) {
        launch { remote.answers.collect { (id, choice) -> if (id == prompt.id && prompt.choices.any { it.id == choice }) answer(choice) } }
        while (true) {
            remote.offer(prompt)
            delay(RemotePrompt.REPEAT_MS)
        }
    }
    DisposableEffect(prompt.id) { onDispose { remote.withdrawSoon(prompt.id) } }
}
