// Exact bounded v3 development geometry; no pose/contact/count input.
#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>
#include <set>
#include <string>
#include <vector>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

namespace camera_geometry {
struct Point { double x,y,qx,qy;int region; };
struct Transform { double a=1,b=0,tx=0,ty=0; };
struct Result {
    std::string reason="insufficientCorrespondences";
    Transform transform;double scale=0,rotation=0,shiftX=0,shiftY=0;
    uint64_t mask=0;int inliers=0;
};
inline double residual(const Transform &t,const Point &p) {
    return std::hypot(t.a*p.x-t.b*p.y+t.tx-p.qx,t.b*p.x+t.a*p.y+t.ty-p.qy);
}
inline bool within(const Transform &t,const std::vector<Point> &points,uint64_t mask) {
    for(size_t i=0;i<points.size();i++) if(mask&(uint64_t(1)<<i))
        if(residual(t,points[i])>.75) return false;
    return true;
}
inline Transform fit(const std::vector<Point> &points,uint64_t mask,const Transform &original) {
    double x=0,y=0,qx=0,qy=0,n=0;
    for(size_t i=0;i<points.size();i++) if(mask&(uint64_t(1)<<i)) {
        x+=points[i].x;y+=points[i].y;qx+=points[i].qx;qy+=points[i].qy;n++;
    }
    x/=n;y/=n;qx/=n;qy/=n;double norm=0,aa=0,bb=0;
    for(size_t i=0;i<points.size();i++) if(mask&(uint64_t(1)<<i)) {
        const auto &p=points[i];const double dx=p.x-x,dy=p.y-y,ex=p.qx-qx,ey=p.qy-qy;
        norm+=dx*dx+dy*dy;aa+=dx*ex+dy*ey;bb+=dx*ey-dy*ex;
    }
    if(norm<=0) return original;
    Transform t{aa/norm,bb/norm,0,0};t.tx=qx-t.a*x+t.b*y;t.ty=qy-t.b*x-t.a*y;
    return within(t,points,mask)?t:original;
}
inline Result estimate(const std::vector<Point> &points,int width,int height) {
    Result result;
    if(points.size()<8 || points.size()>64 || width<64 || height<64) return result;
    for(const auto &p:points) {
        if(!std::isfinite(p.x)||!std::isfinite(p.y)||!std::isfinite(p.qx)||!std::isfinite(p.qy)||p.region<0||p.region>7) {
            result.reason="invalidCorrespondence";return result;
        }
    }
    const double shortSide=std::min(width,height),minimumBaseline=shortSide*.1;
    struct Hypothesis { int count;double sum;uint64_t mask;Transform transform;size_t order; };
    std::vector<Hypothesis> hypotheses;size_t order=0;
    for(size_t i=0;i<points.size();i++) for(size_t j=i+1;j<points.size();j++) {
        const auto &p=points[i];const auto &q=points[j];
        const double x=q.x-p.x,y=q.y-p.y,qx=q.qx-p.qx,qy=q.qy-p.qy,norm=x*x+y*y;
        if(norm<minimumBaseline*minimumBaseline) continue;
        Transform t{(x*qx+y*qy)/norm,(x*qy-y*qx)/norm,0,0};
        t.tx=p.qx-t.a*p.x+t.b*p.y;t.ty=p.qy-t.b*p.x-t.a*p.y;
        uint64_t mask=0;int count=0;double sum=0;
        for(size_t k=0;k<points.size();k++) {const double e=residual(t,points[k]);if(e<=.75){mask|=uint64_t(1)<<k;count++;sum+=e;}}
        if(count>=8) hypotheses.push_back({count,sum,mask,t,order++});
    }
    if(hypotheses.empty()){result.reason="noCoherentTransform";return result;}
    std::stable_sort(hypotheses.begin(),hypotheses.end(),[](const auto &a,const auto &b){
        return a.count!=b.count?a.count>b.count:a.sum<b.sum;
    });
    const auto &winner=hypotheses.front();result.inliers=winner.count;result.mask=winner.mask;
    if(double(winner.count)/points.size()<.5){result.reason="weakConsensus";return result;}
    result.transform=fit(points,winner.mask,winner.transform);
    std::set<uint64_t> masks;
    const cv::Point2d corners[]={{0,0},{double(width),0},{0,double(height)},{double(width),double(height)}};
    for(const auto &h:hypotheses) {
        if(h.count<winner.count) break;
        if(!masks.insert(h.mask).second) continue;
        const Transform t=fit(points,h.mask,h.transform);
        for(const auto &c:corners) {
            const auto &a=result.transform;
            const double dx=(a.a-t.a)*c.x-(a.b-t.b)*c.y+a.tx-t.tx;
            const double dy=(a.b-t.b)*c.x+(a.a-t.a)*c.y+a.ty-t.ty;
            if(std::hypot(dx,dy)>1.5){result.reason="competingTransforms";return result;}
        }
    }
    if(!within(result.transform,points,result.mask)){result.reason="fitResidual";return result;}
    double minX=INFINITY,minY=INFINITY,maxX=-INFINITY,maxY=-INFINITY;
    std::set<int> regions;std::vector<cv::Point2f> hull,values;
    for(size_t i=0;i<points.size();i++) if(result.mask&(uint64_t(1)<<i)) {
        const auto &p=points[i];minX=std::min(minX,p.x);maxX=std::max(maxX,p.x);
        minY=std::min(minY,p.y);maxY=std::max(maxY,p.y);regions.insert(p.region);
        values.emplace_back(p.x,p.y);
    }
    cv::convexHull(values,hull);
    if(regions.size()<3 || std::min(maxX-minX,maxY-minY)<shortSide*.2 || cv::contourArea(hull)<shortSide*shortSide*.03) {
        result.reason="insufficientSpatialSupport";return result;
    }
    const auto &t=result.transform;result.scale=std::hypot(t.a,t.b);
    result.rotation=std::atan2(t.b,t.a)*180/3.1415926535897932384626433832795;
    const double x=width/2.0,y=height/2.0;
    result.shiftX=t.a*x-t.b*y+t.tx-x;result.shiftY=t.b*x+t.a*y+t.ty-y;
    result.reason="supported";return result;
}
}
