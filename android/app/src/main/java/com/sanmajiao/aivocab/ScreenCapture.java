package com.sanmajiao.aivocab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/** Keeps the projection alive without OCR, bitmap conversion, or network work between taps. */
final class ScreenCapture implements AutoCloseable {
    interface Callback {
        void onFrame(Bitmap bitmap);
        void onError(String message);
    }

    private final HandlerThread thread = new HandlerThread("manual-screen-capture");
    private final Handler worker;
    private final Handler main;
    private final int density;
    private ImageReader reader;
    private VirtualDisplay display;
    private int width;
    private int height;
    private volatile boolean closed;
    private Pending pending;

    private static final class Pending {
        final Callback callback;
        final long afterNanos;
        Bitmap candidate;
        boolean settled;
        Pending(Callback callback) {
            this.callback = callback;
            // Virtual-display Image timestamps use the monotonic nanosecond timebase.
            this.afterNanos = System.nanoTime();
        }
    }

    ScreenCapture(Context context, MediaProjection projection, Handler main) {
        this.main = main;
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            Rect bounds = wm.getMaximumWindowMetrics().getBounds();
            width = bounds.width();
            height = bounds.height();
        } else {
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(metrics);
            width = metrics.widthPixels;
            height = metrics.heightPixels;
        }
        density = context.getResources().getConfiguration().densityDpi;
        thread.start();
        worker = new Handler(thread.getLooper());
        reader = newReader(width, height);
        try {
            // Exactly one virtual display for this user-consent token. Resizes reuse it.
            display = projection.createVirtualDisplay("AI词汇助手", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, worker);
        } catch (RuntimeException error) {
            reader.close();
            thread.quitSafely();
            throw error;
        }
    }

    private ImageReader newReader(int w, int h) {
        ImageReader result = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        result.setOnImageAvailableListener(this::onImage, worker);
        return result;
    }

    /** Call immediately AFTER hiding every overlay window. Only this operation creates bitmaps. */
    void requestFresh(Callback callback) {
        Pending request = new Pending(callback);
        worker.post(() -> {
            if (closed) {
                main.post(() -> callback.onError("录屏已停止，请回到设置重新授权。"));
                return;
            }
            clearPending();
            pending = request;
            try {
                // A selected app may be completely static and overlay removal happens outside
                // its captured area. A new output surface requests its current composition;
                // it reuses this consent's one VirtualDisplay, never a second projection/VD.
                replaceReader(width, height, false);
            } catch (RuntimeException | OutOfMemoryError error) {
                clearPending();
                main.post(() -> callback.onError("无法取得当前画面，请重新授权屏幕共享。"));
                return;
            }
            // Retain a fresh frame during this manual request. Static pages may deliver only
            // one frame when the overlay disappears, so waiting first would miss that frame.
            worker.postDelayed(() -> {
                if (pending != request || closed) return;
                request.settled = true;
                deliverCandidate(request);
            }, 180);
            worker.postDelayed(() -> {
                if (pending != request || closed) return;
                clearPending();
                main.post(() -> callback.onError("未取得新的屏幕画面。请确认目标页面可见；受保护页面无法截图。"));
            }, 2500);
        });
    }

    private void onImage(ImageReader source) {
        // A queued notification from a replaced/closed surface must not fail the new request.
        if (closed || source != reader) return;
        Image image = null;
        try {
            image = source.acquireLatestImage();
            if (image == null || closed) return;
            Pending request = pending;
            if (request == null || image.getTimestamp() < request.afterNanos) return;
            Bitmap frame = copyImage(image);
            if (request.candidate != null) request.candidate.recycle();
            request.candidate = frame;
            if (request.settled) deliverCandidate(request);
        } catch (RuntimeException | OutOfMemoryError error) {
            Pending request = pending;
            if (request != null) {
                clearPending();
                main.post(() -> request.callback.onError("截图读取失败，请重试或重新授权录屏。"));
            }
        } finally {
            if (image != null) image.close();
        }
    }

    private static Bitmap copyImage(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        int pixelStride = plane.getPixelStride();
        if (pixelStride != 4) throw new IllegalStateException("Unsupported capture pixel layout");
        ByteBuffer buffer = plane.getBuffer();
        buffer.rewind();
        int rowBytes = image.getWidth() * pixelStride;
        Bitmap bitmap = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
        if (plane.getRowStride() == rowBytes) {
            bitmap.copyPixelsFromBuffer(buffer);
        } else {
            // Some devices omit the final row's trailing padding from the plane buffer.
            // Copy only visible bytes rather than assuming rowStride * height is readable.
            ByteBuffer packed = ByteBuffer.allocateDirect(rowBytes * image.getHeight());
            ByteBuffer source = buffer.duplicate();
            for (int row = 0; row < image.getHeight(); row++) {
                int start = row * plane.getRowStride();
                source.limit(buffer.limit());
                source.position(start);
                source.limit(start + rowBytes);
                packed.put(source);
            }
            packed.rewind();
            bitmap.copyPixelsFromBuffer(packed);
        }
        return bitmap;
    }

    private void deliverCandidate(Pending request) {
        if (pending != request || request.candidate == null) return;
        Bitmap bitmap = request.candidate;
        request.candidate = null;
        pending = null;
        main.post(() -> {
            if (closed) bitmap.recycle();
            else request.callback.onFrame(bitmap);
        });
    }

    void resize(int w, int h) {
        if (w <= 0 || h <= 0 || w > 8192 || h > 8192) return;
        worker.post(() -> {
            if (closed || (width == w && height == h)) return;
            try {
                replaceReader(w, h, true);
                width = w;
                height = h;
                if (pending != null && pending.candidate != null) {
                    pending.candidate.recycle();
                    pending.candidate = null;
                }
            } catch (RuntimeException error) {
                Pending request = pending;
                clearPending();
                if (request != null) main.post(() -> request.callback.onError("屏幕尺寸变化后截图失败，请重新授权。"));
            }
        });
    }

    private void replaceReader(int w, int h, boolean resizeDisplay) {
        ImageReader next = newReader(w, h);
        try {
            if (resizeDisplay) display.resize(w, h, density);
            display.setSurface(next.getSurface());
            ImageReader old = reader;
            reader = next;
            old.close();
        } catch (RuntimeException error) {
            next.close();
            throw error;
        }
    }

    void cancelRequest() {
        worker.post(this::clearPending);
    }

    private void clearPending() {
        if (pending != null && pending.candidate != null) pending.candidate.recycle();
        pending = null;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        worker.post(() -> {
            clearPending();
            if (display != null) display.release();
            if (reader != null) reader.close();
            thread.quitSafely();
        });
    }
}
