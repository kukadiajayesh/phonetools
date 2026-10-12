package dev.local.phonetools;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/**
 * Asks, when {@link SpeedService} starts, for what the app cannot get on its own:
 * <ul>
 * <li>Storage access, for the memory widget's glass background. Without it Android hands out its default wallpaper
 * rather than the real one (OxygenOS, for one), so the glass would show the wrong picture. Asked only while missing;
 * or grant it beforehand with {@code adb shell pm grant dev.local.phonetools android.permission.READ_EXTERNAL_STORAGE}.
 * <li>Screen capture, which SpeedService samples to colour its text and graph like the status icons. Shows the system
 * "start capturing" prompt, unless allowed beforehand with
 * {@code adb shell appops set dev.local.phonetools PROJECT_MEDIA allow}.
 * </ul>
 */
public class CaptureActivity extends Activity {
    private MediaProjectionManager projections;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projections = getSystemService(MediaProjectionManager.class);
        if (savedInstanceState != null) return;
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, 0);
        } else {
            requestCapture();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        // Granted: redraw the widget with the real wallpaper behind it.
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) MemWidget.updateAll(this);
        requestCapture();
    }

    private void requestCapture() {
        startActivityForResult(projections.createScreenCaptureIntent(), 0);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        SpeedService service = SpeedService.instance;
        if (resultCode == RESULT_OK && service != null) {
            service.setProjection(projections.getMediaProjection(resultCode, data));
        }
        finish();
    }
}
