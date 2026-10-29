#!/usr/bin/env python3
"""Propagate source-review landmark regions into a conservative width interval.

No pose/model predictions or desired grip enter this calculation. Human/agent
review supplies four original-pixel regions, a support direction and image size.
References near a band boundary remain uncertain; they do not become successes.
"""
import math


def ratio_interval(regions, support, image_size):
    width,height=image_size
    if not all(math.isfinite(v) and v>0 for v in image_size):
        raise ValueError('Invalid image size')
    def box(name):
        values=regions[name]
        if len(values)!=4 or not all(math.isfinite(v) for v in values):
            raise ValueError('Invalid landmark region')
        x0,y0,x1,y1=values
        if not 0<=x0<=x1<=width or not 0<=y0<=y1<=height:
            raise ValueError('Landmark region outside source image')
        return values
    boxes={name:box(name) for name in ['leftShoulder','rightShoulder','leftWrist','rightWrist']}
    if len(support)!=2 or any(len(p)!=2 or not all(math.isfinite(v) for v in p) for p in support):
        raise ValueError('Invalid support direction')
    dx=support[1][0]-support[0][0];dy=support[1][1]-support[0][1];length=math.hypot(dx,dy)
    if length<=0:raise ValueError('Zero support direction')
    ux,uy=dx/length,dy/length
    def projected(b):
        values=[x*ux+y*uy for x in [b[0],b[2]] for y in [b[1],b[3]]]
        return min(values),max(values)
    def separation(a,b):return max(0,a[0]-b[1],b[0]-a[1]),max(abs(a[0]-b[1]),abs(a[1]-b[0]))
    a,b=boxes['leftShoulder'],boxes['rightShoulder']
    sx=separation((a[0],a[2]),(b[0],b[2]));sy=separation((a[1],a[3]),(b[1],b[3]))
    shoulder_min,shoulder_max=math.hypot(sx[0],sy[0]),math.hypot(sx[1],sy[1])
    if shoulder_min<=0:raise ValueError('Shoulder regions overlap; ratio is unbounded')
    wrists=separation(projected(boxes['leftWrist']),projected(boxes['rightWrist']))
    return wrists[0]/shoulder_max,wrists[1]/shoulder_min


def band(interval):
    low,high=interval
    if not all(math.isfinite(v) for v in interval) or not 0.1<=low<=high<=4.0:
        return 'uncertain'
    if high<0.85:return 'narrow'
    if low>1.30:return 'wide'
    if low>=0.85 and high<=1.30:return 'shoulderWidth'
    return 'uncertain'
