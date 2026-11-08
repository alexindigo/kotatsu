package com.alexindigo.kotatsu.notes

import android.content.Context
import org.json.JSONObject

/** Only selection, seed and counters live in device storage; quote content is always read afresh. */
class QuoteStore(context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("quote-widgets", 0)
    fun config(id: Int): QuoteConfig? = runCatching {
        val json = JSONObject(prefs.getString("config_$id", null) ?: return null)
        QuoteConfig(json.getLong("note"), QuoteFrequency.valueOf(json.getString("frequency")),
            QuoteOrder.valueOf(json.getString("order")), json.getLong("seed"))
    }.getOrNull()
    fun save(id: Int, config: QuoteConfig) {
        require(config.noteId >= 0)
        check(prefs.edit().putString("config_$id", JSONObject().apply {
            put("note", config.noteId); put("frequency", config.frequency.name)
            put("order", config.order.name); put("seed", config.seed)
        }.toString()).commit())
    }
    fun wake(id: Int) = QuoteWake(prefs.getLong("token_$id", -1), prefs.getLong("counter_$id", 0))
    fun consume(id: Int, token: Long?): QuoteWake {
        val old = wake(id)
        return consumeQuoteWake(old, token).also { next ->
            if (old != next) check(prefs.edit().putLong("token_$id", next.lastToken)
                .putLong("counter_$id", next.counter).commit())
        }
    }
    fun delete(id: Int) { check(prefs.edit().remove("config_$id").remove("token_$id").remove("counter_$id").commit()) }
    fun restore(old: Int, new: Int) {
        config(old)?.let { save(new, it) }
        val wake = wake(old)
        check(prefs.edit().putLong("token_$new", wake.lastToken).putLong("counter_$new", wake.counter).commit())
        delete(old)
    }
}
