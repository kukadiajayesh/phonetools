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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MemWidget extends AppWidgetProvider {
    private static final long INTERVAL = 60_000L;
    private static final String ACTION_CLEAN = "dev.local.phonetools.CLEAN";
    /** How often CpuService redraws the live rows and takes a graph sample. */
    static final int INTERVAL_MS = 1000;
    /** How often the graph is redrawn, scrolled part of a step, so it moves smoothly between samples. */
    static final int FRAME_MS = 200;
    // A minute of samples.
    private static final int SAMPLES = 60_000 / INTERVAL_MS;
    private static final int CPU_COLOR = 0xFF80CBC4, RAM_COLOR = 0xFFFFD54F;
    private static final int GRAPH_WIDTH = 300, GRAPH_HEIGHT = 60;

    // CPU and RAM use in percent, oldest first: one sample every INTERVAL_MS while CpuService runs. Kept in memory only.
    private static final int[] cpuHistory = new int[SAMPLES], ramHistory = new int[SAMPLES];
    private static int samples;
    private static long lastTime, lastSampleTime;
    private static boolean wasHomeVisible = true;
    private static final CpuMeter cpuMeter = new CpuMeter();
    // Graph bitmaps, reused for every frame (an update copies them).
    private static Bitmap cpuBitmap, ramBitmap;
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

    /** Called every FRAME_MS: takes a sample when one is due, otherwise only scrolls the graph on. */
    static void tick(Context context, boolean homeVisible) {
        boolean due = SystemClock.elapsedRealtime() - lastTime >= INTERVAL_MS - FRAME_MS / 2;
        if (!homeVisible) {
            // Nobody sees the widget: skip drawing and sending it, but keep sampling (cheap) so that the graphs
            // are complete when the home screen comes back.
            if (due) {
                synchronized (MemWidget.class) {
                    sample(setMemory(context, new RemoteViews(context.getPackageName(), R.layout.widget_mem)));
                }
            }
            wasHomeVisible = false;
            return;
        }
        if (due || !wasHomeVisible) {
            wasHomeVisible = true;
            updateLive(context);
        } else if (samples > 1) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_mem);
            setGraphImages(views);
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            manager.partiallyUpdateAppWidget(manager.getAppWidgetIds(new ComponentName(context, MemWidget.class)), views);
        }
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
        Map<String, Long> m = readMeminfo();
        long total = m.getOrDefault("MemTotal", 0L);
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(info);
        long available = info.availMem / 1024;
        long free = m.getOrDefault("MemFree", 0L);
        long swapTotal = m.getOrDefault("SwapTotal", 0L);
        long swapUsed = swapTotal - m.getOrDefault("SwapFree", 0L);
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
        // How far into the next step the graph has scrolled since the newest sample.
        // A lone first sample is shown at once, at the right edge.
        float progress = samples == 1 ? 1
                : Math.min(1, (SystemClock.elapsedRealtime() - lastSampleTime) / (float) INTERVAL_MS);
        views.setImageViewBitmap(R.id.cpu_graph, graph(cpuBitmap, cpuHistory, CPU_COLOR, progress));
        views.setImageViewBitmap(R.id.ram_graph, graph(ramBitmap, ramHistory, RAM_COLOR, progress));
    }

    /** Forgets the previous reading, so the next sample does not average over a pause. */
    static synchronized void resetCpuBaseline() {
        lastTime = 0;
    }

    /** Adds a sample: average CPU use since the previous one (0 if it cannot be measured), and the RAM in use now. */
    private static void sample(int ramPercent) {
        long now = SystemClock.elapsedRealtime();
        if (lastTime != 0 && now - lastTime < INTERVAL_MS * 9 / 10) return;
        int cpu = cpuMeter.sample(now, 3 * INTERVAL_MS);
        cpuReadable = cpu != CpuMeter.UNAVAILABLE;
        if (lastTime != 0) {
            if (samples == SAMPLES) {
                samples--;
                System.arraycopy(cpuHistory, 1, cpuHistory, 0, samples);
                System.arraycopy(ramHistory, 1, ramHistory, 0, samples);
            }
            cpuHistory[samples] = Math.max(0, cpu);
            ramHistory[samples++] = ramPercent;
            lastSampleTime = now;
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
            long value = readLong(sensor);
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
     * Draws the history as a filled curve into {@code bitmap}. The graph scrolls left as {@code progress} goes from 0
     * to 1, so the newest sample slides in from the right edge until the next one arrives.
     */
    private static Bitmap graph(Bitmap bitmap, int[] values, int color, float progress) {
        bitmap.eraseColor(0);
        Canvas canvas = new Canvas(bitmap);
        Paint baseline = new Paint();
        baseline.setColor(0x33FFFFFF);
        canvas.drawLine(0, GRAPH_HEIGHT - 1, GRAPH_WIDTH, GRAPH_HEIGHT - 1, baseline);

        float step = GRAPH_WIDTH / (float) (SAMPLES - 1);
        float startX = GRAPH_WIDTH - (samples - 2 + progress) * step;
        float[] xs = new float[samples], ys = new float[samples];
        for (int i = 0; i < samples; i++) {
            xs[i] = startX + i * step;
            ys[i] = GRAPH_HEIGHT - 2 - values[i] * (GRAPH_HEIGHT - 4) / 100f;
        }
        // A curve through the midpoints between samples, bending at each sample.
        Path line = new Path();
        line.moveTo(xs[0], ys[0]);
        for (int i = 1; i < samples; i++) {
            line.quadTo(xs[i - 1], ys[i - 1], (xs[i - 1] + xs[i]) / 2, (ys[i - 1] + ys[i]) / 2);
        }
        line.lineTo(xs[samples - 1], ys[samples - 1]);
        // A single sample still shows as a dot.
        if (samples == 1) line.lineTo(xs[0] + 0.1f, ys[0]);
        Path fill = new Path(line);
        fill.lineTo(xs[samples - 1], GRAPH_HEIGHT);
        fill.lineTo(xs[0], GRAPH_HEIGHT);
        fill.close();

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color & 0x55FFFFFF);
        canvas.drawPath(fill, paint);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(samples == 1 ? 6 : 2);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(line, paint);
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
        long input = readLong("/sys/class/power_supply/usb/voltage_now") / 1000;
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

    private static long readLong(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            return Long.parseLong(reader.readLine().trim());
        } catch (Exception ignored) {
            return 0;
        }
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

    private static Map<String, Long> readMeminfo() {
        Map<String, Long> values = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) values.put(parts[0].replace(":", ""), Long.parseLong(parts[1]));
            }
        } catch (Exception ignored) {
        }
        return values;
    }
}
