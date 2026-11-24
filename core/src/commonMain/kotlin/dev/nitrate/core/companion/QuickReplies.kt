package dev.nitrate.core.companion

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem

/**
 * The chat's one-press chips for one title: [emojis] sent as reactions (the title's own first, then
 * the usual [QUICK_REACTIONS]) and [lines] sent as messages (its main characters' names, then
 * [QuickReplies.PHRASES]).
 */
data class QuickPicks(val emojis: List<String>, val lines: List<String>) {
    companion object {
        /** Nothing known about the title: the usual reactions and phrases. */
        val PLAIN = QuickPicks(QUICK_REACTIONS, QuickReplies.PHRASES)
    }
}

/**
 * Quick replies for group chat, worked out on the device from what the server already said about
 * the title (cast roles, genres, tags, overview); nothing is sent anywhere to choose them.
 */
object QuickReplies {
    /** Short things people say while watching, one press each (original wording). */
    val PHRASES = listOf("No way!", "Called it", "Who's that?", "Wait, what?", "Love this part", "Pause?", "Ha!", "So good")

    /** How many emojis the title adds at most, before the usual ones. */
    const val MAX_TITLE_EMOJIS = 4

    /** How many emojis show in all. */
    const val MAX_EMOJIS = 10

    /** How many of the cast count as the main cast (the server lists the leads first). */
    const val MAIN_CAST = 6

    /** How many characters' names are offered. */
    const val MAX_NAMES = 3

    /**
     * The chips for [item] ([show]: an episode's show, when loaded, for its cast, genres and tags;
     * an episode often has none of its own).
     */
    fun forTitle(item: MediaItem?, show: MediaItem? = null): QuickPicks {
        if (item == null) return QuickPicks.PLAIN
        val own = emojis(item, show)
        return QuickPicks(
            (own + QUICK_REACTIONS).distinct().take(MAX_EMOJIS),
            (names(item, show).map { "$it!" } + PHRASES).distinct(),
        )
    }

    /** The main characters' names, from the leads' roles ("Katniss Everdeen" → "Katniss"), at most [MAX_NAMES]. */
    fun names(item: MediaItem, show: MediaItem? = null): List<String> {
        val people = if (item.kind == ItemKind.EPISODE) show?.people.orEmpty() + item.people else item.people + show?.people.orEmpty()
        return people
            .filter { p -> p.type == null || p.type.equals("Actor", ignoreCase = true) }
            .filter { !it.role.isNullOrBlank() }
            .distinctBy { it.name }
            .take(MAIN_CAST)
            .mapNotNull { it.role?.let(::characterName) }
            .distinct()
            .take(MAX_NAMES)
    }

    private val SKIP_ROLES = setOf(
        "self", "himself", "herself", "themselves", "themself", "narrator", "voice", "voices", "host", "various", "additional voices",
        "extra", "cameo", "director", "writer", "producer", "unknown", "n/a", "na", "none", "guest", "presenter", "uncredited",
    )

    private val TITLES = setOf(
        "dr", "dr.", "doctor", "mr", "mr.", "mrs", "mrs.", "ms", "ms.", "miss", "captain", "capt", "capt.", "sir", "lady", "lord", "agent",
        "detective", "det", "det.", "officer", "sgt", "sgt.", "sergeant", "professor", "prof", "prof.", "uncle", "aunt", "auntie", "lt", "lt.",
        "lieutenant", "general", "gen.", "colonel", "col.", "major", "father", "sister", "brother", "mother", "judge", "coach", "nurse",
        "young", "old", "little", "older", "younger", "adult", "teen", "king", "queen", "prince", "princess", "chief", "commander", "special",
    )

