package com.alexindigo.kotatsu.notes

/** Android's optional configure/reconfigure route shares the task kind's settings page. */
class ConfigureActivity : NotesSettingsActivity() {
    override val kind = WidgetKind.TASK
}
