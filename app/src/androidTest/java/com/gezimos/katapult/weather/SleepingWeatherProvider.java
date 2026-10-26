package com.gezimos.katapult.weather;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Remote test-APK process: deliberately self-contained (no target-APK Kotlin runtime). */
public class SleepingWeatherProvider extends ContentProvider {
    private final AtomicInteger entered = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);

    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        entered.incrementAndGet();
        try {
            if (!release.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("Provider query was not released");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Provider query interrupted", error);
        }
        MatrixCursor result = new MatrixCursor(new String[] {"temperature"});
        result.addRow(new Object[] {21});
        return result;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        if ("release".equals(method)) { release.countDown(); return result; }
        result.putInt("entered", entered.get());
        return result;
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/weather"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
