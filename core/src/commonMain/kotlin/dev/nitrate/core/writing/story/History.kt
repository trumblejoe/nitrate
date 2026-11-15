package dev.nitrate.core.writing.story

import dev.nitrate.core.writing.ScriptEdit
import dev.nitrate.core.writing.TextOperation

/** Turns a shared script's history into what writers read: who did what, to which scene or entry, when. */
object History {

    fun activities(log: List<ScriptEdit>, story: Story, scenes: List<Scene>): List<Activity> {
        val sceneNames = scenes.filter { it.id != null }.associate { it.id!! to it.heading }
        val items = mutableListOf<Activity>()
        for (e in log) {
            if (e.op != null) {
                val name = e.sceneId?.let { sceneNames[it] } ?: if (e.sceneId == null) "the title page" else "a scene since removed"
                items += Activity(e.userId, e.userName, e.atMs, e.atMs, e.sceneId?.let(Ref::scene), name, Activity.Kind.WROTE, 1, e.version, e.version)
            }
            for (c in e.records) items += describe(c, e, story, sceneNames)
        }
        return Activities.group(items)
    }

    private fun describe(c: RecordChange, e: ScriptEdit, story: Story, sceneNames: Map<String, String>): Activity {
        fun act(kind: Activity.Kind, target: Ref?, name: String) = Activity(e.userId, e.userName, e.atMs, e.atMs, target, name, kind, 1, e.version, e.version)
        val record = story.records[c.id]
        return when (c.type) {
            RecordTypes.SCENE -> {
                val sceneId = c.id.removePrefix("scene-")
                act(if (c.delete) Activity.Kind.DELETED else Activity.Kind.SCENE_DETAILS, Ref.scene(sceneId), sceneNames[sceneId] ?: "a scene")
            }
            RecordTypes.ENTRY -> act(if (c.delete) Activity.Kind.DELETED else Activity.Kind.ENTRY, Ref.entry(c.id), c.set["name"] ?: record?.get("name") ?: "an entry")
            RecordTypes.LIST -> act(if (c.delete) Activity.Kind.DELETED else Activity.Kind.LIST, null, c.set["name"] ?: record?.get("name") ?: "a list")
            RecordTypes.LINK -> {
                val from = c.set["from"] ?: record?.get("from")
                val to = c.set["to"] ?: record?.get("to")
                act(if (c.delete) Activity.Kind.DELETED else Activity.Kind.LINK, from?.let(Ref::parse), listOfNotNull(from, to).joinToString(" and ") { nameOf(it, story, sceneNames) })
            }
            RecordTypes.COMMENT -> {
                val on = c.set["on"] ?: record?.get("on") ?: ""
                act(Activity.Kind.COMMENT, Ref.parse(on), nameOf(on, story, sceneNames))
            }
            else -> act(Activity.Kind.ENTRY, null, c.type)
        }
    }

    fun nameOf(ref: String, story: Story, sceneNames: Map<String, String>): String = when (val r = Ref.parse(ref)) {
        null -> ref
        else -> if (r.kind == Ref.Kind.SCENE) sceneNames[r.id] ?: "a scene" else story.entry(r.id)?.name ?: story.records[r.id]?.get("name") ?: "an entry"
    }

    /** The scene a text change landed in: the marker on the heading at or before where it starts (as the server works it out). */
    fun sceneAt(text: String, op: TextOperation): String? {
        val first = op.parts.firstOrNull()
        val pos = (if (first is TextOperation.Retain) first.n else 0).coerceAtMost(text.length)
        val lineEnd = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
        if (lineEnd == 0) return null
        val marker = text.lastIndexOf("[[scene:", lineEnd - 1)
        if (marker < 0) return null
        val close = text.indexOf("]]", marker)
        return if (close < 0) null else text.substring(marker + 8, close)
    }

    /** A script and its story data as they were at [version], from where the history starts. */
    fun replay(start: LocalHistoryStart, log: List<ScriptEdit>, version: Long): Pair<String, Story> {
        var text = start.text
        var story = Story(start.records)
        for (e in log) {
            if (e.version > version) break
            e.op?.let { text = it.apply(text) }
            e.records.forEach { story = story.apply(it, e.userId, e.userName, e.atMs) }
        }
        return text to story
    }

    /** Every change to one record, newest first: (version, who, when, the change). */
    fun of(recordId: String, log: List<ScriptEdit>): List<Pair<ScriptEdit, RecordChange>> =
        log.flatMap { e -> e.records.filter { it.id == recordId }.map { e to it } }.reversed()

    /** Who wrote each part of the text, from the script as shared (by its owner) and every change since. */
    fun attribution(initialText: String, ownerId: String, log: List<ScriptEdit>): Pair<String, Attribution> {
        var text = initialText
        var a = Attribution.start(text.length, ownerId)
        for (e in log) {
            val op = e.op ?: continue
            a = a.apply(op, e.userId, text)
            text = op.apply(text)
        }
        return text to a
    }

    /** The changes that put a record back the way it was (fields set to then, ones added since removed). */
    fun restore(now: StoryRecord?, then: StoryRecord?, id: String, type: String): RecordChange = when {
        then == null || then.deleted -> RecordChange(id, type, delete = true)
        else -> RecordChange(id, type, then.fields + (now?.fields.orEmpty().keys - then.fields.keys).associateWith { null })
    }
}
