package com.gezimos.katapult.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gezimos.katapult.R
import com.gezimos.katapult.ui.BottomSheet
import com.gezimos.katapult.ui.LatoFamily
import com.gezimos.katapult.ui.LocalInk
import com.gezimos.katapult.ui.LocalSurface
import kotlin.math.abs
import kotlin.math.max

@Composable
private fun Modifier.inkFocus(): Modifier {
    var focused by remember { mutableStateOf(false) }
    val ink = LocalInk.current
    return onFocusChanged { focused = it.isFocused }.drawBehind {
        if (focused) drawRect(ink, style = Stroke(2.dp.toPx()))
    }
}

/** The three tracks share a real text baseline. The leader never occupies either text's bounds. */
@Composable
fun IndexLine(title: String, value: String, modifier: Modifier = Modifier, root: Boolean = false) {
    val ink = LocalInk.current
    Layout(modifier = modifier.fillMaxWidth(), content = {
        Text(title, fontFamily = LatoFamily, fontSize = if (root) 21.sp else 18.sp,
            lineHeight = if (root) 25.5.sp else 21.75.sp, fontWeight = FontWeight.Bold, color = ink)
        Text(value, fontFamily = LatoFamily, fontSize = if (root) 14.sp else 18.sp,
            lineHeight = if (root) 17.25.sp else 21.75.sp,
            fontWeight = if (root) FontWeight.Normal else FontWeight.Bold, color = ink)
        Canvas(Modifier) {
            drawLine(ink, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                strokeWidth = size.height, cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(.1f, 3.dp.toPx())))
        }
    }) { children, constraints ->
        val gap = 7.5.dp.roundToPx()
        val leaderMin = 9.dp.roundToPx()
        val width = constraints.maxWidth
        val valuePlaceable = children[1].measure(Constraints(maxWidth = width / 2))
        val titlePlaceable = children[0].measure(Constraints(maxWidth = (width - valuePlaceable.width - 2 * gap - leaderMin).coerceAtLeast(1)))
        val baseline = max(titlePlaceable[FirstBaseline], valuePlaceable[FirstBaseline])
        val titleY = baseline - titlePlaceable[FirstBaseline]
        val valueY = baseline - valuePlaceable[FirstBaseline]
        val leader = children[2].measure(Constraints.fixed(
            (width - titlePlaceable.width - valuePlaceable.width - 2 * gap).coerceAtLeast(0), 1.5.dp.roundToPx()))
        layout(width, max(titleY + titlePlaceable.height, valueY + valuePlaceable.height)) {
            titlePlaceable.placeRelative(0, titleY)
            valuePlaceable.placeRelative(width - valuePlaceable.width, valueY)
            leader.placeRelative(titlePlaceable.width + gap, baseline - leader.height / 2)
        }
    }
}

@Composable
fun GroupHeading(title: String) {
    Column(Modifier.fillMaxWidth().padding(top = 3.dp)) {
        Text(title, fontFamily = LatoFamily, fontSize = 14.sp, lineHeight = 17.25.sp,
            fontWeight = FontWeight.Bold, color = LocalInk.current)
        Spacer(Modifier.height(5.25.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(LocalInk.current))
    }
}

@Composable
private fun Chevron(next: Boolean, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    Canvas(modifier.size(18.dp, 21.dp)) {
        val path = Path().apply {
            moveTo(size.width * if (next) .33f else .71f, size.height * .22f)
            lineTo(size.width * if (next) .71f else .33f, size.height * .5f)
            lineTo(size.width * if (next) .33f else .71f, size.height * .78f)
        }
        drawPath(path, ink, style = Stroke(2.25.dp.toPx()))
    }
}

@Composable
fun ChapterHeader(title: String, help: SettingsHelp?, onBack: () -> Unit, onHelp: () -> Unit) {
    val back = stringResource(R.string.paper_back)
    Row(Modifier.fillMaxWidth().heightIn(min = 57.dp).padding(start = 6.dp, end = 12.dp, top = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).semantics { contentDescription = back }
            .inkFocus().clickable(role = Role.Button, onClickLabel = back, onClick = onBack),
            contentAlignment = Alignment.Center) { Chevron(false) }
        Text(title, fontFamily = LatoFamily, fontSize = 26.sp, lineHeight = 31.5.sp,
            fontWeight = FontWeight.Bold, color = LocalInk.current, modifier = Modifier.weight(1f).semantics { heading() })
        if (help != null) Box(Modifier.size(48.dp).inkFocus().clickable(role = Role.Button, onClick = onHelp)
            .semantics { contentDescription = help.label }, contentAlignment = Alignment.Center) {
            Text("?", fontFamily = LatoFamily, fontSize = 22.5.sp, fontWeight = FontWeight.Bold, color = LocalInk.current)
        }
    }
}

