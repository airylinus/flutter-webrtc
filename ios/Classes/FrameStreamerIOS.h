#import <Foundation/Foundation.h>
#import <WebRTC/RTCMacros.h>
#import <WebRTC/RTCVideoRenderer.h>
@class FlutterRTCVideoRenderer;
@class RTCVideoTrack;
typedef void (^SPFrameEventSink)(NSDictionary* _Nonnull event);

@interface FrameStreamerIOS : NSObject<RTC_OBJC_TYPE(RTCVideoRenderer)>

- (instancetype)initWithRenderer:(FlutterRTCVideoRenderer* _Nonnull)renderer
                           track:(RTCVideoTrack* _Nonnull)track
                       eventSink:(SPFrameEventSink _Nonnull)sink
                      targetWidth:(int)targetW
                     targetHeight:(int)targetH
                              fps:(int)fps;

- (void)start;
- (void)stop;
- (BOOL)isRunning;

@end

