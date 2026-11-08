package com.gezimos.katapult;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Separate UID: the original collection fill-in must reach this activity without a resend. */
public class SideTestTarget extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        int id = getIntent().getIntExtra("widget", -1);
        android.content.SharedPreferences prefs = getSharedPreferences("side-fixture", 0);
        int deliveries = prefs.getInt("launches-" + id, 0) + 1;
        prefs.edit().putInt("launches-" + id, deliveries).commit();
        SideTestWidget.publish(this, id, prefs.getInt("rows-" + id, 24));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView payload = new TextView(this);
        payload.setText("Payload: " + getIntent().getStringExtra("payload"));
        root.addView(payload);
        TextView count = new TextView(this);
        count.setText("Deliveries: " + deliveries);
        root.addView(count);
        Button finish = new Button(this);
        finish.setText("Finish fixture");
        finish.setOnClickListener(view -> finishAndRemoveTask());
        root.addView(finish);
        setContentView(root);
    }
}
