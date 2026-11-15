package dev.nitrate.core.writing.story

import kotlinx.serialization.Serializable

/**
 * One piece of a script's story data: a list, an entry in it, a link, a comment or a scene's
 * details. Fields are strings; each field is replaced as a whole by the latest change to it, and
 * every record remembers who changed it last and when.
 */
@Serializable
data class StoryRecord(
    val id: String,
    val type: String,
    val fields: Map<String, String> = emptyMap(),
    val deleted: Boolean = false,
    val updatedBy: String? = null,
    val updatedByName: String? = null,
    val updatedAt: Long = 0,
    val createdBy: String? = null,
    val createdAt: Long = 0,
) {
    operator fun get(field: String): String? = fields[field]
}

/** A change to one record: fields set (null removes one), or the record deleted. */
@Serializable
data class RecordChange(val id: String, val type: String, val set: Map<String, String?> = emptyMap(), val delete: Boolean = false)

object RecordTypes {
    const val LIST = "list"
    const val ENTRY = "entry"
    const val LINK = "link"
    const val COMMENT = "comment"
    const val SCENE = "scene"
}

enum class ListKind { CHARACTERS, LOCATIONS, CUSTOM }

data class StoryList(val id: String, val name: String, val kind: ListKind, val order: Int)

data class StoryEntry(val id: String, val listId: String, val name: String, val notes: String, val record: StoryRecord?)

/** What a link joins: a scene ("scene:<id>") or an entry ("entry:<id>"). */
data class Ref(val kind: Kind, val id: String) {
    enum class Kind { SCENE, ENTRY }
    override fun toString() = (if (kind == Kind.SCENE) "scene:" else "entry:") + id

    companion object {
        fun parse(s: String): Ref? = when {
            s.startsWith("scene:") -> Ref(Kind.SCENE, s.removePrefix("scene:"))
            s.startsWith("entry:") -> Ref(Kind.ENTRY, s.removePrefix("entry:"))
            else -> null
        }
        fun scene(id: String) = Ref(Kind.SCENE, id)
        fun entry(id: String) = Ref(Kind.ENTRY, id)
    }
}

data class StoryLink(val id: String, val from: Ref, val to: Ref, val label: String, val record: StoryRecord)

enum class SceneStatus(val label: String) { TODO("To do"), DRAFTING("Drafting"), DONE("Done") }

/** A scene's own details (its text is in the script). */
data class SceneMeta(
    val sceneId: String,
    val synopsis: String = "",
    /** A colour index into the app's palette (0 = none). */
    val color: Int = 0,
    val assigneeId: String? = null,
    val assigneeName: String? = null,
    val status: SceneStatus? = null,
    val record: StoryRecord? = null,
)

/** A comment pinned to a scene, an entry, or a line (a scene plus the words commented on). */
data class StoryComment(
    val id: String,
    /** "scene:<id>", "entry:<id>". */
    val on: String,
    /** The words commented on, for a line comment. */
    val quote: String?,
    val text: String,
    val parentId: String?,
    val resolved: Boolean,
    val authorId: String?,
    val authorName: String?,
    val createdAt: Long,
    val record: StoryRecord,
)

/** A script's story data: every record by id, with typed views over them. */
data class Story(val records: Map<String, StoryRecord> = emptyMap()) {

    fun apply(change: RecordChange, by: String?, byName: String?, at: Long): Story {
        val old = records[change.id]
        if (change.delete) {
            if (old == null) return this
            return copy(records = records + (change.id to old.copy(deleted = true, updatedBy = by, updatedByName = byName, updatedAt = at)))
        }
        val fields = (old?.fields.orEmpty() + change.set.filterValues { it != null }.mapValues { it.value!! }) - change.set.filterValues { it == null }.keys
        val record = (old ?: StoryRecord(change.id, change.type, createdBy = by, createdAt = at))
            .copy(fields = fields, deleted = false, updatedBy = by, updatedByName = byName, updatedAt = at)
        return copy(records = records + (change.id to record))
    }

    private fun live(type: String) = records.values.filter { it.type == type && !it.deleted }

    /** Characters and Locations always exist (until someone edits them they're only implied). */
    fun lists(): List<StoryList> {
        val made = live(RecordTypes.LIST).map { r ->
            StoryList(r.id, r["name"] ?: "List", runCatching { ListKind.valueOf(r["kind"] ?: "") }.getOrDefault(ListKind.CUSTOM), r["order"]?.toIntOrNull() ?: 100)
        }
        val builtIn = listOf(
            StoryList(CHARACTERS, "Characters", ListKind.CHARACTERS, 0),
            StoryList(LOCATIONS, "Locations", ListKind.LOCATIONS, 1),
        ).filter { b -> made.none { it.id == b.id } && records[b.id]?.deleted != true }
        return (builtIn + made).sortedWith(compareBy({ it.order }, { it.name.lowercase() }))
    }

    fun entries(listId: String): List<StoryEntry> = live(RecordTypes.ENTRY).filter { it["list"] == listId }
        .map { StoryEntry(it.id, listId, it["name"] ?: "", it["notes"] ?: "", it) }
        .sortedBy { it.name.lowercase() }

    fun entry(id: String): StoryEntry? = records[id]?.takeIf { !it.deleted && it.type == RecordTypes.ENTRY }
        ?.let { StoryEntry(it.id, it["list"] ?: "", it["name"] ?: "", it["notes"] ?: "", it) }

