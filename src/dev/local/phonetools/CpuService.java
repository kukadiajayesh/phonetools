package dev.local.phonetools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Redraws the widget's memory rows and CPU/RAM graph every second while the screen is on, scrolls the graph
 * smoothly in between, and redraws its glass background whenever the wallpaper changes.
 */
public class CpuService extends Service {
    private static final String CHANNEL = "cpu";
    private final Handler handler = new Handler();
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            // Without the accessibility service there is no telling what is on screen: keep updating.
            SpeedService speed = SpeedService.instance;
            MemWidget.tick(CpuService.this, speed == null || speed.homeVisible);
            handler.postDelayed(this, MemWidget.FRAME_MS);
        }
    };
    // The glass background is cut from the wallpaper, so rebuild it as soon as the wallpaper changes.
    @SuppressWarnings("deprecation")
    private final BroadcastReceiver wallpaper = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            MemWidget.updateAll(context);
        }
    };
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handler.removeCallbacks(tick);
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                MemWidget.resetCpuBaseline();
                handler.post(tick);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screen, filter);
        registerReceiver(wallpaper, new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED));
        if (((PowerManager) getSystemService(POWER_SERVICE)).isInteractive()) handler.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(
                new NotificationChannel(CHANNEL, getString(R.string.cpu_graph), NotificationManager.IMPORTANCE_MIN));
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_lock)
                .setContentTitle(getString(R.string.cpu_graph))
                .setContentText(getString(R.string.cpu_graph_desc))
                .build();
        startForeground(1, notification);
        try {
            // Left over from showing the network speed as this notification's icon.
            notifications.deleteNotificationChannel("speed");
        } catch (RuntimeException ignored) {
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        unregisterReceiver(screen);
        unregisterReceiver(wallpaper);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
