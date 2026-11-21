package com.alexindigo.kotatsu.sync

import org.junit.Assert.*
import org.junit.Test

class InsertReceiptTest {
    private val id = "56d2abea-b370-498e-bdd1-0324658910af"
    @Test fun receiptRoundTripsWithoutChangingDescriptionOrBecomingVisibleText() {
        for (body in listOf("", "Ordinary\nLocation: text", "Added ${EventMapper.BODY_MARKER}Body")) {
            val wire = InsertReceipt.encode(body, setOf(id))
            assertEquals(body to setOf(id), InsertReceipt.decode(wire))
            assertTrue(wire.drop(body.length).all { Character.getType(it) == Character.FORMAT.toInt() })
        }
    }
    @Test fun movedReceiptStillIdentifiesAnEditedEventAndConflictingReceiptsAreNotEvidence() {
        val wire = InsertReceipt.encode("", setOf(id))
        assertEquals("BeforeAfter" to setOf(id), InsertReceipt.decode("Before${wire}After"))
        val otherId = "12345678-1234-1234-1234-123456789abc"
        val other = InsertReceipt.encode("", setOf(otherId))
        assertEquals(setOf(id, otherId), InsertReceipt.decode(wire + other).second)
    }
    @Test fun receiptIsNotPartOfEventContentOrStandardUploads() {
        val mapper = EventMapper()
        val event = MuditaEvent(1, "Title", "Body", 1, 2)
        val tagged = event.copy(insertReceipts = setOf(id))
        assertEquals(mapper.muditaFingerprint(event), mapper.muditaFingerprint(tagged))
        val source = StandardEvent(1, 1, "Title", "Body", start = 1, end = 2)
        assertEquals(source, mapper.toStandard(tagged, source))
        assertEquals(setOf(id), mapper.toMudita(source, tagged).insertReceipts)
    }
}
