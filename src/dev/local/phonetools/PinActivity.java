package dev.local.phonetools;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;

public class PinActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Launchers take pin requests from Android 8; before that the widget is added from the launcher's own list.
        if (Build.VERSION.SDK_INT >= 26) pin();
        finish();
    }

    private void pin() {
        if ("lock".equals(getIntent().getStringExtra("pin"))) {
            ShortcutInfo shortcut = new ShortcutInfo.Builder(this, "lock")
                    .setShortLabel(getString(R.string.lock))
                    .setIcon(Icon.createWithResource(this, R.drawable.ic_lock))
                    .setIntent(new Intent(Intent.ACTION_MAIN).setClass(this, LockActivity.class))
                    .build();
            getSystemService(ShortcutManager.class).requestPinShortcut(shortcut, null);
        } else {
            getSystemService(AppWidgetManager.class)
                    .requestPinAppWidget(new ComponentName(this, MemWidget.class), null, null);
        }
    }
}
