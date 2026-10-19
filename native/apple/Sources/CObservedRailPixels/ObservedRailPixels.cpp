#include "ObservedRailPixels.h"
#include <TargetConditionals.h>
#include <opencv2/core.hpp>
#include <opencv2/core/version.hpp>
#include <opencv2/imgproc.hpp>
#include <cstdlib>
#include <cstring>
#include <new>
#include <vector>

#if TARGET_OS_IOS
static_assert(CV_VERSION_MAJOR == 4 && CV_VERSION_MINOR == 5 && CV_VERSION_REVISION == 3,
    "iOS requires exact MediaPipeTasksCommon 0.10.21 / OpenCV 4.5.3 headers");
#elif TARGET_OS_OSX
static_assert(CV_VERSION_MAJOR == 4 && CV_VERSION_MINOR == 11 && CV_VERSION_REVISION == 0,
    "macOS requires the pinned OpenCV 4.11.0 headers");
#else
#error Unsupported rail-pixel platform
#endif

namespace {
bool compatibleBackend() {
    return cv::getVersionMajor() == CV_VERSION_MAJOR && cv::getVersionMinor() == CV_VERSION_MINOR &&
        cv::getVersionRevision() == CV_VERSION_REVISION;
}
constexpr size_t maximumPixels = 1024 * 1024;
constexpr size_t maximumPoints = 200000;
constexpr size_t maximumContours = 512;
struct Bitmap {
    CGImageRef crop = nullptr;
    CGColorSpaceRef space = nullptr;
    CGContextRef context = nullptr;
    ~Bitmap() {
        if (context) CGContextRelease(context);
        if (space) CGColorSpaceRelease(space);
        if (crop) CGImageRelease(crop);
    }
};
}

void HITFreeRailPixelContours(HITRailPixelContours *result) {
    if (!result) return;
    std::free(result->points); std::free(result->offsets);
    std::memset(result, 0, sizeof(*result));
}
const char *HITRailPixelBackendVersion(void) { return compatibleBackend() ? CV_VERSION : "incompatible-opencv-backend"; }

int32_t HITCopyRailPixelContours(CGImageRef image, int32_t left, int32_t top,
    int32_t width, int32_t height, HITRailPixelContours *result) {
    if (!result) return 1;
    std::memset(result, 0, sizeof(*result));
    if (!image || left < 0 || top < 0 || width < 1 || height < 1 ||
        static_cast<uint64_t>(left) + width > CGImageGetWidth(image) ||
        static_cast<uint64_t>(top) + height > CGImageGetHeight(image)) return 1;
    const auto pixels = static_cast<size_t>(width) * static_cast<size_t>(height);
    if (pixels > maximumPixels) return 2;
    // Verify the linked private SDK version before constructing any C++ Mat.
    if (!compatibleBackend()) return 5;
    try {
        std::vector<uint8_t> rgba(pixels * 4);
        Bitmap bitmap;
        bitmap.crop = CGImageCreateWithImageInRect(image, CGRectMake(left, top, width, height));
        bitmap.space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
        if (!bitmap.crop || !bitmap.space) return 3;
        bitmap.context = CGBitmapContextCreate(rgba.data(), width, height, 8, width * 4,
            bitmap.space, kCGImageAlphaPremultipliedLast | kCGBitmapByteOrder32Big);
        if (!bitmap.context) return 3;
        CGContextSetBlendMode(bitmap.context, kCGBlendModeCopy);
        CGContextSetInterpolationQuality(bitmap.context, kCGInterpolationNone);
        CGContextSetShouldAntialias(bitmap.context, false);
        CGContextDrawImage(bitmap.context, CGRectMake(0, 0, width, height), bitmap.crop);
        cv::Mat source(height, width, CV_8UC4, rgba.data()), gray, edges;
        cv::cvtColor(source, gray, cv::COLOR_RGBA2GRAY);
        cv::GaussianBlur(gray, gray, cv::Size(5, 5), 1.0, 1.0, cv::BORDER_REFLECT_101);
        cv::Canny(gray, edges, 50.0, 100.0, 3, true);
        std::vector<std::vector<cv::Point>> contours;
        cv::findContours(edges, contours, cv::RETR_LIST, cv::CHAIN_APPROX_NONE);
        if (contours.size() > maximumContours) return 2;
        size_t count = 0;
        for (const auto &contour : contours) {
            if (contour.size() > maximumPoints - count) return 2;
            count += contour.size();
        }
        auto *points = count ? static_cast<HITRailPixelPoint *>(std::malloc(count * sizeof(HITRailPixelPoint))) : nullptr;
        auto *offsets = static_cast<size_t *>(std::malloc((contours.size() + 1) * sizeof(size_t)));
        if ((count && !points) || !offsets) { std::free(points); std::free(offsets); return 3; }
        size_t index = 0, contourIndex = 0;
        for (const auto &contour : contours) {
            offsets[contourIndex++] = index;
            for (const auto &point : contour) points[index++] = {left + point.x + .5, top + point.y + .5};
        }
        offsets[contourIndex] = index;
        result->points = points; result->offsets = offsets;
        result->point_count = count; result->contour_count = contours.size();
        return 0;
    } catch (const std::bad_alloc &) { return 3; }
      catch (...) { return 4; }
}
