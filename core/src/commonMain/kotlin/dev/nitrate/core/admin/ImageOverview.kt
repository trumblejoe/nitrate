package dev.nitrate.core.admin

import dev.nitrate.core.model.ItemKind

/** What's wrong with an image (or that one is missing), as the overview lists it. */
enum class ImageFlawKind(val wire: String, val label: String) {
    MISSING("missing", "Missing"),
    SMALL("small", "Small"),
    SHAPE("shape", "Wrong shape"),
    GONE("gone", "File gone"),
    ;

    companion object {
        fun of(wire: String): ImageFlawKind? = entries.firstOrNull { it.wire.equals(wire, ignoreCase = true) }
    }
}

/** One image type's totals: how many, their files' size, and how many are small, misshapen, unknown, not saved or gone. */
data class ImageTypeTotals(
    val type: ImageType?,
    val typeName: String,
    val count: Int,
    val bytes: Long,
    val small: Int,
    val misshapen: Int,
    val unknownSize: Int,
    val notSaved: Int,
    val gone: Int,
) {
    val label: String get() = type?.label ?: typeName

    /** "404 images · 760 MB · 1 small · 1 file gone". */
    val detail: String get() = listOfNotNull(
        "$count image" + if (count == 1) "" else "s",
        bytesLabel(bytes),
        "$small small".takeIf { small > 0 },
        "$misshapen wrong shape".takeIf { misshapen > 0 },
        "$gone file gone".takeIf { gone > 0 },
        "$notSaved not saved on the server".takeIf { notSaved > 0 },
        "$unknownSize size unknown".takeIf { unknownSize > 0 },
    ).joinToString(" · ")
}

/** One kind of item's totals: how many, how many have every expected image and nothing wrong, and how many lack each. */
data class ImageKindTotals(val kind: ItemKind, val kindName: String, val items: Int, val complete: Int, val bytes: Long, val missing: Map<ImageType, Int>) {
    val label: String get() = ImageGuide.kindLabel(kind, kindName)

    /** "62 · 59 complete · 1.6 GB". */
    val detail: String get() = "$items · $complete complete · ${bytesLabel(bytes)}"

    /** "No logo: 2 · no backdrops: 1", or null when nothing's missing. */
    val missingLine: String? get() = missing.filterValues { it > 0 }.entries.joinToString(" · ") { (t, n) -> "no ${ImageGuide.label(t, t.wire, kind).lowercase()}: $n" }
        .ifEmpty { null }?.replaceFirstChar { it.uppercase() }
}

/** Something wrong with one of an item's images. */
data class ImageFlaw(val type: ImageType?, val typeName: String, val index: Int, val width: Int?, val height: Int?, val kind: ImageFlawKind) {
    /** "Poster small: 600×900", "Backdrop 2 wrong shape: 2000×2000", "Still: file gone" (an episode's, [of]). */
    fun line(of: ItemKind? = null): String {
        val name = ImageGuide.label(type, typeName, of) + if (index > 0) " ${index + 1}" else ""
        val size = RemoteImages.size(width, height)
        return when (kind) {
            ImageFlawKind.GONE -> "$name: file gone"
            else -> "$name ${kind.label.lowercase()}" + (size?.let { ": $it" } ?: "")
        }
    }
}

