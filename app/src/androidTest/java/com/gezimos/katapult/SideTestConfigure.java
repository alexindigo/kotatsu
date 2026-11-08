package com.gezimos.katapult;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

public class SideTestConfigure extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setResult(RESULT_CANCELED);
        Button use = new Button(this);
        use.setText("Use fixture");
        use.setOnClickListener(view -> {
            int id = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
            SideTestWidget.publish(this, id, 24);
            setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id));
            finish();
        });
        setContentView(use);
    }
}
