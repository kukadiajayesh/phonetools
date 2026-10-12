package dev.local.phonetools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * A small CPU use graph for the status bar: a filled curve of the last {@link #SAMPLES} seconds, newest at the right.
 * It steps once per sample rather than scrolling smoothly in between: redrawing 5 times a second kept SurfaceFlinger
 * recomposing the screen, about 8% of a core on a OnePlus 9 Pro.
 */
class CpuGraph extends View {
    static final int SAMPLES = 40;

    private final int[] history = new int[SAMPLES];
    private int samples;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            baseline = new Paint();
    private final Path path = new Path(), area = new Path();
    // The size the paths were built for; they are rebuilt when a sample arrives or the size changes.
    private int pathWidth, pathHeight;
    private boolean pathStale = true;

    CpuGraph(Context context) {
        super(context);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.5f * getResources().getDisplayMetrics().density);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        setColor(0xFFFFFFFF);
    }

    /** Adds a CPU use sample in percent. */
    void add(int percent) {
        if (samples == history.length) System.arraycopy(history, 1, history, 0, --samples);
        history[samples++] = Math.max(0, Math.min(100, percent));
        pathStale = true;
        invalidate();
    }

    void setColor(int color) {
        line.setColor(color);
        fill.setColor(color & 0x55FFFFFF);
        baseline.setColor(color & 0x66FFFFFF);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth(), height = getHeight();
        canvas.drawLine(0, height - 0.5f, width, height - 0.5f, baseline);
        if (samples < 2 || width == 0 || height == 0) return;
        if (pathStale || width != pathWidth || height != pathHeight) {
            buildPaths(width, height);
            pathWidth = width;
            pathHeight = height;
            pathStale = false;
        }
        canvas.drawPath(area, fill);
        canvas.drawPath(path, line);
    }

    /** Builds the curve and the area under it, with the newest sample at the right edge. */
    private void buildPaths(int width, int height) {
        float step = width / (float) (SAMPLES - 1), inset = line.getStrokeWidth();
        float startX = width - (samples - 1) * step;
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
    }
}
