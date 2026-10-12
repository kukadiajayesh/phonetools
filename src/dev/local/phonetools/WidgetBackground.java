package dev.local.phonetools;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.HardwareRenderer;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.view.WindowManager;

/**
 * Fakes an iOS-style "liquid glass" widget background from the slice of the wallpaper behind the widget: a light
 * blur, edges that bend it inward like the rim of a convex lens with colour fringes at the corners, a tint that
 * follows how bright the wallpaper is, and light catching the rim on the sides facing it. An even bright rim read as
 * a border, so the rim light fades out where the edge runs along the light. Android 13+ draws it with an AGSL shader
 * on the GPU; older Android (and any GPU failure) with a pixel loop that draws the same.
 * The widget's place on screen is learned from the bounds the launcher reports when the widget is tapped.
 */
final class WidgetBackground {
    private static final float CORNER_DP = 22f;
    // Each blur pixel covers this many screen pixels; bigger means blurrier.
    private static final int BLUR_SCALE = 6;
    private static final int BLUR_RADIUS = 3;
    private static final float SATURATION = 1.25f;
    // Tint at the top, as black alpha (0..1), over the darkest and the brightest wallpaper; the bottom gets
    // TINT_RAMP more. Bright wallpaper needs more so white text stays readable; dark wallpaper shows through more.
    private static final float TINT_DARK = 0.12f, TINT_BRIGHT = 0.42f, TINT_RAMP = 0.10f;
    // Edge lens: how wide the bent band is, and how far in it reaches for wallpaper at the very edge, in dp.
    private static final float LENS_DP = 26f, LENS_SHIFT_DP = 28f;
    // Edge directions come from a rounder rectangle (this many times the corner radius), so they turn smoothly
    // round the corners instead of snapping along the diagonal, which showed as a crease in the lens band.
    private static final float NORMAL_CORNER_SCALE = 1.5f;
    // Edge dispersion: red bends this much more and blue this much less than green, splitting colours. It is
    // strongest at the corners and fades to nothing at the middle of each side.
    private static final float DISPERSION = 0.12f;
    // Rim light: how wide the band is (dp), how bright at most (added white, 0..1), how quickly it fades as the edge
    // turns away from the light, and how bright the side facing away from it is, relative to the lit side.
    private static final float RIM_DP = 1.5f, RIM_ALPHA = 0.45f, RIM_FALLOFF = 1.5f, RIM_BACK = 0.45f;
    // Direction the light comes from: top left.
    private static final float LIGHT_X = -0.7071f, LIGHT_Y = -0.7071f;
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
        // The lens looks inward, so the margin only gives the blur real wallpaper to spread in from outside.
        int margin = 2 * BLUR_RADIUS * BLUR_SCALE;
        Bitmap blurred = blurredSlice(context, wallpaper, bounds, margin);

