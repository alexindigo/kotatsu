package com.alexindigo.kotatsu.fakemuditacalendar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

class SchemaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.contentResolver.call(Uri.parse("content://com.mudita.calendar/events"), "schema", null,
            Bundle().apply { putBoolean("schema2", intent.getBooleanExtra("schema2", false)) })
    }
}
