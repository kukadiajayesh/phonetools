package dev.local.phonetools;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

/**
 * Frees memory without root: clears the recent apps (through SpeedService, the accessibility service, which closes
 * their tasks and processes), then asks Android to stop every other app's background processes. Swap shrinks as the
 * processes that own it go away; emptying it outright would need root.
 */
final class Cleaner {
    private static boolean running;

    private Cleaner() {
    }

    static void clean(Context context) {
        Context app = context.getApplicationContext();
        if (running) return;
        running = true;
        long[] before = memory(app);
        SpeedService service = SpeedService.instance;
        Handler main = new Handler(Looper.getMainLooper());
        Runnable report = new Runnable() {
            @Override
            public void run() {
                running = false;
                long[] after = memory(app);
                String text = "Freed " + Math.max(0, (after[0] - before[0]) / 1024) + " MB RAM, "
                        + Math.max(0, (before[1] - after[1]) / 1024) + " MB swap";
                if (service == null) text += "\nTurn on Network speed in Accessibility to also clear recents";
                Toast.makeText(app, text, Toast.LENGTH_LONG).show();
                MemWidget.updateAll(app);
            }
        };
        Runnable rest = new Runnable() {
            @Override
            public void run() {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        killBackground(app);
                        // Give Android a moment to reap the processes before measuring.
                        SystemClock.sleep(1500);
                        main.post(report);
                    }
                }).start();
            }
        };
        if (service != null) service.clearRecents(rest); else rest.run();
    }

    private static void killBackground(Context context) {
        ActivityManager activities = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ApplicationInfo info : context.getPackageManager().getInstalledApplications(0)) {
            if (!info.packageName.equals(context.getPackageName())) activities.killBackgroundProcesses(info.packageName);
        }
    }

    /** Returns {available RAM, swap used} in KB. This kernel's /proc/meminfo has no MemAvailable. */
    private static long[] memory(Context context) {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(info);
        long[] swap = new long[2];
        SysFiles.readFields("/proc/meminfo", new String[] {"SwapTotal:", "SwapFree:"}, swap, 0);
        return new long[] {info.availMem / 1024, swap[0] - swap[1]};
    }
}
