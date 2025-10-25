package com.cloudwebrtc.webrtc;

import android.graphics.Bitmap;
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

                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, false);
                int size = targetW * targetH * 4;
                ByteBuffer buffer = ByteBuffer.allocateDirect(size);
                scaled.copyPixelsToBuffer(buffer);
                buffer.rewind();

                // Convert ByteBuffer to byte array for EventChannel
                byte[] bytes = new byte[size];
                buffer.get(bytes);

                Map<String, Object> map = new HashMap<>();
                map.put("bytes", bytes);
                map.put("width", targetW);
                map.put("height", targetH);
                map.put("ts_us", now / 1000);
                sink.success(map);
                
                // Clean up scaled bitmap
                if (scaled != bitmap) {
                    scaled.recycle();
                }
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

