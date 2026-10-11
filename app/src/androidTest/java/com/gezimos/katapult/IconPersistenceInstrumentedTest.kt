package com.gezimos.katapult

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.util.PrefsManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IconPersistenceInstrumentedTest {
    @Test fun unconfirmedStorageRestoresPreviousManualChoice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "curated_icon_save_failure_fixture"
        val backing = context.getSharedPreferences(name, 0)
        assertTrue("Preserve an unexpected existing fixture", backing.all.isEmpty())
        val key = "icon_override_fixture"
        try {
            for (previous in listOf(null, "file:/retained-manual-icon.png", "res:clock")) {
                assertTrue(backing.edit().clear().putString(key, previous).commit())
                var attempts = 0
                val uncertain = object : SharedPreferences by backing {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = backing.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                                editor.putString(key, value)
                                return this
                            }
                            override fun remove(key: String?): SharedPreferences.Editor {
                                editor.remove(key)
                                return this
                            }
                            override fun commit(): Boolean {
                                // Android commits update memory before their disk
                                // acknowledgment. Simulate an uncertain first
                                // acknowledgment even if the disk write landed.
                                assertTrue(editor.commit())
                                return ++attempts > 1
                            }
                        }
                    }
                }
                val wrapped = object : ContextWrapper(context) {
                    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = uncertain
                }
                val prefs = PrefsManager(wrapped)
                assertFalse(prefs.commitIconOverride("fixture", "catalogue:simple-icons:signal"))
                assertEquals("Failed save replaced the previous manual choice", previous, prefs.getIconOverride("fixture"))
                assertEquals(previous, backing.getString(key, null))
                assertEquals(previous, PrefsManager.iconOverrides(wrapped)["fixture"])
                assertTrue("Storage rollback was not attempted", attempts >= 2)
            }
        } finally {
            assertTrue(backing.edit().clear().commit())
            assertTrue(context.deleteSharedPreferences(name))
        }
    }
}
