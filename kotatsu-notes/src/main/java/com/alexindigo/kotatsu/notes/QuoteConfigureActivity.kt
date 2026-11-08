package com.alexindigo.kotatsu.notes

/** Android's optional configure/reconfigure route shares the quote kind's settings page. */
class QuoteConfigureActivity : NotesSettingsActivity() {
    override val kind = WidgetKind.QUOTE
}
