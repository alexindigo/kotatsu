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
    ) : SettingsEntry()

    data class Switch(
        override val id: String, override val title: String, override val help: String,
        val checked: Boolean, val onCheckedChange: (Boolean) -> Unit,
        override val visible: Boolean = true,
    ) : SettingsEntry()

    data class Open(
        override val id: String, override val title: String, override val help: String,
        val onOpen: () -> Unit, override val visible: Boolean = true,
        val summary: String = "",
    ) : SettingsEntry()

    data class Link(
        override val id: String, override val title: String, override val help: String,
        val onOpen: () -> Unit, override val visible: Boolean = true,
    ) : SettingsEntry()

    /** Runtime information is rendered in the same grammar, without an invented action. */
    data class Metadata(
        override val id: String, override val title: String, val value: String,
        override val help: String = "", override val visible: Boolean = true,
    ) : SettingsEntry()
}

data class SettingsChoice(val id: String, val label: String)
data class ContentsEntry(val id: String, val title: String, val summary: String, val onOpen: () -> Unit)

/** Whole-group allocation: fewest pages, then no avoidable singleton, then balanced entry counts. */
fun paginateGroups(counts: List<Int>, heights: List<Int>, availableHeight: Int): List<IntRange> =
    paginateGroups(counts, heights) { availableHeight }

/** Capacity belongs to each candidate page: a contextual footer need not occupy other pages. */
fun paginateGroups(counts: List<Int>, heights: List<Int>, availableHeight: (IntRange) -> Int): List<IntRange> {
    require(counts.size == heights.size)
    require(counts.all { it in 1..6 }) { "A Settings group must contain one to six visible entries" }
    data class Allocation(val pages: List<IntRange>, val orphans: Int, val densityCost: Int)
    val best = arrayOfNulls<Allocation>(counts.size + 1)
    best[counts.size] = Allocation(emptyList(), 0, 0)
    for (start in counts.indices.reversed()) {
        var count = 0
        var height = 0
        for (end in start until counts.size) {
            count += counts[end]
            height += heights[end]
            if (count > 6) break
            if (height > availableHeight(start..end) && end > start) continue
            val tail = best[end + 1] ?: continue
            val candidate = Allocation(listOf(start..end) + tail.pages, tail.orphans + if (count == 1) 1 else 0,
                tail.densityCost + count * count)
            val current = best[start]
            if (current == null || candidate.pages.size < current.pages.size ||
                (candidate.pages.size == current.pages.size && (candidate.orphans < current.orphans ||
                    (candidate.orphans == current.orphans && candidate.densityCost < current.densityCost)))) best[start] = candidate
        }
    }
    return best[0]?.pages.orEmpty()
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
