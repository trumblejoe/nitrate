package dev.nitrate.core.companion

/** One heading of the chat's emoji list and its emojis. */
data class EmojiGroup(val name: String, val emojis: List<String>)

/**
 * The chat's full emoji list, opened from its one emoji button (as chat apps do): the title's own
 * and the usual reactions first ("For this", [QuickPicks.emojis]), then the rest by kind, each emoji
 * once. Picking one sends it as a reaction.
 */
object Emojis {
    val GROUPS = listOf(
        EmojiGroup(
            "Faces",
            listOf(
                "😀", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "😉", "😊", "😇", "🥰", "😍", "🤩", "😘", "😋",
                "😜", "🤪", "🤔", "🤨", "😐", "😑", "😶", "🙄", "😏", "😬", "😌", "😴", "🥱", "🤯", "🥳", "😎",
                "🤓", "🧐", "😕", "😟", "🙁", "😮", "😯", "😲", "😳", "🥺", "😦", "😧", "😨", "😰", "😥", "😢",
                "😭", "😱", "😖", "😣", "😞", "😓", "😩", "😫", "😤", "😡", "😠", "🤬", "💀", "👻", "🤡", "🤖",
            ),
        ),
        EmojiGroup(
            "Hands and hearts",
            listOf(
                "👏", "🙌", "👍", "👎", "👊", "✊", "🤞", "✌️", "🤘", "👌", "🤌", "👀", "🙈", "🙉", "🙊", "🫣",
                "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "💔", "💕", "💯", "💥", "✨", "💫", "💤", "💬", "❗",
                "❓", "‼️",
            ),
        ),
        EmojiGroup(
            "Things",
            listOf(
                "🍿", "🔥", "🎬", "🎥", "📺", "🎵", "🎶", "🎉", "🏆", "⭐", "🌟", "⚡", "🌈", "☔", "❄️", "🌙",
                "🍕", "🍔", "🍩", "🍫", "☕", "🍷", "🍻", "🥂", "🔪", "🩸", "⚰️", "🔍", "🕵️", "💡", "💣", "🚀",
            ),
        ),
    )

    /** "For this" ([picks]' emojis) then every group, without repeating one already shown above it. */
    fun groupsFor(picks: QuickPicks): List<EmojiGroup> {
        val seen = picks.emojis.toMutableSet()
        return listOf(EmojiGroup("For this", picks.emojis)) +
            GROUPS.map { g -> EmojiGroup(g.name, g.emojis.filter { seen.add(it) }) }.filter { it.emojis.isNotEmpty() }
    }
}
