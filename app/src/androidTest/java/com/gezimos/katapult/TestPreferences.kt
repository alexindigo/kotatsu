package com.gezimos.katapult

import android.content.Context
import org.junit.Assert.assertTrue

/** Call after the fixture has stopped its producers and closed its subscriptions. */
internal fun deleteTestPreferences(context: Context, name: String) {
    // Editor.commit waits for outstanding apply writes on this same store. Deletion alone
    // has no disk-write barrier, so an earlier apply could otherwise recreate XML or .bak.
    // Use the actual named backing store, not a fixture's deliberately failing editor wrapper.
    val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    assertTrue("Could not flush test preferences $name", prefs.edit().commit())
    assertTrue("Could not delete test preferences $name", context.deleteSharedPreferences(name))
}
