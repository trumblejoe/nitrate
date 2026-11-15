package dev.nitrate.android.writing

import dev.nitrate.core.writing.ScriptEdit
import dev.nitrate.core.writing.TextOperation
import dev.nitrate.core.writing.UserRef
import dev.nitrate.core.writing.story.RecordChange
import dev.nitrate.core.writing.story.Scene
import dev.nitrate.core.writing.story.Story

/** A shared script's history, for the activity list, versions and restoring. */
interface StoryHistory {
    suspend fun log(): List<ScriptEdit>
    suspend fun textAt(version: Long): String
    suspend fun recordAt(version: Long, id: String): dev.nitrate.core.writing.story.StoryRecord?
}

/** What the story screens work on: the script, its scenes and story data, and how to change them. */
class StoryContext(
    val text: String,
    val scenes: List<Scene>,
    val story: Story,
    val selfId: String?,
    val selfName: String,
    /** Who scenes can be assigned to (the script's writers). */
    val people: List<UserRef>,
    val changeStory: (List<RecordChange>) -> Unit,
    /** Changes the script's text (moving and adding scenes, restoring a version). */
    val applyText: (TextOperation) -> Unit,
    /** Shows a scene in the script. */
    val openInScript: (Scene) -> Unit,
    /** Null for an unshared script (no history kept). */
    val history: StoryHistory?,
)