/** An item with something missing or wrong. */
data class ImageProblem(
    val itemId: String,
    val name: String,
    val kind: ItemKind,
    val seriesName: String?,
    val season: Int?,
    val episode: Int?,
    val missing: List<ImageType>,
    val flaws: List<ImageFlaw>,
) {
    /** What to call it: [ImageGuide.title]. */
    val title: String get() = ImageGuide.title(name, kind, seriesName, season, episode)

    /** "Missing logo · Poster small: 600×900". */
    val line: String get() = (listOfNotNull(missing.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Missing ") { ImageGuide.label(it, it.wire, kind).lowercase() }) + flaws.map { it.line(kind) }).joinToString(" · ")
}

/** One of the biggest image files; [title] as [ImageGuide.title] gives it. */
data class BigImageFile(val itemId: String, val title: String, val kind: ItemKind, val type: ImageType?, val typeName: String, val index: Int, val bytes: Long, val width: Int?, val height: Int?) {
    /** "Backdrop 3 · 3840×2160 · 12 MB". */
    val detail: String get() = listOfNotNull(ImageGuide.label(type, typeName, kind) + if (index > 0) " ${index + 1}" else "", RemoteImages.size(width, height), bytesLabel(bytes)).joinToString(" · ")
}

/** The Library images overview (Companion feature `imagesurvey`). */
data class ImageOverview(
    val items: Int,
    val images: Int,
    val bytes: Long,
    val types: List<ImageTypeTotals>,
    val kinds: List<ImageKindTotals>,
    val problems: List<ImageProblem>,
    val problemsTotal: Int,
    val largest: List<BigImageFile>,
) {
    val complete: Int get() = kinds.sumOf { it.complete }
    val missing: Int get() = kinds.sumOf { k -> k.missing.values.sum() }
    val small: Int get() = types.sumOf { it.small }
    val misshapen: Int get() = types.sumOf { it.misshapen }
    val gone: Int get() = types.sumOf { it.gone }

    /** "412 items · 1,186 images · 2.5 GB in all". */
    val summary: String get() = "$items items · $images images · ${bytesLabel(bytes)} in all"
}

/** "2.5 GB", "760 MB", "820 KB". */
fun bytesLabel(bytes: Long): String = dev.nitrate.core.downloads.DownloadSizing.formatBytes(bytes)

/** What to ask the overview for: a library (null for all) and which problems to list. */
data class ImageOverviewAsk(val libraryId: String?, val only: ImageFlawKind? = null, val onlyType: ImageType? = null, val limit: Int = 300)

/**
 * The rules the overview applies, sent to the Companion with each ask so they live here: what each
 * kind of item should have, and the size guide (`RemoteImages.spec`).
 */
object ImageGuide {
    /** The Companion lists this when it has the overview. */
    const val FEATURE = "imagesurvey"

    /** What each kind should have. Other kinds' images are counted, never "missing". */
    val expected: Map<ItemKind, List<ImageType>> = mapOf(
        ItemKind.MOVIE to listOf(ImageType.PRIMARY, ImageType.BACKDROP, ImageType.LOGO),
        ItemKind.SERIES to listOf(ImageType.PRIMARY, ImageType.BACKDROP, ImageType.LOGO),
        ItemKind.SEASON to listOf(ImageType.PRIMARY),
        ItemKind.EPISODE to listOf(ImageType.PRIMARY),
        ItemKind.BOX_SET to listOf(ImageType.PRIMARY),
    )

    /** Jellyfin's name for a kind. */
    fun wire(kind: ItemKind): String = when (kind) {
        ItemKind.MOVIE -> "Movie"
        ItemKind.SERIES -> "Series"
        ItemKind.SEASON -> "Season"
        ItemKind.EPISODE -> "Episode"
        ItemKind.BOX_SET -> "BoxSet"
        ItemKind.MUSIC_VIDEO -> "MusicVideo"
        ItemKind.VIDEO -> "Video"
        else -> kind.name
    }

    /**
     * An item as the overview names it: an episode by its show and number only ("Show · S1E2", never
     * its name: no spoilers), a season with its show ("Show · Season 1"), anything else by its name.
     */
    fun title(name: String, kind: ItemKind, seriesName: String?, season: Int?, episode: Int?): String = when {
        kind == ItemKind.EPISODE && seriesName != null -> if (season != null && episode != null) "$seriesName · S${season}E$episode" else "$seriesName · an episode"
        kind == ItemKind.SEASON && seriesName != null -> "$seriesName · $name"
        else -> name
    }

    /** One picture of a kind: "Poster", "Backdrop", an episode's main picture "Still". */
    fun label(type: ImageType?, wire: String, of: ItemKind? = null): String = when {
        type == ImageType.PRIMARY && of == ItemKind.EPISODE -> "Still"
        type == ImageType.BACKDROP -> "Backdrop"
        else -> type?.label ?: wire
    }

    fun kindLabel(kind: ItemKind, wire: String): String = when (kind) {
        ItemKind.MOVIE -> "Films"
        ItemKind.SERIES -> "Shows"
        ItemKind.SEASON -> "Seasons"
        ItemKind.EPISODE -> "Episodes"
        ItemKind.BOX_SET -> "Collections"
        ItemKind.MUSIC_VIDEO -> "Music videos"
        ItemKind.VIDEO -> "Other videos"
        else -> wire
    }

    /** The size guide as rules: each type for any kind, and an episode's main image as a 16:9 still. */
    data class Rule(val type: ImageType, val itemKind: ItemKind?, val minWidth: Int, val ratio: Double?)

    val rules: List<Rule> = ImageType.entries.map { t -> RemoteImages.spec(t).let { Rule(t, null, it.goodWidth, it.ratio) } } +
        RemoteImages.spec(ImageType.PRIMARY, episode = true).let { Rule(ImageType.PRIMARY, ItemKind.EPISODE, it.goodWidth, it.ratio) }
}