    /**
     * What people would call a character from a credited role: its quoted nickname, else its first
     * name after any title ("Dr. Gregory House" → "Gregory", "Theodore 'Ted' Lasso" → "Ted"); a role
     * starting "The" stays whole when short ("The Doctor"). Null for no character: empty,
     * uncredited, "Himself", "Narrator", a numbered extra ("Guard #2").
     */
    fun characterName(role: String): String? {
        if (role.contains("uncredited", ignoreCase = true)) return null
        val bare = role.replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ").split('/', ',', ';', '|').first().split(" and ", " & ").first().trim()
        if (bare.isEmpty() || bare.any { it.isDigit() || it == '#' }) return null
        if (bare.lowercase() in SKIP_ROLES) return null
        Regex("[\"“”‘’']([^\"“”‘’']{2,})[\"“”‘’']").find(bare)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && ' ' !in it }?.let { return it }
        val words = bare.split(Regex("\\s+")).map { it.trim('"', '“', '”', '\'', '’', '‘', ',', '.', ':', '-').ifEmpty { it } }.filter { it.isNotBlank() }
        if (words.isEmpty() || words.first().lowercase() in setOf("self", "himself", "herself")) return null
        if (words.first().lowercase() in setOf("the", "a") ) return bare.takeIf { words.size <= 3 && bare.length <= MAX_NAME }
        val rest = words.dropWhile { it.lowercase() in TITLES || it.lowercase().trimEnd('.') in TITLES }
        val name = (rest.firstOrNull() ?: words.joinToString(" ")).trimEnd('.', ',', '!', '?')
        return name.takeIf { n -> n.length in 2..MAX_NAME && n.any { it.isLetter() } && n.lowercase() !in SKIP_ROLES }
    }

    private const val MAX_NAME = 20

    /**
     * One row of the emoji table: any of [words] (a word, its plural with s/es, or several words in
     * a row) brings [emoji]; [genreOnly] words count only in genres and tags, never in the overview
     * ("action", "drama" are everywhere in overviews).
     */
    internal class Entry(val emoji: String, val words: List<String>, val genreOnly: Boolean = false)

    /** Curated: a title's words to an emoji that fits (original pairing, a few dozen). */
    internal val TABLE: List<Entry> = listOf(
        Entry("🏹", listOf("archery", "archer", "bow and arrow", "arrow", "crossbow")),
        Entry("🐉", listOf("dragon")),
        Entry("🚀", listOf("science fiction", "sci fi", "space", "spaceship", "starship", "astronaut", "galaxy", "planet", "rocket")),
        Entry("👽", listOf("alien", "extraterrestrial", "ufo")),
        Entry("👻", listOf("horror", "ghost", "haunted", "haunting", "poltergeist", "demon", "possessed")),
        Entry("🧟", listOf("zombie", "undead")),
        Entry("🧛", listOf("vampire", "dracula")),
        Entry("🪄", listOf("magic", "magical", "wizard", "witch", "sorcerer", "sorceress", "spell", "magician", "witchcraft")),
        Entry("💍", listOf("wedding", "bride", "groom", "marriage", "engagement", "proposal")),
        Entry("⚽", listOf("football", "soccer")),
        Entry("🏀", listOf("basketball")),
        Entry("⚾", listOf("baseball")),
        Entry("🥊", listOf("boxing", "boxer", "prizefight")),
        Entry("🦈", listOf("shark", "ocean", "underwater", "submarine", "sea")),
        Entry("🍳", listOf("cooking", "chef", "kitchen", "restaurant", "baking", "bakery", "cook")),
        Entry("🏎️", listOf("car race", "racing", "racer", "race car", "formula one", "nascar", "street race", "car")),
        Entry("💰", listOf("heist", "robbery", "bank robber", "thief", "thieves", "fortune", "money")),
        Entry("🔍", listOf("mystery", "detective", "murder", "investigation", "investigates", "sleuth", "whodunit")),
        Entry("🚓", listOf("police", "cop", "sheriff")),
        Entry("🎄", listOf("christmas", "santa", "xmas")),
        Entry("🎃", listOf("halloween", "pumpkin")),
        Entry("🐶", listOf("dog", "puppy")),
        Entry("🐱", listOf("cat", "kitten")),
        Entry("🦖", listOf("dinosaur", "jurassic", "t rex")),
        Entry("🤖", listOf("robot", "android", "cyborg", "artificial intelligence")),
        Entry("🏴‍☠️", listOf("pirate")),
        Entry("👑", listOf("king", "queen", "kingdom", "throne", "royal", "prince", "princess", "crown")),
        Entry("🎸", listOf("musical", "music", "band", "singer", "concert", "musician", "rock star", "song")),
        Entry("💃", listOf("dance", "dancer", "dancing", "ballet")),
        Entry("🎒", listOf("high school", "school", "college", "student", "teacher")),
        Entry("💕", listOf("romance", "romantic", "love story", "falls in love", "love")),
        Entry("🎖️", listOf("war", "soldier", "army", "military", "battlefield")),
        Entry("⚔️", listOf("sword", "samurai", "ninja", "knight", "swordsman")),
        Entry("🤠", listOf("western", "cowboy", "ranch", "outlaw", "horse")),
        Entry("🚂", listOf("train", "railway")),
        Entry("✈️", listOf("plane", "pilot", "airplane", "aircraft", "flight")),
        Entry("🦸", listOf("superhero", "superpowers", "superpower", "hero")),
        Entry("🕶️", listOf("spy", "spies", "espionage", "secret agent")),
        Entry("❄️", listOf("snow", "winter", "frozen", "arctic", "ice")),
        Entry("🏝️", listOf("island", "beach", "tropical")),
        Entry("👶", listOf("baby", "pregnant", "pregnancy")),
        Entry("💀", listOf("death", "skeleton", "killer", "serial killer")),
        Entry("💎", listOf("treasure", "diamond", "jewel")),
        Entry("🧪", listOf("scientist", "experiment", "laboratory", "chemistry")),
        Entry("🏥", listOf("hospital", "surgeon", "doctor", "medical")),
        Entry("⚖️", listOf("lawyer", "courtroom", "trial", "attorney", "legal")),
        Entry("🔒", listOf("prison", "jail", "inmate", "escape")),
        Entry("👹", listOf("monster", "creature", "beast")),
        Entry("🕷️", listOf("spider")),
        Entry("⏳", listOf("time travel", "time machine", "time loop")),
        Entry("🏕️", listOf("survival", "wilderness", "camping", "forest")),
        Entry("🎉", listOf("party", "birthday", "celebration")),
        Entry("🏔️", listOf("mountain", "climbing", "climber")),
        Entry("🌪️", listOf("tornado", "storm", "hurricane", "earthquake", "volcano", "disaster")),
        Entry("🏆", listOf("sport", "championship", "tournament", "olympics", "competition")),
        Entry("🗺️", listOf("adventure", "quest", "expedition")),
        Entry("💥", listOf("action"), genreOnly = true),
        Entry("📜", listOf("history", "historical"), genreOnly = true),
        Entry("🎥", listOf("documentary"), genreOnly = true),
        Entry("😬", listOf("thriller", "suspense"), genreOnly = true),
    )

    private fun tokens(text: String): List<String> = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** Where in [words] any of [entry]'s words first appear (a word, or its plural), else null. */
    private fun firstAt(words: List<String>, entry: Entry): Int? =
        entry.words.mapNotNull { phrase ->
            val parts = tokens(phrase)
            (0..words.size - parts.size).firstOrNull { i ->
                parts.indices.all { j ->
                    val w = words[i + j]
                    val p = parts[j]
                    w == p || (j == parts.lastIndex && (w == p + "s" || w == p + "es"))
                }
            }
        }.minOrNull()

    /**
     * The title's own emojis (at most [MAX_TITLE_EMOJIS], none of the usual ones): from its tags
     * first (the most particular), then what its overview says, then its genres; each in the order
     * its words appear.
     */
    fun emojis(item: MediaItem, show: MediaItem? = null): List<String> {
        val sources = listOf(
            (item.tags + show?.tags.orEmpty()).joinToString(" . ") to true,
            listOfNotNull(item.overview, show?.overview).joinToString(" . ") to false,
            (item.genres + show?.genres.orEmpty()).joinToString(" . ") to true,
        )
        val found = mutableListOf<String>()
        for ((text, genreLike) in sources) {
            val words = tokens(text)
            if (words.isEmpty()) continue
            TABLE.filter { genreLike || !it.genreOnly }
                .mapNotNull { e -> firstAt(words, e)?.let { it to e.emoji } }
                .sortedBy { it.first }
                .forEach { (_, emoji) -> if (emoji !in found && emoji !in QUICK_REACTIONS) found += emoji }
        }
        return found.take(MAX_TITLE_EMOJIS)
    }
}
