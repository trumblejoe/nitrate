package dev.nitrate.core.jellyfin

import dev.nitrate.core.admin.ImageType
import dev.nitrate.core.admin.ItemMetadata
import dev.nitrate.core.admin.MetadataEditKind
import dev.nitrate.core.admin.MetadataEditRefused
import dev.nitrate.core.admin.MetadataEditorScope
import dev.nitrate.core.admin.RefreshMode
import dev.nitrate.core.admin.RemoteMatch
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.PlaybackSettingsRepository
import dev.nitrate.core.playback.caps
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Metadata editors (Companion feature `metadata`): which route a change takes, the scope, Access kept briefly, refusals in the server's words. */
class MetadataEditorsTest {
    private val server = "http://jf.example:8096"
    private val item = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
    private val itemDashless = item.replace("-", "")
    private var isAdmin = false
    private var features = """["metadata"]"""
    private var me = """{"IsEditor":true,"AllLibraries":false,"LibraryIds":["aaaaaaaabbbbccccddddeeeeeeeeeeee"]}"""
    /** What the Companion answers a change with: status and body. */
    private var refuse: Pair<HttpStatusCode, String>? = null
    private val calls = mutableListOf<String>()
    private val bodies = mutableMapOf<String, String>()

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val call = "${request.method.value} $path"
        synchronized(calls) { calls += call }
        val body = request.body.toByteArray().decodeToString()
        if (body.isNotEmpty()) bodies[call] = body
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val refused = refuse
        when {
            path == "/Users/Me" -> respond("""{"Id":"user1","Name":"Sam","Policy":{"IsAdministrator":$isAdmin}}""", HttpStatusCode.OK, json)
            path == "/Nitrate/Companion" -> respond("""{"Version":"1","Features":$features}""", HttpStatusCode.OK, json)
            path == "/Nitrate/Metadata/Me" -> respond(me, HttpStatusCode.OK, json)
            path.endsWith("/Access") -> if (path.contains("hidden")) respond("", HttpStatusCode.NotFound) else respond("""{"CanEdit":true}""", HttpStatusCode.OK, json)
            path == "/Items/$item" || path == "/Items/$itemDashless" ->
                respond("""{"Id":"$itemDashless","Name":"Old","Trickplay":{"x":1},"LockedFields":[]}""", HttpStatusCode.OK, json)
            path == "/Nitrate/Metadata/Editors" -> respond(
                """[{"UserId":"0123456789abcdef0123456789abcdef","UserName":"Sam","AllLibraries":false,"LibraryIds":["aaaaaaaabbbbccccddddeeeeeeeeeeee"]}]""", HttpStatusCode.OK, json,
            )
            path == "/Nitrate/Metadata/Edits" -> respond(
                """[{"Id":"e2","AtMs":1795450200000,"UserId":"0123456789abcdef0123456789abcdef","UserName":"Sam","ItemId":"$itemDashless","ItemName":"Nitrate","Kind":"details","Summary":"Overview","CanUndo":true},
                    {"Id":"e1","AtMs":1795450100000,"UserId":"0123456789abcdef0123456789abcdef","UserName":"Sam","ItemId":"$itemDashless","ItemName":"Nitrate","Kind":"image","Summary":"Primary","CanUndo":false}]""",
                HttpStatusCode.OK, json,
            )
            path.startsWith("/Nitrate/Metadata") && refused != null -> respond(refused.second, refused.first, json)
            path.endsWith("/ExternalIdInfos") -> respond("""[{"Name":"TheMovieDb","Key":"Tmdb","Type":"Movie"}]""", HttpStatusCode.OK, json)
            else -> respond("", HttpStatusCode.NoContent)
        }
    }

    private val settings = object : PlaybackSettingsRepository {
        override val settings = MutableStateFlow(PlaybackSettings())
        override suspend fun current() = settings.value
        override suspend fun update(transform: (PlaybackSettings) -> PlaybackSettings) { settings.value = transform(settings.value) }
    }

    private fun client() = JellyfinClient(
        engine, ClientIdentity("Nitrate", "1.0", "dev1", "Test"),
        MemorySessionStorage(listOf(StoredSession(server, "token", "user1", "sam"))), { caps() }, settings,
    ).also { runBlocking { it.restoreSession() } }

    private fun metadata() = ItemMetadata(item, name = "New name", overview = "Better", lockedFields = listOf("Overview"))

    @Test fun anEditorsScopeIsReadWithTheAccount() = runBlocking {
        val account = client().account()
        assertFalse(account.isAdmin)
        assertTrue(account.metadataEditor.isEditor)
        assertEquals(listOf("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), account.metadataEditor.libraryIds)
        assertTrue(account.canEditMetadataIn("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
        assertFalse(account.canEditMetadataIn("ffffffff-bbbb-cccc-dddd-eeeeeeeeeeee"))
        assertTrue(account.editsMetadata)
    }

    @Test fun adminsAskNothingOfTheCompanion() = runBlocking {
        isAdmin = true
        val c = client()
        val account = c.account()
        assertTrue(account.isAdmin)
        assertFalse(account.metadataEditor.isEditor)
        assertTrue(c.canEditMetadata(item))
        c.saveMetadata(metadata())
        c.refreshItem(item, RefreshMode.NEW_FILES, false, false)
        c.deleteImage(item, ImageType.BACKDROP, 1)
        assertTrue(calls.none { it.contains("/Nitrate/Metadata") })
        assertTrue("POST /Items/$item" in calls)
        assertTrue("POST /Items/$item/Refresh" in calls)
        assertTrue("DELETE /Items/$item/Images/Backdrop/1" in calls)
    }

    @Test fun editorsChangesGoThroughTheCompanion() = runBlocking {
        val c = client()
        c.account()
        c.saveMetadata(metadata())
        c.applyMatch(item, RemoteMatch("Nitrate", 2023, null, null, "TheMovieDb", mapOf("Tmdb" to "1"), """{"Name":"Nitrate"}"""), replaceImages = true)
        c.refreshItem(item, RefreshMode.REPLACE_ALL, true, false)
        c.downloadRemoteImage(item, ImageType.PRIMARY, "https://img/x.jpg")
        c.moveImage(item, ImageType.BACKDROP, 2, 0)
        c.deleteImage(item, ImageType.LOGO, null)
        c.uploadImage(item, ImageType.PRIMARY, byteArrayOf(1, 2, 3), "image/png")
        assertEquals(listOf("TheMovieDb"), c.externalIdFields(item).map { it.label })
        val base = "/Nitrate/Metadata/Items/$itemDashless"
        listOf(
            "POST $base", "POST $base/Apply", "POST $base/Refresh", "POST $base/RemoteImages/Download",
            "POST $base/Images/Backdrop/2/Index", "DELETE $base/Images/Logo", "POST $base/Images/Primary", "GET $base/ExternalIdInfos",
        ).forEach { assertTrue("$it in $calls", it in calls) }
        // Reads stay on Jellyfin's own endpoints; nothing is changed there.
        assertTrue("GET /Items/$item" in calls)
        assertTrue(calls.none { it.startsWith("POST /Items") || it.startsWith("DELETE /Items") })
        // The same round-tripped JSON, read-only parts dropped.
        val sent = Json.parseToJsonElement(bodies.getValue("POST $base")).jsonObject
        assertEquals("New name", sent["Name"]!!.jsonPrimitive.content)
        assertFalse("Trickplay" in sent)
        assertEquals("AQID", bodies.getValue("POST $base/Images/Primary"))
    }

    @Test fun accessIsAskedOncePerTitleAndOnlyForEditors() = runBlocking {
        val c = client()
        c.account()
        assertTrue(c.canEditMetadata(item))
        assertTrue(c.canEditMetadata(item))
        assertEquals(1, calls.count { it.endsWith("/Access") })
        // A title the account can't see: not editable, no error.
        assertFalse(c.canEditMetadata("hidden"))
        // Not an editor: nothing asked.
        me = """{"IsEditor":false,"AllLibraries":false,"LibraryIds":[]}"""
        val other = client()
        assertFalse(other.account().metadataEditor.isEditor)
        calls.clear()
        assertFalse(other.canEditMetadata(item))
        assertTrue(calls.none { it.endsWith("/Access") })
    }

    @Test fun withoutTheCompanionNothingIsAsked() = runBlocking {
        features = "[]"
        val c = client()
        assertFalse(c.account().metadataEditor.isEditor)
        assertFalse(c.canEditMetadata(item))
        assertTrue(calls.none { it.contains("/Nitrate/Metadata") })
    }

    @Test fun refusalsSayWhyAndAreNeverSuccess() = runBlocking {
        val c = client()
        c.account()
        refuse = HttpStatusCode.Forbidden to """{"message":"You can't edit this title"}"""
        try { c.saveMetadata(metadata()); fail("refused") } catch (e: MetadataEditRefused) {
            assertEquals("You can't edit this title", e.message)
            assertEquals(403, e.status)
        }
        refuse = HttpStatusCode.TooManyRequests to """{"message":"Too many changes: wait a minute"}"""
        try { c.refreshItem(item, RefreshMode.NEW_FILES, false, false); fail("refused") } catch (e: MetadataEditRefused) {
            assertEquals("Too many changes: wait a minute", e.message)
        }
        // No words from the server: Nitrate's own.
        refuse = HttpStatusCode.PayloadTooLarge to ""
        try { c.uploadImage(item, ImageType.PRIMARY, byteArrayOf(1), "image/jpeg"); fail("refused") } catch (e: MetadataEditRefused) {
            assertEquals("That image is too big (20 MB at most)", e.message)
        }
        refuse = HttpStatusCode.UnsupportedMediaType to ""
        try { c.uploadImage(item, ImageType.PRIMARY, byteArrayOf(1), "image/gif"); fail("refused") } catch (e: MetadataEditRefused) {
            assertEquals("Only JPEG, PNG or WebP images can be added", e.message)
        }
    }

    @Test fun adminsListEditorsTheLogAndUndo() = runBlocking {
        isAdmin = true
        val c = client()
        val editors = c.metadataEditors()
        assertEquals("01234567-89ab-cdef-0123-456789abcdef", editors.single().userId)
        assertEquals(listOf("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), editors.single().libraryIds)
        c.setMetadataEditor("01234567-89ab-cdef-0123-456789abcdef", MetadataEditorScope(true, false, listOf("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
        val sent = Json.parseToJsonElement(bodies.getValue("POST /Nitrate/Metadata/Editors/0123456789abcdef0123456789abcdef")).jsonObject
        assertEquals("true", sent["isEditor"]!!.jsonPrimitive.content)
        assertEquals("aaaaaaaabbbbccccddddeeeeeeeeeeee", sent["libraryIds"].toString().trim('[', ']', '"'))
        val log = c.metadataEdits()
        assertEquals(listOf("e2", "e1"), log.map { it.id })
        assertEquals(MetadataEditKind.IMAGE, log[1].kind)
        assertTrue(log[0].canUndo)
        assertEquals(item, log[0].itemId)
        c.undoMetadataEdit("e2")
        assertTrue("POST /Nitrate/Metadata/Edits/e2/Undo" in calls)
        refuse = HttpStatusCode.BadRequest to """{"message":"This change was undone already"}"""
        try { c.undoMetadataEdit("e2"); fail("refused") } catch (e: MetadataEditRefused) {
            assertEquals("This change was undone already", e.message)
        }
        // Jellyfin's ProblemDetails (404): its detail.
        refuse = HttpStatusCode.NotFound to """{"type":"x","title":"Not Found","status":404,"detail":"User not found"}"""
        try { c.setMetadataEditor("0123", MetadataEditorScope.NONE); fail("refused") } catch (e: MetadataEditRefused) {
            assertEquals("User not found", e.message)
        }
    }
}

/** The Users page's library chips. */
class MetadataEditorScopeTest {
    @Test fun chipsNarrowAddAndGoBackToAll() {
        val all = MetadataEditorScope(true, allLibraries = true)
        val one = dev.nitrate.core.admin.MetadataEditors.toggle(all, "a")
        assertEquals(MetadataEditorScope(true, false, listOf("a")), one)
        val two = dev.nitrate.core.admin.MetadataEditors.toggle(one, "b")
        assertEquals(listOf("a", "b"), two.libraryIds)
        assertTrue(two.covers("B".lowercase()) && !two.covers("c") && !two.covers(null))
        val back = dev.nitrate.core.admin.MetadataEditors.toggle(dev.nitrate.core.admin.MetadataEditors.toggle(two, "a"), "b")
        assertTrue(back.allLibraries)
        assertFalse(MetadataEditorScope.NONE.covers("a"))
    }
}
