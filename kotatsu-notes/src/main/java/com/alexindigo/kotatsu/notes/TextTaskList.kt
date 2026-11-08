package com.alexindigo.kotatsu.notes

/** Source ranges, not reconstructed lines, own edits. Separators and untouched bytes are never normalized. */
object TextTaskList {
    data class Row(val item: TaskItem, val markerStart: Int = -1, val marker: String = "")

    fun rows(content: String): List<Row> = buildList {
        val lines = Regex("[^\\r\\n]*(?:\\r\\n|\\r|\\n|$)").findAll(content)
        for (match in lines) {
            if (match.value.isEmpty()) continue
            val line = match.value.trimEnd('\r', '\n')
            val start = line.indexOfFirst { it != ' ' && it != '\t' }
            if (start < 0) {
                if (lastOrNull()?.item?.kind != TaskRowKind.GAP) add(Row(TaskItem("", false, TaskRowKind.GAP)))
                continue
            }
            val body = line.substring(start)
            val marker = listOf("[ ]", "[x]", "[X]", "\u2713", "\u25e6", "\u2022", "-", "*", "\u00b7")
                .firstOrNull { marker -> body.startsWith(marker) &&
                    (marker != "-" && marker != "*" || body.length == marker.length || body[marker.length].isWhitespace()) }
            if (marker == null) add(Row(TaskItem(line.trim(), false, TaskRowKind.HEADING)))
            else add(Row(TaskItem(body.substring(marker.length).trimStart(' ', '\t'), marker in listOf("[x]", "[X]", "\u2713")),
                match.range.first + start, marker))
        }
    }

    fun toggle(content: String, index: Int): String? {
        val row = rows(content).getOrNull(index)?.takeIf { it.item.kind == TaskRowKind.ITEM } ?: return null
        val replacement = when (row.marker) {
            "[ ]" -> "[x]"
            "[x]", "[X]" -> "[ ]"
            "\u2713" -> "\u25e6"
            else -> "\u2713"
        }
        return content.replaceRange(row.markerStart, row.markerStart + row.marker.length, replacement)
    }
}
