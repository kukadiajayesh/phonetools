package dev.local.phonetools;

import android.accessibilityservice.AccessibilityService;
import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shows a CPU use graph and download and upload speed in KB/s in the status bar, just left of the status icons and
 * clock. Apps cannot add
 * views to the status bar, so this draws an accessibility overlay above it and finds the icons' place on screen from
 * the status bar's accessibility nodes. Updates every second while the screen is on.
 * The text is white or black to match the status icons: Android 8 does not tell other apps which the system chose, so
 * each update samples the icons' pixels from a half-size screen capture (see {@link CaptureActivity}).
 * Also clears the recent apps for {@link Cleaner}, by pressing each task's dismiss button in the recents screen.
 */
public class SpeedService extends AccessibilityService {
    // The status icons' view ids, tried in order: Android 8-11, Android 12+, then the clock alone. On a status bar
    // with none of them (some vendor builds) the overlay stays hidden.
    private static final String[] STATUS_ICONS = {"com.android.systemui:id/system_icon_area",
            "com.android.systemui:id/status_bar_end_side_content", "com.android.systemui:id/system_icons",
            "com.android.systemui:id/clock"};
    // Shown only while the notification shade is pulled down; its header has its own clock and battery.
    private static final String SHADE = "com.android.systemui:id/quick_settings_container";
    // The space stock SystemUI leaves for a camera hole; where the cutout is found before Android 10.
    private static final String CUTOUT_SPACER = "com.android.systemui:id/cutout_space_view";
    // The status bar's own network speed indicator, where the system has one (OxygenOS, LineageOS 17+, MIUI):
    // while it is shown, only the CPU graph is drawn.
    private static final String[] SYSTEM_SPEED = {"com.android.systemui:id/network_speed",
            "com.android.systemui:id/network_traffic", "com.android.systemui:id/network_speed_view"};
    // The CPU graph's width: as wide as there is room for, up to the first, and hidden below the second.
    private static final int GRAPH_DP = 56, MIN_GRAPH_DP = 16;
    private static final int GAP_DP = 4;
    private static final String DISMISS_TASK = "com.android.systemui:id/dismiss_task";
    // Recents is checked every ROUND_MS; gives up after MAX_STALLS rounds in which the cards shown did not change.
    private static final int ROUND_MS = 350, MAX_STALLS = 8, MAX_ROUNDS = 150;

    /** The running service, or null when it is turned off in Accessibility settings. */
    static SpeedService instance;

    /** Whether the home screen (and so the memory widget) is on screen; CpuService pauses the widget otherwise. */
    volatile boolean homeVisible = true;
    private String launcher;
    private long launcherCheckedAt;

