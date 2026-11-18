package dev.nitrate.core.effects

import kotlinx.serialization.Serializable

/** What a card does when it's clicked, before its page opens. */
@Serializable
enum class ClickEffect(val label: String) {
    NONE("None"),
    SPIN("Spin"),
    BOUNCE("Bounce"),
    WOBBLE("Wobble"),
    POP("Pop"),
    FLIP("Flip"),
    JELLY("Jelly"),
}

/** Something that now and then happens to a card in a row while browsing. */
@Serializable
enum class CardSpark(val label: String) {
    NONE("None"),
    LIGHTNING("Lightning strikes"),
    FIREWORKS("Fireworks"),
    SPARKLES("Sparkles"),
    EMBERS("Embers"),
    SHIMMER("Shimmer"),
}

/** How often a row's [CardSpark] goes off. */
@Serializable
enum class SparkRate(val label: String, val everyMs: Long) {
    RARE("Rarely", 22_000),
    SOMETIMES("Sometimes", 10_000),
    OFTEN("Often", 4_500),
}

/** A page's animated background. Every design is drawn in code and original. */
@Serializable
enum class PageTheme(val label: String, val detail: String) {
    NONE("None", "No theme"),
    THUNDERSTORM("Thunderstorm", "Rain, rolling clouds and lightning"),
    SAFARI("Wild safari", "Dusk on the savanna, animals crossing"),
    DEEP_SPACE("Deep space", "Drifting stars, planets and a passing comet"),
    UNDERWATER("Underwater", "Light rays, bubbles and fish"),
    HAUNTED("Haunted", "Fog, a full moon and bats"),
    DESERT("Desert", "Heat haze, dunes and tumbleweed"),
    SNOWY_FOREST("Snowy forest", "Pines and falling snow"),
    SUPERHERO_CITY("Superhero city", "Comic-book skyline, halftone sky and action bursts"),
    ALIEN_AGENTS("Alien agents", "Black suits, a sunglasses glint and neon visitors"),
    NEON_ARCADE("Neon arcade", "A glowing grid running to the horizon"),
    ROSES("Roses and thorns", "Climbing briars in bloom, petals drifting down"),
    FLOWERS("Flower garden", "A meadow of blossoms swaying, petals on the breeze"),
    POETRY("Poetry", "Old Japanese verses fading in and out, cherry petals falling"),
}

/** How much of each effect there is. */
@Serializable
enum class EffectIntensity(val label: String, val scale: Float) {
    LIGHT("Light", 0.5f),
    NORMAL("Normal", 1f),
    FULL("Full", 1.6f),
}

/**
 * Days and seasons with an effect of their own, most specific first (that one wins when two fall
 * together: Festivus over Christmas, Christmas over winter). [onByDefault]: whether it plays until
 * switched off.
 */
@Serializable
enum class Occasion(val label: String, val detail: String, val onByDefault: Boolean = true) {
    FESTIVUS("Festivus", "23 December: the bare pole and the airing of grievances"),
    NEW_YEAR("New Year", "31 December – 1 January: confetti and a countdown glow"),
    VALENTINES("Valentine's Day", "14 February: drifting hearts"),
    ST_PATRICKS("St Patrick's Day", "17 March: shamrocks"),
    ARMY_BIRTHDAY("Army birthday", "14 June: tanks rolling past, shells bursting"),
    INDEPENDENCE_DAY("Independence Day", "3–5 July: fireworks over the stars and stripes"),
    AIR_FORCE_BIRTHDAY("Air Force birthday", "18 September: bombers crossing, bombs falling"),
    NAVY_BIRTHDAY("Navy birthday", "13 October: battleships and cannon smoke"),
    MARINE_CORPS_BIRTHDAY("Marine Corps birthday", "10 November: down-range view of machine-gun nests"),
    VETERANS_DAY("Veterans Day", "11 November: waving flags and stars"),
    THANKSGIVING("Thanksgiving", "Fourth Thursday of November and the day after: falling leaves"),
    LABOR_DAY("Labor Day", "First Monday of September and the weekend: red banners, hammer and sickle, a workers' march"),
    EASTER("Easter", "Good Friday to Easter Monday: hopping rabbits and eggs"),
    HALLOWEEN("Halloween", "25–31 October: bats, pumpkins and fog"),
    CHRISTMAS("Christmas", "18–26 December: snow, lights and a sleigh"),
    WINTER("Winter snow", "December to February: falling snow"),
}

