package com.gezimos.katapult;

import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

/** Cross-UID collection whose rows change without a new RemoteViews update. */
public class SideTestCollectionService extends RemoteViewsService {
    @Override public RemoteViewsFactory onGetViewFactory(Intent intent) {
        final int id = intent.getIntExtra("id", -1);
        return new RemoteViewsFactory() {
            private int count;
            @Override public void onCreate() { onDataSetChanged(); }
            @Override public void onDataSetChanged() { count = getSharedPreferences("side-fixture", 0).getInt("service-rows-" + id, 0); }
            @Override public void onDestroy() { }
            @Override public int getCount() { return count; }
            @Override public RemoteViews getViewAt(int position) {
                RemoteViews row = new RemoteViews(getPackageName(), getResources().getIdentifier("side_test_row", "layout", getPackageName()));
                row.setTextViewText(getResources().getIdentifier("fixture_text", "id", getPackageName()), "Deferred item " + position);
                return row;
            }
            @Override public RemoteViews getLoadingView() { return null; }
            @Override public int getViewTypeCount() { return 1; }
            @Override public long getItemId(int position) { return position; }
            @Override public boolean hasStableIds() { return true; }
        };
    }
}
