package com.cloudwebrtc.webrtc;

import android.util.Log;

import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;
import org.webrtc.VideoTrack;
import org.webrtc.VideoFrame.Buffer;
import org.webrtc.VideoFrame.I420Buffer;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import io.flutter.plugin.common.EventChannel;

/**
 * Track-level frame streamer: attach to remote VideoTrack, crop+scale to target, convert I420->RGB,
 * and push frames via EventChannel. Avoids GPU readback & UI render dependency.
 */
class TrackFrameStreamer implements VideoSink {
  private final VideoTrack track;
  private final int targetW;
  private final int targetH;
  private final int fps;
  private final EventChannel.EventSink sink;
  private volatile boolean running = false;
  private long lastNs = 0L;

  TrackFrameStreamer(VideoTrack track, int w, int h, int fps, EventChannel.EventSink sink) {
    this.track = track;
    this.targetW = w;
    this.targetH = h;
    this.fps = Math.max(1, fps);
    this.sink = sink;
  }

  public void start() {
    if (running) return;
    running = true;
    track.addSink(this);
    Log.i(FlutterWebRTCPlugin.TAG, "[TrackFrameStreamer] started, target=" + targetW + "x" + targetH + " @" + fps + "fps");
  }

  public void stop() {
    running = false;
    try { track.removeSink(this); } catch (Throwable t) { /* ignore */ }
    Log.i(FlutterWebRTCPlugin.TAG, "[TrackFrameStreamer] stopped");
  }

  @Override
  public void onFrame(VideoFrame frame) {
    if (!running) return;

    long now = System.nanoTime();
    if (lastNs != 0) {
      double minInterval = 1_000_000_000.0 / fps;
      if ((now - lastNs) < minInterval) return; // throttle
    }
    lastNs = now;

    final Buffer buffer = frame.getBuffer();
    try {
      int srcW = buffer.getWidth();
      int srcH = buffer.getHeight();

      // 新策略：摄像端已设置为最小边 640，训练端只需裁剪中间 640x640
      // 无需缩放，直接裁剪，节省计算资源
      int cropX = (srcW - targetW) / 2;
      int cropY = (srcH - targetH) / 2;

      // 确保裁剪区域不超出边界
      if (cropX < 0) cropX = 0;
      if (cropY < 0) cropY = 0;
      if (cropX + targetW > srcW) cropX = srcW - targetW;
      if (cropY + targetH > srcH) cropY = srcH - targetH;

      // 直接裁剪中间区域（无缩放）
      Buffer cropped = buffer.cropAndScale(cropX, cropY, targetW, targetH, targetW, targetH);
      I420Buffer out = cropped.toI420();

      try {
        // Convert I420 -> RGB (uint8)
        int outSize = targetW * targetH * 3;
        byte[] rgb = new byte[outSize];
        i420ToRgb(out, rgb);

        Map<String, Object> map = new HashMap<>();
        map.put("bytes", rgb);
        map.put("width", targetW);
        map.put("height", targetH);
        map.put("ts_us", now / 1000);
        map.put("format", "RGB");
        // Optionally, provide source dimensions for inverse mapping on Dart side
        map.put("srcW", srcW);
        map.put("srcH", srcH);
        // Provide frame rotation (degrees clockwise: 0/90/180/270) for overlay alignment
        map.put("rotation", frame.getRotation());
        sink.success(map);
      } finally {
        out.release();
        cropped.release();
      }
    } catch (Throwable t) {
      Log.e(FlutterWebRTCPlugin.TAG, "[TrackFrameStreamer] onFrame error: " + t.getMessage());
    }
  }

  // Simple I420 (YUV420 planar) to RGB conversion
  private static void i420ToRgb(I420Buffer src, byte[] dst) {
    final ByteBuffer yBuf = src.getDataY();
    final ByteBuffer uBuf = src.getDataU();
    final ByteBuffer vBuf = src.getDataV();
    final int yStride = src.getStrideY();
    final int uStride = src.getStrideU();
    final int vStride = src.getStrideV();
    final int w = src.getWidth();
    final int h = src.getHeight();

    int idx = 0;
    for (int j = 0; j < h; j++) {
      for (int i = 0; i < w; i++) {
        int y = yBuf.get(j * yStride + i) & 0xFF;
        int u = uBuf.get((j / 2) * uStride + (i / 2)) & 0xFF;
        int v = vBuf.get((j / 2) * vStride + (i / 2)) & 0xFF;

        int c = y - 16; if (c < 0) c = 0;
        int d = u - 128;
        int e = v - 128;

        int r = (298 * c + 409 * e + 128) >> 8;
        int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
        int b = (298 * c + 516 * d + 128) >> 8;

        if (r < 0) r = 0; else if (r > 255) r = 255;
        if (g < 0) g = 0; else if (g > 255) g = 255;
        if (b < 0) b = 0; else if (b > 255) b = 255;

        dst[idx++] = (byte) r;
        dst[idx++] = (byte) g;
        dst[idx++] = (byte) b;
      }
    }
  }
}