/** The global effect settings (per display profile, synced). */
@Serializable
data class EffectsPrefs(
    /** Every effect: row, page and seasonal. */
    val on: Boolean = true,
    /** No movement that isn't asked for: themes hold still, cards don't spin. */
    val reduceMotion: Boolean = false,
    val intensity: EffectIntensity = EffectIntensity.NORMAL,
    /** The calendar's effects. */
    val seasonal: Boolean = true,
    /** Occasions switched against their default. */
    val occasionsOff: Set<Occasion> = emptySet(),
    val occasionsOn: Set<Occasion> = emptySet(),
) {
    fun isOn(o: Occasion) = if (o.onByDefault) o !in occasionsOff else o in occasionsOn

    fun with(o: Occasion, on: Boolean) = if (o.onByDefault) {
        copy(occasionsOff = if (on) occasionsOff - o else occasionsOff + o)
    } else {
        copy(occasionsOn = if (on) occasionsOn + o else occasionsOn - o)
    }
}

/** A date without a time or zone: what the calendar works in. */
data class Day(val year: Int, val month: Int, val day: Int) : Comparable<Day> {
    override fun compareTo(other: Day) = compareValuesBy(this, other, Day::year, Day::month, Day::day)

    /** Days since 1970-01-01 (proleptic Gregorian). */
    val epochDay: Long get() {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468
    }

    fun plus(days: Int): Day = fromEpochDay(epochDay + days)

    /** 1 = Monday … 7 = Sunday. */
    val dayOfWeek: Int get() = (((epochDay + 3) % 7 + 7) % 7 + 1).toInt()

    companion object {
        fun fromEpochDay(epoch: Long): Day {
            val z = epoch + 719468
            val era = (if (z >= 0) z else z - 146096) / 146097
            val doe = z - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
            return Day(y, m, d)
        }
    }
}

object Occasions {
    /** Easter Sunday (Western), by the anonymous Gregorian computus. */
    fun easter(year: Int): Day {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return Day(year, month, day)
    }

    /** The [n]th (1-based) [weekday] (1 = Monday) of a month. */
    fun nthWeekday(year: Int, month: Int, weekday: Int, n: Int): Day {
        val first = Day(year, month, 1)
        val offset = (weekday - first.dayOfWeek + 7) % 7
        return first.plus(offset + 7 * (n - 1))
    }

    /** The days [o] covers in [year] (inclusive), as epoch days. */
    fun span(o: Occasion, year: Int): List<LongRange> = when (o) {
        Occasion.FESTIVUS -> listOf(range(Day(year, 12, 23), Day(year, 12, 23)))
        Occasion.NEW_YEAR -> listOf(range(Day(year, 1, 1), Day(year, 1, 1)), range(Day(year, 12, 31), Day(year, 12, 31)))
        Occasion.VALENTINES -> listOf(range(Day(year, 2, 14), Day(year, 2, 14)))
        Occasion.ST_PATRICKS -> listOf(range(Day(year, 3, 17), Day(year, 3, 17)))
        Occasion.ARMY_BIRTHDAY -> listOf(range(Day(year, 6, 14), Day(year, 6, 14)))
        Occasion.INDEPENDENCE_DAY -> listOf(range(Day(year, 7, 3), Day(year, 7, 5)))
        Occasion.AIR_FORCE_BIRTHDAY -> listOf(range(Day(year, 9, 18), Day(year, 9, 18)))
        Occasion.NAVY_BIRTHDAY -> listOf(range(Day(year, 10, 13), Day(year, 10, 13)))
        Occasion.MARINE_CORPS_BIRTHDAY -> listOf(range(Day(year, 11, 10), Day(year, 11, 10)))
        Occasion.VETERANS_DAY -> listOf(range(Day(year, 11, 11), Day(year, 11, 11)))
        Occasion.THANKSGIVING -> nthWeekday(year, 11, 4, 4).let { listOf(range(it, it.plus(1))) }
        // The long weekend: Saturday to the Monday itself.
        Occasion.LABOR_DAY -> nthWeekday(year, 9, 1, 1).let { listOf(range(it.plus(-2), it)) }
        Occasion.EASTER -> easter(year).let { listOf(range(it.plus(-2), it.plus(1))) }
        Occasion.HALLOWEEN -> listOf(range(Day(year, 10, 25), Day(year, 10, 31)))
        Occasion.CHRISTMAS -> listOf(range(Day(year, 12, 18), Day(year, 12, 26)))
        Occasion.WINTER -> listOf(range(Day(year, 1, 1), Day(year, 3, 1).plus(-1)), range(Day(year, 12, 1), Day(year, 12, 31)))
    }

