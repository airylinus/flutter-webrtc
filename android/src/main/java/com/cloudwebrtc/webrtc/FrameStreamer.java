package com.cloudwebrtc.webrtc;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import org.webrtc.EglRenderer;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import io.flutter.plugin.common.EventChannel;

/**
 * Grabs RGBA frames from FlutterRTCVideoRenderer's EglRenderer and pushes via EventChannel.
 * This uses EglRenderer.addFrameListener to avoid touching track internals.
 */
class FrameStreamer {
    private final FlutterRTCVideoRenderer renderer;
    private final int targetW;
    private final int targetH;
    private final int fps;
    private final EventChannel.EventSink sink;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private long lastTs = 0;
    private EglRenderer.FrameListener frameListener;
    // Reusable buffers to avoid per-frame allocations
    private byte[] buf0, buf1;
    private ByteBuffer bb0, bb1;
    private int bufferSize = 0;
    private int ping = 0;

    // Reusable target bitmaps and canvases to eliminate per-frame createScaledBitmap
    private Bitmap target0, target1;
    private Canvas canvas0, canvas1;
    // Destination rect is calculated per-frame to preserve aspect ratio (letterbox)
    private final Rect srcRect = new Rect();

    private void ensureBuffers() {
        int needed = targetW * targetH * 4;
        if (bufferSize != needed || buf0 == null || buf1 == null || bb0 == null || bb1 == null) {
            bufferSize = needed;
            buf0 = new byte[needed];
            buf1 = new byte[needed];
            bb0 = ByteBuffer.wrap(buf0);
            bb1 = ByteBuffer.wrap(buf1);
        }
    }

    private void ensureTargets() {
        if (target0 == null || target0.getWidth() != targetW || target0.getHeight() != targetH) {
            target0 = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
            canvas0 = new Canvas(target0);
        }
        if (target1 == null || target1.getWidth() != targetW || target1.getHeight() != targetH) {
            target1 = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
            canvas1 = new Canvas(target1);
        }
    }


    FrameStreamer(FlutterRTCVideoRenderer renderer, int w, int h, int fps, EventChannel.EventSink sink) {
        this.renderer = renderer;
        this.targetW = w;
        this.targetH = h;
        this.fps = Math.max(1, fps);
        this.sink = sink;
    }

    void start() {
        running = true;
        // EglRenderer frame listener delivers ARGB_8888 Bitmap on UI thread
        frameListener = new EglRenderer.FrameListener() {
            @Override
            public void onFrame(@Nullable Bitmap bitmap) {
                if (!running || bitmap == null) return;
                long now = System.nanoTime();
                if (lastTs != 0) {
                    double intervalNs = 1_000_000_000.0 / fps;
                    if ((now - lastTs) < intervalNs) return; // FPS throttle
                }
                lastTs = now;

                // Draw into reusable target bitmap via Canvas to avoid per-frame allocations
                ensureTargets();
                ensureBuffers();
                Canvas canvas = (ping == 0) ? canvas0 : canvas1;
                Bitmap target = (ping == 0) ? target0 : target1;

                int srcW = bitmap.getWidth();
                int srcH = bitmap.getHeight();
                srcRect.set(0, 0, srcW, srcH);

                // Preserve aspect ratio: letterbox into targetW x targetH
                float scale = Math.min((float) targetW / srcW, (float) targetH / srcH);
                int drawW = Math.round(srcW * scale);
                int drawH = Math.round(srcH * scale);
                int padX = (targetW - drawW) / 2;
                int padY = (targetH - drawH) / 2;
                Rect dst = new Rect(padX, padY, padX + drawW, padY + drawH);
                canvas.drawBitmap(bitmap, srcRect, dst, null);

                ByteBuffer buffer = (ping == 0) ? bb0 : bb1;
                byte[] bytes = (ping == 0) ? buf0 : buf1;
                buffer.clear();
                target.copyPixelsToBuffer(buffer);
                buffer.rewind();
                ping ^= 1;

                Map<String, Object> map = new HashMap<>();
                map.put("bytes", bytes);
                map.put("width", targetW);
                map.put("height", targetH);
                map.put("srcW", srcW);
                map.put("srcH", srcH);
                map.put("ts_us", now / 1000);

                // EventChannel requires main thread
                mainHandler.post(() -> sink.success(map));
            }
        };
        renderer.getSurfaceTextureRenderer().addFrameListener(frameListener, 1.0f);
    }

    void stop() {
        running = false;
        if (frameListener != null && renderer != null && renderer.getSurfaceTextureRenderer() != null) {
            renderer.getSurfaceTextureRenderer().removeFrameListener(frameListener);
        }
    }
}

