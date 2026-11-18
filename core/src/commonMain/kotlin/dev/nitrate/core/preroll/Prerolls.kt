package dev.nitrate.core.preroll

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.poetry.Poem
import dev.nitrate.core.poetry.Poems
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * A short scene shown full screen before a title. [clip] is the film clip it plays when the build
 * ships one (`fx/<clip>.mp4`, see `EffectClips`); [drawn] scenes have a drawing to fall back on, the
 * others are only offered when their clip is there.
 */
@Serializable
enum class PrerollScene(val label: String, val detail: String, val clip: String, val drawn: Boolean, val photos: String? = null) {
    OCEAN_FLOOR("Ocean floor", "Light shafts and drifting life on the sea bed", "preroll-ocean-floor", true),
    NORTHERN_LIGHTS("Northern lights", "Aurora curtains over a still night", "preroll-northern-lights", true),
    STARFIELD("Into the stars", "Flying through a field of stars", "preroll-starfield", true),
    SKYDIVE("Skydive", "Free fall over the clouds, as the diver sees it", "preroll-skydive", false),
    JUNGLE("Through the jungle", "Moving through dense rainforest", "preroll-jungle", false),
    AMAZON_RIVER("Down the river", "A small boat on a wide jungle river", "preroll-river", false),
    ABOVE_CLOUDS("Above the clouds", "Gliding over a sea of cloud", "preroll-clouds", false),
    WILD_ANIMALS("Wild animals", "Tigers, a giraffe, a bear, a gorilla and more, one after another", "preroll-animals", false, photos = "animals"),
    JELLYFISH("Jellyfish", "Moon jellies glowing in the blue", "preroll-jellyfish", false, photos = "jellyfish"),
    ;

    /** Whether this build can show it: drawn, or its film clip or photos are there (`photos/<set>` in [shipped]). */
    fun usable(shipped: Set<String>) = drawn || clip in shipped || (photos != null && "photos/$photos" in shipped)
}

/** Where the user's own pre-roll videos are: a playlist or a collection on the server. */
@Serializable
data class PrerollSource(val id: String, val name: String, val playlist: Boolean)

/** One thing that plays before the title. */
sealed interface PrerollStep {
    /** A verse fading in and out on black. */
    data class Verse(val poem: Poem) : PrerollStep
    /** A line of the user's own, the same way. */
    data class Line(val text: String) : PrerollStep
    data class Scene(val scene: PrerollScene) : PrerollStep
    /** A video: the server's intro for the title, or one of the user's own pre-roll videos. */
    data class Video(val item: MediaItem) : PrerollStep
}

/** What plays before titles (per display profile, synced; off until switched on). */
@Serializable
data class PrerollPrefs(
    val on: Boolean = false,
    val films: Boolean = true,
    /** Episodes too: only the first one played in a sitting ([Prerolls.SITTING_MS]). */
    val episodes: Boolean = false,
    /** The server's intros (a cinema-mode plugin's trailers and pre-rolls). */
    val serverIntros: Boolean = true,
    /** At most this many of them. */
    val maxIntros: Int = 2,
    /** A playlist or collection of the user's own pre-roll videos: one at random. */
    val ownVideos: PrerollSource? = null,
    val verses: Boolean = true,
    /** Lines of the user's own, shown like verses (one at random, mixed in with them). */
    val ownLines: List<String> = emptyList(),
    /** Scenes to choose from; one at random. */
    val scenes: Set<PrerollScene> = emptySet(),
    /** How long a verse stays up, fades included. */
    val verseSeconds: Int = 12,
    val sceneSeconds: Int = 12,
)

object Prerolls {
    /** Whether anything plays before [item]: switched on, the right kind, and starting from the top. */
    /** Episodes get pre-rolls once a sitting: not again within this long of the last. */
    const val SITTING_MS = 3 * 60 * 60 * 1000L

    fun applies(item: MediaItem, prefs: PrerollPrefs, fromStart: Boolean, nowMs: Long = 0, lastPrerollMs: Long? = null): Boolean {
        if (!prefs.on) return false
        val resuming = !fromStart && item.user.positionMs > 0
        if (resuming) return false
        return when (item.kind) {
            ItemKind.MOVIE -> prefs.films
            ItemKind.EPISODE -> prefs.episodes && (lastPrerollMs == null || nowMs - lastPrerollMs > SITTING_MS)
            else -> false
        }
    }

    /**
     * The steps in the order they play: a scene, then a verse (or own line) just before the videos,
     * then the server's intros and one of the user's own videos. [shippedClips] says which scene clips
     * (and `photos/<set>` photo sets) this build has; a scene with neither only plays if it can be drawn.
     */
    fun plan(
        prefs: PrerollPrefs,
        intros: List<MediaItem>,
        ownVideos: List<MediaItem>,
        shippedClips: Set<String>,
        random: Random = Random.Default,
        recentVerses: Collection<Poem> = emptyList(),
    ): List<PrerollStep> = buildList {
        prefs.scenes.filter { it.usable(shippedClips) }.randomOrNull(random)?.let { add(PrerollStep.Scene(it)) }
        val lines = prefs.ownLines.map { it.trim() }.filter { it.isNotEmpty() }
        val verse = prefs.verses
        when {
            verse && lines.isNotEmpty() -> add(if (random.nextInt(Poems.all.size + lines.size) < lines.size) PrerollStep.Line(lines.random(random)) else PrerollStep.Verse(Poems.pick(random, recentVerses)))
            verse -> add(PrerollStep.Verse(Poems.pick(random, recentVerses)))
            lines.isNotEmpty() -> add(PrerollStep.Line(lines.random(random)))
        }
        if (prefs.serverIntros) intros.take(prefs.maxIntros.coerceAtLeast(0)).forEach { add(PrerollStep.Video(it)) }
        ownVideos.randomOrNull(random)?.let { add(PrerollStep.Video(it)) }
    }
}
