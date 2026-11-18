package dev.nitrate.core.poetry

/** A short poem, its poet and where the English comes from. */
data class Poem(val lines: List<String>, val poet: String, val source: String)

/**
 * Verses for the poetry page theme and the pre-roll cards: classical Japanese tanka in English
 * translations that are in the public domain. Only public-domain translations go here (modern ones
 * are under copyright), and only verses that came through the source text cleanly.
 */
object Poems {
    const val HYAKUNIN_ISSHU = "Hyakunin Isshu, tr. William N. Porter (1909)"
    private const val PORTER_INTRO = "tr. William N. Porter, A Hundred Verses from Old Japan (1909)"

    val all: List<Poem> = listOf(
        Poem(listOf("Though masterless my home appear,", "When I have gone away,", "Oh plum tree growing by the eaves,", "Forget not to display", "Thy buds in spring, I pray."), "Sanetomo Minamoto", PORTER_INTRO),
        Poem(listOf("Out in the fields this autumn day", "They're busy reaping grain;", "I sought for shelter 'neath this roof,", "But fear I sought in vain, —", "My sleeve is wet with rain."), "The Emperor Tenchi", HYAKUNIN_ISSHU),
        Poem(listOf("The spring has gone, the summer's come,", "And I can just descry", "The peak of Ama-no-kagu,", "Where angels of the sky", "Spread their white robes to dry."), "The Empress Jito", HYAKUNIN_ISSHU),
        Poem(listOf("Long is the mountain pheasant's tail", "That curves down in its flight;", "But longer still, it seems to me,", "Left in my lonely plight,", "Is this unending night."), "The Nobleman Kaki-no-moto", HYAKUNIN_ISSHU),
        Poem(listOf("When on the Magpies' Bridge I see", "The Hoar-frost King has cast", "His sparkling mantle, well I know", "The night is nearly past,", "Daylight approaches fast."), "The Imperial Adviser Yakamochi", HYAKUNIN_ISSHU),
        Poem(listOf("My home is near the Capital,", "My humble cottage bare", "Lies south-east on Mount Uji; so", "The people all declare", "My life's a 'Hill of Care'."), "The Priest Kizen", HYAKUNIN_ISSHU),
        Poem(listOf("Oh! Fishers in your little boats,", "Quick! tell my men, I pray,", "They'll find me at Yasoshima,", "I'm being rowed away", "Far off across the bay."), "The Privy Councillor Takamura", HYAKUNIN_ISSHU),
        Poem(listOf("Mother, for thy sake I have been", "Where the wakana grow,", "To bring thee back some fresh green leaves;", "And see — my koromo", "Is sprinkled with the snow!"), "The Emperor Kwoko", HYAKUNIN_ISSHU),
        Poem(listOf("If breezes on Inaba's peak", "Sigh through the old pine tree,", "To whisper in my lonely ears", "That thou dost pine for me, —", "Swiftly I'll fly to thee."), "The Imperial Adviser Yuki-Hira Ariwara", HYAKUNIN_ISSHU),
        Poem(listOf("All red with leaves Tatsuta's stream", "So softly purls along,", "The everlasting Gods themselves,", "Who judge 'twixt right and wrong,", "Ne'er heard so sweet a song."), "The Minister Nari-Hira Ariwara", HYAKUNIN_ISSHU),
        Poem(listOf("To-night on Sumi-no-ye beach", "The waves alone draw near;", "And, as we wander by the cliffs,", "No prying eyes shall peer,", "No one shall dream we're here."), "The Minister Toshi-Yuki Fujiwara", HYAKUNIN_ISSHU),
        Poem(listOf("Short as the joints of bamboo reeds", "That grow beside the sea", "On pebble beach at Naniwa,", "I hope the time may be,", "When thou'rt away from me."), "The Princess Ise", HYAKUNIN_ISSHU),
        Poem(listOf("We met but for a moment, and", "I'm wretched as before;", "The tide shall measure out my life,", "Unless I see once more", "The maid, whom I adore."), "The Heir-Apparent Moto-Yoshi", HYAKUNIN_ISSHU),
        Poem(listOf("Oh! rippling River Izumi,", "That flows through Mika plain,", "Why should the maid I saw but now", "And soon shall see again", "Torment my love-sick brain?"), "The Imperial Adviser Kanesuke", HYAKUNIN_ISSHU),
        Poem(listOf("The mountain village solitude", "In winter time I dread;", "It seems as if, when friends are gone,", "And trees their leaves have shed,", "All men and plants are dead."), "The Minister Mune-Yuki Minamoto", HYAKUNIN_ISSHU),
        Poem(listOf("How desolate my former life,", "Those dismal years, ere yet", "I chanced to see thee face to face;", "'Twere better to forget", "Those days before we met."), "The Imperial Adviser Yatsu-Tada", HYAKUNIN_ISSHU),
        Poem(listOf("To fall in love with womankind", "Is my unlucky fate;", "If only it were otherwise,", "I might appreciate", "Some men, whom now I hate."), "The Imperial Adviser Asa-Tada", HYAKUNIN_ISSHU),
        Poem(listOf("The fishing-boats are tossed about,", "When stormy winds blow strong;", "With rudder lost, how can they reach", "The port for which they long?", "So runs the old love-song."), "The Priest Ne-Yoshi-Tada", HYAKUNIN_ISSHU),
        Poem(listOf("My little temple stands alone,", "No other hut is near;", "No one will pass to stop and praise", "Its vine-grown roof, I fear,", "Now that the autumn's here."), "The Priest Ye-Kei", HYAKUNIN_ISSHU),
        Poem(listOf("My constancy to her I love", "I never will forsake;", "As surely as the Palace Guards", "Each night their watch-fire make", "And guard it till daybreak."), "The Minister Yoshi-Nobu, of Priestly Rank", HYAKUNIN_ISSHU),
        Poem(listOf("Although I know the gentle night", "Will surely follow morn,", "Yet, when I'm wakened by the sun,", "Turn over, stretch and yawn —", "How I detest the dawn!"), "The Minister Michi-Nobu Fujiwara", HYAKUNIN_ISSHU),
        Poem(listOf("How difficult it is for men", "Not to forget the past!", "I fear my husband's love for me", "Is disappearing fast;", "This day must be my last."), "The Mother of the Minister of State", HYAKUNIN_ISSHU),
        Poem(listOf("In lonely solitude I dwell,", "No human face I see;", "And so we two must sympathize,", "Oh mountain cherry tree;", "I have no friend but thee."), "The Archbishop Gyoson", HYAKUNIN_ISSHU),
        Poem(listOf("If I had made thy proffered arm", "A pillow for my head", "For but the moment's time, in which", "A summer's dream had fled,", "What would the world have said?"), "The Lady-in-Waiting Suwo", HYAKUNIN_ISSHU),
        Poem(listOf("If in this troubled world of ours", "I still must linger on,", "My only friend shall be the moon,", "Which on my sadness shone,", "When other friends were gone."), "The Retired Emperor Sanjo", HYAKUNIN_ISSHU),
        Poem(listOf("The storms, which round Mount Mimuro", "Are wont to howl and scream,", "Have thickly scattered maple leaves", "Upon Tatsuta's stream;", "Like red brocade they seem."), "The Priest No-In", HYAKUNIN_ISSHU),
        Poem(listOf("This autumn night the wind blows shrill,", "And would that I could catch", "Its message, as it whistles through", "The rushes in the thatch", "And leaves of my rice-patch."), "The First Adviser of State Tsune-Nobu", HYAKUNIN_ISSHU),
        Poem(listOf("The cherry trees are blossoming", "On Takasago's height;", "Oh may no mountain mist arise,", "No clouds so soft and white,", "To hide them from our sight."), "The Assistant Imperial Adviser Masafusa", HYAKUNIN_ISSHU),
        Poem(listOf("Oh! Kwannon, Patron of this hill,", "The maid, for whom I pine,", "Is obstinate and wayward, like", "The gusts around thy shrine.", "What of those prayers of mine?"), "The Minister Toshi-Yori Minamoto", HYAKUNIN_ISSHU),
        Poem(listOf("When rowing on the open sea,", "The waves, all capped with white,", "Roll onward, like the fleecy clouds", "With their resistless might;", "Truly a wondrous sight!"), "The Lay Priest of the Hosho Temple", HYAKUNIN_ISSHU),
        Poem(listOf("The rock divides the stream in two,", "And both with might and main", "Go tumbling down the waterfall;", "But well I know the twain", "Will soon unite again."), "The Retired Emperor Sutoku", HYAKUNIN_ISSHU),
        Poem(listOf("How sad and gloomy is the world,", "This world of sin and woe!", "Ah! while I drift along Life's stream,", "Tossed helpless to and fro,", "My tears will ever flow."), "The Priest Do-In", HYAKUNIN_ISSHU),
        Poem(listOf("All through the never-ending night", "I lie awake and think;", "In vain I look to try and see", "The daybreak's feeble blink", "Peep through the shutter's chink."), "The Priest Shun-Ye", HYAKUNIN_ISSHU),
        Poem(listOf("O'ercome with pity for this world,", "My tears obscure my sight;", "I wonder, can it be the moon", "Whose melancholy light", "Has saddened me to-night?"), "The Priest Saigyo", HYAKUNIN_ISSHU),
        Poem(listOf("The rain, which fell from passing showers,", "Like drops of dew, still lies", "Upon the fir-tree needles, and", "The mists of evening rise", "Up to the autumn skies."), "The Priest Jaku-Ren", HYAKUNIN_ISSHU),
        Poem(listOf("Around Mount Miyoshino's crest", "The autumn winds blow drear;", "The villagers are beating cloth,", "Their merry din I hear,", "This night so cold and clear."), "The Privy Councillor Masatsune", HYAKUNIN_ISSHU),
        Poem(listOf("Unfit to rule this wicked world", "With all its pomp and pride,", "I'd rather in my plain black robe", "A humble priest abide,", "Far up the mountain side."), "The Former Archbishop Jiyen", HYAKUNIN_ISSHU),
        Poem(listOf("Upon the shore of Matsu-ho", "For thee I pine and sigh;", "Though calm and cool the evening air,", "These salt-pans caked and dry", "Are not more parched than I!"), "The Assistant Imperial Adviser Sada-Iye", HYAKUNIN_ISSHU),
        Poem(listOf("The twilight dim, the gentle breeze", "By Nara's little stream,", "The splash of worshippers who wash", "Before the shrine, all seem", "A perfect summer's dream."), "The Official Iye-Taka", HYAKUNIN_ISSHU),
        Poem(listOf("How I regret my fallen friends", "How I despise my foes!", "And, tired of life, I only seek", "To reach my long day's close,", "And gain at last repose."), "The Retired Emperor Gotoba", HYAKUNIN_ISSHU),
        Poem(listOf("My ancient Palace I regret,", "Though rot attacks the eaves,", "And o'er the roof the creeping vine", "Spreads out and interweaves", "Unpruned its straggling leaves."), "The Retired Emperor Jun-Toku", HYAKUNIN_ISSHU),
    )

    /** The poem for a moment: changes every [everyMs], the same on every screen at once. */
    fun at(timeMs: Long, everyMs: Long = 30_000): Poem = all[((timeMs / everyMs) % all.size).toInt()]

    /** One at random (pre-rolls), avoiding the [recent] ones while others are left. */
    fun pick(random: kotlin.random.Random, recent: Collection<Poem> = emptyList()): Poem =
        (all - recent.toSet()).ifEmpty { all }.random(random)
}
