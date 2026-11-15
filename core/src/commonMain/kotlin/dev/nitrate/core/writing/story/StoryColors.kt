package dev.nitrate.core.writing.story

/** Colours the story screens share (ARGB), so TV and phone show the same thing the same way. */
object StoryColors {
    /** Scene card colours; 0 is "none". */
    val SCENE = listOf(0x00000000, 0xFFE57373, 0xFFFFB74D, 0xFFFFF176, 0xFF81C784, 0xFF64B5F6, 0xFFBA68C8).map { it.toInt() }
    val SCENE_NAMES = listOf("None", "Red", "Orange", "Yellow", "Green", "Blue", "Purple")

    /** One colour per kind of line on the map. */
    fun edge(kind: EdgeKind): Int = when (kind) {
        EdgeKind.APPEARS -> 0xFF4FC3F7
        EdgeKind.SET_IN -> 0xFF81C784
        EdgeKind.RELATES -> 0xFFFF8A65
        EdgeKind.LINKED -> 0xFFBA68C8
    }.toInt()

    /** Node colours: scenes, then one per list (in list order). */
    const val SCENE_NODE = 0xFFECEFF1.toInt()
    private val LISTS = listOf(0xFF4FC3F7, 0xFF81C784, 0xFFFFD54F, 0xFFF06292, 0xFF9575CD, 0xFF4DB6AC).map { it.toInt() }
    fun list(index: Int): Int = LISTS[index.mod(LISTS.size)]

    /** A colour per writer for "who wrote this", the same for them on every device. */
    private val WRITERS = listOf(0xFF4FC3F7, 0xFFFFB74D, 0xFFAED581, 0xFFF06292, 0xFF9575CD, 0xFF4DB6AC, 0xFFFFF176, 0xFFFF8A65).map { it.toInt() }
    fun writer(userId: String): Int = WRITERS[userId.hashCode().mod(WRITERS.size)]
}
