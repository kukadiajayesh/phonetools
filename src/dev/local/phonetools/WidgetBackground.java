package dev.local.phonetools;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.WindowManager;

/**
 * Fakes an iOS-style "liquid glass" widget background from the slice of the wallpaper behind the widget: a light
 * blur, edges that bend the wallpaper like a lens, and a light tint for the text. No bright rim: it read as a border.
 * The widget's place on screen is learned from the bounds the launcher reports when the widget is tapped.
 */
final class WidgetBackground {
    private static final float CORNER_DP = 22f;
    // Each blur pixel covers this many screen pixels; bigger means blurrier.
    private static final int BLUR_SCALE = 6;
    private static final int BLUR_RADIUS = 3;
    private static final float SATURATION = 1.25f;
    // Tint from top to bottom, as black alpha (0..1), so white text stays readable.
    private static final float TINT_TOP = 0.25f, TINT_BOTTOM = 0.35f;
    // Edge lens: how wide the bent band is, and how far it reaches out for wallpaper, in dp.
    private static final float LENS_DP = 26f, LENS_SHIFT_DP = 28f;
    // Edge dispersion: red bends this much more and blue this much less than green, splitting colours at the rim.
    private static final float DISPERSION = 0.12f;
    // With one home page, Trebuchet keeps the wallpaper at its left edge (mWallpaperX=0 in dumpsys window).
    private static final float WALLPAPER_X_OFFSET = 0f;

    private static String cachedKey;
    private static Bitmap cached;

    private WidgetBackground() {
    }

    static void saveBounds(Context context, Rect bounds) {
        prefs(context).edit().putString("bounds", bounds.flattenToString()).apply();
    }

