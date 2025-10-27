package com.cloudwebrtc.webrtc;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.cloudwebrtc.webrtc.audio.AudioProcessingController;
import com.cloudwebrtc.webrtc.audio.AudioSwitchManager;
import com.cloudwebrtc.webrtc.utils.AnyThreadSink;
import com.cloudwebrtc.webrtc.utils.ConstraintsMap;

import org.webrtc.ExternalAudioProcessingFactory;
import org.webrtc.MediaStreamTrack;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.embedding.engine.plugins.lifecycle.HiddenLifecycleReference;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.MethodCall;
import io.flutter.view.TextureRegistry;

/**
 * FlutterWebRTCPlugin
 */
public class FlutterWebRTCPlugin implements FlutterPlugin, ActivityAware, EventChannel.StreamHandler {

    static public final String TAG = "FlutterWebRTCPlugin";
    private static Application application;

    private MethodChannel methodChannel;
    private MethodCallHandlerImpl methodCallHandler;
    private LifeCycleObserver observer;
    private Lifecycle lifecycle;
    private EventChannel eventChannel;

    // Frame stream channels
    private EventChannel frameEventChannel;
    private EventChannel.EventSink frameSink;
    private MethodChannel frameCtlChannel;
    private TrackFrameStreamer trackStreamer;

    // eventSink is static because FlutterWebRTCPlugin can be instantiated multiple times
    // but the onListen(Object, EventChannel.EventSink) event only fires once for the first
    // FlutterWebRTCPlugin instance, so for the next instances eventSink will be == null
    public static EventChannel.EventSink eventSink;

    public FlutterWebRTCPlugin() {
        sharedSingleton = this;
    }

    public static FlutterWebRTCPlugin sharedSingleton;

    public AudioProcessingController getAudioProcessingController() {
        return methodCallHandler.audioProcessingController;
    }

    public MediaStreamTrack getTrackForId(String trackId, String peerConnectionId) {
        return methodCallHandler.getTrackForId(trackId, peerConnectionId);
    }

    public LocalTrack getLocalTrack(String trackId) {
        return methodCallHandler.getLocalTrack(trackId);
    }

    public MediaStreamTrack getRemoteTrack(String trackId) {
        return methodCallHandler.getRemoteTrack(trackId);
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        startListening(binding.getApplicationContext(), binding.getBinaryMessenger(),
                binding.getTextureRegistry());
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        stopListening();
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        methodCallHandler.setActivity(binding.getActivity());
        this.observer = new LifeCycleObserver();
        this.lifecycle = ((HiddenLifecycleReference) binding.getLifecycle()).getLifecycle();
        this.lifecycle.addObserver(this.observer);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        methodCallHandler.setActivity(null);
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        methodCallHandler.setActivity(binding.getActivity());
    }

    @Override
    public void onDetachedFromActivity() {
        methodCallHandler.setActivity(null);
        if (this.observer != null) {
            this.lifecycle.removeObserver(this.observer);
            if (application!=null) {
                application.unregisterActivityLifecycleCallbacks(this.observer);
            }
        }
        this.lifecycle = null;
    }

