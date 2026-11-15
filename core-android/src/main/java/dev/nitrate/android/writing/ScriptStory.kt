package dev.nitrate.android.writing

import dev.nitrate.core.writing.SharedScriptSession
import dev.nitrate.core.writing.story.RecordChange
import dev.nitrate.core.writing.story.Story
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** A script's story data (scene details, lists, links, comments) and the way to change it. */
interface ScriptStory {
    val story: StateFlow<Story>
    fun change(changes: List<RecordChange>)
}

/** A shared script's story: the live session's. */
class SharedStory(private val session: SharedScriptSession) : ScriptStory {
    override val story get() = session.story
    override fun change(changes: List<RecordChange>) = session.changeStory(changes)
}

/** An unshared script's story, kept on the device with the script (and in its backups). */
class LocalStory(
    private val store: WritingStore,
    private val localId: String,
    private val scope: CoroutineScope,
    private val selfId: String?,
    private val selfName: () -> String?,
) : ScriptStory {
    private val state = MutableStateFlow(Story())
    override val story: StateFlow<Story> = state

    init {
        scope.launch { state.value = Story(store.readStory(localId)) }
    }

    override fun change(changes: List<RecordChange>) {
        var s = state.value
        val now = System.currentTimeMillis()
        changes.forEach { s = s.apply(it, selfId, selfName(), now) }
        state.value = s
        scope.launch {
            store.saveStory(localId, s.records)
            store.recordStory(localId, changes)
        }
    }
}
