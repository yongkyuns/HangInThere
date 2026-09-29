#!/usr/bin/env python3
"""Bar-only method screening on frozen guided regions.

This is research/evaluation code, not production bar detection.  It deliberately
has no pose/joint inputs.  Generic line extractors feed the same deterministic
line-merging and edge-pair ranking stage so the comparison is about line evidence,
not exercise landmarks.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import sys
from typing import Iterable

import cv2
import numpy as np


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def canonical_unit(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    v = b - a
    length = float(np.linalg.norm(v))
    if not math.isfinite(length) or length <= 1e-9:
        raise ValueError('degenerate segment')
    u = v / length
    if u[0] < 0 or (abs(float(u[0])) < 1e-12 and u[1] < 0):
        u = -u
    return u


def angle_diff(u: np.ndarray, v: np.ndarray) -> float:
    return math.acos(max(-1.0, min(1.0, float(np.dot(u, v)))))


def merge_segments(segments: Iterable[list[float]], angle_deg=5.0, offset_px=3.0, gap_px=15.0):
    groups: list[dict] = []
    ordered = sorted(segments, key=lambda s: -math.hypot(s[2]-s[0], s[3]-s[1]))
    for row in ordered:
        a = np.array(row[:2], dtype=float); b = np.array(row[2:], dtype=float)
        try:
            u = canonical_unit(a, b)
        except ValueError:
            continue
        n = np.array([-u[1], u[0]])
        pts = np.array([a, b]); off = float(np.mean(pts @ n)); ts = pts @ u
        q0, q1 = float(ts.min()), float(ts.max())
        placed = False
        for g in groups:
            if angle_diff(u, g['u']) > math.radians(angle_deg):
                continue
            ng = np.array([-g['u'][1], g['u'][0]])
            off2 = float(np.mean(pts @ ng)); qs = pts @ g['u']
            r0, r1 = float(qs.min()), float(qs.max())
            gap = max(g['t0'] - r1, r0 - g['t1'], 0.0)
            if abs(off2 - g['offset']) > offset_px or gap > gap_px:
                continue
            g['points'].extend([a, b])
            p = np.array(g['points']); mean = p.mean(axis=0)
            _, _, vh = np.linalg.svd(p - mean, full_matrices=False)
            u2 = vh[0]
            if u2[0] < 0 or (abs(float(u2[0])) < 1e-12 and u2[1] < 0):
                u2 = -u2
            n2 = np.array([-u2[1], u2[0]])
            t = p @ u2
            g.update(u=u2, offset=float(np.mean(p @ n2)), t0=float(t.min()), t1=float(t.max()))
            placed = True
            break
        if not placed:
            groups.append({'u': u, 'offset': off, 't0': q0, 't1': q1, 'points': [a, b]})

    result = []
    for g in groups:
        u = g['u']; n = np.array([-u[1], u[0]])
        a = g['t0'] * u + g['offset'] * n
        b = g['t1'] * u + g['offset'] * n
        result.append({'a': a, 'b': b, 'length': float(np.linalg.norm(b-a)), 'u': u})
    return result


def pair_lines(lines: list[dict], region: list[float]):
    x0, y0, x1, y1 = region
    major = max(x1-x0, y1-y0); minor = min(x1-x0, y1-y0)
    pairs = []
    for i, l1 in enumerate(lines):
        if l1['length'] < max(12.0, 0.18 * major):
            continue
        u1 = l1['u']; n1 = np.array([-u1[1], u1[0]])
        for l2 in lines[i+1:]:
            if l2['length'] < max(12.0, 0.18 * major) or angle_diff(u1, l2['u']) > math.radians(6):
                continue
            off1 = float(np.mean(np.array([l1['a'], l1['b']]) @ n1))
            off2 = float(np.mean(np.array([l2['a'], l2['b']]) @ n1))
            separation = abs(off1-off2)
            if not 2.0 <= separation <= min(30.0, 0.45*minor):
                continue
            t1 = sorted(np.array([l1['a'], l1['b']]) @ u1)
            t2 = sorted(np.array([l2['a'], l2['b']]) @ u1)
            overlap = max(0.0, min(t1[1], t2[1]) - max(t1[0], t2[0]))
            if overlap < max(10.0, 0.15*major):
                continue
            score = 2*overlap + 0.25*(l1['length']+l2['length']) - 20*angle_diff(u1,l2['u']) - 0.05*separation
            pairs.append((score, l1, l2, separation, overlap))
    pairs.sort(key=lambda p: -p[0])
    return pairs


def upper_edge(pair, region):
    _, l1, l2, _, _ = pair
    x = (region[0]+region[2])/2
    def y_at(line):
        a, b = line['a'], line['b']
        if abs(float(b[0]-a[0])) < 1e-9:
            return float((a[1]+b[1])/2)
        t = (x-a[0])/(b[0]-a[0])
        return float(a[1]+t*(b[1]-a[1]))
    return min((l1,l2), key=y_at)


def point_segment_distance(p, a, b):
    p=np.array(p,float); a=np.array(a,float); b=np.array(b,float); ab=b-a
    d=float(np.dot(ab,ab))
    if d <= 1e-12: return float(np.linalg.norm(p-a))
    t=max(0.0,min(1.0,float(np.dot(p-a,ab)/d)))
    return float(np.linalg.norm(p-(a+t*ab)))


def lsd_segments(image, region):
    x0,y0,x1,y1 = map(int, region); crop=image[y0:y1,x0:x1]
    gray=cv2.cvtColor(crop,cv2.COLOR_BGR2GRAY)
    raw=cv2.createLineSegmentDetector(cv2.LSD_REFINE_ADV).detect(gray)[0]
    if raw is None: return []
    return [[float(l[0]+x0),float(l[1]+y0),float(l[2]+x0),float(l[3]+y0)] for l in raw[:,0,:]]


def hough_segments(image, region):
    x0,y0,x1,y1 = map(int, region); crop=image[y0:y1,x0:x1]
    gray=cv2.cvtColor(crop,cv2.COLOR_BGR2GRAY); edges=cv2.Canny(gray,50,150,apertureSize=3)
    raw=cv2.HoughLinesP(edges,1,np.pi/180,threshold=max(8,int(.08*min(crop.shape[:2]))),
                        minLineLength=max(12,int(.2*max(crop.shape[:2]))),maxLineGap=8)
    if raw is None: return []
    return [[float(l[0]+x0),float(l[1]+y0),float(l[2]+x0),float(l[3]+y0)] for l in raw[:,0,:]]


def load_mlsd(root: Path):
    import torch
    sys.path.insert(0, str(root))
    module_path=root/'models/mbv2_mlsd_tiny.py'
    spec=importlib.util.spec_from_file_location('hit_mlsd_model', module_path)
    mod=importlib.util.module_from_spec(spec); assert spec.loader; spec.loader.exec_module(mod)
    model=mod.MobileV2_MLSD_Tiny()
    state=torch.load(root/'models/mlsd_tiny_512_fp32.pth', map_location='cpu', weights_only=True)
    model.load_state_dict(state); model.eval()
    return model


def mlsd_segments(image, region, model):
    import torch
    x0,y0,x1,y1 = map(int,region); crop=image[y0:y1,x0:x1]
    h,w,_=crop.shape
    resized=cv2.resize(cv2.cvtColor(crop,cv2.COLOR_BGR2RGB),(512,512),interpolation=cv2.INTER_AREA)
    rgba=np.concatenate([resized,np.ones((512,512,1),dtype=resized.dtype)],axis=-1)
    batch=((rgba.transpose(2,0,1)[None].astype('float32')/127.5)-1.0)
    with torch.no_grad(): out=model(torch.from_numpy(batch).float())
    center=torch.sigmoid(out[:,0:1]); hmax=torch.nn.functional.max_pool2d(center,3,1,1)
    heat=(center*(hmax==center)).reshape(-1); scores,indices=torch.topk(heat,200)
    H,W=out.shape[-2:]; yy=torch.div(indices,W,rounding_mode='floor'); xx=torch.fmod(indices,W)
    disp=out[0,1:5].permute(1,2,0).cpu().numpy(); pts=torch.stack((yy,xx),dim=-1).cpu().numpy(); scores=scores.cpu().numpy()
    lines=[]
    for (y,x), score in zip(pts,scores):
        d=disp[y,x]; dist=float(np.linalg.norm(d[:2]-d[2:]))
        if float(score)<=0.10 or dist<=20: continue
        xs=(x+d[0])*2*w/512 + x0; ys=(y+d[1])*2*h/512 + y0
        xe=(x+d[2])*2*w/512 + x0; ye=(y+d[3])*2*h/512 + y0
        lines.append([float(xs),float(ys),float(xe),float(ye)])
    return lines


def evaluate(method, extractor, source_root: Path, spec: dict):
    results=[]
    static=spec['pullup_video_static']
    ref=np.array([[p['x'],p['y']] for p in static['upperEdgePoints']],float)
    for index, rel in enumerate(static['frames']):
        path=source_root/rel; image=cv2.imread(str(path));
        if image is None: raise ValueError(f'Unreadable image {path}')
        segments=extractor(image, static['region']); merged=merge_segments(segments); pairs=pair_lines(merged,static['region'])
        expected=index not in static['noBarFrameIndices']
        row={'id':'pullup_video_static','frame':index,'expected_bar':expected,'raw_segments':len(segments),'merged_lines':len(merged),'candidate_pairs':len(pairs),'detected':bool(pairs)}
        if pairs:
            edge=upper_edge(pairs[0],static['region']); errs=[point_segment_distance(p,edge['a'],edge['b']) for p in ref]
            row.update(mean_reference_error_px=float(np.mean(errs)),max_reference_error_px=float(max(errs)),supported_edge_length_px=edge['length'])
        results.append(row)
    for item in spec['stills']:
        path=source_root/item['image']; image=cv2.imread(str(path));
        if image is None: raise ValueError(f'Unreadable image {path}')
        segments=extractor(image,item['region']); merged=merge_segments(segments); pairs=pair_lines(merged,item['region'])
        row={'id':item['id'],'frame':0,'expected_bar':True,'raw_segments':len(segments),'merged_lines':len(merged),'candidate_pairs':len(pairs),'detected':bool(pairs)}
        if pairs and item['upperEdgePoints']:
            edge=upper_edge(pairs[0],item['region']); pts=np.array([[p['x'],p['y']] for p in item['upperEdgePoints']],float)
            errs=[point_segment_distance(p,edge['a'],edge['b']) for p in pts]
            row.update(mean_reference_error_px=float(np.mean(errs)),max_reference_error_px=float(max(errs)),supported_edge_length_px=edge['length'])
        results.append(row)
    return {'method':method,'results':results}


def summarize(report):
    rows=report['results']; seq=[r for r in rows if r['id']=='pullup_video_static']
    positives=[r for r in seq if r['expected_bar']]; negatives=[r for r in seq if not r['expected_bar']]
    errs=[r['mean_reference_error_px'] for r in positives if r.get('detected') and 'mean_reference_error_px' in r]
    return {'method':report['method'],'visible_frames':len(positives),'visible_detected':sum(r['detected'] for r in positives),
            'no_bar_frames':len(negatives),'no_bar_false_positives':sum(r['detected'] for r in negatives),
            'sequence_mean_reference_error_px':float(np.mean(errs)) if errs else None,
            'sequence_p95_reference_error_px':float(np.percentile(errs,95)) if errs else None,
            'stills':{r['id']:{k:r.get(k) for k in ('detected','mean_reference_error_px','max_reference_error_px','supported_edge_length_px')} for r in rows if r['id']!='pullup_video_static'}}


def main():
    ap=argparse.ArgumentParser(); ap.add_argument('--source-root',type=Path,required=True); ap.add_argument('--spec',type=Path,required=True); ap.add_argument('--output',type=Path,required=True); ap.add_argument('--mlsd-root',type=Path)
    args=ap.parse_args(); spec=json.loads(args.spec.read_text())
    methods=[('opencv_lsd',lsd_segments),('opencv_houghp',hough_segments)]
    if args.mlsd_root:
        model=load_mlsd(args.mlsd_root.resolve()); methods.append(('mlsd_tiny_512',lambda image,region:mlsd_segments(image,region,model)))
    reports=[evaluate(name,fn,args.source_root.resolve(),spec) for name,fn in methods]
    out={'schema_version':1,'purpose':'bar-only method screening; no pose inputs and no production selection',
         'source_root_manifest_sha256':digest(args.source_root/'manifest.json') if (args.source_root/'manifest.json').exists() else None,
         'spec_sha256':digest(args.spec),'methods':[{'report':r,'summary':summarize(r)} for r in reports]}
    args.output.parent.mkdir(parents=True,exist_ok=True); args.output.write_text(json.dumps(out,indent=2,sort_keys=True)+'\n')
    print(json.dumps([m['summary'] for m in out['methods']],indent=2))

if __name__=='__main__': main()