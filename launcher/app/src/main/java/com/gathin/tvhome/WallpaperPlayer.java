package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays local or URL-backed wallpapers only while Home is visible. */
final class WallpaperPlayer {
    private final FrameLayout root;
    private ImageView image;
    private final List<File> files;
    private final long interval;
    private final long dynamicInterval;
    private final long dynamicCrossfade;
    private final String dynamicUrl;
    private final String order;
    private final SharedPreferences preferences;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    /** wallpaper_dim as an ARGB black, painted into each picture once instead of a full-screen shade view. */
    private final int dimColor;
    /** The picture on screen, released once the next one has been drawn. */
    private Bitmap current;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private boolean transitioning;
    private ImageView incoming;
    private Bitmap incomingBitmap;
    private String displayed = "";
    /** wallpaper_captions: the shown picture's name from wallpapers/captions.txt; null when off or there are none. */
    private final TextView caption;
    private final Map<String, String> names;
    private final Runnable refreshDynamic = new Runnable() {
        @Override
        public void run() {
            if (!active || closed || dynamicUrl.isEmpty()) return;
            update();
            handler.postDelayed(this, dynamicInterval);
        }
    };

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        files = new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationMinutes * 60_000L;
        dynamicUrl = config.dynamicWallpaper ? config.dynamicWallpaperUrl : "";
        dynamicInterval = config.dynamicWallpaperIntervalSeconds * 1000L;
        dynamicCrossfade = config.dynamicWallpaperCrossfadeSeconds * 1000L;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        // The dim is painted into the picture (decode), so no full-screen shade layer is drawn on every frame.
        dimColor = config.wallpaperDim > 0 ? (config.wallpaperDim * 255 / 100) << 24 : 0;
        order = config.wallpaperOrder;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        if (config.dynamicWallpaper
                && !dynamicUrl.equals(preferences.getString("remote_url", ""))) {
            preferences.edit().putString("remote_url", dynamicUrl).remove("remote_changed").apply();
        }
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        names = config.wallpaperCaptions && config.wallpaperEnabled && !files.isEmpty()
                ? WallpaperCaptions.read(files.get(0).getParentFile()) : Collections.emptyMap();
        caption = names.isEmpty() ? null : makeCaption(context, root);
        if (config.wallpaperEnabled && !files.isEmpty()) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            showFirst();
        }
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        File first = WallpaperRotation.find(files, previous);
        if (first == null) first = WallpaperRotation.pick(files, previous, order, random);
        while (first != null) {
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                show(bitmap);
                // The picture covers the whole screen: the root's plain colour under it is overdraw.
                root.setBackground(null);
                displayed = first.getName();
                showCaption(false);
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            first = WallpaperRotation.pick(files, previous, order, random);
        }
    }

    void start() {
        active = true;
        update();
        if (!dynamicUrl.isEmpty()) handler.postDelayed(refreshDynamic, dynamicInterval);
    }

    void stop() {
        active = false;
        handler.removeCallbacks(refreshDynamic);
        finishTransition();
    }

    void close() {
        stop();
        closed = true;
        decoder.shutdownNow();
    }

    void update() {
        if (!active || closed || loading || transitioning) return;
        if (!dynamicUrl.isEmpty()) {
            updateDynamic();
            return;
        }
        if (files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        File selected = WallpaperRotation.find(files, previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(files, previous, order, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                show(bitmap);
                displayed = next.getName();
                showCaption(true);
                if (!displayed.equals(previous) || changed == 0) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
            });
        });
    }

    private void updateDynamic() {
        long changed = preferences.getLong("remote_changed", 0);
        if (!WallpaperRotation.due(changed, System.currentTimeMillis(), dynamicInterval)) return;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = null;
            try {
                byte[] bytes = download(dynamicUrl);
                bitmap = decode(bytes);
                if (bitmap == null) throw new IOException("Response is not a supported wallpaper image");
            } catch (IOException | RuntimeException failure) {
                Log.w("TvHome.Wallpaper", "Remote wallpaper request failed", failure);
            } catch (OutOfMemoryError failure) {
                Log.e("TvHome.Wallpaper", "Not enough memory to download the wallpaper image", failure);
            }
            Bitmap result = bitmap;
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (result != null && !result.isRecycled()) result.recycle();
                    return;
                }
                if (result == null) return;
                preferences.edit().putLong("remote_changed", System.currentTimeMillis()).apply();
                displayed = "";
                showCaption(false);
                showRemote(result);
                root.setBackground(null);
            });
        });
    }

    private static byte[] download(String address) throws IOException {
        URL url = new URL(address);
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (WallpaperSettings.validUrl(url.toString()) == null) {
                throw new IOException("Wallpaper redirect has an unsupported URL");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(10_000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "image/*, application/octet-stream");
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("Pragma", "no-cache");
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirects == 5) {
                        throw new IOException("Too many or invalid wallpaper redirects");
                    }
                    url = new URL(url, location);
                    continue;
                }
                if (status < 200 || status >= 300) {
                    throw new IOException("Wallpaper server returned HTTP " + status);
                }
                String contentType = connection.getContentType();
                String mediaType = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
                if (contentType != null && !mediaType.startsWith("image/")
                        && !mediaType.startsWith("application/octet-stream")) {
                    throw new IOException("Wallpaper server returned a non-image response");
                }
                int length = connection.getContentLength();
                if (length > WallpaperSettings.MAX_IMAGE_BYTES) {
                    throw new IOException("Wallpaper response exceeds the 10 MB limit");
                }
                try (InputStream input = connection.getInputStream();
                     ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(0, length))) {
                    byte[] buffer = new byte[16 * 1024];
                    int total = 0;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > WallpaperSettings.MAX_IMAGE_BYTES) {
                            throw new IOException("Wallpaper response exceeds the 10 MB limit");
                        }
                        output.write(buffer, 0, count);
                    }
                    return output.toByteArray();
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many wallpaper redirects");
    }

    private Bitmap decode(byte[] data) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            int sourceWidth = options.outWidth, sourceHeight = options.outHeight;
            if (sourceWidth <= 0 || sourceHeight <= 0) return null;
            int sample = WallpaperSize.sampleSize(sourceWidth, sourceHeight, width, height);
            if (!WallpaperSize.accepted(sourceWidth / sample, sourceHeight / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(sourceWidth / sample, sourceHeight / sample, width, height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = bitmapConfig;
            options.inMutable = dimColor != 0;
            Bitmap decoded = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (decoded == null) return null;
            if (dimColor != 0) new Canvas(decoded).drawColor(dimColor);
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return decoded;
            decoded.recycle();
            return gpu;
        } catch (Exception | OutOfMemoryError failure) {
            Log.e("TvHome.Wallpaper", "Could not decode remote wallpaper image", failure);
            return null;
        }
    }

    private void showRemote(Bitmap bitmap) {
        if (current == null) {
            if (image.getParent() == null) {
                root.addView(image, 0, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            current = bitmap;
            image.setImageBitmap(bitmap);
            image.setAlpha(1f);
            return;
        }
        ImageView next = new ImageView(image.getContext());
        next.setScaleType(ImageView.ScaleType.CENTER_CROP);
        next.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        next.setImageBitmap(bitmap);
        next.setAlpha(0f);
        root.addView(next, Math.min(1, root.getChildCount()), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        incoming = next;
        incomingBitmap = bitmap;
        transitioning = true;
        next.animate().alpha(1f).setDuration(dynamicCrossfade).withEndAction(this::finishTransition).start();
        image.animate().alpha(0f).setDuration(dynamicCrossfade).start();
    }

    private void finishTransition() {
        if (!transitioning || incoming == null) return;
        ImageView oldImage = image;
        Bitmap oldBitmap = current;
        ImageView nextImage = incoming;
        Bitmap nextBitmap = incomingBitmap;
        incoming = null;
        incomingBitmap = null;
        transitioning = false;
        nextImage.animate().cancel();
        oldImage.animate().cancel();
        root.removeView(oldImage);
        image = nextImage;
        image.setAlpha(1f);
        current = nextBitmap;
        if (oldBitmap != null && oldBitmap != current) root.postDelayed(() -> {
            if (!oldBitmap.isRecycled()) oldBitmap.recycle();
        }, 500);
    }

    /**
     * Small light text under the top-right buttons, above everything else and never focusable. It sits where the
     * top bar ends in every layout, so it does not cover the clock, the stats line or the tiles.
     */
    private static TextView makeCaption(Context context, FrameLayout root) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(0xD9FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(520 * dp));
        text.setFocusable(false);
        text.setAlpha(0f);
        try {
            text.setTypeface(context.getResources().getFont(R.font.outfit_regular));
        } catch (Exception ignored) {
            // the system font is fine
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(110 * dp);
        params.setMarginEnd(Math.round(62 * dp));
        root.addView(text, params);
        return text;
    }

    /** The displayed picture's name, faded across a change; a picture without a name hides the caption. */
    private void showCaption(boolean fade) {
        if (caption == null) return;
        String name = names.get(displayed);
        caption.animate().cancel();
        if (name == null) {
            caption.setAlpha(0f);
            return;
        }
        caption.setContentDescription(name);
        if (!fade || caption.getAlpha() == 0f) {
            caption.setText(name);
            caption.setAlpha(1f);
            return;
        }
        caption.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            caption.setText(name);
            caption.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    /** Puts a picture on screen and releases the previous one after a frame has drawn the new one. */
    private void show(Bitmap bitmap) {
        Bitmap old = current;
        current = bitmap;
        image.setImageBitmap(bitmap);
        if (old != null && old != bitmap) image.postDelayed(old::recycle, 500);
    }

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = bitmapConfig;
            options.inMutable = dimColor != 0;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            if (dimColor != 0) new Canvas(decoded).drawColor(dimColor);
            // One copy in GPU memory instead of a software bitmap plus its texture; the decode stays off the UI
            // thread for rotations, and Home's return no longer uploads 4-8 MB to the GPU.
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return decoded;
            decoded.recycle();
            return gpu;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
