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

typedef struct {
    int32_t supported;
    int32_t reason;
    int32_t reference_features;
    int32_t correspondences;
    int32_t inliers;
    double scale_fraction;
    int32_t translation_count;
} HITBackgroundScaleMeasurement;

enum { HITBackgroundMaximumTranslations = 6 };
typedef struct {
    double reference_x, reference_y, current_x, current_y;
    int32_t region;
} HITBackgroundPointTranslation;

// Image-only current/reference correspondence; no pose, apparatus or contact
// input. Unsupported evidence is distinct from a measured zero scale change.
// Bounded64 corners, reciprocal15/31px NCC,0.75px geometric residual.
// Same status codes as the pixel API above; result owns no allocation.
int32_t HITMeasureBackgroundScale(CGImageRef reference, CGImageRef current,
    HITBackgroundScaleMeasurement *result);
// Actual observed inlier points in original image pixels, one per perimeter
// region (largest measured displacement), up to the existing six-patch budget.
// Caller provides six output slots. Unsupported evidence supplies no points.
int32_t HITMeasureBackgroundGeometry(CGImageRef reference, CGImageRef current,
    HITBackgroundScaleMeasurement *result, HITBackgroundPointTranslation *translations);
#ifdef __cplusplus
}
#endif
#endif
