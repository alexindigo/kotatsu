package com.alexindigo.kotatsu.weather

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.CancellationSignal
import android.text.InputFilter
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class ConfigureActivity : Activity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val store by lazy { WeatherStore(this) }
    private var editorReady = false
    private var choosing = false
    private data class PlaceEdit(val widgetId: Int, val placeId: String, val label: String, val start: Int, val end: Int)
    private var editing: PlaceEdit? = null
    private var editDialog: AlertDialog? = null
    private lateinit var rows: LinearLayout
    private lateinit var add: TextView
    private lateinit var addNote: TextView
    private lateinit var units: View
    private lateinit var done: TextView
    private lateinit var doneHint: TextView
    private val executor = Executors.newSingleThreadExecutor()
    private var searchGeneration = 0
    private var locating: CancellationSignal? = null
    private var afterLocationPermission: ((Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        // Black on white to the bottom edge, like Kotatsu's own settings.
        window.setBackgroundDrawable(ColorDrawable(Color.WHITE))
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val requested = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val restored = savedInstanceState?.getInt("widgetId", AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Saved fields belong only to their original bound widget. Never move them to a replacement.
        if (restored != AppWidgetManager.INVALID_APPWIDGET_ID && restored !in WeatherRuntime.ids(this)) { finish(); return }
        widgetId = if (requested != AppWidgetManager.INVALID_APPWIDGET_ID) requested else {
            // Opened from the app icon: settings belong to a widget.
            when (val target = settingsTarget(WeatherRuntime.ids(this))) {
                is SettingsTarget.One -> target.widgetId
                is SettingsTarget.Choose -> { chooseWidget(target.widgetIds); return }
                SettingsTarget.NoWidget -> { showNoWidget(); return }
            }
        }
        if (widgetId !in WeatherRuntime.ids(this)) { finish(); return }
        if (restored == widgetId && savedInstanceState?.containsKey("editPlace") == true) {
            editing = PlaceEdit(widgetId, savedInstanceState.getString("editPlace")!!,
                savedInstanceState.getString("editLabel")!!, savedInstanceState.getInt("editStart"), savedInstanceState.getInt("editEnd"))
        }
        if (store.config(widgetId) == null) { showUnreadable(); return }
        // Settings scroll; the actions stay in a fixed footer, so Done is always on screen at the bottom.
        val body = column().apply { setPadding(dp(16), dp(16), dp(16), dp(8)) }
        body.addView(text("Kotatsu Weather", 24f, bold = true).apply { setPadding(0, 0, 0, dp(8)) })
        units = cycleRow("Temperature") {
            changed(store.toggleUnits(widgetId))
        }
        body.addView(units)
        rows = column()
        body.addView(rows, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        // The screen's actions sit together, like Mudita's stacked buttons, with the one that finishes (Done,
        // filled) last; a note on a disabled button sits right above them.
        val footer = column().apply { setPadding(dp(16), dp(8), dp(16), dp(20)) }
        footer.addView(text("Weather data by Open-Meteo.com (CC BY 4.0)", 13f))
        addNote = text("Four places is the most a widget shows. Remove one to add another.", 14f).apply {
            setPadding(0, dp(12), 0, 0)
        }
        footer.addView(addNote)
        doneHint = text("Add a place to finish.", 14f).apply { setPadding(0, dp(12), 0, 0) }
        footer.addView(doneHint)
        add = button("Add location") { search() }
        footer.addView(add, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
        done = button("Done") {
            // Every change is already saved by the store. Closing an old editor cannot write a list.
            if (widgetId !in WeatherRuntime.ids(this)) { finish(); return@button }
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
        footer.addView(done, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
        // Kotatsu's ink rule marks the footer's edge, but only while the settings above actually scroll under it;
        // it keeps its space when hidden, so showing it never moves anything.
        val edge = View(this).apply { setBackgroundColor(Color.BLACK); visibility = View.INVISIBLE }
        val scroll = ScrollView(this).apply { addView(body) }
        scroll.viewTreeObserver.addOnGlobalLayoutListener {
            edge.visibility = if (scroll.canScrollVertically(1) || scroll.canScrollVertically(-1)) View.VISIBLE else View.INVISIBLE
        }
        setContentView(column().apply {
            setBackgroundColor(Color.WHITE)
            addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(edge, LinearLayout.LayoutParams(MATCH, stroke()))
            addView(footer)
        })
        showRows()
        showUnits()
        editorReady = true
        editing?.let { edit(it.placeId, it) }
    }

    override fun onResume() {
        super.onResume()
        if (!editorReady || choosing) return
        if (widgetId !in WeatherRuntime.ids(this)) { finish(); return }
        if (!intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID)) {
            when (val target = settingsTarget(WeatherRuntime.ids(this))) {
                is SettingsTarget.Choose -> { chooseWidget(target.widgetIds); return }
                is SettingsTarget.One -> if (target.widgetId != widgetId) { finish(); return }
                SettingsTarget.NoWidget -> { finish(); return }
            }
        }
        if (store.config(widgetId) == null) { showUnreadable(); return }
        showRows(); showUnits()
    }

    private fun changed(result: WeatherStore.Result) {
        when (result) {
            WeatherStore.Result.APPLIED -> {
                WeatherRuntime.schedule(this)
                WeatherRuntime.redraw(this, widgetId)
                WeatherRuntime.refresh(this, force = true)
            }
            WeatherStore.Result.UNREADABLE -> { showUnreadable(); return }
            WeatherStore.Result.GONE -> {
                Toast.makeText(this, R.string.widget_gone, Toast.LENGTH_SHORT).show()
                finish(); return
            }
            WeatherStore.Result.FULL -> Toast.makeText(this, R.string.places_full, Toast.LENGTH_SHORT).show()
            WeatherStore.Result.MISSING -> Toast.makeText(this, R.string.place_gone, Toast.LENGTH_SHORT).show()
            WeatherStore.Result.WRITE_FAILED -> Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
        }
        if (editorReady) { showRows(); showUnits() }
    }

    private fun showUnreadable() {
        editorReady = false
        editDialog?.dismiss()
        setContentView(column().apply {
            setBackgroundColor(Color.WHITE); setPadding(dp(16), dp(16), dp(16), dp(20))
            addView(text("Kotatsu Weather", 24f, bold = true))
            addView(text(getString(R.string.config_unreadable), 18f).apply { setPadding(0, dp(16), 0, 0) })
        })
    }

    /** Several widgets: ask which one's settings to open, naming each by its places. */
    private fun chooseWidget(widgetIds: List<Int>) {
        if (choosing) return
        choosing = true
        val store = WeatherStore(this)
        val names = widgetIds.map { id ->
            store.config(id)?.locations?.joinToString(", ") { it.label }?.ifEmpty { "No places yet" }
                ?: getString(R.string.config_unreadable)
        }
        AlertDialog.Builder(this).setCustomTitle(dialogTitle("Which widget?"))
            .setAdapter(listItems(names)) { _, which ->
                startActivity(Intent(this, ConfigureActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetIds[which]))
                finish()
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { if (!isChangingConfigurations) finish() }
            .show()
    }

    /** No widget yet: settings belong to a widget, so say where to add one. */
    private fun showNoWidget() {
        val body = column().apply { setPadding(dp(16), dp(16), dp(16), dp(20)) }
        body.addView(text("Kotatsu Weather", 24f, bold = true).apply { setPadding(0, 0, 0, dp(16)) })
        body.addView(text("The weather widget isn't on your lock screen yet.", 18f))
        body.addView(text("In Kotatsu, open Settings, then Lockscreen, then Lock-screen widgets. Tap Add widget and " +
            "choose Kotatsu Weather. Its settings open from here afterwards.", 14f).apply { setPadding(0, dp(12), 0, 0) })
        body.addView(View(this), LinearLayout.LayoutParams(MATCH, 0, 1f))
        body.addView(button("Close") { finish() }.apply {
            setTextColor(Color.WHITE); background = shape(Color.BLACK, Color.BLACK)
        })
        setContentView(body)
    }

    private fun showRows() {
        val config = store.config(widgetId) ?: run { showUnreadable(); return }
        rows.removeAllViews()
        if (config.locations.isEmpty()) {
            rows.addView(text("No places yet. Each place you add gets its own line on the widget.", 14f).apply {
                setPadding(0, dp(6), 0, dp(6))
            })
        }
        config.locations.forEach { location ->
            rows.addView(actionRow(location.label, "${location.place}\n${hours(location.start)} to ${hours(location.end)}") { edit(location.id) })
        }
        val full = config.locations.size >= MAX_LOCATIONS
        add.isEnabled = !full
        add.setTextColor(if (full) QUIET else Color.BLACK)
        add.background = shape(Color.WHITE, if (full) QUIET else Color.BLACK)
        addNote.visibility = if (full) View.VISIBLE else View.GONE
        val ready = config.locations.isNotEmpty()
        done.isEnabled = ready
        done.setTextColor(if (ready) Color.WHITE else QUIET)
        done.background = shape(fill = if (ready) Color.BLACK else Color.WHITE, outline = if (ready) Color.BLACK else QUIET)
        doneHint.visibility = if (ready) View.GONE else View.VISIBLE
    }

    private fun showUnits() {
        val config = store.config(widgetId) ?: return
        setRowDetail(units, if (config.fahrenheit) "Fahrenheit" else "Celsius")
        units.findViewWithTag<TextView>(PILL).text = if (config.fahrenheit) "°F" else "°C"
    }

    private fun edit(placeId: String, restored: PlaceEdit? = null) {
        val location = store.config(widgetId)?.locations?.firstOrNull { it.id == placeId } ?: return
        editing = restored ?: PlaceEdit(widgetId, placeId, location.label, location.start, location.end)
        lateinit var dialog: AlertDialog
        // The title row lives in the body, whose edges line up with Mudita's full-width dialog buttons, so the
        // remove icon's edge sits on the buttons' edge. (Mudita stacks dialog buttons, so a third would run off.)
        val body = column().apply { setPadding(dp(24), 0, dp(24), dp(8)) }
        body.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text("Edit location", 24f, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_delete)
                contentDescription = "Remove location"
                setPadding(dp(16), dp(12), 0, dp(12))
                setOnClickListener {
                    changed(store.remove(widgetId, placeId))
                    dialog.dismiss()
                }
            })
        })
        body.addView(text(location.place, 14f))
        val label = field("Up to 8 characters").apply {
            setText(editing!!.label)
            filters = arrayOf(InputFilter { source, start, end, dest, dstart, dend ->
                val kept = dest.toString().removeRange(dstart, dend)
                val available = 8 - kept.codePointCount(0, kept.length)
                val input = source.subSequence(start, end).toString()
                if (input.codePointCount(0, input.length) <= available) null
                else input.substring(0, input.offsetByCodePoints(0, available.coerceAtLeast(0)))
            })
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    editing = editing?.copy(label = s.toString())
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        body.addView(caption("Label").apply { setPadding(0, dp(20), 0, dp(6)) })
        body.addView(label)
        // Hours step between marks (odd hours) with − and +; the steppers keep the end after the start, so the
        // span is always valid. No scroll wheels: they animate badly on e-ink and their dividers are system grey.
        var start = editing!!.start
        var end = editing!!.end
        lateinit var refresh: () -> Unit
        val startRow = stepper("Start", { start }, { start = it; editing = editing?.copy(start = it); refresh() },
            min = { MARK_HOURS.first }, max = { end - MARK_EVERY_HOURS })
        val endRow = stepper("End", { end }, { end = it; editing = editing?.copy(end = it); refresh() },
            min = { start + MARK_EVERY_HOURS }, max = { MARK_HOURS.last })
        refresh = { startRow.refresh(); endRow.refresh() }
        body.addView(startRow.view, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
        body.addView(endRow.view, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        refresh()
        dialog = AlertDialog.Builder(this).setView(body)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                changed(store.edit(widgetId, placeId, label.text.toString(), start, end))
            }.create()
        editDialog = dialog
        dialog.setOnDismissListener { editing = null; editDialog = null }
        dialog.show()
    }

    private class Stepper(val view: View, val refresh: () -> Unit)

    /** A caption on the left, then − value +; each button steps one mark and goes quiet at its limit. */
    private fun stepper(title: String, value: () -> Int, set: (Int) -> Unit, min: () -> Int, max: () -> Int): Stepper {
        val shown = text("", 18f, bold = true).apply { gravity = Gravity.CENTER; minWidth = dp(64) }
        fun button(sign: String) = text(sign, 20f, bold = true).apply {
            gravity = Gravity.CENTER
            contentDescription = (if (sign == "+") "Later " else "Earlier ") + title.lowercase()
        }
        val minus = button("−")
        val plus = button("+")
        fun refresh() {
            shown.text = hours(value())
            for ((button, enabled) in listOf(minus to (value() > min()), plus to (value() < max()))) {
                button.isEnabled = enabled
                button.setTextColor(if (enabled) Color.BLACK else QUIET)
                button.background = shape(Color.WHITE, if (enabled) Color.BLACK else QUIET)
            }
        }
        minus.setOnClickListener { set(value() - MARK_EVERY_HOURS) }
        plus.setOnClickListener { set(value() + MARK_EVERY_HOURS) }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(caption(title), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(minus, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(shown)
            addView(plus, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        return Stepper(row) { refresh() }
    }

    private fun search() {
        val originWidgetId = widgetId
        val input = field("City or town")
        val body = column().apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
        style(body)
        // The other way to add a place, sitting with the dialog's own buttons and drawn like them.
        val current = dialogButton(ADD_CURRENT)
        body.addView(current, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
        val status = text("", 14f).apply { visibility = View.GONE; setPadding(0, dp(8), 0, 0) }
        body.addView(status)
        val dialog = AlertDialog.Builder(this).setCustomTitle(dialogTitle("Add location")).setView(body)
            .setNegativeButton("Cancel", null).setPositiveButton("Search", null).create()
        fun say(message: String) { status.text = message; status.visibility = View.VISIBLE }
        fun locate() {
            current.isEnabled = false; current.text = FINDING; status.visibility = View.GONE
            locating = CurrentPosition.find(this) { result ->
                locating = null
                if (isFinishing || isDestroyed || !dialog.isShowing) return@find
                current.isEnabled = true; current.text = ADD_CURRENT
                when (result) {
                    is CurrentPosition.Result.Found -> {
                        changed(store.add(originWidgetId, currentLocation(result.latitude, result.longitude)))
                        dialog.dismiss()
                    }
                    CurrentPosition.Result.Denied ->
                        say("Location access is off for Kotatsu Weather. Search by name, or allow it in the phone's settings.")
                    CurrentPosition.Result.Off ->
                        say("Location is off on this phone. Turn it on in the phone's settings, or search by name.")
                    CurrentPosition.Result.NotFound ->
                        say("Couldn't get a location. GPS needs a view of the sky, so try near a window or search by name.")
                }
            }
        }
        current.setOnClickListener {
            if (CurrentPosition.granted(this)) locate()
            else {
                afterLocationPermission = { granted ->
                    if (granted) locate()
                    else say("Location access is off for Kotatsu Weather. Search by name, or allow it in the phone's settings.")
                }
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    REQUEST_LOCATION)
            }
        }
        dialog.setOnDismissListener { locating?.cancel(); locating = null }
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
                                AlertDialog.Builder(this).setCustomTitle(dialogTitle("Choose a place"))
                                    .setAdapter(listItems(places.map { it.display })) { _, which ->
                                        val place = places[which]
                                        changed(store.add(originWidgetId, Location(shortLabel(place.name), place.display, place.latitude, place.longitude)))
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
        outState.putInt("widgetId", widgetId)
        editing?.let {
            outState.putString("editPlace", it.placeId)
            outState.putString("editLabel", it.label)
            outState.putInt("editStart", it.start)
            outState.putInt("editEnd", it.end)
        }
        super.onSaveInstanceState(outState)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_LOCATION) return
        val then = afterLocationPermission ?: return
        afterLocationPermission = null
        then(CurrentPosition.granted(this))
    }

    override fun onDestroy() { searchGeneration++; locating?.cancel(); executor.shutdownNow(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun hours(hour: Int) = "$hour:00"
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.BLACK)
        typeface = resources.getFont(if (bold) R.font.lato_bold else R.font.lato_regular)
    }
    /** A full-width action button, Mudita's dialog-button height; showRows() sets its fill for each state. */
    private fun button(label: String, onClick: () -> Unit) = text(label, 18f, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(56)
        setOnClickListener { onClick() }
    }

    /** Drawn like Mudita's own dialog buttons (outline, corners, bold system text), for a button in a dialog body. */
    private fun dialogButton(label: String) = TextView(this).apply {
        text = label; textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.BLACK)
        gravity = Gravity.CENTER; minHeight = dp(56); isAllCaps = false
        background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.WHITE); setStroke(stroke(), Color.BLACK) }
    }

    /** Kotatsu's outline weight. */
    private fun stroke() = (2.5f * resources.displayMetrics.density).toInt()
    private fun shape(fill: Int, outline: Int) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat(); setColor(fill); setStroke(stroke(), outline)
    }
    /** Kotatsu's text field: Lato in an ink outline, no platform underline. */
    private fun field(hint: String) = EditText(this).apply {
        setSingleLine(true)
        this.hint = hint
        textSize = 18f
        typeface = resources.getFont(R.font.lato_regular)
        setTextColor(Color.BLACK)
        setHintTextColor(QUIET)
        background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(stroke(), Color.BLACK) }
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    /** A dialog pick list in Lato, its text lined up with the dialog's title and buttons. */
    private fun listItems(items: List<String>) = object : ArrayAdapter<String>(this, 0, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            ((convertView as? TextView) ?: text("", 18f).apply {
                minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8))
            }).apply { text = items[position] }
    }

    /** The one caption style for dialog fields. */
    private fun caption(value: String) = text(value, 14f)

    /** Kotatsu's settings row: bold title over a detail line, chevron on the right; the whole row is the button. */
    private fun actionRow(title: String, detail: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        val texts = column()
        texts.addView(text(title, 18f, bold = true).apply { tag = TITLE })
        texts.addView(text(detail, 14f).apply { tag = DETAIL })
        addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(ImageView(context).apply { setImageResource(R.drawable.ic_chevron_right) },
            LinearLayout.LayoutParams(dp(24), dp(24)))
        setOnClickListener { onClick() }
    }

    /** Kotatsu's cycle row: title and detail, the current value in an outlined pill; tapping switches it. */
    private fun cycleRow(title: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        val texts = column()
        texts.addView(text(title, 18f, bold = true).apply { tag = TITLE })
        texts.addView(text("", 14f).apply { tag = DETAIL })
        addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(text("", 14f, bold = true).apply {
            tag = PILL; gravity = Gravity.CENTER; minWidth = dp(48)
            setPadding(dp(12), dp(6), dp(12), dp(6)); background = shape(Color.WHITE, Color.BLACK)
        })
        setOnClickListener { onClick() }
    }

    /** Every dialog's title in Lato like the screen it opens from, lined up with the dialog's buttons. */
    private fun dialogTitle(title: String): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setPadding(dp(24), 0, 0, dp(4))
        addView(text(title, 24f, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    private fun setRowDetail(row: View, detail: String) { row.findViewWithTag<TextView>(DETAIL).text = detail }

    /** Dialog content: Lato in ink throughout, including the pickers' and field's own text. */
    private fun style(view: View) {
        if (view is TextView) {
            view.setTextColor(Color.BLACK)
            if (view.typeface != resources.getFont(R.font.lato_bold)) view.typeface = resources.getFont(R.font.lato_regular)
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) style(view.getChildAt(i))
    }

    private companion object {
        const val REQUEST_LOCATION = 0x4B57
        const val ADD_CURRENT = "Add current location"
        const val FINDING = "Finding your location…"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        /** Disabled ink, the same grey the widget uses for an old forecast. */
        val QUIET = Color.rgb(160, 160, 160)
        const val TITLE = "title"
        const val DETAIL = "detail"
        const val PILL = "pill"
    }
}