data class NoticeContent(
    val message: String, val regionLabel: String, val dismissLabel: String,
    val onDismiss: () -> Unit, val actionLabel: String? = null, val onAction: (() -> Unit)? = null,
    val announcementPending: Boolean = false, val onAnnounced: () -> Unit = {},
)

/** This component knows no consumer, service, preference or navigation destination. */
@Composable
fun Notice(content: NoticeContent) {
    val ink = LocalInk.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        .drawBehind { drawLine(ink, Offset.Zero, Offset(size.width, 0f), 2.dp.toPx()) }
        .semantics { paneTitle = content.regionLabel }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(content.message, fontFamily = LatoFamily, fontSize = 14.sp, lineHeight = 18.sp,
                color = LocalInk.current, modifier = Modifier.weight(1f).padding(vertical = 6.dp).semantics {
                    if (content.announcementPending) liveRegion = LiveRegionMode.Polite
                })
            if (content.actionLabel != null && content.onAction != null) Box(
                Modifier.widthIn(min = 64.5.dp).heightIn(min = 48.dp).inkFocus()
                    .clickable(role = Role.Button, onClick = content.onAction), contentAlignment = Alignment.Center) {
                Text(content.actionLabel, fontFamily = LatoFamily, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    textDecoration = TextDecoration.Underline, color = LocalInk.current)
            }
            Box(Modifier.size(48.dp).inkFocus().clickable(role = Role.Button, onClick = content.onDismiss)
                .semantics { contentDescription = content.dismissLabel }, contentAlignment = Alignment.Center) {
                Text("×", fontFamily = LatoFamily, fontSize = 22.5.sp, color = LocalInk.current)
            }
        }
    }
    LaunchedEffect(content.announcementPending) {
        if (content.announcementPending) {
            // Publish the changed condition for accessibility once; revisiting a page is not a new event.
            withFrameNanos { }
            content.onAnnounced()
        }
    }
}

@Composable
private fun Entry(entry: SettingsEntry, onChoice: (SettingsEntry.Value) -> Unit) {
    val on = stringResource(R.string.paper_on)
    val off = stringResource(R.string.paper_off)
    val open = stringResource(R.string.paper_open)
    val external = stringResource(R.string.paper_external)
    val value = when (entry) {
        is SettingsEntry.Value -> entry.value
        is SettingsEntry.Switch -> if (entry.checked) on else off
        is SettingsEntry.Open -> open
        is SettingsEntry.Link -> "↗"
        is SettingsEntry.Metadata -> entry.value
    }
    val action = when (entry) {
        is SettingsEntry.Switch -> Modifier.toggleable(entry.checked, role = Role.Switch, onValueChange = entry.onCheckedChange)
        is SettingsEntry.Value -> Modifier.clickable(role = Role.Button) { onChoice(entry) }
        is SettingsEntry.Open -> Modifier.clickable(role = Role.Button, onClick = entry.onOpen)
        is SettingsEntry.Link -> Modifier.clickable(role = Role.Button, onClickLabel = external, onClick = entry.onOpen)
        is SettingsEntry.Metadata -> Modifier
    }
    Column(Modifier.fillMaxWidth().heightIn(min = 67.5.dp).inkFocus().then(action)
        .semantics(mergeDescendants = true) {
            if (entry is SettingsEntry.Switch) stateDescription = if (entry.checked) on else off
            if (entry is SettingsEntry.Value) contentDescription = "${entry.title}, ${entry.value}"
        }.padding(vertical = 9.dp), verticalArrangement = Arrangement.Center) {
        IndexLine(entry.title, value)
        if (entry.help.isNotEmpty()) Text(entry.help, fontFamily = LatoFamily, fontSize = 14.sp,
            lineHeight = 16.5.sp, color = LocalInk.current, modifier = Modifier.padding(top = 1.5.dp))
        if (entry is SettingsEntry.Open && entry.summary.isNotEmpty()) Text(entry.summary,
            fontFamily = LatoFamily, fontSize = 14.sp, lineHeight = 16.5.sp, fontWeight = FontWeight.Bold,
            color = LocalInk.current, modifier = Modifier.padding(top = 1.5.dp))
    }
}