    private fun range(from: Day, to: Day) = from.epochDay..to.epochDay

    /** The occasion showing on [today] given [prefs] (the most specific one switched on), if any. */
    fun active(today: Day, prefs: EffectsPrefs): Occasion? {
        if (!prefs.on || !prefs.seasonal) return null
        return Occasion.entries.firstOrNull { o -> prefs.isOn(o) && span(o, today.year).any { today.epochDay in it } }
    }

    /** When [o] next starts, from [today] (today itself if it's on now). */
    fun next(o: Occasion, today: Day): Day {
        for (year in today.year..today.year + 1) {
            val start = span(o, year).filter { it.last >= today.epochDay }.minOfOrNull { maxOf(it.first, today.epochDay) }
            if (start != null) return Day.fromEpochDay(start)
        }
        return Day.fromEpochDay(span(o, today.year + 1).first().first)
    }
}

object PageThemes {
    /** The page a menu stop shows, whose theme ([DisplayPrefs.pageThemes]) it draws; null for stops that aren't pages. */
    fun pageKeyFor(stop: String): String? = when {
        stop == dev.nitrate.core.pages.MenuStops.HOME -> dev.nitrate.core.pages.PageKeys.HOME
        stop.startsWith("library:") -> dev.nitrate.core.pages.PageKeys.library(stop.removePrefix("library:"))
        stop.startsWith("page:") -> dev.nitrate.core.pages.PageKeys.custom(stop.removePrefix("page:"))
        else -> null
    }
}

/**
 * Scenes built around real subjects (animals, aircraft, ships, people) play a short looping film
 * clip along the bottom of the screen instead of a drawing, when the app ships one: `fx/<name>.mp4`
 * in the TV app's assets, made by `tools/build-effect-clips.py` from freely licensed footage (each
 * clip's credit in `fx/credits.json`, shown on the licences page). [band] is the share of the screen
 * height the clip covers, from the bottom; clips are encoded at that shape.
 */
data class EffectClip(val name: String, val band: Float)

object EffectClips {
    /** A theme shown with the app's own photos (when shipped, and no film clip is): the set's name. */
    fun photosForTheme(theme: PageTheme): String? = if (theme == PageTheme.SAFARI) "animals" else null

    fun forTheme(theme: PageTheme): EffectClip? = when (theme) {
        PageTheme.SAFARI -> EffectClip("safari", 0.42f)
        else -> null
    }

    fun forOccasion(o: Occasion): EffectClip? = when (o) {
        Occasion.ARMY_BIRTHDAY -> EffectClip("army", 0.42f)
        Occasion.AIR_FORCE_BIRTHDAY -> EffectClip("air-force", 0.42f)
        Occasion.NAVY_BIRTHDAY -> EffectClip("navy", 0.42f)
        Occasion.MARINE_CORPS_BIRTHDAY -> EffectClip("marines", 0.42f)
        Occasion.VETERANS_DAY -> EffectClip("veterans", 0.42f)
        Occasion.EASTER -> EffectClip("easter", 0.36f)
        else -> null
    }
}
