#!/usr/bin/env python3
"""Research-only bar line extraction from Apple Vision raw contours.

No pose or joint inputs.  This evaluates whether Vision's contour evidence can be
converted into a stable guided bar line without introducing an OpenCV/TFLite app
runtime dependency.  It is not production code or a semantic bar detector.
"""
from __future__ import annotations
import argparse, hashlib, json, math
from pathlib import Path
import numpy as np


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def rdp(points, epsilon: float):
    pts = np.asarray(points, dtype=float)
    if len(pts) <= 2:
        return pts
    a, b = pts[0], pts[-1]
    ab = b - a
    denom = float(np.dot(ab, ab))
    if denom <= 1e-12:
        distances = np.linalg.norm(pts - a, axis=1)
    else:
        t = np.clip(((pts-a) @ ab) / denom, 0.0, 1.0)
        projected = a + t[:, None] * ab
        distances = np.linalg.norm(pts - projected, axis=1)
    idx = int(np.argmax(distances)); maximum = float(distances[idx])
    if maximum <= epsilon:
        return np.vstack([a, b])
    left = rdp(pts[:idx+1], epsilon)
    right = rdp(pts[idx:], epsilon)
    return np.vstack([left[:-1], right])


def canonical_unit(a, b):
    v=np.asarray(b,float)-np.asarray(a,float); length=float(np.linalg.norm(v))
    if not math.isfinite(length) or length <= 1e-9: return None
    u=v/length
    if u[0] < 0 or (abs(float(u[0])) < 1e-12 and u[1] < 0): u=-u
    return u


def angle_diff(u,v):
    return math.acos(max(-1.0,min(1.0,float(np.dot(u,v)))))


def raw_segments(contours, epsilon=1.0, minimum=2.0):
    result=[]
    for contour in contours:
        simplified=rdp(contour, epsilon)
        for a,b in zip(simplified[:-1], simplified[1:]):
            if float(np.linalg.norm(b-a)) >= minimum:
                result.append([float(a[0]),float(a[1]),float(b[0]),float(b[1])])
    return result


def merge_segments(segments, region, angle_deg=8.0, offset_px=5.0):
    # A guided setup region lets us bridge short internal occlusions while never
    # extrapolating beyond the outermost observed fragment endpoints.
    major=max(region[2]-region[0],region[3]-region[1])
    gap_px=min(45.0, 0.20*major)
    groups=[]
    for row in sorted(segments,key=lambda s:-math.hypot(s[2]-s[0],s[3]-s[1])):
        a=np.array(row[:2],float); b=np.array(row[2:],float); u=canonical_unit(a,b)
        if u is None: continue
        n=np.array([-u[1],u[0]]); pts=np.array([a,b]); off=float(np.mean(pts@n)); ts=pts@u
        q0,q1=float(ts.min()),float(ts.max()); placed=False
        for g in groups:
            if angle_diff(u,g['u']) > math.radians(angle_deg): continue
            ng=np.array([-g['u'][1],g['u'][0]]); off2=float(np.mean(pts@ng)); qs=pts@g['u']
            r0,r1=float(qs.min()),float(qs.max()); gap=max(g['t0']-r1,r0-g['t1'],0.0)
            if abs(off2-g['offset']) > offset_px or gap > gap_px: continue
            g['points'].extend([a,b]); p=np.array(g['points']); mean=p.mean(axis=0)
            _,_,vh=np.linalg.svd(p-mean,full_matrices=False); u2=vh[0]
            if u2[0] < 0 or (abs(float(u2[0])) < 1e-12 and u2[1] < 0): u2=-u2
            n2=np.array([-u2[1],u2[0]]); tt=p@u2
            g.update(u=u2,offset=float(np.mean(p@n2)),t0=float(tt.min()),t1=float(tt.max()))
            placed=True; break
        if not placed:
            groups.append({'u':u,'offset':off,'t0':q0,'t1':q1,'points':[a,b]})
    merged=[]
    for g in groups:
        u=g['u']; n=np.array([-u[1],u[0]])
        a=g['t0']*u+g['offset']*n; b=g['t1']*u+g['offset']*n
        merged.append({'a':a,'b':b,'length':float(np.linalg.norm(b-a))})
    return merged