    private void startListening(final Context context, BinaryMessenger messenger,
                                TextureRegistry textureRegistry) {
        AudioSwitchManager.instance = new AudioSwitchManager(context);
        methodCallHandler = new MethodCallHandlerImpl(context, messenger, textureRegistry);
        methodChannel = new MethodChannel(messenger, "FlutterWebRTC.Method");
        methodChannel.setMethodCallHandler(methodCallHandler);
        eventChannel = new EventChannel( messenger,"FlutterWebRTC.Event");
        eventChannel.setStreamHandler(this);

        // Register frame stream channels
        frameEventChannel = new EventChannel(messenger, "com.example.swoshpro/webrtc_frames");
        frameEventChannel.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object args, EventChannel.EventSink events) {
                Log.i(TAG, "[EventChannel] onListen called, setting frameSink");
                // Wrap with AnyThreadSink to allow calling from non-UI threads safely
                frameSink = new AnyThreadSink(events);
            }
            @Override
            public void onCancel(Object args) {
                Log.i(TAG, "[EventChannel] onCancel called, clearing frameSink");
                frameSink = null;
            }
        });
        frameCtlChannel = new MethodChannel(messenger, "com.example.swoshpro/webrtc_frames_ctl");
        frameCtlChannel.setMethodCallHandler(new FrameControlHandler());

        AudioSwitchManager.instance.audioDeviceChangeListener = (devices, currentDevice) -> {
            Log.w(TAG, "audioFocusChangeListener " + devices+ " " + currentDevice);
            ConstraintsMap params = new ConstraintsMap();
            params.putString("event", "onDeviceChange");
            sendEvent(params.toMap());
            return null;
        };
    }

    private void stopListening() {
        methodCallHandler.dispose();
        methodCallHandler = null;
        methodChannel.setMethodCallHandler(null);
        eventChannel.setStreamHandler(null);
        if (trackStreamer != null) {
            trackStreamer.stop();
            trackStreamer = null;
        }
        if (frameCtlChannel != null) {
            frameCtlChannel.setMethodCallHandler(null);
        }
        if (frameEventChannel != null) {
            frameEventChannel.setStreamHandler(null);
        }
        if (AudioSwitchManager.instance != null) {
            Log.d(TAG, "Stopping the audio manager...");
            AudioSwitchManager.instance.stop();
        }
    }

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        eventSink = new AnyThreadSink(events);
    }
    @Override
    public void onCancel(Object arguments) {
        eventSink = null;
    }

    public void sendEvent(Object event) {
        if(eventSink != null) {
            eventSink.success(event);
        }
    }

    private class LifeCycleObserver implements Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {

        }

        @Override
        public void onActivityStarted(Activity activity) {

        }

        @Override
        public void onActivityResumed(Activity activity) {
            if (null != methodCallHandler) {
                methodCallHandler.reStartCamera();
            }
        }

        @Override
        public void onResume(LifecycleOwner owner) {
            if (null != methodCallHandler) {
                methodCallHandler.reStartCamera();
            }
        }

        @Override
        public void onActivityPaused(Activity activity) {

        }

        @Override
        public void onActivityStopped(Activity activity) {

        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {

        }

        @Override
        public void onActivityDestroyed(Activity activity) {

        }
    }

    // Handler for frame control method channel
    private class FrameControlHandler implements MethodCallHandler {
        @Override
        public void onMethodCall(MethodCall call, Result result) {
            if ("startTextureFrameStream".equals(call.method)) {
                if (frameSink == null) {
                    result.error("NO_EVENT", "frame EventChannel not listening", null);
                    return;
                }
                Integer textureId = call.argument("textureId");
                Integer width = call.argument("width");
                Integer height = call.argument("height");
                Integer fps = call.argument("fps");

                if (textureId == null || width == null || height == null || fps == null) {
                    result.error("INVALID_ARGS", "Missing required arguments", null);
                    return;
                }

                FlutterRTCVideoRenderer renderer = methodCallHandler.getRenderer(textureId);
                if (renderer == null) {
                    result.error("NO_RENDERER", "renderer not found for textureId: " + textureId, null);
                    return;
                }
                if (trackStreamer != null) {
                    trackStreamer.stop();
                    trackStreamer = null;
                }
                org.webrtc.VideoTrack track = renderer.getVideoTrack();
                if (track == null) {
                    result.error("NO_TRACK", "video track not attached to renderer", null);
                    return;
                }
                trackStreamer = new TrackFrameStreamer(track, width, height, fps, frameSink);
                trackStreamer.start();
                result.success(true);
            } else if ("stopTextureFrameStream".equals(call.method)) {
                if (trackStreamer != null) {
                    trackStreamer.stop();
                    trackStreamer = null;
                }
                result.success(true);
            } else {
                result.notImplemented();
            }
        }
    }
}
