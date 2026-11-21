package com.alexindigo.kotatsu.sync

import java.util.UUID

/** Transport metadata, never visible text, event content or a server field. */
internal object InsertReceipt {
    private const val OPEN = "\u2062\u2064\u2062"
    private const val CLOSE = "\u2064\u2062\u2064"
    private const val ZERO = '\u200B'
    private const val ONE = '\u200C'
    private val token = Regex(OPEN + "([$ZERO$ONE]{128})" + CLOSE)

    fun encode(description: String, receipts: Set<String>): String = description + receipts.joinToString("") { receipt ->
        val id = UUID.fromString(receipt)
        val bits = listOf(id.mostSignificantBits, id.leastSignificantBits).joinToString("") { value ->
            (63 downTo 0).map { if ((value ushr it) and 1L == 0L) ZERO else ONE }.joinToString("")
        }
        OPEN + bits + CLOSE
    }

    fun decode(description: String): Pair<String, Set<String>> {
        val matches = token.findAll(description).toList()
        // Repeated identical receipts still carry one identity; distinct receipts are not evidence.
        val receipts = matches.map { match ->
            val words = match.groupValues[1].chunked(64).map { word ->
                word.fold(0L) { value, bit -> (value shl 1) or if (bit == ONE) 1L else 0L }
            }
            UUID(words[0], words[1]).toString()
        }.toSet()
        return token.replace(description, "") to receipts
    }
}