    private final Handler handler = new Handler();
    private WindowManager windows;
    // The overlay window: the CPU graph, then the speed text.
    private LinearLayout bar;
    private CpuGraph graph;
    private TextView view;
    private WindowManager.LayoutParams params;
    private long lastRx = -1, lastTx, lastTime;
    // Right edge of whatever the status bar shows left of the status icons (clock, notification icons), found with them.
    private int contentRight;
    private boolean systemShowsSpeed;
    private Rect cutoutSpacer;
    // Screen capture for the icon colour; null until allowed. The virtual display only gets a surface while sampling.
    private MediaProjection projection;
    private VirtualDisplay capture;
    private ImageReader reader;
    private Rect sampleArea;
    private boolean darkText;
    private long lastCaptureRequest;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            handler.postDelayed(this, 1000);
        }
    };
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handler.removeCallbacks(tick);
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                lastRx = -1;
                handler.post(tick);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        view = new TextView(this);
        view.setTextColor(0xFFFFFFFF);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        view.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD));
        view.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        view.setLineSpacing(0, 0.85f);
        view.setIncludeFontPadding(false);
        view.setShadowLayer(2, 0, 1, 0x80000000);
        float density = getResources().getDisplayMetrics().density;
        graph = new CpuGraph(this);
        LinearLayout.LayoutParams graphSize = new LinearLayout.LayoutParams(Math.round(GRAPH_DP * density), Math.round(20 * density));
        graphSize.setMarginEnd(Math.round(GAP_DP * density));
        bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(graph, graphSize);
        bar.addView(view);
        bar.setVisibility(View.GONE);

        params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, statusBarHeight(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        // Windows added from a service are drawn in software unless asked; the graph animates.
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        windows.addView(bar, params);

        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screen, filter);
        if (((PowerManager) getSystemService(POWER_SERVICE)).isInteractive()) handler.post(tick);
        instance = this;
        requestCapture();
    }

    private void requestCapture() {
        lastCaptureRequest = SystemClock.elapsedRealtime();
        startActivity(new Intent(this, CaptureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Whether screen capture is allowed beforehand (appops PROJECT_MEDIA), so asking shows no prompt. */
    private boolean captureAllowed() {
        return ((AppOpsManager) getSystemService(APP_OPS_SERVICE)).checkOpNoThrow("android:project_media",
                android.os.Process.myUid(), getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    /** Called by {@link CaptureActivity} once screen capture is allowed. */
    void setProjection(MediaProjection projection) {
        stopCapture();
        this.projection = projection;
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (SpeedService.this.projection == projection) {
                    stopCapture();
                    setDarkText(false);
                }
            }
        }, handler);
    }

    private void stopCapture() {
        if (capture != null) capture.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        capture = null;
        reader = null;
        projection = null;
        sampleArea = null;
    }

    /**
     * Captures one frame and sets the text colour from the status icons inside {@code icons} (screen coordinates).
     * The display gets its surface only for this frame, so the screen is not mirrored the rest of the time.
     */
    private void sampleIconColor(Rect icons) {
        if (projection == null || sampleArea != null) return;
        DisplayMetrics real = new DisplayMetrics();
        windows.getDefaultDisplay().getRealMetrics(real);
        int width = real.widthPixels / 2, height = real.heightPixels / 2;
        if (capture == null || reader.getWidth() != width || reader.getHeight() != height) {
            if (capture != null) capture.release();
            if (reader != null) reader.close();
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    Image image = r.acquireLatestImage();
                    if (image == null) return;
                    if (sampleArea != null) {
                        capture.setSurface(null);
                        setDarkText(isDark(image, sampleArea));
                        sampleArea = null;
                    }
                    image.close();
                }
            }, handler);
            capture = projection.createVirtualDisplay("status bar colour", width, height, real.densityDpi / 2,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, handler);
        }
        sampleArea = new Rect(icons.left / 2, icons.top / 2, icons.right / 2, icons.bottom / 2);
        if (!sampleArea.intersect(0, 0, width, height)) {
            sampleArea = null;
            return;
        }
        capture.setSurface(reader.getSurface());
        // Give up on this frame if none comes (the next update tries again).
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (sampleArea != null && capture != null) capture.setSurface(null);
                sampleArea = null;
            }
        }, 900);
    }

    /**
     * Whether the icons in {@code area} are dark. The background fills most of the area, so its brightness is the
     * median; the icons are whichever end of the brightness range lies further from it.
     */
    private static boolean isDark(Image image, Rect area) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer pixels = plane.getBuffer();
        int[] histogram = new int[256];
        for (int y = area.top; y < area.bottom; y++) {
            for (int x = area.left; x < area.right; x++) {
                int i = y * plane.getRowStride() + x * plane.getPixelStride();
                int r = pixels.get(i) & 0xFF, g = pixels.get(i + 1) & 0xFF, b = pixels.get(i + 2) & 0xFF;
                histogram[(r * 299 + g * 587 + b * 114) / 1000]++;
            }
        }
        int count = area.width() * area.height();
        int low = percentile(histogram, count / 50), median = percentile(histogram, count / 2),
                high = percentile(histogram, count - 1 - count / 50);
        int icon = high - median >= median - low ? high : low;
        // No icons stand out: pick the colour that reads on the background.
        if (Math.abs(icon - median) < 24) return median > 140;
        return icon < 128;
    }

    private static int percentile(int[] histogram, int rank) {
        int seen = 0;
        for (int value = 0; value < histogram.length; value++) {
            seen += histogram[value];
            if (seen > rank) return value;
        }
        return histogram.length - 1;
    }

    private void setDarkText(boolean dark) {
        if (dark == darkText) return;
        darkText = dark;
        view.setTextColor(dark ? 0xFF000000 : 0xFFFFFFFF);
        graph.setColor(dark ? 0xFF000000 : 0xFFFFFFFF);
        // System icons have no shadow when dark.
        if (dark) view.setShadowLayer(0, 0, 0, 0);
        else view.setShadowLayer(2, 0, 1, 0x80000000);
    }

    /** Opens recents, dismisses every task, goes home, then runs {@code done}. */
    void clearRecents(Runnable done) {
        performGlobalAction(GLOBAL_ACTION_RECENTS);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                dismissNext(done, 0, "", 0);
            }
        }, ROUND_MS);
    }

    /**
     * Presses "Clear all" if the recents screen shows it, otherwise the first task's dismiss button, and repeats.
     * Only the cards on screen are visible to accessibility, so progress is judged by the cards shown changing, not
     * their count. The buttons are not clickable while recents animates, so a round may only wait. Stops when no task
     * is left, or when the cards have not changed for a while (for example a locked task).
     */
    private void dismissNext(Runnable done, int round, String lastCards, int stalls) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo target = null;
        StringBuilder cards = new StringBuilder();
        boolean inRecents = root != null && "com.android.systemui".contentEquals(root.getPackageName());
        if (inRecents) {
            target = firstClickable(root.findAccessibilityNodeInfosByText("Clear all"), true);
            if (target != null) {
                cards.append(lastCards);
            } else {
                List<AccessibilityNodeInfo> dismiss = root.findAccessibilityNodeInfosByViewId(DISMISS_TASK);
                for (AccessibilityNodeInfo node : dismiss) cards.append(node.getContentDescription()).append('|');
                // Task cards are drawn scaled, so their reported bounds run off screen: do not require visibility.
                target = firstClickable(dismiss, false);
            }
        }
        if (root != null) root.recycle();
        String shown = cards.toString();
        int nextStalls = shown.equals(lastCards) ? stalls + 1 : 0;
        // Until recents is on screen (and done animating in), keep waiting; once it shows no cards, they are all gone.
        boolean finished = inRecents && shown.isEmpty() && round >= 2;
        if (!finished && nextStalls < MAX_STALLS && round < MAX_ROUNDS) {
            if (target != null) target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    dismissNext(done, round + 1, shown, nextStalls);
                }
            }, ROUND_MS);
        } else {
            performGlobalAction(GLOBAL_ACTION_HOME);
            handler.postDelayed(done, 500);
        }
        if (target != null) target.recycle();
    }

    private static AccessibilityNodeInfo firstClickable(List<AccessibilityNodeInfo> nodes, boolean mustBeVisible) {
        AccessibilityNodeInfo result = null;
        for (AccessibilityNodeInfo node : nodes) {
            if (result == null && node.isClickable() && (!mustBeVisible || node.isVisibleToUser())) result = node;
            else node.recycle();
        }
        return result;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        checkHome();
        // Hide at once when the shade is pulled down, rather than at the next update.
        if (bar != null && bar.getVisibility() == View.VISIBLE && statusIcons() == null) {
            bar.setVisibility(View.GONE);
            lastRx = -1;
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        instance = null;
        handler.removeCallbacks(tick);
        stopCapture();
        if (bar != null) {
            unregisterReceiver(screen);
            windows.removeView(bar);
        }
        super.onDestroy();
    }

    private void update() {
        checkHome();
        Rect icons = statusIcons();
        if (icons == null) {
            // No visible status bar, e.g. a full-screen app.
            bar.setVisibility(View.GONE);
            lastRx = -1;
            return;
        }
        long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes();
        long now = SystemClock.elapsedRealtime();
        sampleCpu(now);
        if (rx == TrafficStats.UNSUPPORTED || tx == TrafficStats.UNSUPPORTED) return;
        if (lastRx >= 0 && now > lastTime) {
            float seconds = (now - lastTime) / 1000f;
            long down = Math.max(0, Math.round((rx - lastRx) / 1024f / seconds));
            long up = Math.max(0, Math.round((tx - lastTx) / 1024f / seconds));
            view.setText("↓ " + kb(down) + "\n↑ " + kb(up));
            int gap = Math.round(GAP_DP * getResources().getDisplayMetrics().density);
            // The icons are in screen coordinates, so measure from the screen's edge: in landscape the app width
            // leaves out the navigation bar and cutout at the sides. The status bar is also shorter in landscape, so
            // the overlay is as tall as centres it on the icons.
            DisplayMetrics real = new DisplayMetrics();
            windows.getDefaultDisplay().getRealMetrics(real);
            int[] span = freeSpan(contentRight, icons.left - gap, icons.bottom, gap);
            int start = span[0], end = span[1];
            int x = real.widthPixels - end, height = icons.top + icons.bottom;
            if (params.x != x || params.height != height) {
                params.x = x;
                params.height = height;
                windows.updateViewLayout(bar, params);
            }
            bar.setVisibility(layOut(end - start) ? View.VISIBLE : View.GONE);
            // Ask again if capture was refused, cancelled or stopped, but only where that shows no prompt.
            if (projection == null && now - lastCaptureRequest > 10_000 && captureAllowed()) requestCapture();
            sampleIconColor(icons);
        }
        lastRx = rx;
        lastTx = tx;
        lastTime = now;
    }

    /** Updates {@link #homeVisible}: an app window of the default launcher is on screen, and the phone is unlocked. */
    private void checkHome() {
        long now = SystemClock.elapsedRealtime();
        // The default launcher can change; look it up again now and then.
        if (launcher == null || now - launcherCheckedAt > 60_000) {
            ResolveInfo home = getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            launcher = home != null && home.activityInfo != null ? home.activityInfo.packageName : "";
            launcherCheckedAt = now;
        }
        boolean visible = false;
        if (!((KeyguardManager) getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked()) {
            for (AccessibilityWindowInfo window : getWindows()) {
                if (visible || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                visible = launcher.contentEquals(root.getPackageName());
                root.recycle();
            }
        }
        homeVisible = visible;
    }

    /** The narrowest room that shows everything: the speed text (unless the system shows its own) and the graph. */
    private int fullWidth() {
        float density = getResources().getDisplayMetrics().density;
        int width = 0;
        if (!systemShowsSpeed) {
            view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            width = view.getMeasuredWidth() + Math.round(GAP_DP * density);
        }
        return graph.getVisibility() != View.GONE ? width + Math.round(MIN_GRAPH_DP * density) : width;
    }

    /**
     * Returns the part of {@code [start, end]} to draw in, as {start, end}, so that no camera hole, notch or other
     * display cutout cuts the graph or text. The cutouts reaching into the status bar (above {@code bottom}) split the
     * room into gaps, each kept {@code gap} clear of them; the gap nearest the status icons that fits everything wins,
     * else the widest (where the graph stays and the text is hidden). Works for any number and position of cutouts.
     */
    private int[] freeSpan(int start, int end, int bottom, int gap) {
        List<Rect> holes = new ArrayList<>();
        // Android 10+ reports the cutouts; before that, stock SystemUI keeps a spacer view where the hole is.
        if (Build.VERSION.SDK_INT >= 29) holes.addAll(Cutout.rects(windows));
        else if (cutoutSpacer != null) holes.add(cutoutSpacer);
        List<int[]> gaps = new ArrayList<>();
        gaps.add(new int[] {start, end});
        for (Rect hole : holes) {
            if (hole.isEmpty() || hole.top >= bottom) continue;
            List<int[]> split = new ArrayList<>();
            for (int[] g : gaps) {
                if (hole.right + gap <= g[0] || hole.left - gap >= g[1]) {
                    split.add(g);
                    continue;
                }
                if (hole.left - gap > g[0]) split.add(new int[] {g[0], hole.left - gap});
                if (hole.right + gap < g[1]) split.add(new int[] {hole.right + gap, g[1]});
            }
            gaps = split;
        }
        if (gaps.isEmpty()) return new int[] {end, end};
        // The gaps are in order from left to right, so the last is nearest the status icons.
        int full = fullWidth();
        for (int i = gaps.size() - 1; i >= 0; i--) {
            if (gaps.get(i)[1] - gaps.get(i)[0] >= full) return gaps.get(i);
        }
        int[] widest = gaps.get(gaps.size() - 1);
        for (int[] g : gaps) {
            if (g[1] - g[0] > widest[1] - widest[0]) widest = g;
        }
        return widest;
    }

    /** The display cutouts, in their own class so Android 9 and older never load DisplayCutout. */
    private static final class Cutout {
        static List<Rect> rects(WindowManager windows) {
            DisplayCutout cutout = windows.getDefaultDisplay().getCutout();
            return cutout != null ? cutout.getBoundingRects() : new ArrayList<Rect>();
        }
    }

    /**
     * Fits the graph and speed text into {@code room} pixels, and returns whether anything is left to show. Some status
     * bars (OxygenOS, for one) fill the space up to the status icons with notification icons: rather than cover them,
     * the graph narrows, then the speed text is hidden to keep the graph, then the graph. The speed text is also left
     * out where the system shows its own.
     */
    private boolean layOut(int room) {
        float density = getResources().getDisplayMetrics().density;
        boolean text = !systemShowsSpeed, cpu = graph.getVisibility() != View.GONE;
        int textWidth = 0;
        if (text) {
            view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            textWidth = view.getMeasuredWidth();
        }
        int minGraph = Math.round(MIN_GRAPH_DP * density), margin = Math.round(GAP_DP * density);
        // No room for both: the graph stays and the text goes, or the text alone if even the graph does not fit.
        if (text && cpu && room - textWidth - margin < minGraph) {
            if (room >= minGraph) text = false;
            else cpu = false;
        }
        view.setVisibility(text ? View.VISIBLE : View.GONE);
        if (!cpu) {
            graph.setVisibility(View.GONE);
            return text && textWidth <= room;
        }
        LinearLayout.LayoutParams size = (LinearLayout.LayoutParams) graph.getLayoutParams();
        if (!text) margin = 0;
        int width = Math.min(Math.round(GRAPH_DP * density), room - (text ? textWidth : 0) - margin);
        if (width < minGraph) {
            graph.setVisibility(View.GONE);
            return false;
        }
        if (size.width != width || size.getMarginEnd() != margin) {
            size.width = width;
            size.setMarginEnd(margin);
            graph.setLayoutParams(size);
        }
        return true;
    }

    /** Adds the CPU use since the previous update to the graph; after a pause (hidden or screen off) only restarts. */
    private void sampleCpu(long now) {
        int cpu = CpuMeter.sampleShared(now, 3000);
        // Where the phone allows no way of measuring, only the speed is shown.
        graph.setVisibility(cpu == CpuMeter.UNAVAILABLE ? View.GONE : View.VISIBLE);
        if (cpu >= 0) graph.add(cpu);
    }

    /**
     * Returns the on-screen bounds of the status icons (or the clock), or null when the status bar is not shown or
     * the notification shade covers it.
     */
    private Rect statusIcons() {
        for (AccessibilityWindowInfo window : getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            try {
                if (!"com.android.systemui".contentEquals(root.getPackageName())) continue;
                if (visibleBounds(root, SHADE) != null) return null;
                Rect bounds = null;
                for (int i = 0; bounds == null && i < STATUS_ICONS.length; i++) bounds = visibleBounds(root, STATUS_ICONS[i]);
                if (bounds != null && bounds.top < statusBarHeight()) {
                    contentRight = rightmostBefore(root, bounds.left);
                    if (Build.VERSION.SDK_INT < 29) cutoutSpacer = visibleBounds(root, CUTOUT_SPACER);
                    systemShowsSpeed = false;
                    for (String id : SYSTEM_SPEED) systemShowsSpeed |= visibleBounds(root, id) != null;
                    return bounds;
                }
            } finally {
                root.recycle();
            }
        }
        return null;
    }

    /**
     * Returns the right edge of the visible views that end left of {@code limit}, or 0. The camera hole's spacer is
     * empty space, not content: {@link #freeSpan} keeps clear of the hole itself.
     */
    private static int rightmostBefore(AccessibilityNodeInfo node, int limit) {
        int right = 0;
        Rect bounds = new Rect();
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            if (child.isVisibleToUser() && !CUTOUT_SPACER.equals(child.getViewIdResourceName())) {
                if (child.getChildCount() > 0) {
                    right = Math.max(right, rightmostBefore(child, limit));
                } else {
                    child.getBoundsInScreen(bounds);
                    if (!bounds.isEmpty() && bounds.right <= limit) right = Math.max(right, bounds.right);
                }
            }
            child.recycle();
        }
        return right;
    }

    private static Rect visibleBounds(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
        Rect result = null;
        for (AccessibilityNodeInfo node : nodes) {
            if (result == null && node.isVisibleToUser()) {
                result = new Rect();
                node.getBoundsInScreen(result);
                if (result.isEmpty()) result = null;
            }
            node.recycle();
        }
        return result;
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : Math.round(24 * getResources().getDisplayMetrics().density);
    }

    /** KB/s with a unit; from 10 000 KB/s on, MB/s. */
    private static String kb(long kb) {
        if (kb < 10_000) return kb + " KB/s";
        return String.format(Locale.US, "%.1f MB/s", kb / 1024f);
    }
}