    /** Returns the background bitmap, or null when the widget's place or the wallpaper is not known. */
    static synchronized Bitmap get(Context context) {
        Rect bounds = Rect.unflattenFromString(prefs(context).getString("bounds", ""));
        if (bounds == null || bounds.isEmpty()) return null;
        // Without storage access Android 14 (OxygenOS, at least) quietly returns its default wallpaper instead of the
        // real one; glass cut from that would show the wrong picture, so fall back to the plain background.
        if (context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        WallpaperManager wallpapers = WallpaperManager.getInstance(context);
        // A live wallpaper has no picture to cut glass from: getDrawable() then still returns the last still
        // wallpaper (or the default one), which is no longer on screen.
        if (wallpapers.getWallpaperInfo() != null) return null;
        String key = bounds.flattenToString() + "/" + wallpapers.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
        if (key.equals(cachedKey)) return cached;

        Bitmap wallpaper;
        try {
            Drawable drawable = wallpapers.getDrawable();
            if (!(drawable instanceof BitmapDrawable)) return null;
            wallpaper = ((BitmapDrawable) drawable).getBitmap();
        } catch (RuntimeException e) {
            return null;
        }
        cached = render(context, wallpaper, bounds);
        cachedKey = key;
        return cached;
    }

    private static Bitmap render(Context context, Bitmap wallpaper, Rect bounds) {
        float density = context.getResources().getDisplayMetrics().density;
        int margin = Math.round(LENS_SHIFT_DP * (1 + DISPERSION) * density) + BLUR_SCALE;
        Bitmap blurred = blurredSlice(context, wallpaper, bounds, margin);

        int w = bounds.width(), h = bounds.height();
        int[] src = new int[blurred.getWidth() * blurred.getHeight()];
        blurred.getPixels(src, 0, blurred.getWidth(), 0, 0, blurred.getWidth(), blurred.getHeight());
        int[] out = new int[w * h];
        float radius = CORNER_DP * density, lens = LENS_DP * density, shift = LENS_SHIFT_DP * density;
        float[] normal = new float[2];

        for (int y = 0; y < h; y++) {
            float tint = TINT_TOP + (TINT_BOTTOM - TINT_TOP) * y / (float) h;
            for (int x = 0; x < w; x++) {
                float d = insideDistance(x + 0.5f - w / 2f, y + 0.5f - h / 2f, w / 2f, h / 2f, radius, normal);
                float coverage = Math.min(1f, d + 0.5f);
                if (coverage <= 0f) continue;

                // Near the edge, look further out, as if through the curved rim of a lens.
                float t = d < lens ? 1f - d / lens : 0f;
                float pull = (float) Math.pow(t, 1.6) * shift;
                float sx = x + margin, sy = y + margin, nx = normal[0], ny = normal[1];
                float r, g, b;
                int green = sample(src, blurred.getWidth(), blurred.getHeight(),
                        (sx + nx * pull) / BLUR_SCALE, (sy + ny * pull) / BLUR_SCALE);
                g = (green >> 8) & 0xFF;
                if (pull > 0.5f) {
                    float redPull = pull * (1 + DISPERSION), bluePull = pull * (1 - DISPERSION);
                    r = (sample(src, blurred.getWidth(), blurred.getHeight(),
                            (sx + nx * redPull) / BLUR_SCALE, (sy + ny * redPull) / BLUR_SCALE) >> 16) & 0xFF;
                    b = sample(src, blurred.getWidth(), blurred.getHeight(),
                            (sx + nx * bluePull) / BLUR_SCALE, (sy + ny * bluePull) / BLUR_SCALE) & 0xFF;
                } else {
                    r = (green >> 16) & 0xFF;
                    b = green & 0xFF;
                }
                float grey = 0.299f * r + 0.587f * g + 0.114f * b;
                r = grey + (r - grey) * SATURATION;
                g = grey + (g - grey) * SATURATION;
                b = grey + (b - grey) * SATURATION;
                // The lens band is a little brighter, like light gathering in thick glass.
                float shade = (1f - tint) * (1f + 0.22f * t);
                r *= shade;
                g *= shade;
                b *= shade;

                out[y * w + x] = Math.round(coverage * 255) << 24 | clamp(r) << 16 | clamp(g) << 8 | clamp(b);
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
    }

    /** Draws the wallpaper behind the widget (plus a margin) at low resolution and blurs it. */
    private static Bitmap blurredSlice(Context context, Bitmap wallpaper, Rect bounds, int margin) {
        // Place the wallpaper the way ImageWallpaper does: scaled to cover the screen, scrolled by the x offset.
        Point screen = new Point();
        ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealSize(screen);
        float scale = Math.max(screen.x / (float) wallpaper.getWidth(), screen.y / (float) wallpaper.getHeight());
        float left = -WALLPAPER_X_OFFSET * (wallpaper.getWidth() * scale - screen.x);
        float top = (screen.y - wallpaper.getHeight() * scale) / 2f;

        int w = (bounds.width() + 2 * margin) / BLUR_SCALE + 1, h = (bounds.height() + 2 * margin) / BLUR_SCALE + 1;
        Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(small);
        canvas.scale(1f / BLUR_SCALE, 1f / BLUR_SCALE);
        canvas.translate(left - bounds.left + margin, top - bounds.top + margin);
        canvas.scale(scale, scale);
        // Past the screen edge the lens would see nothing, so stretch the wallpaper's edge pixels outward.
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setShader(new BitmapShader(wallpaper, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        canvas.drawPaint(paint);
        blur(small);
        return small;
    }

    /**
     * Returns how far (x, y) is inside a rounded rectangle centred on the origin (negative outside), and stores the
     * outward direction of the nearest edge in {@code normal}.
     */
    private static float insideDistance(float x, float y, float halfW, float halfH, float radius, float[] normal) {
        float qx = Math.abs(x) - (halfW - radius), qy = Math.abs(y) - (halfH - radius);
        float sx = Math.signum(x), sy = Math.signum(y);
        if (qx > 0 && qy > 0) {
            float len = (float) Math.hypot(qx, qy);
            normal[0] = sx * qx / len;
            normal[1] = sy * qy / len;
            return radius - len;
        }
        if (qx > qy) {
            normal[0] = sx;
            normal[1] = 0;
        } else {
            normal[0] = 0;
            normal[1] = sy;
        }
        return radius - Math.max(qx, qy);
    }

    /** Bilinear sample of an opaque pixel array, clamped to its edges. */
    private static int sample(int[] px, int w, int h, float x, float y) {
        x = Math.max(0, Math.min(w - 1.001f, x - 0.5f));
        y = Math.max(0, Math.min(h - 1.001f, y - 0.5f));
        int x0 = (int) x, y0 = (int) y;
        float fx = x - x0, fy = y - y0;
        int a = px[y0 * w + x0], b = px[y0 * w + x0 + 1], c = px[(y0 + 1) * w + x0], d = px[(y0 + 1) * w + x0 + 1];
        int result = 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            float top = ((a >> shift) & 0xFF) * (1 - fx) + ((b >> shift) & 0xFF) * fx;
            float bottom = ((c >> shift) & 0xFF) * (1 - fx) + ((d >> shift) & 0xFF) * fx;
            result |= Math.round(top * (1 - fy) + bottom * fy) << shift;
        }
        return result;
    }

    private static int clamp(float v) {
        return v < 0 ? 0 : v > 255 ? 255 : Math.round(v);
    }

    /** Two passes of a box blur, horizontal then vertical, on an opaque bitmap. */
    private static void blur(Bitmap bitmap) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int[] pixels = new int[w * h], temp = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 2; pass++) {
            boxBlur(pixels, temp, w, h, 1, w);
            boxBlur(temp, pixels, h, w, w, 1);
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
    }

    /** Blurs each line of {@code len} pixels ({@code step} apart; lines {@code lineStep} apart) from src into dst. */
    private static void boxBlur(int[] src, int[] dst, int len, int lines, int step, int lineStep) {
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep;
            for (int i = 0; i < len; i++) {
                int r = 0, g = 0, b = 0, n = 0;
                for (int k = Math.max(0, i - BLUR_RADIUS); k <= Math.min(len - 1, i + BLUR_RADIUS); k++) {
                    int c = src[base + k * step];
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                    n++;
                }
                dst[base + i * step] = 0xFF000000 | (r / n) << 16 | (g / n) << 8 | (b / n);
            }
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("widget", Context.MODE_PRIVATE);
    }
}
