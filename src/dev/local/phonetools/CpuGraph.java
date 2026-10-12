package dev.local.phonetools;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;

/**
 * A small CPU use graph for the status bar: a filled curve of the last {@link #SAMPLES} seconds, newest at the right.
 * It scrolls smoothly: each new sample slides in from the right edge over the time until the next one is due. The
 * curve is drawn once per sample into a bitmap one step wider than the view, and each frame only shifts that bitmap.
 */
class CpuGraph extends View {
    static final int SAMPLES = 40;
    // The scrolling's frame interval. The graph moves only a few pixels a second, so 5 frames a second move it under
    // a pixel each. Each frame costs about 3% of a core (half of it in SurfaceFlinger) on a OnePlus 9 Pro.
    private static final long FRAME_MS = 200;

    // One more than fits, so the oldest scrolls out at the left edge instead of vanishing.
    private final int[] history = new int[SAMPLES + 1];
    private int samples;
    private long lastAdd;
    private long interval = 1000;
    private int color = 0xFFFFFFFF;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            baseline = new Paint(), bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path(), area = new Path();
    // The curve as of the newest sample, before any scrolling; redrawn when a sample arrives or the size changes.
    private Bitmap curve;
    private boolean curveStale = true;

    CpuGraph(Context context) {
        super(context);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.5f * getResources().getDisplayMetrics().density);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        setColor(color);
    }

    /** Adds a CPU use sample in percent. */
    void add(int percent) {
        long now = SystemClock.uptimeMillis();
        // Scroll at the pace samples actually arrive, which drifts a little from a second.
        if (lastAdd != 0) interval = Math.max(500, Math.min(2000, now - lastAdd));
        lastAdd = now;
        if (samples == history.length) System.arraycopy(history, 1, history, 0, --samples);
        history[samples++] = Math.max(0, Math.min(100, percent));
        curveStale = true;
        invalidate();
    }

    void setColor(int color) {
        this.color = color;
        line.setColor(color);
        fill.setColor(color & 0x55FFFFFF);
        baseline.setColor(color & 0x66FFFFFF);
        curveStale = true;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth(), height = getHeight();
        canvas.drawLine(0, height - 0.5f, width, height - 0.5f, baseline);
        if (samples < 2 || width == 0 || height == 0) return;
        float step = width / (float) (SAMPLES - 1);
        int curveWidth = (int) Math.ceil(width + step);
        if (curve == null || curve.getWidth() != curveWidth || curve.getHeight() != height) {
            if (curve != null) curve.recycle();
            curve = Bitmap.createBitmap(curveWidth, height, Bitmap.Config.ARGB_8888);
            curveStale = true;
        }
        if (curveStale) {
            drawCurve(step);
            curveStale = false;
        }
        // How far the newest sample has slid in: from one step beyond the right edge (0) to the edge itself (1).
        float progress = Math.min(1, (SystemClock.uptimeMillis() - lastAdd) / (float) interval);
        canvas.drawBitmap(curve, -progress * step, 0, bitmapPaint);
        // Keep scrolling until the newest sample is fully in, while on screen.
        if (progress < 1 && isShown()) postInvalidateDelayed(FRAME_MS);
    }

    /** Draws the curve into the bitmap with the newest sample one step beyond the view's right edge. */
    private void drawCurve(float step) {
        curve.eraseColor(0);
        Canvas canvas = new Canvas(curve);
        float height = curve.getHeight(), inset = line.getStrokeWidth();
        float startX = curve.getWidth() - (samples - 1) * step;
        path.rewind();
        float lastX = 0, lastY = 0;
        for (int i = 0; i < samples; i++) {
            float x = startX + i * step, y = height - inset - history[i] * (height - 2 * inset) / 100f;
            // A curve through the midpoints between samples, bending at each sample.
            if (i == 0) path.moveTo(x, y);
            else path.quadTo(lastX, lastY, (lastX + x) / 2, (lastY + y) / 2);
            lastX = x;
            lastY = y;
        }
        path.lineTo(lastX, lastY);
        area.set(path);
        area.lineTo(lastX, height);
        area.lineTo(startX, height);
        area.close();
        canvas.drawPath(area, fill);
        canvas.drawPath(path, line);
    }
}
