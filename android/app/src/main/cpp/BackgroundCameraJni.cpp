#include <jni.h>
#include <opencv2/core.hpp>
#include <cmath>
#include <limits>
#include <vector>
#include "BackgroundCameraGeometry.hpp"
#include "BackgroundCameraSampler.hpp"

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_dev_hanginthere_android_vision_BackgroundCameraNative_measure(JNIEnv *env, jobject, jlong first, jlong second) {
    if (!first || !second) return nullptr;
    try {
        const auto &a = *reinterpret_cast<const cv::Mat *>(first);
        const auto &b = *reinterpret_cast<const cv::Mat *>(second);
        if (a.type()!=CV_8UC1 || b.type()!=CV_8UC1 || a.size()!=b.size() ||
            a.cols<64 || a.rows<64 || std::max(a.cols,a.rows)>640) return nullptr;
        int detected=0;
        const auto matches=camera_pixels::sample(a,b,detected);
        std::vector<camera_geometry::Point> points;
        for (const auto &p:matches) points.push_back({p.x,p.y,p.qx,p.qy,p.region});
        const auto fit=camera_geometry::estimate(points,a.cols,a.rows);
        if (fit.reason!="supported" || !std::isfinite(fit.scale) || fit.scale<.5 || fit.scale>2) return nullptr;
        int selected[8];std::fill(std::begin(selected),std::end(selected),-1);
        const auto magnitude=[&](size_t i) { const auto &p=matches[i];return std::hypot(p.qx-p.x,p.qy-p.y); };
        for (size_t i=0;i<matches.size();i++) if (fit.mask&(uint64_t(1)<<i)) {
            int &old=selected[matches[i].region];
            if(old<0 || magnitude(i)>magnitude(old))old=static_cast<int>(i);
        }
        std::vector<int> regions;
        for(int index:selected)if(index>=0)regions.push_back(index);
        std::stable_sort(regions.begin(),regions.end(),[&](int a,int b){return magnitude(a)>magnitude(b);});
        std::vector<double> values{std::abs(fit.scale-1)};
        for(int index:regions) {
            if(values.size()==13)break;
            const auto &p=matches[index];values.push_back(p.qx-p.x);values.push_back(p.qy-p.y);
        }
        auto result=env->NewDoubleArray(static_cast<jsize>(values.size()));
        if(result)env->SetDoubleArrayRegion(result,0,static_cast<jsize>(values.size()),values.data());
        return result;
    } catch (...) { return nullptr; }
}