        int w = bounds.width(), h = bounds.height();
        int[] src = new int[blurred.getWidth() * blurred.getHeight()];
        blurred.getPixels(src, 0, blurred.getWidth(), 0, 0, blurred.getWidth(), blurred.getHeight());
        float brightness = meanBrightness(src, blurred.getWidth(), margin / BLUR_SCALE,
                (margin + w) / BLUR_SCALE, (margin + h) / BLUR_SCALE);
        float tintTop = TINT_DARK + (TINT_BRIGHT - TINT_DARK) * brightness;
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                return Agsl.render(blurred, margin, w, h, density, tintTop);
            } catch (RuntimeException e) {
                // No GPU to draw on right now (or a driver that rejects the shader): the pixel loop draws the same.
            }
        }

        int[] out = new int[w * h];
        float radius = CORNER_DP * density, lens = LENS_DP * density, shift = LENS_SHIFT_DP * density;
        float rim = RIM_DP * density;
        float halfW = w / 2f, halfH = h / 2f;
        float normalRadius = normalRadius(radius, lens, w, h);
        float[] normal = new float[2];

        for (int y = 0; y < h; y++) {
            float tint = tintTop + TINT_RAMP * (y + 0.5f) / h;
            for (int x = 0; x < w; x++) {
                float px = x + 0.5f - halfW, py = y + 0.5f - halfH;
                float d = insideDistance(px, py, halfW, halfH, radius);
                float coverage = Math.min(1f, d + 0.5f);
                if (coverage <= 0f) continue;
                edgeNormal(px, py, halfW, halfH, normalRadius, normal);

                // Near the edge, look further in, as through the curved rim of a convex lens. The profile is a
                // circular arc, so right at the edge the bending outruns the step and mirrors a sliver of wallpaper.
                float t = d < lens ? 1f - d / lens : 0f;
                float pull = (1f - (float) Math.sqrt(1f - t * t)) * shift;
                float sx = x + 0.5f + margin, sy = y + 0.5f + margin, nx = normal[0], ny = normal[1];
                float r, g, b;
                int green = sample(src, blurred.getWidth(), blurred.getHeight(),
                        (sx - nx * pull) / BLUR_SCALE, (sy - ny * pull) / BLUR_SCALE);
                g = (green >> 8) & 0xFF;
                float dispersion = DISPERSION * Math.abs(px * py) / (halfW * halfH);
                if (pull * dispersion > 0.25f) {
                    float redPull = pull * (1 + dispersion), bluePull = pull * (1 - dispersion);
                    r = (sample(src, blurred.getWidth(), blurred.getHeight(),
                            (sx - nx * redPull) / BLUR_SCALE, (sy - ny * redPull) / BLUR_SCALE) >> 16) & 0xFF;
                    b = sample(src, blurred.getWidth(), blurred.getHeight(),
                            (sx - nx * bluePull) / BLUR_SCALE, (sy - ny * bluePull) / BLUR_SCALE) & 0xFF;
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
                // Light caught by the rim, added on top: full where the edge faces the light, dimmer on the far
                // side, nothing where the edge runs along the light.
                if (d < rim) {
                    float facing = nx * LIGHT_X + ny * LIGHT_Y;
                    float band = 1f - d / rim;
                    float light = 255f * RIM_ALPHA * band * band * (float) Math.pow(Math.abs(facing), RIM_FALLOFF)
                            * (facing > 0 ? 1f : RIM_BACK);
                    r += light;
                    g += light;
                    b += light;
                }

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
        // Past the screen edge there is no wallpaper to blur in, so stretch its edge pixels outward.
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setShader(new BitmapShader(wallpaper, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        canvas.drawPaint(paint);
        blur(small);
        return small;
    }

    /**
     * Returns the corner radius of the rounder rectangle the edge directions come from: at least as round as the lens
     * band is wide, or the band would reach where the directions still snap.
     */
    private static float normalRadius(float radius, float lens, int w, int h) {
        return Math.min(Math.max(radius * NORMAL_CORNER_SCALE, lens), Math.min(w, h) / 2f);
    }

    /** Returns how far (x, y) is inside a rounded rectangle centred on the origin (negative outside). */
    private static float insideDistance(float x, float y, float halfW, float halfH, float radius) {
        float qx = Math.abs(x) - (halfW - radius), qy = Math.abs(y) - (halfH - radius);
        if (qx > 0 && qy > 0) return radius - (float) Math.hypot(qx, qy);
        return radius - Math.max(qx, qy);
    }

    /**
     * Stores in {@code normal} the outward direction of the nearest edge of a rounded rectangle centred on the
     * origin. It turns smoothly within {@code radius} of the edge; deeper inside it snaps between the sides.
     */
    private static void edgeNormal(float x, float y, float halfW, float halfH, float radius, float[] normal) {
        float qx = Math.abs(x) - (halfW - radius), qy = Math.abs(y) - (halfH - radius);
        float sx = Math.signum(x), sy = Math.signum(y);
        float cx = Math.max(qx, 0), cy = Math.max(qy, 0), len = (float) Math.hypot(cx, cy);
        if (len > 0) {
            normal[0] = sx * cx / len;
            normal[1] = sy * cy / len;
        } else if (qx > qy) {
            normal[0] = sx;
            normal[1] = 0;
        } else {
            normal[0] = 0;
            normal[1] = sy;
        }
    }

    /** Returns the mean brightness (0..1) of the pixels in [start, right) x [start, bottom) of an opaque pixel array. */
    private static float meanBrightness(int[] px, int w, int start, int right, int bottom) {
        double sum = 0;
        int n = 0;
        for (int y = start; y < bottom; y++) {
            for (int x = start; x < right; x++) {
                int c = px[y * w + x];
                sum += 0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF) + 0.114 * (c & 0xFF);
                n++;
            }
        }
        return n == 0 ? 0.5f : (float) (sum / n / 255);
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

    /**
     * The same glass as the pixel loop in {@link #render}, drawn by an AGSL shader (Android 13+). Kept in its own
     * class so older Android never loads RuntimeShader.
     */
    private static final class Agsl {
        private static final String SHADER = ""
                + "uniform shader wallpaper;\n"
                + "uniform float2 size;\n"
                + "uniform float radius;\n"
                + "uniform float normalRadius;\n"
                + "uniform float lens;\n"
                + "uniform float shift;\n"
                + "uniform float rim;\n"
                + "uniform float saturation;\n"
                + "uniform float dispersion;\n"
                + "uniform float tintTop;\n"
                + "uniform float tintRamp;\n"
                + "uniform float rimAlpha;\n"
                + "uniform float rimFalloff;\n"
                + "uniform float rimBack;\n"
                + "uniform float2 lightDir;\n"
                + "\n"
                + "float insideDistance(float2 p, float2 halfSize, float r) {\n"
                + "    float2 q = abs(p) - (halfSize - r);\n"
                + "    if (q.x > 0.0 && q.y > 0.0) return r - length(q);\n"
                + "    return r - max(q.x, q.y);\n"
                + "}\n"
                + "\n"
                + "float2 edgeNormal(float2 p, float2 halfSize, float r) {\n"
                + "    float2 q = abs(p) - (halfSize - r);\n"
                + "    float2 c = max(q, 0.0);\n"
                + "    float len = length(c);\n"
                + "    if (len > 0.0) return sign(p) * c / len;\n"
                + "    return q.x > q.y ? float2(sign(p.x), 0.0) : float2(0.0, sign(p.y));\n"
                + "}\n"
                + "\n"
                + "half4 main(float2 coord) {\n"
                + "    float2 halfSize = size * 0.5;\n"
                + "    float2 p = coord - halfSize;\n"
                + "    float d = insideDistance(p, halfSize, radius);\n"
                + "    float coverage = min(1.0, d + 0.5);\n"
                + "    if (coverage <= 0.0) return half4(0.0);\n"
                + "    float2 n = edgeNormal(p, halfSize, normalRadius);\n"
                + "\n"
                + "    float t = d < lens ? 1.0 - d / lens : 0.0;\n"
                + "    float2 pull = n * (1.0 - sqrt(1.0 - t * t)) * shift;\n"
                + "    float split = dispersion * abs(p.x * p.y) / (halfSize.x * halfSize.y);\n"
                + "    float3 c = float3(wallpaper.eval(coord - pull * (1.0 + split)).r,\n"
                + "                      wallpaper.eval(coord - pull).g,\n"
                + "                      wallpaper.eval(coord - pull * (1.0 - split)).b);\n"
                + "    float grey = dot(c, float3(0.299, 0.587, 0.114));\n"
                + "    c = grey + (c - grey) * saturation;\n"
                + "    float tint = tintTop + tintRamp * coord.y / size.y;\n"
                + "    c *= (1.0 - tint) * (1.0 + 0.22 * t);\n"
                + "    if (d < rim) {\n"
                + "        float facing = dot(n, lightDir);\n"
                + "        float band = 1.0 - d / rim;\n"
                + "        c += rimAlpha * band * band * pow(abs(facing), rimFalloff)\n"
                + "                * (facing > 0.0 ? 1.0 : rimBack);\n"
                + "    }\n"
                + "    return half4(clamp(c, 0.0, 1.0) * coverage, coverage);\n"
                + "}\n";

        static Bitmap render(Bitmap blurred, int margin, int w, int h, float density, float tintTop) {
            // Lay the small blurred slice over the widget: each of its pixels covers BLUR_SCALE screen pixels, and
            // it starts margin pixels above and left of the widget.
            BitmapShader wallpaper = new BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            wallpaper.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            Matrix matrix = new Matrix();
            matrix.setScale(BLUR_SCALE, BLUR_SCALE);
            matrix.postTranslate(-margin, -margin);
            wallpaper.setLocalMatrix(matrix);

            float radius = CORNER_DP * density, lens = LENS_DP * density;
            RuntimeShader shader = new RuntimeShader(SHADER);
            shader.setInputShader("wallpaper", wallpaper);
            shader.setFloatUniform("size", w, h);
            shader.setFloatUniform("radius", radius);
            shader.setFloatUniform("normalRadius", normalRadius(radius, lens, w, h));
            shader.setFloatUniform("lens", lens);
            shader.setFloatUniform("shift", LENS_SHIFT_DP * density);
            shader.setFloatUniform("rim", RIM_DP * density);
            shader.setFloatUniform("saturation", SATURATION);
            shader.setFloatUniform("dispersion", DISPERSION);
            shader.setFloatUniform("tintTop", tintTop);
            shader.setFloatUniform("tintRamp", TINT_RAMP);
            shader.setFloatUniform("rimAlpha", RIM_ALPHA);
            shader.setFloatUniform("rimFalloff", RIM_FALLOFF);
            shader.setFloatUniform("rimBack", RIM_BACK);
            shader.setFloatUniform("lightDir", LIGHT_X, LIGHT_Y);

            Paint paint = new Paint();
            paint.setShader(shader);
            return drawOnGpu(paint, w, h);
        }

        /**
         * Fills a w x h bitmap with the paint on the GPU (a Bitmap's own canvas is software, which refuses
         * RuntimeShader), and copies it back into an ordinary bitmap that RemoteViews can carry.
         */
        private static Bitmap drawOnGpu(Paint paint, int w, int h) {
            ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
            HardwareRenderer renderer = new HardwareRenderer();
            try {
                renderer.setOpaque(false);
                renderer.setSurface(reader.getSurface());
                RenderNode node = new RenderNode("glass");
                node.setPosition(0, 0, w, h);
                Canvas canvas = node.beginRecording();
                canvas.drawPaint(paint);
                node.endRecording();
                renderer.setContentRoot(node);
                renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
                try (Image image = reader.acquireNextImage()) {
                    if (image == null) throw new IllegalStateException("GPU drew no frame");
                    try (HardwareBuffer buffer = image.getHardwareBuffer()) {
                        Bitmap gpu = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB));
                        if (gpu == null) throw new IllegalStateException("GPU frame is not a bitmap");
                        return gpu.copy(Bitmap.Config.ARGB_8888, false);
                    }
                }
            } finally {
                renderer.destroy();
                reader.close();
            }
        }
    }
}
