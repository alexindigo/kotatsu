package com.gezimos.katapult.weather;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Remote test-APK process: deliberately self-contained (no target-APK Kotlin runtime). */
public class SleepingWeatherProvider extends ContentProvider {
    private static final class Lifetime {
        final CountDownLatch release = new CountDownLatch(1);
        int entered;
        int inFlight;
        int completed;
    }
    private final Object lock = new Object();
    private final Map<String, Lifetime> lifetimes = new HashMap<>();

    private Lifetime requireLifetime(String id) {
        Lifetime value = lifetimes.get(id);
        if (value == null) throw new IllegalStateException("Query has no owned provider lifetime");
        return value;
    }

    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        if (selectionArgs == null || selectionArgs.length != 1)
            throw new IllegalArgumentException("Query requires its lifetime identity");
        final Lifetime lifetime;
        synchronized (lock) {
            lifetime = requireLifetime(selectionArgs[0]);
            lifetime.entered++;
            lifetime.inFlight++;
        }
        try {
            if (!lifetime.release.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("Provider query was not released");
            MatrixCursor result = new MatrixCursor(new String[] {"temperature"});
            result.addRow(new Object[] {21});
            synchronized (lock) { lifetime.completed++; }
            return result;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Provider query interrupted", error);
        } finally {
            synchronized (lock) { lifetime.inFlight--; }
        }
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        synchronized (lock) {
            if ("begin".equals(method)) {
                if (arg == null || arg.isEmpty()) throw new IllegalArgumentException("Lifetime identity required");
                if (lifetimes.containsKey(arg)) throw new IllegalStateException("Lifetime already owned");
                lifetimes.put(arg, new Lifetime());
            }
            Lifetime lifetime = lifetimes.get(arg);
            if ("release".equals(method) && lifetime != null) lifetime.release.countDown();
            if ("end".equals(method) && lifetime != null) {
                if (lifetime.inFlight != 0) throw new IllegalStateException("Provider lifetime still has queries");
                lifetime.release.countDown();
                lifetimes.remove(arg);
                lifetime = null;
            }
            result.putInt("pid", android.os.Process.myPid());
            result.putInt("lifetimes", lifetimes.size());
            result.putBoolean("active", lifetime != null);
            result.putInt("entered", lifetime == null ? 0 : lifetime.entered);
            result.putInt("inFlight", lifetime == null ? 0 : lifetime.inFlight);
            result.putInt("completed", lifetime == null ? 0 : lifetime.completed);
            result.putBoolean("released", lifetime != null && lifetime.release.getCount() == 0);
            return result;
        }
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/weather"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
