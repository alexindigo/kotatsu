package com.gezimos.katapult

/**
 * Attempt every independent restoration without losing the primary exception.
 * Put each independent operation in its own action, including cleanup on the main thread.
 * Sequential operations within ONE action are only for prerequisites: e.g. drain writers,
 * then restore preferences only if the drain succeeded. Other actions still run on failure.
 * Never put further cleanup after this call: it rethrows after collecting all failures.
 * Use [withFixtureCleanup] around test bodies (or AutoCloseable.use for owned fixtures),
 * not raw finally/@After cleanup that can replace the original test failure.
 * The tools/tests guard checks androidTest finally blocks and trailing helper cleanup.
 */
internal fun fixtureCleanup(vararg actions: () -> Unit, failure: Throwable? = null) {
    var primary = failure
    actions.forEach { action ->
        try { action() } catch (error: Throwable) {
            if (primary == null) primary = error else if (primary !== error) primary!!.addSuppressed(error)
        }
    }
    primary?.let { throw it }
}

/** Keep the test's failure primary while attempting each independent restoration. */
internal inline fun <T> withFixtureCleanup(vararg actions: () -> Unit, body: () -> T): T {
    var failure: Throwable? = null
    try {
        return body()
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        fixtureCleanup(*actions, failure = failure)
    }
}
