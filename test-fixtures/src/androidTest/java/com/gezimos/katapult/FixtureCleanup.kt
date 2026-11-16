package com.gezimos.katapult

/** Attempt every restoration without losing the primary exception. */
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
