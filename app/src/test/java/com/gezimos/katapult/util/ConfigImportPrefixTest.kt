package com.gezimos.katapult.util

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class ConfigImportPrefixTest {
    private class TrackedStream(private val bytes: ByteArray, private val chunk: Int = Int.MAX_VALUE) : InputStream() {
        var consumed = 0
        override fun read(): Int = if (consumed == bytes.size) -1 else bytes[consumed++].toInt() and 255
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (consumed == bytes.size) return -1
            val count = minOf(length, chunk, bytes.size - consumed)
            bytes.copyInto(buffer, offset, consumed, consumed + count)
            consumed += count
            return count
        }
    }

    @Test fun acceptsObjectWhitespaceAndOptionalBomWithoutDroppingContent() {
        for (prefix in listOf("", " \n\t", "\uFEFF \r\n")) {
            val json = "{\"_type\":\"katapult_config\",\"name\":\"café\"}"
            assertEquals(prefix.removePrefix("\uFEFF") + json,
                PrefsManager.readImportText(TrackedStream((prefix + json).toByteArray(), 1)))
        }
    }

    @Test fun rejectsNonObjectsAndEmptyInput() {
        for (text in listOf("", " \n", "[]", "null", "PNG", "<html>", " \uFEFF{}")) {
            assertNull(PrefsManager.readImportText(ByteArrayInputStream(text.toByteArray())))
        }
    }

    @Test fun rejectsWrongTypeAndMalformedUtf8BeforeReadingBeyond4KiB() {
        for (start in listOf("not JSON".toByteArray(), byteArrayOf(123, 0xC3.toByte(), 0x28))) {
            val bytes = ByteArray(8192) { 32 }.apply { start.copyInto(this) }
            val stream = TrackedStream(bytes, 7)
            assertNull(PrefsManager.readImportText(stream))
            assertEquals(4096, stream.consumed)
        }
    }

    @Test fun rejectsWhitespaceOnlyPrefixWithoutReadingFurther() {
        val stream = TrackedStream((" ".repeat(4096) + "{}").toByteArray())
        assertNull(PrefsManager.readImportText(stream))
        assertEquals(4096, stream.consumed)
    }

    @Test fun acceptsUtf8CharacterSplitAcrossPrefixBoundaryAndReadsRemainder() {
        val text = "{\"text\":\"" + "a".repeat(4086) + "é\"}"
        val stream = TrackedStream(text.toByteArray())
        assertEquals(text, PrefsManager.readImportText(stream))
        assertEquals(text.toByteArray().size, stream.consumed)
    }

    @Test fun rejectsTruncatedUtf8AtEndOfShortDocument() {
        assertNull(PrefsManager.readImportText(ByteArrayInputStream(byteArrayOf(123, 0xE2.toByte(), 0x82.toByte()))))
    }
}
