package com.alexindigo.kotatsu.notes;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import java.util.Arrays;
import java.util.HashSet;

/** Standalone emulator counterpart. Java keeps it independent of the target APK's Kotlin runtime. */
public class FakeNotesProvider extends ContentProvider {
    private static final Uri COLLECTION = Uri.parse("content://com.mudita.notes/notes");
    private String title;
    private String content;
    private long updatedAt;
    private int writes;
    private boolean titlesOnly;

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "vnd.android.cursor.dir/notes"; }
    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("Unexpected Notes contract");
    }
    @Override public synchronized MatrixCursor query(Uri uri, String[] projection, String selection,
            String[] args, String order) {
        require(COLLECTION.equals(uri));
        boolean choosing = Arrays.equals(projection, new String[]{"id", "title"});
        if (choosing) {
            require("note_type = ?".equals(selection) && Arrays.equals(args, new String[]{"CHECKLIST"}));
            require("updated_at DESC, id ASC".equals(order));
            titlesOnly = true;
        } else {
            require(Arrays.equals(projection, new String[]{"id", "title", "content", "updated_at"}));
            require("id = ? AND note_type = ?".equals(selection) && args.length == 2 && "CHECKLIST".equals(args[1]));
        }
        MatrixCursor cursor = new MatrixCursor(projection);
        if (content != null && (choosing || "4".equals(args[0]))) {
            Object[] values = new Object[projection.length];
            for (int i = 0; i < projection.length; i++) {
                switch (projection[i]) {
                    case "id": values[i] = 4L; break;
                    case "title": values[i] = title; break;
                    case "content": values[i] = content; break;
                    case "updated_at": values[i] = updatedAt; break;
                    default: throw new IllegalArgumentException("Unexpected column");
                }
            }
            cursor.addRow(values);
        }
        return cursor;
    }
    @Override public synchronized int update(Uri uri, ContentValues values, String selection, String[] args) {
        require(COLLECTION.equals(uri));
        require("id = ? AND note_type = ? AND content = ? AND updated_at = ?".equals(selection));
        require(values.keySet().equals(new HashSet<>(Arrays.asList("content", "updated_at"))));
        writes++;
        if (content == null || !Arrays.equals(args, new String[]{"4", "CHECKLIST", content, Long.toString(updatedAt)})) return 0;
        content = values.getAsString("content");
        updatedAt = values.getAsLong("updated_at");
        getContext().getContentResolver().notifyChange(Uri.withAppendedPath(COLLECTION, "4"), null);
        return 1;
    }
    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        switch (method) {
            case "host-stats": return NotesCrossPackageHostActivity.stats();
            case "host-capture": return NotesCrossPackageHostActivity.capture();
            case "host-close": NotesCrossPackageHostActivity.closeHost(); return new Bundle();
            case "reset":
                writes = 0; titlesOnly = false; title = "Kotatsu long list"; updatedAt = 100;
                content = "[{\"text\":\"Milk\",\"checked\":false},{\"text\":\"Ask the pharmacist about the allergy tablets for the weekend trip\",\"checked\":false}]";
                break;
            case "edit":
                title = arg == null ? "Edited in Notes" : arg; updatedAt++;
                getContext().getContentResolver().notifyChange(Uri.withAppendedPath(COLLECTION, "4"), null);
                break;
            case "rows":
                int count = Integer.parseInt(arg);
                require(count > 0 && count <= 100);
                StringBuilder rows = new StringBuilder("[");
                for (int i = 0; i < count; i++) {
                    if (i > 0) rows.append(',');
                    rows.append("{\"text\":\"").append(i == 0 ? "Milk" : i == count - 1 ? "Yogurt" : "Task " + i).append("\",\"checked\":false}");
                }
                content = rows.append(']').toString(); updatedAt++;
                getContext().getContentResolver().notifyChange(COLLECTION, null);
                break;
            case "silent-edit":
                content = "[{\"text\":\"Different first task\",\"checked\":false}]"; updatedAt = 101;
                break;
            case "delete":
                content = null;
                getContext().getContentResolver().notifyChange(COLLECTION, null);
                break;
            case "stats": break;
            default: throw new IllegalArgumentException("Unknown fixture operation");
        }
        Bundle result = new Bundle();
        result.putInt("writes", writes); result.putBoolean("titlesOnly", titlesOnly); result.putString("content", content);
        return result;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
