// Development native sampler. No poses, prediction labels or zero-scale fallback.
// Exact current-pixel counterparts of the frozen v3 imgproc sampler.
#include <CoreGraphics/CoreGraphics.h>
#include "ObservedRailPixels.h"
#include <TargetConditionals.h>
#include <opencv2/core.hpp>
#include <opencv2/core/version.hpp>
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <stdexcept>
#include <new>
#include <string>
#include <vector>
#include "BackgroundCameraGeometry.hpp"
#include "BackgroundCameraSampler.hpp"

namespace {
cv::Mat pixels(CGImageRef image) {
    CGImageRetain(image);
    const size_t w=CGImageGetWidth(image),h=CGImageGetHeight(image);
    if(w<64 || h<64 || w>4096 || h>4096) { CGImageRelease(image);throw std::runtime_error("geometry"); }
    std::vector<unsigned char> rgba(w*h*4);
    CGColorSpaceRef space=CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    CGContextRef context=CGBitmapContextCreate(rgba.data(),w,h,8,w*4,space,
        kCGImageAlphaPremultipliedLast|kCGBitmapByteOrder32Big);CGColorSpaceRelease(space);
    if(!context) { CGImageRelease(image);throw std::runtime_error("context"); }
    CGContextSetBlendMode(context,kCGBlendModeCopy);
    CGContextSetInterpolationQuality(context,kCGInterpolationNone);
    CGContextSetShouldAntialias(context,false);
    CGContextDrawImage(context,CGRectMake(0,0,w,h),image);
    CGContextRelease(context);CGImageRelease(image);
    cv::Mat pixels(static_cast<int>(h),static_cast<int>(w),CV_8UC4,rgba.data()),small,gray;
    const double scale=std::min(1.0,640.0/std::max(w,h));
    if(scale<1) cv::resize(pixels,small,cv::Size(std::lround(w*scale),std::lround(h*scale)),0,0,cv::INTER_AREA);
    else small=pixels;
    cv::cvtColor(small,gray,cv::COLOR_RGBA2GRAY);
    return gray;
}


}


#if TARGET_OS_IOS
static_assert(CV_VERSION_MAJOR==4 && CV_VERSION_MINOR==5 && CV_VERSION_REVISION==3,"Pinned iOS backend required");
#elif TARGET_OS_OSX
static_assert(CV_VERSION_MAJOR==4 && CV_VERSION_MINOR==11 && CV_VERSION_REVISION==0,"Pinned Mac backend required");
#else
#error Unsupported camera pixel platform
#endif

int32_t HITMeasureBackgroundGeometry(CGImageRef reference,CGImageRef current,HITBackgroundScaleMeasurement *result,
    HITBackgroundPointTranslation *translations) {
    if(!result || !translations) return 1;
    std::memset(result,0,sizeof(*result));
    std::memset(translations,0,sizeof(*translations)*HITBackgroundMaximumTranslations);
    if(!reference || !current || CGImageGetWidth(reference)!=CGImageGetWidth(current) ||
       CGImageGetHeight(reference)!=CGImageGetHeight(current)) return 1;
    if(CGImageGetWidth(reference)>4096 || CGImageGetHeight(reference)>4096) return 2;
    if(cv::getVersionMajor()!=CV_VERSION_MAJOR || cv::getVersionMinor()!=CV_VERSION_MINOR ||
       cv::getVersionRevision()!=CV_VERSION_REVISION) return 5;
    try {
        const cv::Mat a=pixels(reference),b=pixels(current);
        int detected=0;const auto matches=camera_pixels::sample(a,b,detected);
        std::vector<camera_geometry::Point> points;
        for(const auto &p:matches) points.push_back({p.x,p.y,p.qx,p.qy,p.region});
        const auto fit=camera_geometry::estimate(points,a.cols,a.rows);
        result->reference_features=detected;result->correspondences=static_cast<int32_t>(matches.size());
        result->inliers=fit.inliers;
        const char *reasons[]={"supported","insufficientCorrespondences","noCoherentTransform","weakConsensus",
            "competingTransforms","fitResidual","insufficientSpatialSupport","invalidCorrespondence"};
        result->reason=8;
        for(int32_t i=0;i<8;i++) if(fit.reason==reasons[i]) result->reason=i;
        if(fit.reason=="supported" && std::isfinite(fit.scale) && fit.scale>=.5 && fit.scale<=2) {
            result->supported=1;result->scale_fraction=std::abs(fit.scale-1);
            // Select actual matched points, never projections of the fitted
            // transform. A region contributes only once; keep its largest
            // observed displacement rather than averaging motion toward zero.
            int selected[8];std::fill(std::begin(selected),std::end(selected),-1);
            const auto magnitude=[&](size_t i) {
                const auto &p=matches[i];return std::hypot(p.qx-p.x,p.qy-p.y);
            };
            for(size_t i=0;i<matches.size();i++) if(fit.mask&(uint64_t(1)<<i)) {
                int &old=selected[matches[i].region];
                if(old<0 || magnitude(i)>magnitude(old)) old=static_cast<int>(i);
            }
            std::vector<int> regions;
            for(int index:selected) if(index>=0) regions.push_back(index);
            std::stable_sort(regions.begin(),regions.end(),[&](int a,int b){return magnitude(a)>magnitude(b);});
            const double sx=double(CGImageGetWidth(reference))/a.cols;
            const double sy=double(CGImageGetHeight(reference))/a.rows;
            for(int index:regions) {
                if(result->translation_count==HITBackgroundMaximumTranslations) break;
                const auto &p=matches[index];
                translations[result->translation_count++]={p.x*sx,p.y*sy,p.qx*sx,p.qy*sy,p.region};
            }
        } else if(fit.reason=="supported") result->reason=8;
        return 0;
    } catch(const std::bad_alloc &) { return 3; }
      catch(...) { return 4; }
}

int32_t HITMeasureBackgroundScale(CGImageRef reference,CGImageRef current,HITBackgroundScaleMeasurement *result) {
    HITBackgroundPointTranslation translations[HITBackgroundMaximumTranslations];
    return HITMeasureBackgroundGeometry(reference,current,result,translations);
}
