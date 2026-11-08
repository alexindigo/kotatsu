package com.alexindigo.kotatsu.notes

import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class QuotesTest {
    private val quotes = (0..5).map { Quote("Quote $it", "Author $it") }
    @Test fun blankEntriesCrLfWhitespaceOneTwoAndManyLines() {
        assertEquals(listOf(Quote("Alone"), Quote("Two lines", "Author"), Quote("Several quote lines", "Last author")),
            parseQuotes(" \r\n Alone  \r\n\t\r\nTwo lines \r\n Author \r\n\r\nSeveral\nquote lines\nLast author\n\n "))
        assertTrue(parseQuotes("\r\n \n\t").isEmpty())
        assertEquals(listOf(Quote("Unicode é and punctuation: !?", "Name")), parseQuotes("Unicode é and punctuation: !?\nName"))
    }
    @Test fun everyOrderAndFrequencyIsStableWithinItsPeriod() {
        val now = ZonedDateTime.parse("2026-11-06T12:15:00+01:00[Europe/Paris]")
        for (frequency in QuoteFrequency.entries) for (order in QuoteOrder.entries) {
            val period = quotePeriod(frequency, now, 3)
            val selected = selectQuote(quotes, order, period, 19)
            assertTrue(selected in quotes)
            assertEquals(selected, selectQuote(quotes, order, quotePeriod(frequency, now.plusMinutes(20), 3), 19))
            assertNull(selectQuote(emptyList(), order, period, 19))
            assertEquals(quotes.first(), selectQuote(quotes.take(1), order, period, 19))
            assertTrue(selectQuote(quotes.take(4), order, period, 19) in quotes.take(4))
        }
    }
    @Test fun rotationFollowsNoteOrderAndWrapsForAllFrequencies() {
        for (frequency in QuoteFrequency.entries) {
            val now = ZonedDateTime.parse("2026-11-06T12:15:00+01:00[Europe/Paris]")
            val start = quotePeriod(frequency, now, 0)
            val actual = (0..12).map { selectQuote(quotes, QuoteOrder.ROTATION, start + it, 1) }
            actual.zipWithNext().forEach { (a,b) -> assertEquals((quotes.indexOf(a) + 1) % 6, quotes.indexOf(b)) }
        }
    }
    @Test fun shuffleIsAPermutationPerCycleIncludingNegativePeriods() {
        for (cycle in -4L..10L) {
            val selected = (0L..5L).map { selectQuote(quotes, QuoteOrder.SHUFFLE, cycle * 6 + it, 798) }
            assertEquals(quotes.toSet(), selected.toSet())
        }
        assertNotEquals((0L..5L).map { selectQuote(quotes, QuoteOrder.SHUFFLE, it, 1) },
            (6L..11L).map { selectQuote(quotes, QuoteOrder.SHUFFLE, it, 1) })
    }
    @Test fun independentRandomAllowsRepeatsAndSeedChangesSequence() {
        val selected = (0L..100L).map { selectQuote(quotes, QuoteOrder.RANDOM, it, 9) }
        assertTrue(selected.zipWithNext().any { it.first == it.second })
        assertNotEquals(selected, (0L..100L).map { selectQuote(quotes, QuoteOrder.RANDOM, it, 10) })
    }
    @Test fun dailyBoundariesFollowLocalDatesAcrossShortAndLongDstDays() {
        for ((date, hours) in listOf("2026-03-29" to 23L, "2026-10-25" to 25L)) {
            val start = ZonedDateTime.parse("${date}T00:00:00+01:00[Europe/Paris]").toLocalDate().atStartOfDay(java.time.ZoneId.of("Europe/Paris"))
            val next = nextQuoteBoundary(QuoteFrequency.DAILY, start)!!
            assertEquals(hours, java.time.Duration.between(start.toInstant(), next).toHours())
            assertEquals(quotePeriod(QuoteFrequency.DAILY, start, 0), quotePeriod(QuoteFrequency.DAILY, next.minusSeconds(1).atZone(start.zone), 0))
            assertEquals(quotePeriod(QuoteFrequency.DAILY, start, 0) + 1, quotePeriod(QuoteFrequency.DAILY, next.atZone(start.zone), 0))
        }
    }
    @Test fun hourlyHandlesRepeatedSkippedAndHalfHourZoneHours() {
        for (now in listOf(ZonedDateTime.parse("2026-10-25T02:15:00+02:00[Europe/Paris]"),
            ZonedDateTime.parse("2026-03-29T01:15:00+01:00[Europe/Paris]"),
            ZonedDateTime.parse("2026-11-06T12:15:00+05:30[Asia/Kolkata]"))) {
            val next = nextQuoteBoundary(QuoteFrequency.HOURLY, now)!!.atZone(now.zone)
            assertEquals(0, next.minute)
            assertEquals(quotePeriod(QuoteFrequency.HOURLY, now, 0) + 1, quotePeriod(QuoteFrequency.HOURLY, next, 0))
        }
    }
    @Test fun wakesAreStrictlyGreaterPersistableAndDoNotNeedAnAlarm() {
        val first = consumeQuoteWake(QuoteWake(), 5)
        assertEquals(QuoteWake(5, 1), first)
        for (token in listOf(null, -1L, 3L, 5L)) assertEquals(first, consumeQuoteWake(first, token))
        assertEquals(QuoteWake(6, 2), consumeQuoteWake(first.copy(), 6))
        assertNull(nextQuoteBoundary(QuoteFrequency.WAKE, ZonedDateTime.now()))
    }
}
