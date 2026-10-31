// Identical bounded reciprocal background sampler for Apple and Android.
#pragma once
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <cmath>
#include <vector>
namespace camera_pixels {
constexpr int radius = 7;
struct Match { double x,y,qx,qy,score,margin;int region;int contextRadius; };
struct Peak { double x,y,score,second;bool valid=false; };

inline double subpixel(double a,double b,double c) {
    const double denominator=a-2*b+c;
    return std::abs(denominator)>1e-9?std::clamp(.5*(a-c)/denominator,-.5,.5):0;
}

inline Peak peak(const cv::Mat &a,const cv::Mat &b,int x,int y,int radius) {
    if(x-radius<0 || y-radius<0 || x+radius>=a.cols || y+radius>=a.rows) return {};
    const cv::Mat templ=a(cv::Rect(x-radius,y-radius,2*radius+1,2*radius+1));
    cv::Scalar mean,sd;cv::meanStdDev(templ,mean,sd);
    if(sd[0]<5) return {};
    const int search=std::min(64,static_cast<int>(std::lround(std::min(a.cols,a.rows)*.12)));
    const int x0=std::max(radius,x-search),x1=std::min(a.cols-radius-1,x+search);
    const int y0=std::max(radius,y-search),y1=std::min(a.rows-radius-1,y+search);
    cv::Mat response;
    cv::matchTemplate(b(cv::Rect(x0-radius,y0-radius,x1-x0+2*radius+1,y1-y0+2*radius+1)),
        templ,response,cv::TM_CCOEFF_NORMED);
    double best;cv::Point at;cv::minMaxLoc(response,nullptr,&best,nullptr,&at);
    if(!std::isfinite(best) || best<.90 || at.x==0 || at.y==0 || at.x==response.cols-1 || at.y==response.rows-1) return {};
    cv::Mat other=response.clone();
    const int lx=std::max(0,at.x-3),ly=std::max(0,at.y-3);
    other(cv::Rect(lx,ly,std::min(response.cols,at.x+4)-lx,std::min(response.rows,at.y+4)-ly)).setTo(-1);
    double second;cv::minMaxLoc(other,nullptr,&second);
    if(best-second<.05) return {};
    const auto value=[&](int yy,int xx){return static_cast<double>(response.at<float>(yy,xx));};
    const double dx=subpixel(value(at.y,at.x-1),value(at.y,at.x),value(at.y,at.x+1));
    const double dy=subpixel(value(at.y-1,at.x),value(at.y,at.x),value(at.y+1,at.x));
    return {x0+at.x+dx,y0+at.y+dy,best,second,true};
}

inline std::vector<Match> sample(const cv::Mat &a,const cv::Mat &b,int &detected) {
    std::vector<Match> result;
    int xs[]={0,a.cols/3,2*a.cols/3,a.cols},ys[]={0,a.rows/3,2*a.rows/3,a.rows};
    int region=0;
    for(int ry=0;ry<3;ry++) for(int rx=0;rx<3;rx++) {
        if(rx==1 && ry==1) continue;
        const cv::Rect cell(xs[rx],ys[ry],xs[rx+1]-xs[rx],ys[ry+1]-ys[ry]);
        std::vector<cv::Point2f> corners;
        cv::goodFeaturesToTrack(a(cell),corners,8,.03,8,cv::noArray(),5);
        for(const auto &point:corners) {
            const int x=std::lround(point.x)+cell.x,y=std::lround(point.y)+cell.y;detected++;
            for(int contextRadius:{7,15}) {
                const Peak forward=peak(a,b,x,y,contextRadius);
                if(!forward.valid) continue;
                const Peak reverse=peak(b,a,std::lround(forward.x),std::lround(forward.y),contextRadius);
                if(!reverse.valid || std::hypot(reverse.x-x,reverse.y-y)>1) continue;
                result.push_back({static_cast<double>(x),static_cast<double>(y),forward.x,forward.y,
                    forward.score,forward.score-forward.second,region,contextRadius});
                break;
            }

        }
        region++;
    }
    return result;
}
}
