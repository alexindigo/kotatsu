package com.gezimos.katapult.ui.settings

/** Screen definitions supply data and callbacks; the kit owns presentation and navigation. */
data class SettingsCategory(
    val id: String,
    val title: String,
    val groups: List<SettingsGroup>,
    val summary: String = "",
    val help: SettingsHelp? = null,
)

data class SettingsHelp(val label: String, val title: String, val paragraphs: List<String>)

data class SettingsGroup(val id: String, val title: String, val entries: List<SettingsEntry>) {
    val visibleEntries get() = entries.filter { it.visible }
}

sealed class SettingsEntry {
    abstract val id: String
    abstract val title: String
    abstract val help: String
    abstract val visible: Boolean

    data class Value(
        override val id: String, override val title: String, override val help: String,
        val value: String, val choices: List<SettingsChoice>, val onSelect: (String) -> Unit,
        val selected: String, override val visible: Boolean = true,
        val appBuiltChoices: Boolean = false,
    ) : SettingsEntry() {
        val usesSheet get() = !appBuiltChoices && choices.size in 1..4
    }

    data class Switch(
        override val id: String, override val title: String, override val help: String,
        val checked: Boolean, val onCheckedChange: (Boolean) -> Unit,
        override val visible: Boolean = true,
    ) : SettingsEntry()

    data class Open(
        override val id: String, override val title: String, override val help: String,
        val onOpen: () -> Unit, override val visible: Boolean = true,
        val summary: String = "",
        val actionLabel: String? = null,
        val external: Boolean = false,
        val minimumHeightDp: Float? = null,
    ) : SettingsEntry()

    /** A plain action, not a fabricated index value. */
    data class Action(
        override val id: String, override val title: String, val onClick: () -> Unit,
        override val help: String = "", override val visible: Boolean = true,
    ) : SettingsEntry()

    data class Link(
        override val id: String, override val title: String, override val help: String,
        val onOpen: () -> Unit, override val visible: Boolean = true,
        val summary: String = "",
    ) : SettingsEntry()

    /** Runtime information is rendered in the same grammar, without an invented action. */
    data class Metadata(
        override val id: String, override val title: String, val value: String,
        override val help: String = "", override val visible: Boolean = true,
    ) : SettingsEntry()
}

data class SettingsChoice(val id: String, val label: String, val help: String = "", val external: Boolean = false)

/** Fill each page in order with whole groups; never defer a group that fits to balance pages. */
fun paginateGroups(counts: List<Int>, heights: List<Int>, availableHeight: Int): List<IntRange> =
    paginateGroups(counts, heights) { availableHeight }

/** Capacity belongs to each candidate page: a contextual footer need not occupy other pages. */
fun paginateGroups(counts: List<Int>, heights: List<Int>, availableHeight: (IntRange) -> Int): List<IntRange> {
    require(counts.size == heights.size)
    require(counts.all { it in 1..6 }) { "A Settings group must contain one to six visible entries" }
    val pages = mutableListOf<IntRange>()
    var start = 0
    while (start < counts.size) {
        var count = 0
        var height = 0
        // An oversized first group remains whole in its own scrollable viewport.
        var last = start
        for (end in start until counts.size) {
            count += counts[end]
            height += heights[end]
            if (count > 6) break
            if (height <= availableHeight(start..end)) last = end
        }
        pages += start..last
        start = last + 1
    }
    return pages
}

/** Pure dependency-message state, independent of screen and translated copy. */
data class ActionServiceNoticeState(val serviceOn: Boolean, val dismissed: Boolean, val announcementPending: Boolean = false) {
    val visible get() = !serviceOn && !dismissed
    fun observe(on: Boolean) = ActionServiceNoticeState(on, if (on) false else dismissed,
        !on && (serviceOn || announcementPending))
    fun dismiss() = copy(dismissed = true, announcementPending = false)
    fun announced() = copy(announcementPending = false)
}

/** Format strings belong to the locale; builders accept live state, never fixture defaults. */
object SettingsSummaries {
    fun appearance(format: String, shape: String, theme: String, eink: String) =
        String.format(java.util.Locale.getDefault(), format, shape, theme, eink)
    fun home(format: String, extraApps: Int) =
        String.format(java.util.Locale.getDefault(), format, extraApps)
}
