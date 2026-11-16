package dev.nitrate.core.model

/** Labels for episodes the library doesn't have yet. */
object AirDates {
    private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    private val MONTH_NAMES = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    /** "Monday, April 19th, 2026" (air dates are whole days, kept in UTC). */
    fun long(epochMs: Long): String {
        val (year, month, day, weekday) = civil(epochMs)
        val suffix = if (day in 11..13) "th" else when (day % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "${DAY_NAMES[weekday]}, ${MONTH_NAMES[month - 1]} $day$suffix, $year"
    }

    /** Year, month (1–12), day and weekday (0 = Monday) of a UTC date. */
    private fun civil(epochMs: Long): List<Int> {
        val days = epochMs.floorDiv(86_400_000L)
        // Civil date from days since 1970-01-01 (Howard Hinnant's algorithm).
        val z = days + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
        val weekday = (days + 3).mod(7L).toInt() // 1970-01-01 was a Thursday
        return listOf(year, month, day, weekday)
    }

    /** "Fri 21 Nov", from a date (air dates are whole days, kept in UTC). */
    fun short(epochMs: Long): String {
        val days = epochMs.floorDiv(86_400_000L)
        // Civil date from days since 1970-01-01 (Howard Hinnant's algorithm).
        val z = days + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val weekday = (days + 3).mod(7L).toInt() // 1970-01-01 was a Thursday
        return "${DAYS[weekday]} $day ${MONTHS[month - 1]}"
    }

    /** "Airs Fri 21 Nov" for an episode still to come, "Missing" for one that aired but isn't here, else null. */
    fun status(item: MediaItem, nowMs: Long): String? {
        if (!item.isMissing) return null
        val at = item.premiereDateMs
        return if (at != null && at > nowMs - 86_400_000L) "Airs ${short(at)}" else "Missing"
    }
}
