package com.alexindigo.kotatsu.notes

import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Random

data class Quote(val text: String, val author: String = "")
enum class QuoteFrequency { DAILY, HOURLY, WAKE }
enum class QuoteOrder { ROTATION, SHUFFLE, RANDOM }
data class QuoteConfig(val noteId: Long, val frequency: QuoteFrequency = QuoteFrequency.DAILY,
    val order: QuoteOrder = QuoteOrder.ROTATION, val seed: Long = 0)
data class QuoteWake(val lastToken: Long = -1, val counter: Long = 0)

fun parseQuotes(content: String): List<Quote> {
    val entries = mutableListOf<Quote>()
    val lines = mutableListOf<String>()
    fun flush() {
        if (lines.isNotEmpty()) {
            entries += if (lines.size == 1) Quote(lines.single())
                else Quote(lines.dropLast(1).joinToString(" "), lines.last())
            lines.clear()
        }
    }
    content.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
        if (line.isBlank()) flush() else lines += line.trim()
    }
    flush()
    return entries
}

/** Replayed options, old deliveries and ordinary redraws never invent a wake. */
fun consumeQuoteWake(previous: QuoteWake, token: Long?): QuoteWake =
    if (token == null || token <= previous.lastToken || token < 0) previous
    else QuoteWake(token, previous.counter + 1)

fun quotePeriod(frequency: QuoteFrequency, now: ZonedDateTime, wake: Long): Long = when (frequency) {
    QuoteFrequency.DAILY -> now.toLocalDate().toEpochDay()
    // Truncate in the device zone first (including half-hour offsets). The repeated DST hour is distinct.
    QuoteFrequency.HOURLY -> Math.floorDiv(now.truncatedTo(ChronoUnit.HOURS).toEpochSecond(), 3600)
    QuoteFrequency.WAKE -> wake
}

fun nextQuoteBoundary(frequency: QuoteFrequency, now: ZonedDateTime): Instant? = when (frequency) {
    QuoteFrequency.DAILY -> now.toLocalDate().plusDays(1).atStartOfDay(now.zone).toInstant()
    QuoteFrequency.HOURLY -> now.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant()
    QuoteFrequency.WAKE -> null
}

/** No content or shuffled list is persisted. Count edits simply re-index the same period. */
fun selectQuote(entries: List<Quote>, order: QuoteOrder, period: Long, seed: Long): Quote? {
    if (entries.isEmpty()) return null
    val count = entries.size
    val offset = Math.floorMod(period, count.toLong()).toInt()
    val index = when (order) {
        QuoteOrder.ROTATION -> offset
        QuoteOrder.RANDOM -> Random(mixQuoteSeed(seed, period)).nextInt(count)
        QuoteOrder.SHUFFLE -> {
            val indexes = entries.indices.toMutableList()
            val random = Random(mixQuoteSeed(seed, Math.floorDiv(period, count.toLong())))
            for (i in indexes.lastIndex downTo 1) {
                val j = random.nextInt(i + 1)
                val old = indexes[i]; indexes[i] = indexes[j]; indexes[j] = old
            }
            indexes[offset]
        }
    }
    return entries[index]
}

private fun mixQuoteSeed(seed: Long, period: Long): Long {
    var value = seed xor (period * -7046029254386353131L)
    value = (value xor (value ushr 30)) * -4658895280553007687L
    value = (value xor (value ushr 27)) * -7723592293110705685L
    return value xor (value ushr 31)
}