@Composable
private fun Pager(page: Int, count: Int, compact: Boolean, onPage: (Int) -> Unit) {
    val label = stringResource(R.string.paper_page, page + 1, count)
    Box(Modifier.fillMaxWidth().height(if (compact) 48.dp else 63.dp)
        .padding(start = 12.dp, end = 12.dp, bottom = if (compact) 0.dp else 7.5.dp)
        .semantics { contentDescription = label }) {
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(4.5.dp)) {
            repeat(count) { index ->
                Box(Modifier.size(6.dp).border(1.5.dp, LocalInk.current, CircleShape)
                    .then(if (index == page) Modifier.background(LocalInk.current, CircleShape) else Modifier))
            }
        }
        if (page > 0) PageArrow(false, Modifier.align(Alignment.CenterStart)) { onPage(page - 1) }
        if (page < count - 1) PageArrow(true, Modifier.align(Alignment.CenterEnd)) { onPage(page + 1) }
    }
}

@Composable
private fun PageArrow(next: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val label = stringResource(if (next) R.string.paper_next_page else R.string.paper_previous_page)
    Box(modifier.size(48.dp).inkFocus().clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.5.dp).border(3.dp, LocalInk.current, CircleShape), contentAlignment = Alignment.Center) {
            Chevron(next)
        }
    }
}

@Composable
private fun IndexGroup(group: SettingsGroup, onChoice: (SettingsEntry.Value) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        GroupHeading(group.title)
        group.visibleEntries.forEach { entry -> Entry(entry, onChoice) }
    }
}

/** The same footer is measured for allocation and placed below the current page's content. */
@Composable
private fun ChapterFooter(page: Int, count: Int, notice: NoticeContent?, onPage: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (notice != null) Notice(notice)
        if (count > 1) Pager(page, count, notice != null, onPage)
    }
}

@Composable
fun PaperIndexRenderer(category: SettingsCategory, onBack: () -> Unit, modifier: Modifier = Modifier,
    notice: NoticeContent? = null, noticeGroup: String? = null) {
    var page by rememberSaveable(category.id) { mutableIntStateOf(0) }
    var count by remember(category.id) { mutableIntStateOf(1) }
    var choice by remember { mutableStateOf<SettingsEntry.Value?>(null) }
    var help by remember { mutableStateOf(false) }
    val groups = category.groups.filter { it.visibleEntries.isNotEmpty() }
    BackHandler { onBack() }
    Column(modifier.fillMaxSize()) {
        ChapterHeader(category.title, category.help, onBack) { help = true }
        // The body constraint is independent of the current page's footer. Measure the same
        // footer grammar for each candidate, including wrapped/localized Notice text.
        var drag by remember { mutableFloatStateOf(0f) }
        val scroll = rememberScrollState()
        SubcomposeLayout(Modifier.weight(1f).fillMaxWidth().pointerInput(count) {
            detectVerticalDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                if (abs(drag) > 80 && count > 1) page = (page + if (drag < 0) 1 else -1).coerceIn(0, count - 1)
            }, onVerticalDrag = { _, amount -> drag += amount })
        }) { constraints ->
            val childConstraints = Constraints(maxWidth = constraints.maxWidth)
            val measured = groups.map { group ->
                subcompose("measure-${group.id}") { IndexGroup(group) {} }.single().measure(childConstraints)
            }
            fun noticeFor(range: IntRange) = notice?.takeIf {
                noticeGroup == null || range.any { groups[it].id == noticeGroup }
            }
            val footerHeights = mutableMapOf<Pair<Boolean, Boolean>, Int>()
            fun footerHeight(paged: Boolean, hasNotice: Boolean): Int = footerHeights.getOrPut(paged to hasNotice) {
                subcompose("measure-footer-$paged-$hasNotice") {
                    ChapterFooter(0, if (paged) 2 else 1,
                        if (hasNotice) notice?.copy(announcementPending = false, onAnnounced = {}) else null) {}
                }.single().measure(childConstraints).height
            }
            val groupGap = 9.dp.roundToPx()
            val all = groups.indices
            val singleFits = groups.sumOf { it.visibleEntries.size } <= 6 &&
                measured.sumOf { it.height } + (groups.size - 1).coerceAtLeast(0) * groupGap <=
                constraints.maxHeight - footerHeight(false, noticeFor(all) != null)
            val pages = if (singleFits) listOf(all) else paginateGroups(
                groups.map { it.visibleEntries.size }, measured.map { it.height + groupGap }) { candidate ->
                constraints.maxHeight - footerHeight(true, noticeFor(candidate) != null) + groupGap
            }
            val pageCount = pages.size.coerceAtLeast(1)
            if (count != pageCount) count = pageCount
            val current = page.coerceIn(0, pageCount - 1)
            if (page != current) page = current
            val range = pages.getOrNull(current) ?: IntRange.EMPTY
            val activeNotice = noticeFor(range)
            val footer = subcompose("footer") {
                ChapterFooter(current, pageCount, activeNotice) { page = it }
            }.single().measure(childConstraints)
            val contentHeight = (constraints.maxHeight - footer.height).coerceAtLeast(0)
            val content = subcompose("content") {
                Column(Modifier.fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    range.forEach { index -> IndexGroup(groups[index]) { choice = it } }
                }
            }.single().measure(Constraints.fixed(constraints.maxWidth, contentHeight))
            layout(constraints.maxWidth, constraints.maxHeight) {
                content.placeRelative(0, 0)
                footer.placeRelative(0, contentHeight)
            }
        }
        LaunchedEffect(page) { scroll.scrollTo(0) }
    }
    choice?.let { selected ->
        ChoiceSheet(selected.title, selected.choices, selected.selected, { id -> selected.onSelect(id) }, { choice = null })
    }
    if (help) category.help?.let { HelpSheet(it.title, it.paragraphs) { help = false } }
}

