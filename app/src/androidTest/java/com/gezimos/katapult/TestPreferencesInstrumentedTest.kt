package com.gezimos.katapult

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class TestPreferencesInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun deletionWaitsForTheStoreFlushAndRemovesBothFiles() {
        val name = "teardown-${UUID.randomUUID()}"
        val backing = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val flushed = AtomicBoolean(false)
        val deleted = AtomicBoolean(false)
        val worker = Executors.newSingleThreadExecutor()
        val prefs = object : SharedPreferences by backing {
            override fun edit(): SharedPreferences.Editor {
                val editor = backing.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun commit(): Boolean {
                        entered.countDown()
                        check(release.await(8, TimeUnit.SECONDS))
                        return editor.commit().also { flushed.set(it) }
                    }
                }
            }
        }
        val owner = object : ContextWrapper(context) {
            override fun getSharedPreferences(store: String, mode: Int): SharedPreferences {
                assertEquals(name, store)
                return prefs
            }
            override fun deleteSharedPreferences(store: String): Boolean {
                assertEquals(name, store)
                assertTrue("Deletion raced the store's pending disk flush", flushed.get())
                // Read the durable consumer before deletion, not just the in-memory preference.
                val persisted = mutableMapOf<String, String>()
                File(applicationInfo.dataDir, "shared_prefs/$name.xml").inputStream().use { stream ->
                    val parser = android.util.Xml.newPullParser().apply { setInput(stream, "UTF-8") }
                    while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "int") {
                            persisted[parser.getAttributeValue(null, "name")] = parser.getAttributeValue(null, "value")
                        }
                        parser.next()
                    }
                }
                assertEquals("42", persisted["applied"])
                return super.deleteSharedPreferences(store).also { deleted.set(it) }
            }
        }
        try {
            backing.edit().putInt("applied", 42).apply()
            val task = worker.submit { deleteTestPreferences(owner, name) }
            assertTrue("Teardown did not enter the store's disk-write barrier", entered.await(5, TimeUnit.SECONDS))
            assertFalse("Teardown deleted the fixture before the flush completed", deleted.get())
            release.countDown()
            task.get(8, TimeUnit.SECONDS)
            assertTrue(deleted.get())
            assertFalse(File(context.applicationInfo.dataDir, "shared_prefs/$name.xml").exists())
            assertFalse(File(context.applicationInfo.dataDir, "shared_prefs/$name.xml.bak").exists())
        } finally {
            release.countDown()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(8, TimeUnit.SECONDS))
            assertTrue(backing.edit().commit())
            deleteTestPreferences(context, name)
        }
    }

    @Test fun failedFlushPreservesTheFixtureAndFailsBeforeDeletion() {
        val name = "teardown-failure-${UUID.randomUUID()}"
        val backing = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val deleted = AtomicBoolean(false)
        val failing = object : SharedPreferences by backing {
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by backing.edit() {
                override fun commit(): Boolean = false
            }
        }
        val owner = object : ContextWrapper(context) {
            override fun getSharedPreferences(store: String, mode: Int): SharedPreferences = failing
            override fun deleteSharedPreferences(store: String): Boolean {
                deleted.set(true)
                return super.deleteSharedPreferences(store)
            }
        }
        try {
            assertTrue(backing.edit().putBoolean("evidence", true).commit())
            val error = runCatching { deleteTestPreferences(owner, name) }.exceptionOrNull()
            assertTrue("An unconfirmed disk flush must fail teardown", error is AssertionError)
            assertFalse("A failed barrier must leave evidence available", deleted.get())
            assertTrue(File(context.applicationInfo.dataDir, "shared_prefs/$name.xml").exists())
            assertTrue(backing.getBoolean("evidence", false))
        } finally {
            deleteTestPreferences(context, name)
        }
    }
}