    fun links(): List<StoryLink> = live(RecordTypes.LINK).mapNotNull { r ->
        val from = Ref.parse(r["from"] ?: "") ?: return@mapNotNull null
        val to = Ref.parse(r["to"] ?: "") ?: return@mapNotNull null
        StoryLink(r.id, from, to, r["label"] ?: "", r)
    }

    fun sceneMeta(sceneId: String): SceneMeta {
        val r = records[sceneRecordId(sceneId)]?.takeIf { !it.deleted } ?: return SceneMeta(sceneId)
        return SceneMeta(
            sceneId = sceneId,
            synopsis = r["synopsis"] ?: "",
            color = r["color"]?.toIntOrNull() ?: 0,
            assigneeId = r["assignee"],
            assigneeName = r["assigneeName"],
            status = r["status"]?.let { s -> runCatching { SceneStatus.valueOf(s) }.getOrNull() },
            record = r,
        )
    }

    fun comments(on: String? = null): List<StoryComment> = live(RecordTypes.COMMENT).map { r ->
        StoryComment(r.id, r["on"] ?: "", r["quote"], r["text"] ?: "", r["parent"], r["resolved"] == "1", r.createdBy, r["authorName"], r.createdAt, r)
    }.filter { on == null || it.on == on }.sortedBy { it.createdAt }

    /**
     * A list's entries merged with names found in the script: a detected character or location
     * with no entry yet shows as one (with a null record) until someone edits it.
     */
    fun listItems(list: StoryList, scenes: List<Scene>): List<StoryEntry> {
        val made = entries(list.id)
        val detected: List<String> = when (list.kind) {
            ListKind.CHARACTERS -> scenes.flatMap { it.characters }
            ListKind.LOCATIONS -> scenes.mapNotNull { it.location }
            ListKind.CUSTOM -> emptyList()
        }.distinctBy { it.uppercase() }
        val implied = detected.filter { name -> made.none { it.name.equals(name, ignoreCase = true) } }
            .map { StoryEntry(detectedId(list.id, it), list.id, it, "", null) }
        return (made + implied).sortedBy { it.name.lowercase() }
    }

    /** Scenes an entry is in by the script itself: a character speaking, or the scene's location. */
    fun scenesOf(entry: StoryEntry, list: StoryList?, scenes: List<Scene>): List<Scene> = when (list?.kind) {
        ListKind.CHARACTERS -> scenes.filter { s -> s.characters.any { it.equals(entry.name, ignoreCase = true) } }
        ListKind.LOCATIONS -> scenes.filter { it.location.equals(entry.name, ignoreCase = true) }
        else -> emptyList()
    }

    companion object {
        const val CHARACTERS = "characters"
        const val LOCATIONS = "locations"

        fun sceneRecordId(sceneId: String) = "scene-$sceneId"

        /** The id a detected (not yet edited) entry gets when it's first edited: stable per name, so two writers make the same one. */
        fun detectedId(listId: String, name: String) = "d-$listId-" + name.uppercase().filter { it.isLetterOrDigit() }.ifEmpty { "x" }

        // ---- Changes the editors make ----

        /** Creates (or edits) an entry; a detected one keeps its stable id. */
        fun setEntry(id: String, listId: String, name: String, notes: String) =
            RecordChange(id, RecordTypes.ENTRY, mapOf("list" to listId, "name" to name, "notes" to notes))

        fun deleteRecord(record: StoryRecord) = RecordChange(record.id, record.type, delete = true)

        fun setList(id: String, name: String, kind: ListKind, order: Int) =
            RecordChange(id, RecordTypes.LIST, mapOf("name" to name, "kind" to kind.name, "order" to order.toString()))

        fun link(id: String, from: Ref, to: Ref, label: String) =
            RecordChange(id, RecordTypes.LINK, mapOf("from" to from.toString(), "to" to to.toString(), "label" to label))

        /** Changes a scene's details; only what's given changes. An assignee of ("", "") clears it, as does [clearStatus]. */
        fun sceneMeta(sceneId: String, synopsis: String? = null, color: Int? = null, assignee: Pair<String, String>? = null, status: SceneStatus? = null, clearStatus: Boolean = false): RecordChange {
            val set = buildMap<String, String?> {
                synopsis?.let { put("synopsis", it) }
                color?.let { put("color", it.toString()) }
                if (assignee != null) {
                    put("assignee", assignee.first.takeIf { it.isNotEmpty() })
                    put("assigneeName", assignee.second.takeIf { it.isNotEmpty() })
                }
                if (status != null) put("status", status.name) else if (clearStatus) put("status", null)
            }
            return RecordChange(sceneRecordId(sceneId), RecordTypes.SCENE, set)
        }

        fun comment(id: String, on: String, text: String, authorName: String, quote: String? = null, parentId: String? = null) =
            RecordChange(id, RecordTypes.COMMENT, buildMap {
                put("on", on); put("text", text); put("authorName", authorName)
                quote?.let { put("quote", it) }
                parentId?.let { put("parent", it) }
            })

        fun resolve(commentId: String, resolved: Boolean) = RecordChange(commentId, RecordTypes.COMMENT, mapOf("resolved" to if (resolved) "1" else null))
    }
}
