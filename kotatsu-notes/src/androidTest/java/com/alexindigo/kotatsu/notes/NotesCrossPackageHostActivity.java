package com.alexindigo.kotatsu.notes;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.PixelCopy;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A real host in the separate test APK/UID, with ordinary per-widget bind consent. */
public class NotesCrossPackageHostActivity extends Activity {
    private static volatile NotesCrossPackageHostActivity current;
    private AppWidgetHost host;
    private AppWidgetHostView view;
    private int widgetId;
    private String providerPackage;
    private String state = "starting";
    private FrameLayout root;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;
        providerPackage = getIntent().getStringExtra("providerPackage");
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        setContentView(root);
        host = new AppWidgetHost(this, 0x4b4e11);
        widgetId = host.allocateAppWidgetId();
        ComponentName provider = new ComponentName(providerPackage, getIntent().getStringExtra("providerClass"));
        if (AppWidgetManager.getInstance(this).bindAppWidgetIdIfAllowed(widgetId, provider)) showWidget();
        else {
            state = "consent";
            startActivityForResult(new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider), 1);
        }
    }

    @Override public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 1 && result == RESULT_OK) showWidget();
        else state = "cancelled";
    }

    private void showWidget() {
        view = host.createView(this, widgetId, AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId));
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        host.startListening();
        state = "bound";
    }

    private int id(String name) {
        try { return getPackageManager().getResourcesForApplication(providerPackage).getIdentifier(name, "id", providerPackage); }
        catch (android.content.pm.PackageManager.NameNotFoundException error) { throw new IllegalStateException(error); }
    }

    private Bundle inspect() {
        Bundle result = new Bundle();
        result.putString("hostState", state);
        result.putInt("hostId", widgetId);
        result.putInt("hostUid", Process.myUid());
        result.putString("hostPackage", getPackageName());
        if (view != null) result.putString("providerLabel", AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId).loadLabel(getPackageManager()));
        if (view == null) return result;
        ListView list = view.findViewById(id("tasks"));
        if (list != null) result.putParcelable("listBounds", bounds(list));
        TextView heading = view.findViewById(id("heading"));
        if (heading != null) {
            result.putString("heading", heading.getText().toString());
            result.putInt("headingWeight", heading.getTypeface().getWeight());
        }
        if (list == null || list.getVisibility() != View.VISIBLE || list.getChildCount() < 2) return result;
        TextView text = list.getChildAt(0).findViewById(id("task_text"));
        TextView wrapped = list.getChildAt(1).findViewById(id("task_text"));
        if (text == null || text.getWidth() == 0) return result;
        result.putString("task", text.getText().toString());
        result.putBoolean("restricted", text.getContext().isRestricted());
        result.putInt("weight", text.getTypeface().getWeight());
        result.putBoolean("fakeBold", text.getPaint().isFakeBoldText());
        result.putFloat("textSize", text.getTextSize());
        result.putInt("lineHeight", text.getLineHeight());
        result.putInt("wrappedLines", wrapped.getLineCount());
        View row = list.getChildAt(0);
        CheckBox check = row.findViewById(id("task_check"));
        result.putParcelable("rowBounds", bounds(row));
        result.putParcelable("checkBounds", bounds(check));
        Rect drawing = new Rect(check.getButtonDrawable().getBounds());
        Rect checkBounds = bounds(check);
        drawing.offset(checkBounds.left, checkBounds.top);
        result.putParcelable("checkDrawing", drawing);
        Rect ink = new Rect();
        text.getPaint().getTextBounds(text.getText().toString(), 0, text.length(), ink);
        Rect textBounds = bounds(text);
        result.putParcelable("firstLine", new Rect(textBounds.left, textBounds.top + text.getTotalPaddingTop() + text.getLayout().getLineTop(0),
            textBounds.right, textBounds.top + text.getTotalPaddingTop() + text.getLayout().getLineBottom(0)));
        ink.offset(textBounds.left + text.getTotalPaddingLeft(), textBounds.top + text.getTotalPaddingTop() + text.getLayout().getLineBaseline(0));
        result.putParcelable("textInk", ink);
        result.putInt("rowPitch", list.getChildAt(1).getTop() - row.getTop());
        View wrappedRow = list.getChildAt(1);
        Rect wrappedBounds = bounds(wrapped);
        result.putParcelable("wrappedRowBounds", bounds(wrappedRow));
        result.putParcelable("wrappedTextBounds", wrappedBounds);
        result.putParcelable("wrappedFirstLine", new Rect(wrappedBounds.left, wrappedBounds.top + wrapped.getTotalPaddingTop() + wrapped.getLayout().getLineTop(0),
            wrappedBounds.right, wrappedBounds.top + wrapped.getTotalPaddingTop() + wrapped.getLayout().getLineBottom(0)));
        String[] samples = {"Milk", "WWmm ii 0123456789", "Ask the pharmacist"};
        float[] widths = new float[samples.length];
        for (int i = 0; i < samples.length; i++) widths[i] = text.getPaint().measureText(samples[i]);
        result.putFloatArray("widths", widths);
        int[] location = new int[2];
        text.getLocationOnScreen(location);
        result.putParcelable("textBounds", new Rect(location[0], location[1], location[0] + text.getWidth(), location[1] + text.getHeight()));
        return result;
    }

    private static Rect bounds(View view) {
        int[] position = new int[2];
        view.getLocationOnScreen(position);
        return new Rect(position[0], position[1], position[0] + view.getWidth(), position[1] + view.getHeight());
    }

    /** Called through the test counterpart's provider, not through target-process statics. */
    public static Bundle stats() {
        NotesCrossPackageHostActivity activity = current;
        if (activity == null) return new Bundle();
        Bundle result = new Bundle();
        CountDownLatch ready = new CountDownLatch(1);
        activity.runOnUiThread(() -> {
            // A binder request may have captured this owner just before its queued teardown.
            if (current == activity) result.putAll(activity.inspect());
            ready.countDown();
        });
        await(ready);
        return result;
    }

    public static Bundle capture() {
        NotesCrossPackageHostActivity activity = current;
        if (activity == null) throw new IllegalStateException("No host window");
        CountDownLatch committed = new CountDownLatch(1);
        int[] size = new int[2];
        activity.runOnUiThread(() -> {
            View decor = activity.getWindow().getDecorView();
            size[0] = decor.getWidth(); size[1] = decor.getHeight();
            decor.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);
            decor.invalidate();
        });
        await(committed);
        Bitmap bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
        CountDownLatch copied = new CountDownLatch(1);
        int[] status = {-1};
        PixelCopy.request(activity.getWindow(), bitmap, value -> { status[0] = value; copied.countDown(); }, new Handler(Looper.getMainLooper()));
        await(copied);
        if (status[0] != PixelCopy.SUCCESS) throw new IllegalStateException("PixelCopy failed: " + status[0]);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)) throw new IllegalStateException("Could not encode capture");
        bitmap.recycle();
        Bundle result = new Bundle();
        result.putByteArray("png", png.toByteArray());
        return result;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Host frame did not arrive");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(error);
        }
    }

    public static void closeHost() {
        NotesCrossPackageHostActivity activity = current;
        if (activity != null) activity.runOnUiThread(activity::finish);
    }

    @Override public void onDestroy() {
        // Invalidate observation before releasing the resource it can inspect.
        if (current == this) current = null;
        view = null;
        if (host != null) { host.stopListening(); host.deleteAppWidgetId(widgetId); }
        super.onDestroy();
    }
}
