package com.cloudwebrtc.webrtc;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
    private boolean processing = false; // guard to avoid piling up when UI is busy
    private long lastTs = 0;
    private EglRenderer.FrameListener frameListener;
    private final float scaleHint; // downscale at source to reduce load
    private boolean listenerAttached = false;
    private Runnable retryTask = null;
    private int retryCount = 0;
    // Reusable buffers to avoid per-frame allocations
    private byte[] buf0, buf1;
    // RGB buffers (3 bytes per pixel) to avoid sending alpha channel over EventChannel
    private byte[] rgb0, rgb1;

    private ByteBuffer bb0, bb1;
    private int bufferSize = 0;
    private int ping = 0;

    // Reusable target bitmaps and canvases to eliminate per-frame createScaledBitmap
    private Bitmap target0, target1;
    private Canvas canvas0, canvas1;
    // Destination rect is calculated per-frame to preserve aspect ratio (letterbox)
    private final Rect srcRect = new Rect();

    private void ensureBuffers() {
        int neededRgba = targetW * targetH * 4;
        int neededRgb = targetW * targetH * 3;
        if (bufferSize != neededRgba || buf0 == null || buf1 == null || bb0 == null || bb1 == null || rgb0 == null || rgb1 == null) {
            bufferSize = neededRgba;
            buf0 = new byte[neededRgba];
            buf1 = new byte[neededRgba];
            bb0 = ByteBuffer.wrap(buf0);
            bb1 = ByteBuffer.wrap(buf1);
            rgb0 = new byte[neededRgb];
            rgb1 = new byte[neededRgb];
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
        // Heuristic: downscale source bitmap before our own letterbox to reduce CPU/GPU load.
        // Typical camera frames are >= 720p; for 640x640 target, 0.66 is sufficient.
        this.scaleHint = Math.min(1.0f, (float) Math.max(this.targetW, this.targetH) / 960.0f);
    }

    void start() {
        running = true;
        // EglRenderer frame listener delivers ARGB_8888 Bitmap on UI thread
        frameListener = new EglRenderer.FrameListener() {
            @Override
            public void onFrame(@Nullable Bitmap bitmap) {
                if (!running || bitmap == null) return;
                Log.d(FlutterWebRTCPlugin.TAG, "[FrameStreamer] onFrame: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                if (processing) return; // drop if previous frame still processing

                long now = System.nanoTime();
                if (lastTs != 0) {
                    double intervalNs = 1_000_000_000.0 / fps;
                    if ((now - lastTs) < intervalNs) return; // FPS throttle
                }
                lastTs = now;

                processing = true;
                try {
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
                    byte[] rgba = (ping == 0) ? buf0 : buf1;
                    byte[] rgb = (ping == 0) ? rgb0 : rgb1;
                    buffer.clear();
                    target.copyPixelsToBuffer(buffer);
                    buffer.rewind();

                    // Convert RGBA (we use R,G,B,A order here) to RGB to save 25% bandwidth
                    int n = targetW * targetH;
                    int j = 0;
                    for (int i = 0, p = 0; i < n; i++, p += 4) {
                        rgb[j++] = rgba[p];     // R
                        rgb[j++] = rgba[p + 1]; // G
                        rgb[j++] = rgba[p + 2]; // B
                    }

                    // CRITICAL: Copy rgb array BEFORE ping-pong switch to avoid data race
                    // The mainHandler.post() is async, so by the time it executes, the next frame
                    // may have already overwritten the rgb buffer.
                    byte[] rgbCopy = new byte[rgb.length];
                    System.arraycopy(rgb, 0, rgbCopy, 0, rgb.length);

                    final int finalSrcW = srcW;
                    final int finalSrcH = srcH;
                    final long finalTs = now;

                    ping ^= 1;

                    // EventChannel requires main thread, and onFrame is called on EglThread
                    mainHandler.post(() -> {
                        if (sink != null) {
                            Map<String, Object> map = new HashMap<>();
                            map.put("bytes", rgbCopy);
                            map.put("width", targetW);
                            map.put("height", targetH);
                            map.put("srcW", finalSrcW);
                            map.put("srcH", finalSrcH);
                            map.put("ts_us", finalTs / 1000);
                            map.put("format", "RGB");
                            sink.success(map);
                            Log.d(FlutterWebRTCPlugin.TAG, "[FrameStreamer] Frame sent via EventChannel: " + targetW + "x" + targetH);
                        } else {
                            Log.w(FlutterWebRTCPlugin.TAG, "[FrameStreamer] sink is null, frame dropped");
                        }
                    });
                } finally {
                    processing = false;
                }

                // CRITICAL: Re-attach for continuous frames (addFrameListener is one-shot).
                // Must be done AFTER onFrame callback completes, so post to next message loop.
                // Cannot call addFrameListener directly inside onFrame callback due to internal locks.
                listenerAttached = false;
                mainHandler.post(() -> {
                    if (running && !listenerAttached) {
                        listenerAttached = true;
                        Log.d(FlutterWebRTCPlugin.TAG, "[FrameStreamer] Re-attaching listener for continuous frames");
                        renderer.getSurfaceTextureRenderer().addFrameListener(frameListener, scaleHint);
                    }
                });
            }
        };

        // Attach listener only after first frame is rendered to avoid dead starts
        if (renderer.getSurfaceTextureRenderer().hasFirstFrame()) {
            Log.i(FlutterWebRTCPlugin.TAG, "[FrameStreamer] First frame already rendered; attaching listener now. target=" + targetW + "x" + targetH + ", fps=" + fps + ", scaleHint=" + scaleHint);
            attachListenerIfNeeded();
        } else {
            Log.i(FlutterWebRTCPlugin.TAG, "[FrameStreamer] Waiting for first frame to attach listener...");
            renderer.getSurfaceTextureRenderer().setFirstFrameCallback(this::safeAttach);
            scheduleRetryAttach();
        }
    }

    void stop() {
        running = false;
        if (retryTask != null) {
            mainHandler.removeCallbacks(retryTask);
            retryTask = null;
        }
        if (frameListener != null && renderer != null && renderer.getSurfaceTextureRenderer() != null) {
            renderer.getSurfaceTextureRenderer().removeFrameListener(frameListener);
            renderer.getSurfaceTextureRenderer().clearFirstFrameCallback();
        }
        listenerAttached = false;
    }

    private void attachListenerIfNeeded() {
        if (listenerAttached || !running) return;
        listenerAttached = true;
        Log.i(FlutterWebRTCPlugin.TAG, "[FrameStreamer] addFrameListener: target=" + targetW + "x" + targetH + ", fps=" + fps + ", scaleHint=" + scaleHint);
        renderer.getSurfaceTextureRenderer().addFrameListener(frameListener, scaleHint);
    }

    private void safeAttach() {
        mainHandler.post(this::attachListenerIfNeeded);
    }

    private void scheduleRetryAttach() {
        retryCount = 0;
        retryTask = new Runnable() {
            @Override
            public void run() {
                if (!running || listenerAttached) return;
                retryCount++;
                Log.w(FlutterWebRTCPlugin.TAG, "[FrameStreamer] Retry attach attempt #" + retryCount);
                safeAttach();
                if (retryCount < 10 && !listenerAttached && running) {
                    mainHandler.postDelayed(this, 300);
                }
            }
        };
        mainHandler.postDelayed(retryTask, 300);
    }
}

