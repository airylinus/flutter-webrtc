#import "FrameStreamerIOS.h"
#import "FlutterRTCVideoRenderer.h"
#import "FlutterRTCFrameCapturer.h"
#import <WebRTC/RTCCVPixelBuffer.h>
#import <WebRTC/RTCVideoTrack.h>
#import <WebRTC/RTCVideoFrame.h>
#import <CoreImage/CoreImage.h>
#import <Accelerate/Accelerate.h>

@interface FrameStreamerIOS()
@property(nonatomic, weak) FlutterRTCVideoRenderer *renderer;
@property(nonatomic, strong) RTCVideoTrack *track;
@property(nonatomic, copy) SPFrameEventSink sink;
@property(nonatomic, assign) int targetW;
@property(nonatomic, assign) int targetH;
@property(nonatomic, assign) int fps;
@property(nonatomic, assign) BOOL running;
@property(nonatomic, strong) CIContext *ciContext;
@property(nonatomic, assign) CFTimeInterval lastSent;
@end

@implementation FrameStreamerIOS

- (instancetype)initWithRenderer:(FlutterRTCVideoRenderer *)renderer
                           track:(RTCVideoTrack *)track
                       eventSink:(SPFrameEventSink)sink
                      targetWidth:(int)targetW
                     targetHeight:(int)targetH
                              fps:(int)fps {
  self = [super init];
  if (self) {
    _renderer = renderer;
    _track = track;
    _sink = [sink copy];
    _targetW = targetW;
    _targetH = targetH;
    _fps = MAX(1, fps);
    _running = NO;
    _ciContext = [CIContext contextWithOptions:@{kCIContextUseSoftwareRenderer: @(NO)}];
    _lastSent = 0;
  }
  return self;
}

- (BOOL)isRunning { return _running; }

- (void)start {
  if (_running || !_track) return;
  _running = YES;
  // Attach as renderer to receive frames continuously
  [_track addRenderer:self];
}

- (void)stop {
  if (!_running) return;
  _running = NO;
  @try { [_track removeRenderer:self]; } @catch (__unused NSException *e) {}
}

#pragma mark - RTCVideoRenderer

- (void)setSize:(CGSize)size {
  // no-op
}

- (void)renderFrame:(RTCVideoFrame *)frame {
  if (!_running || !frame) return;

  // Throttle to target fps
  CFTimeInterval now = CACurrentMediaTime();
  CFTimeInterval interval = 1.0 / (double)_fps;
  if (_lastSent > 0 && (now - _lastSent) < interval) {
    return;
  }
  _lastSent = now;

  // Obtain a CVPixelBuffer in BGRA from the frame
  CVPixelBufferRef pixelBuffer = NULL;
  BOOL shouldRelease = NO;
  id<RTC_OBJC_TYPE(RTCVideoFrameBuffer)> buffer = frame.buffer;
  if (![buffer isKindOfClass:[RTC_OBJC_TYPE(RTCCVPixelBuffer) class]]) {
    pixelBuffer = [FlutterRTCFrameCapturer convertToCVPixelBuffer:frame];
    shouldRelease = YES;
  } else {
    pixelBuffer = ((RTC_OBJC_TYPE(RTCCVPixelBuffer) *)buffer).pixelBuffer;
  }
  if (!pixelBuffer) return;

  size_t srcW = CVPixelBufferGetWidth(pixelBuffer);
  size_t srcH = CVPixelBufferGetHeight(pixelBuffer);

  // Create RGBA 8-bit destination buffer at target size
  const int dstW = _targetW;
  const int dstH = _targetH;
  size_t bytesPerRow = dstW * 4;
  size_t dataSize = bytesPerRow * dstH;
  uint8_t *rgbaData = (uint8_t *)malloc(dataSize);
  if (!rgbaData) {
    if (shouldRelease) CVPixelBufferRelease(pixelBuffer);
    return;
  }

  // Use CoreImage to scale and convert to RGBA (premultiplied last)
  CIImage *ciImage = [CIImage imageWithCVPixelBuffer:pixelBuffer];
  // Apply rotation to match display
  CGRect outputRect = CGRectMake(0, 0, srcW, srcH);
  if (@available(iOS 11, *)) {
    switch (frame.rotation) {
      case RTCVideoRotation_90:
        ciImage = [ciImage imageByApplyingCGOrientation:kCGImagePropertyOrientationRight];
        outputRect = CGRectMake(0, 0, srcH, srcW);
        break;
      case RTCVideoRotation_180:
        ciImage = [ciImage imageByApplyingCGOrientation:kCGImagePropertyOrientationDown];
        outputRect = CGRectMake(0, 0, srcW, srcH);
        break;
      case RTCVideoRotation_270:
        ciImage = [ciImage imageByApplyingCGOrientation:kCGImagePropertyOrientationLeft];
        outputRect = CGRectMake(0, 0, srcH, srcW);
        break;
      default:
        outputRect = CGRectMake(0, 0, srcW, srcH);
        break;
    }
  }

  CGColorSpaceRef colorSpace = CGColorSpaceCreateDeviceRGB();
  CGBitmapInfo bitmapInfo = kCGBitmapByteOrder32Big | kCGImageAlphaPremultipliedLast; // RGBA
  CGContextRef ctx = CGBitmapContextCreate(rgbaData, dstW, dstH, 8, bytesPerRow, colorSpace, bitmapInfo);
  CGImageRef cgImage = [_ciContext createCGImage:ciImage fromRect:outputRect];
  if (cgImage && ctx) {
    CGRect dstRect = CGRectMake(0, 0, dstW, dstH);
    CGContextDrawImage(ctx, dstRect, cgImage);
  }
  if (cgImage) CGImageRelease(cgImage);
  if (ctx) CGContextRelease(ctx);
  CGColorSpaceRelease(colorSpace);
  if (shouldRelease) CVPixelBufferRelease(pixelBuffer);

  // Build event map
  int rotationDeg = 0;
  switch (frame.rotation) {
    case RTCVideoRotation_90: rotationDeg = 90; break;
    case RTCVideoRotation_180: rotationDeg = 180; break;
    case RTCVideoRotation_270: rotationDeg = 270; break;
    default: rotationDeg = 0; break;
  }

  // Create NSData wrapping the bytes; sink must be called on main thread
  NSData *data = [NSData dataWithBytesNoCopy:rgbaData length:dataSize freeWhenDone:YES];
  NSDictionary *event = @{ @"bytes": data,
                           @"width": @(dstW),
                           @"height": @(dstH),
                           @"srcW": @(srcW),
                           @"srcH": @(srcH),
                           @"rotation": @(rotationDeg),
                           @"ts_us": @((long long)(now * 1000000.0)),
                           @"format": @"RGBA" };
  dispatch_async(dispatch_get_main_queue(), ^{
    if (self.sink) self.sink(event);
  });
}

@end