def border_artifact(line, region, tolerance=2.0):
    x0,y0,x1,y1=region; a=line['a']; b=line['b']
    return ((abs(a[0]-x0)<=tolerance and abs(b[0]-x0)<=tolerance) or
            (abs(a[0]-x1)<=tolerance and abs(b[0]-x1)<=tolerance) or
            (abs(a[1]-y0)<=tolerance and abs(b[1]-y0)<=tolerance) or
            (abs(a[1]-y1)<=tolerance and abs(b[1]-y1)<=tolerance))


def candidates(contours, region):
    major=max(region[2]-region[0],region[3]-region[1])
    merged=merge_segments(raw_segments(contours),region)
    useful=[x for x in merged if not border_artifact(x,region) and x['length'] >= .25*major]
    useful.sort(key=lambda x:-x['length'])
    return useful


def point_segment_distance(p,a,b):
    p=np.asarray(p,float); a=np.asarray(a,float); b=np.asarray(b,float); ab=b-a; d=float(np.dot(ab,ab))
    if d<=1e-12: return float(np.linalg.norm(p-a))
    t=max(0.0,min(1.0,float(np.dot(p-a,ab)/d)))
    return float(np.linalg.norm(p-(a+t*ab)))


def score(contour_dump, spec):
    static=spec['pullup_video_static']; refs=np.array([[p['x'],p['y']] for p in static['upperEdgePoints']],float)
    stills={x['id']:x for x in spec['stills']}; rows=[]
    for item in contour_dump['rows']:
        cs=candidates(item['contours'],item['region']); first=cs[0] if cs else None
        expected = item['id'] != 'pullup_video_static' or item['frame'] not in static['noBarFrameIndices']
        result={'id':item['id'],'frame':item['frame'],'expected_bar':expected,'contours':len(item['contours']),
                'candidate_lines':len(cs),'detected':first is not None}
        label_points=refs if item['id']=='pullup_video_static' else np.array([[p['x'],p['y']] for p in stills[item['id']]['upperEdgePoints']],float)
        if first is not None:
            result['line']={'a':first['a'].tolist(),'b':first['b'].tolist(),'length_px':first['length']}
        if first is not None and len(label_points):
            errors=[point_segment_distance(p,first['a'],first['b']) for p in label_points]
            result['mean_reference_error_px']=float(np.mean(errors)); result['max_reference_error_px']=float(max(errors))
        rows.append(result)
    seq=[r for r in rows if r['id']=='pullup_video_static']; pos=[r for r in seq if r['expected_bar']]; neg=[r for r in seq if not r['expected_bar']]
    errors=[r['mean_reference_error_px'] for r in pos if r.get('detected') and 'mean_reference_error_px' in r]
    summary={'visible_frames':len(pos),'visible_detected':sum(r['detected'] for r in pos),
             'no_bar_frames':len(neg),'no_bar_false_positives':sum(r['detected'] for r in neg),
             'sequence_mean_reference_error_px':float(np.mean(errors)) if errors else None,
             'sequence_p95_reference_error_px':float(np.percentile(errors,95)) if errors else None,
             'stills':{r['id']:{k:r.get(k) for k in ('detected','mean_reference_error_px','max_reference_error_px')}
                       for r in rows if r['id']!='pullup_video_static'}}
    return rows,summary


def main():
    ap=argparse.ArgumentParser(); ap.add_argument('--contours',type=Path,required=True); ap.add_argument('--spec',type=Path,required=True); ap.add_argument('--output',type=Path,required=True)
    args=ap.parse_args(); contour_dump=json.loads(args.contours.read_text()); spec=json.loads(args.spec.read_text())
    rows,summary=score(contour_dump,spec)
    out={'schema_version':1,'purpose':'Vision raw-contour line-refinement research; no pose inputs or production selection',
         'contour_sha256':digest(args.contours),'spec_sha256':digest(args.spec),'summary':summary,'results':rows}
    args.output.parent.mkdir(parents=True,exist_ok=True); args.output.write_text(json.dumps(out,indent=2,sort_keys=True)+'\n')
    print(json.dumps(summary,indent=2,sort_keys=True))

if __name__=='__main__': main()