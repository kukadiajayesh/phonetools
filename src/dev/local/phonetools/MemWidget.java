package dev.local.phonetools;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public class MemWidget extends AppWidgetProvider {
    private static final long INTERVAL = 60_000L;
    private static final String ACTION_CLEAN = "dev.local.phonetools.CLEAN";
    /**
     * How often CpuService takes a sample and redraws the live rows and the graph. The graph steps once per sample:
     * scrolling it smoothly in between (5 redraws a second) cost about 40% of a core on a OnePlus 9 Pro, across this
     * app, the launcher and SurfaceFlinger.
     */
    static final int INTERVAL_MS = 1000;
    // A minute of samples.
    private static final int SAMPLES = 60_000 / INTERVAL_MS;
    private static final int CPU_COLOR = 0xFF80CBC4, RAM_COLOR = 0xFFFFD54F;
    private static final int GRAPH_WIDTH = 300, GRAPH_HEIGHT = 60;

    // CPU and RAM use in percent, oldest first: one sample every INTERVAL_MS while CpuService runs. Kept in memory only.
    private static final int[] cpuHistory = new int[SAMPLES], ramHistory = new int[SAMPLES];
    private static int samples;
    private static long lastTime;
    // Graph bitmaps, reused for every update (an update copies them), and what draws into them.
    private static Bitmap cpuBitmap, ramBitmap;
    private static final Canvas canvas = new Canvas();
    private static final Paint baseline = new Paint(), curve = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path line = new Path(), fill = new Path();
    private static final float[] xs = new float[SAMPLES], ys = new float[SAMPLES];
    // MemTotal, MemFree, SwapTotal, SwapFree from /proc/meminfo, in KB.
    private static final String[] MEMINFO_KEYS = {"MemTotal:", "MemFree:", "SwapTotal:", "SwapFree:"};
    private static final long[] meminfo = new long[MEMINFO_KEYS.length];
    // False when the phone allows no way of measuring CPU use; then it is left out and only RAM is graphed.
    private static boolean cpuReadable = true;
    // The CPU's temperature sensor files (thermal zones named after the CPU), found on first use.
    // Newer Android does not let apps read them; then the temperature is left out.
    private static List<String> cpuSensors;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        RemoteViews views = render(context);
        for (int id : ids) manager.updateAppWidget(id, views);
        alarms(context).setRepeating(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + INTERVAL, INTERVAL, selfIntent(context));
        context.startForegroundService(new Intent(context, CpuService.class));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_CLEAN.equals(action)) {
            // A tap reports where the widget is on screen, which the glass background needs.
            if (intent.getSourceBounds() != null) WidgetBackground.saveBounds(context, intent.getSourceBounds());
            Cleaner.clean(context);
        } else if (Intent.ACTION_POWER_CONNECTED.equals(action) || Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            manager.updateAppWidget(new ComponentName(context, MemWidget.class), render(context));
        } else {
            super.onReceive(context, intent);
        }
    }

    @Override
    public void onDisabled(Context context) {
        alarms(context).cancel(selfIntent(context));
        context.stopService(new Intent(context, CpuService.class));
    }

    /** Redraws every widget in full, for example after the wallpaper changed. */
    static void updateAll(Context context) {
        AppWidgetManager.getInstance(context).updateAppWidget(new ComponentName(context, MemWidget.class), render(context));
    }

    /** Called every INTERVAL_MS: takes a sample, and redraws the widget if it is on screen. */
    static void tick(Context context, boolean homeVisible) {
        if (!homeVisible) {
            // Nobody sees the widget: skip drawing and sending it, but keep sampling (cheap) so that the graphs
            // are complete when the home screen comes back.
            synchronized (MemWidget.class) {
                sample(setMemory(context, new RemoteViews(context.getPackageName(), R.layout.widget_mem)));
            }
            return;
        }
        updateLive(context);
    }

    /** Takes a sample and redraws the memory rows and the graph of every widget. */
    static void updateLive(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_mem);
        setGraph(views, setMemory(context, views));
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        manager.partiallyUpdateAppWidget(manager.getAppWidgetIds(new ComponentName(context, MemWidget.class)), views);
    }

    private static RemoteViews render(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_mem);
        int ramPercent = setMemory(context, views);
        Intent battery = context.getApplicationContext()
                .registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        views.setTextViewText(R.id.battery, battery(battery));
        views.setTextViewText(R.id.power, power(context, battery));
        setGraph(views, ramPercent);
        Bitmap background = WidgetBackground.get(context);
        if (background != null) {
            views.setImageViewBitmap(R.id.background, background);
            views.setInt(R.id.root, "setBackgroundResource", 0);
        }
        views.setOnClickPendingIntent(R.id.root, PendingIntent.getBroadcast(context, 1,
                new Intent(context, MemWidget.class).setAction(ACTION_CLEAN), PendingIntent.FLAG_UPDATE_CURRENT));
        return views;
    }

    /** Fills in the memory text and bar, and returns the percentage of RAM in use. */
    private static int setMemory(Context context, RemoteViews views) {
        long[] m = meminfo;
        SysFiles.readFields("/proc/meminfo", MEMINFO_KEYS, m, 0);
        long total = m[0];
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(info);
        long available = info.availMem / 1024;
        long free = m[1];
        long swapTotal = m[2];
        long swapUsed = swapTotal - m[3];
        int usedPercent = total > 0 ? (int) ((total - available) * 100 / total) : 0;

        views.setTextViewText(R.id.available, "Available " + mb(available) + " of " + mb(total));
        views.setProgressBar(R.id.bar, 100, usedPercent, false);
        views.setTextViewText(R.id.details, "Free " + mb(free) + "  ·  Swap " + (swapUsed / 1024) + " / " + mb(swapTotal));
        return usedPercent;
    }

    private static synchronized void setGraph(RemoteViews views, int ramPercent) {
        sample(ramPercent);
        int temp = cpuTemp();
        String usage = !cpuReadable ? "CPU" : samples > 0 ? "CPU " + cpuHistory[samples - 1] + "%" : "CPU …";
        // Without the use figure the temperature fits on the same line.
        views.setTextViewText(R.id.cpu, usage + (temp > 0 ? (cpuReadable ? "\n" : " ") + temp + " °C" : ""));
        // Without CPU use the row only shows the temperature, and without either it is hidden.
        views.setViewVisibility(R.id.cpu_row, cpuReadable || temp > 0 ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.cpu_graph, cpuReadable ? View.VISIBLE : View.INVISIBLE);
        views.setTextViewText(R.id.ram, "RAM " + ramPercent + "%");
        setGraphImages(views);
    }

    private static synchronized void setGraphImages(RemoteViews views) {
        if (samples == 0) return;
        if (cpuBitmap == null) {
            cpuBitmap = Bitmap.createBitmap(GRAPH_WIDTH, GRAPH_HEIGHT, Bitmap.Config.ARGB_8888);
            ramBitmap = Bitmap.createBitmap(GRAPH_WIDTH, GRAPH_HEIGHT, Bitmap.Config.ARGB_8888);
        }
        views.setImageViewBitmap(R.id.cpu_graph, graph(cpuBitmap, cpuHistory, CPU_COLOR));
        views.setImageViewBitmap(R.id.ram_graph, graph(ramBitmap, ramHistory, RAM_COLOR));
    }

    /** Forgets the previous reading, so the next sample does not average over a pause. */
    static synchronized void resetCpuBaseline() {
        lastTime = 0;
    }

    /** Adds a sample: average CPU use since the previous one (0 if it cannot be measured), and the RAM in use now. */
    private static void sample(int ramPercent) {
        long now = SystemClock.elapsedRealtime();
        if (lastTime != 0 && now - lastTime < INTERVAL_MS * 9 / 10) return;
        int cpu = CpuMeter.sampleShared(now, 3 * INTERVAL_MS);
        cpuReadable = cpu != CpuMeter.UNAVAILABLE;
        if (lastTime != 0) {
            if (samples == SAMPLES) {
                samples--;
                System.arraycopy(cpuHistory, 1, cpuHistory, 0, samples);
                System.arraycopy(ramHistory, 1, ramHistory, 0, samples);
            }
            cpuHistory[samples] = Math.max(0, cpu);
            ramHistory[samples++] = ramPercent;
        }
        lastTime = now;
    }

    /** Returns the hottest CPU sensor's temperature in °C, or 0 if none can be read. */
    private static int cpuTemp() {
        if (cpuSensors == null) {
            cpuSensors = new ArrayList<>();
            File[] zones = new File("/sys/class/thermal").listFiles();
            if (zones != null) {
                for (File zone : zones) {
                    try (BufferedReader reader = new BufferedReader(new FileReader(new File(zone, "type")))) {
                        if (isCpuSensor(reader.readLine().trim().toLowerCase(java.util.Locale.US))) {
                            cpuSensors.add(zone + "/temp");
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        long hottest = 0;
        for (String sensor : cpuSensors) {
            long value = SysFiles.readLong(sensor, 0);
            // Most kernels report millidegrees, some (like the Moto G 2014's) whole degrees.
            if (value > 1000) value /= 1000;
            if (value < 150) hottest = Math.max(hottest, value);
        }
        return (int) hottest;
    }

    /** Thermal zone types that measure the CPU, across Qualcomm, MediaTek, Exynos and Intel kernels. */
    private static boolean isCpuSensor(String type) {
        return type.startsWith("tsens_tz_sensor") || type.contains("cpu") || type.startsWith("mtktscpu")
                || type.startsWith("soc_thermal") || type.startsWith("x86_pkg_temp") || type.contains("cluster");
    }

    /**
     * Draws the history as a filled curve into {@code bitmap}, the newest sample at the right edge.
     */
    private static Bitmap graph(Bitmap bitmap, int[] values, int color) {
        bitmap.eraseColor(0);
        canvas.setBitmap(bitmap);
        baseline.setColor(0x33FFFFFF);
        canvas.drawLine(0, GRAPH_HEIGHT - 1, GRAPH_WIDTH, GRAPH_HEIGHT - 1, baseline);

        float step = GRAPH_WIDTH / (float) (SAMPLES - 1);
        float startX = GRAPH_WIDTH - (samples - 1) * step;
        for (int i = 0; i < samples; i++) {
            xs[i] = startX + i * step;
            ys[i] = GRAPH_HEIGHT - 2 - values[i] * (GRAPH_HEIGHT - 4) / 100f;
        }
        // A curve through the midpoints between samples, bending at each sample.
        line.rewind();
        line.moveTo(xs[0], ys[0]);
        for (int i = 1; i < samples; i++) {
            line.quadTo(xs[i - 1], ys[i - 1], (xs[i - 1] + xs[i]) / 2, (ys[i - 1] + ys[i]) / 2);
        }
        line.lineTo(xs[samples - 1], ys[samples - 1]);
        // A single sample still shows as a dot.
        if (samples == 1) line.lineTo(xs[0] + 0.1f, ys[0]);
        fill.set(line);
        fill.lineTo(xs[samples - 1], GRAPH_HEIGHT);
        fill.lineTo(xs[0], GRAPH_HEIGHT);
        fill.close();

        curve.setColor(color & 0x55FFFFFF);
        curve.setStyle(Paint.Style.FILL);
        canvas.drawPath(fill, curve);
        curve.setColor(color);
        curve.setStyle(Paint.Style.STROKE);
        curve.setStrokeWidth(samples == 1 ? 6 : 2);
        curve.setStrokeCap(Paint.Cap.ROUND);
        curve.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(line, curve);
        canvas.setBitmap(null);
        return bitmap;
    }

    private static String battery(Intent battery) {
        if (battery == null) return "Battery unknown";
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        String percent = level >= 0 && scale > 0 ? (level * 100 / scale) + "%  ·  " : "";
        return "Battery " + percent + volts(battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)) + " V  ·  "
                + String.format(java.util.Locale.US, "%.1f", battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0) + " °C";
    }

    private static String power(Context context, Intent battery) {
        int plugged = battery == null ? 0 : battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        if (plugged == 0) return "Not charging";
        String source = plugged == BatteryManager.BATTERY_PLUGGED_AC ? "AC"
                : plugged == BatteryManager.BATTERY_PLUGGED_USB ? "USB" : "Wireless";
        long input = SysFiles.readLong("/sys/class/power_supply/usb/voltage_now", 0) / 1000;
        String charger = source + (input > 0 ? " " + volts(input) + " V" : "");
        // Plugged in is not always charging: phones hold the battery below full (OxygenOS at 80-85%, for one).
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        if (status == BatteryManager.BATTERY_STATUS_FULL) return "Charged  ·  " + charger;
        if (status != BatteryManager.BATTERY_STATUS_CHARGING) return "Plugged in, not charging  ·  " + charger;
        BatteryManager manager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        int current = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        if (current == Integer.MIN_VALUE || current == 0) return "Charging " + charger;
        // Android specifies microamps, but some phones (OnePlus, for one) report milliamps. Charging draws well over
        // 10 mA, so a smaller-looking number must already be milliamps.
        long ma = Math.abs((long) current);
        if (ma >= 10_000) ma /= 1000;
        return "Charging " + charger + "  ·  " + ma + " mA";
    }

    private static String volts(long mv) {
        return String.format(java.util.Locale.US, "%.2f", mv / 1000.0);
    }

    private static PendingIntent selfIntent(Context context) {
        int[] ids = AppWidgetManager.getInstance(context)
                .getAppWidgetIds(new ComponentName(context, MemWidget.class));
        Intent intent = new Intent(context, MemWidget.class)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static AlarmManager alarms(Context context) {
        return (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }

    private static String mb(long kb) {
        return (kb / 1024) + " MB";
    }

}
