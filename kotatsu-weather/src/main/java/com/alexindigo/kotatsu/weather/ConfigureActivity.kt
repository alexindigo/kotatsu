package com.alexindigo.kotatsu.weather

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.concurrent.Executors

class ConfigureActivity : Activity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var config = WidgetConfig(emptyList())
    private lateinit var rows: LinearLayout
    private lateinit var done: Button
    private lateinit var add: Button
    private val executor = Executors.newSingleThreadExecutor()
    private var searchGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId !in WeatherRuntime.ids(this)) { finish(); return }
        config = savedInstanceState?.getString("draft")?.let { ConfigJson.decode(it) } ?: WeatherStore(this).config(widgetId)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val body = column().apply { setPadding(dp(20), dp(16), dp(20), dp(20)); setBackgroundColor(Color.WHITE) }
        body.addView(text("Kotatsu Weather", 24f, true))
        rows = column()
        body.addView(rows)
        add = button("Add location") { search() }
        body.addView(add)
        val units = Switch(this).apply {
            text = if (config.fahrenheit) "°F" else "°C"
            isChecked = !config.fahrenheit
            setOnCheckedChangeListener { _, checked ->
                config = config.copy(fahrenheit = !checked)
                text = if (checked) "°C" else "°F"
            }
        }
        body.addView(units)
        done = button("Done") {
            WeatherStore(this).save(widgetId, config)
            WeatherRuntime.schedule(this)
            WeatherRuntime.redraw(this, widgetId)
            WeatherRuntime.refresh(this, force = true)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
        body.addView(done)
        body.addView(text("Weather data by Open-Meteo.com (CC BY 4.0)", 14f))
        setContentView(ScrollView(this).apply { addView(body) })
        style(body)
        showRows()
    }

    private fun showRows() {
        rows.removeAllViews()
        config.locations.forEachIndexed { index, location ->
            val row = column().apply { setPadding(0, dp(16), 0, dp(8)) }
            row.addView(text("${location.label} · ${location.place} · ${location.start}–${location.end}", 18f, true))
            row.addView(LinearLayout(this).apply {
                addView(button("Edit") { edit(index) })
                addView(button("Remove") {
                    config = config.copy(locations = config.locations.filterIndexed { i, _ -> i != index })
                    showRows()
                })
            })
            rows.addView(row)
        }
        done.isEnabled = config.locations.isNotEmpty()
        add.isEnabled = config.locations.size < 3
        style(rows)
    }

    private fun edit(index: Int) {
        val location = config.locations[index]
        val body = column().apply { setPadding(dp(20), 0, dp(20), 0) }
        val label = EditText(this).apply {
            setSingleLine(true)
            setText(location.label)
            hint = "Label (up to 8 characters)"
            filters = arrayOf(InputFilter { source, start, end, dest, dstart, dend ->
                val kept = dest.toString().removeRange(dstart, dend)
                val available = 8 - kept.codePointCount(0, kept.length)
                val input = source.subSequence(start, end).toString()
                if (input.codePointCount(0, input.length) <= available) null
                else input.substring(0, input.offsetByCodePoints(0, available.coerceAtLeast(0)))
            })
        }
        body.addView(text("Label", 16f)); body.addView(label)
        val pickers = LinearLayout(this)
        val start = NumberPicker(this).apply { minValue = 0; maxValue = 23; value = location.start; contentDescription = "Start hour" }
        val end = NumberPicker(this).apply { minValue = 1; maxValue = 24; value = location.end; contentDescription = "End hour" }
        pickers.addView(column().apply { addView(text("Start hour", 16f)); addView(start) })
        pickers.addView(column().apply { addView(text("End hour", 16f)); addView(end) })
        body.addView(pickers)
        style(body)
        val dialog = AlertDialog.Builder(this).setTitle("Edit location").setView(body)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (start.value >= end.value) {
                    label.error = "End hour must be after start hour"
                } else {
                    config = config.copy(locations = config.locations.mapIndexed { i, row ->
                        if (i == index) row.copy(label = shortLabel(label.text.toString()), start = start.value, end = end.value) else row
                    })
                    showRows(); dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun search() {
        val input = EditText(this).apply { hint = "Place name"; setSingleLine(true) }
        val body = column().apply { setPadding(dp(20), 0, dp(20), 0); addView(input) }
        style(body)
        val dialog = AlertDialog.Builder(this).setTitle("Add location").setView(body)
            .setNegativeButton("Cancel", null).setPositiveButton("Search", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val query = input.text.toString().trim()
                if (query.isBlank()) { input.error = "Enter a place name"; return@setOnClickListener }
                val generation = ++searchGeneration
                input.error = null
                val search = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                search.isEnabled = false
                search.text = "Searching…"
                executor.submit {
                    val result = runCatching { OpenMeteo.search(query) }
                    runOnUiThread {
                        if (isFinishing || isDestroyed || generation != searchGeneration || !dialog.isShowing) return@runOnUiThread
                        search.isEnabled = true; search.text = "Search"
                        result.fold(onSuccess = { places ->
                            if (places.isEmpty()) input.error = "No places found. Try another name."
                            else {
                                dialog.dismiss()
                                AlertDialog.Builder(this).setTitle("Choose a place")
                                    .setItems(places.map { it.display }.toTypedArray()) { _, which ->
                                        if (config.locations.size < 3) {
                                            val place = places[which]
                                            config = config.copy(locations = config.locations + Location(shortLabel(place.name), place.display, place.latitude, place.longitude))
                                            showRows()
                                        }
                                    }.setNegativeButton("Cancel", null).show()
                            }
                        }, onFailure = { input.error = "Search unavailable. Check your connection and try again." })
                    }
                }
            }
        }
        dialog.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draft", ConfigJson.encode(config))
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { searchGeneration++; executor.shutdownNow(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.BLACK)
        typeface = resources.getFont(if (bold) R.font.lato_bold else R.font.lato_regular)
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; setOnClickListener { action() }
    }
    private fun style(view: View) {
        if (view is TextView) {
            view.setTextColor(Color.BLACK)
            if (view.typeface != resources.getFont(R.font.lato_bold)) view.typeface = resources.getFont(R.font.lato_regular)
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) style(view.getChildAt(i))
    }
}