@Composable
fun TableOfContents(title: String, entries: List<ContentsEntry>, notice: NoticeContent?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
            Text(title, fontFamily = LatoFamily, fontSize = 26.sp, lineHeight = 31.5.sp,
                fontWeight = FontWeight.Bold, color = LocalInk.current, modifier = Modifier.semantics { heading() })
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            entries.forEach { entry ->
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).inkFocus()
                    .clickable(role = Role.Button, onClick = entry.onOpen).semantics(mergeDescendants = true) {}
                    .padding(horizontal = 16.dp, vertical = 9.dp), contentAlignment = Alignment.CenterStart) {
                    IndexLine(entry.title, entry.summary, root = true)
                }
            }
        }
        if (notice != null) Notice(notice) else Spacer(Modifier.height(48.dp))
    }
}

/** Reuses the app's no-scrim, no-animation, Back-dismissable page window. */
@Composable
fun SheetPage(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BottomSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
            Text(title, fontFamily = LatoFamily, fontSize = 21.sp, lineHeight = 25.5.sp,
                fontWeight = FontWeight.Bold, color = LocalInk.current,
                modifier = Modifier.padding(bottom = 10.5.dp).semantics { heading() })
            content()
        }
    }
}

@Composable
fun ChoiceSheet(title: String, choices: List<SettingsChoice>, selected: String,
    onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    SheetPage(title, onDismiss) {
        choices.forEach { choice ->
            Row(Modifier.fillMaxWidth().heightIn(min = 53.25.dp).inkFocus()
                .selectable(choice.id == selected, role = Role.RadioButton, onClick = { onSelect(choice.id); onDismiss() }),
                verticalAlignment = Alignment.CenterVertically) {
                Text(choice.label, fontFamily = LatoFamily, fontSize = 18.sp, lineHeight = 21.75.sp,
                    fontWeight = if (choice.id == selected) FontWeight.Bold else FontWeight.Normal,
                    color = LocalInk.current, modifier = Modifier.weight(1f))
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (choice.id == selected) CheckMark()
                }
            }
        }
    }
}

@Composable
fun CheckMark(ink: androidx.compose.ui.graphics.Color = LocalInk.current) {
    Canvas(Modifier.size(21.dp)) {
        val path = Path().apply {
            moveTo(size.width * 4 / 24, size.height * 13 / 24)
            lineTo(size.width * 9 / 24, size.height * 18 / 24)
            lineTo(size.width * 20 / 24, size.height * 4 / 24)
        }
        drawPath(path, ink, style = Stroke(1.5.dp.toPx()))
    }
}

/** State drawing only: the enclosing row/cell owns its independent 48dp control and semantics. */
@Composable
fun IndexCheckbox(checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(3.75.dp)
    val ink = LocalInk.current
    val paper = LocalSurface.current
    Box(modifier.size(20.dp).border(if (enabled) 2.5.dp else 1.dp, ink, shape)
        .then(if (checked) Modifier.background(ink, shape) else Modifier), contentAlignment = Alignment.Center) {
        if (checked) Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) { CheckMark(paper) }
    }
}

@Composable
fun HelpSheet(title: String, paragraphs: List<String>, onDismiss: () -> Unit) {
    SheetPage(title, onDismiss) {
        paragraphs.forEach { paragraph ->
            Text(paragraph, fontFamily = LatoFamily, fontSize = 14.sp, lineHeight = 19.5.sp,
                color = LocalInk.current, modifier = Modifier.padding(bottom = 13.5.dp))
        }
    }
}
