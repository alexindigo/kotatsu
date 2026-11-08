package com.gezimos.katapult;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

/** A standalone, cross-UID collection counterpart, present only in the emulator test APK. */
public class SideTestWidget extends AppWidgetProvider {
    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if ((context.getPackageName() + ".SIDE_ROWS").equals(intent.getAction())) {
            int id = intent.getIntExtra("id", -1);
            int count = intent.getIntExtra("count", 24);
            context.getSharedPreferences("side-fixture", 0).edit().putInt("rows-" + id, count).commit();
            publish(context, id, count);
        }
        if ((context.getPackageName() + ".SIDE_CLICK").equals(intent.getAction())) {
            int id = intent.getIntExtra("id", -1);
            android.content.SharedPreferences prefs = context.getSharedPreferences("side-fixture", 0);
            prefs.edit().putInt("broadcast-" + id, prefs.getInt("broadcast-" + id, 0) + 1).commit();
            publish(context, id, prefs.getInt("rows-" + id, 24));
        }
    }
    private static int resource(Context context, String type, String name) {
        return context.getResources().getIdentifier(name, type, context.getPackageName());
    }
    public static void publish(Context context, int id, int count) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        RemoteViews views = new RemoteViews(context.getPackageName(), resource(context, "layout", "side_test_widget"));
        views.setViewVisibility(resource(context, "id", "fixture_heading"),
            manager.getAppWidgetOptions(id).getBoolean("kotatsu.host.title", false) ? View.GONE : View.VISIBLE);
        android.content.SharedPreferences prefs = context.getSharedPreferences("side-fixture", 0);
        views.setTextViewText(resource(context, "id", "fixture_broadcast"), "Broadcast " + prefs.getInt("broadcast-" + id, 0));
        views.setTextViewText(resource(context, "id", "fixture_launches"), "Launches " + prefs.getInt("launches-" + id, 0));
        views.setOnClickPendingIntent(resource(context, "id", "fixture_broadcast"), PendingIntent.getBroadcast(context, id,
            new Intent(context, SideTestWidget.class).setAction(context.getPackageName() + ".SIDE_CLICK").putExtra("id", id), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        views.setPendingIntentTemplate(resource(context, "id", "fixture_list"), PendingIntent.getActivity(context, id,
            new Intent(context, SideTestTarget.class).putExtra("widget", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));
        RemoteViews.RemoteCollectionItems.Builder rows = new RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true);
        for (int i = 0; i < count; i++) {
            RemoteViews row = new RemoteViews(context.getPackageName(), resource(context, "layout", "side_test_row"));
            row.setTextViewText(resource(context, "id", "fixture_text"), "Hosted item " + i);
            row.setOnClickFillInIntent(resource(context, "id", "fixture_text"), new Intent().putExtra("payload", "row-" + i));
            rows.addItem(i, row);
        }
        views.setRemoteAdapter(resource(context, "id", "fixture_list"), rows.build());
        manager.updateAppWidget(id, views);
    }
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            // A fresh binding is a fresh fixture, including after an emulator restores its ID pool.
            context.getSharedPreferences("side-fixture", 0).edit().putInt("rows-" + id, 24)
                .putInt("broadcast-" + id, 0).putInt("launches-" + id, 0).commit();
            publish(context, id, 24);
        }
    }
    @Override public void onDeleted(Context context, int[] ids) {
        android.content.SharedPreferences.Editor edit = context.getSharedPreferences("side-fixture", 0).edit();
        for (int id : ids) edit.remove("rows-" + id).remove("broadcast-" + id).remove("launches-" + id);
        edit.commit();
    }
    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        publish(context, id, context.getSharedPreferences("side-fixture", 0).getInt("rows-" + id, 24));
    }
}
