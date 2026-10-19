#ifndef HIT_OBSERVED_RAIL_PIXELS_H
#define HIT_OBSERVED_RAIL_PIXELS_H
#include <CoreGraphics/CoreGraphics.h>
#include <stdint.h>
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif

typedef struct { double x; double y; } HITRailPixelPoint;
typedef struct {
    HITRailPixelPoint *points;
    size_t *offsets;
    size_t point_count;
    size_t contour_count;
} HITRailPixelContours;

// Upright source pixels, integral crop coordinates. Ordered contours contain
// pixel centers in the original image. No line fitting, feature selection or
// motion interpretation occurs here. Call off the UI thread and free the result.
// Pass a fresh zero-initialized output. Free any previous result before reuse;
// this function initializes the output and does not release an earlier allocation.
// 0 success, 1 invalid image/region, 2 complexity budget, 3 allocation,
// 4 processing, 5 incompatible linked OpenCV backend.
int32_t HITCopyRailPixelContours(CGImageRef image, int32_t left, int32_t top,
    int32_t width, int32_t height, HITRailPixelContours *result);
void HITFreeRailPixelContours(HITRailPixelContours *result);
const char *HITRailPixelBackendVersion(void);
#ifdef __cplusplus
}
#endif
#endif
