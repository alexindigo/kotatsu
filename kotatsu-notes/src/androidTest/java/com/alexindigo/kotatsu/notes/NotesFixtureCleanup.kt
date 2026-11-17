package com.alexindigo.kotatsu.notes

import com.gezimos.katapult.fixtureCleanup

/** NotesRuntime logs RuntimeExceptions, so carry cleanup failures back to the test thread. */
internal fun notesFixtureCleanup(vararg actions: () -> Unit) {
    var outcome: Result<Unit>? = null
    NotesRuntime.enqueue { outcome = runCatching { fixtureCleanup(*actions) } }.get()
    outcome!!.getOrThrow()
}
