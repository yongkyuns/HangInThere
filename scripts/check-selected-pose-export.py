#!/usr/bin/env python3
"""Verify complete native observation exports; does not score reps or model accuracy."""
import hashlib
import json
import math
from pathlib import Path
import re
import runpy
import sys

ROOT=Path(__file__).resolve().parents[1]

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def require(condition,message):
    if not condition:raise ValueError(message)
def receipt(value):return isinstance(value,str) and re.fullmatch('[0-9a-f]{64}',value) is not None
def shipping_pins():
    model=runpy.run_path(str(ROOT/'scripts/prepare-mediapipe-model.py'))['SHA256']
    gradle=(ROOT/'android/app/build.gradle.kts').read_text()
    def value(name):
        matches=re.findall(r'val '+name+r' = "([^"]+)"',gradle)
        require(len(matches)==1,'Missing/ambiguous shipping runtime pin: '+name)
        return matches[0]
    return dict(modelSha256=model,poseArtifact='com.google.mediapipe:tasks-vision:'+value('poseRuntimeVersion'),
        poseArtifactSha256=value('poseRuntimeSha256'))

def check(directory):
    directory=Path(directory)
    manifest_path=directory/'selected-pose-manifest.json'
    manifest=json.loads(manifest_path.read_text())
    report=json.loads((directory/'selected-pose-report.json').read_text())
    native=json.loads((directory/'report.json').read_text())
    recipe_path=ROOT/'Evaluation/fixtures/temporal-pilot.json'
    recipe=json.loads(recipe_path.read_text())
    require(manifest['schemaVersion']==report['schemaVersion']==1 and report['complete'] is True,'Incomplete export')
    require(manifest['selectionRecipeSha256']==sha(recipe_path),'Selection recipe changed')
    require(report['manifestSha256']==sha(manifest_path),'Manifest receipt changed')
    for key,value in shipping_pins().items():
        require(report[key]==native[key]==value,'Changed shipping SDK/model receipt: '+key)
    require(type(report['sdk']) is int and report['sdk']>=26 and report['sdk']==native['sdk'],'Changed native Android API')
    expected=recipe['sources'];clips=manifest['clips'];summaries=report['clips']
    require([r['id'] for r in expected]==[c['id'] for c in clips]==[s['id'] for s in summaries],'Missing/reordered source')
    total=0
    for source,clip,summary in zip(expected,clips,summaries):
        id=source['id'];count=source['frame_range'][1]-source['frame_range'][0]
        require(clip['expectedFrames']==summary['frames']==count and clip['sourceSha256']==source['source_sha256'],f'Changed source coverage: {id}')
        require(summary['sourceSha256']==clip['sha256'] and receipt(clip['sha256']),f'Changed derivative receipt: {id}')
        source_size=clip['imageSize']
        require(all(type(v) is int and v>0 for v in source_size.values()) and set(source_size)=={'width','height'},f'Invalid source dimensions: {id}')
        ratio=min(1,1280/max(source_size.values()))
        decoded_size={k:math.floor(v*ratio+.5) for k,v in source_size.items()}
        require(summary['decodedImageSize']==decoded_size,f'Changed canonical dimensions: {id}')
        pts=clip['sourcePtsMicroseconds']
        require(len(pts)==count and all(type(t) is int and t>=0 for t in pts) and all(a<b for a,b in zip(pts,pts[1:])),f'Invalid source clock: {id}')
        base=clip['sourceTimeBase'];ticks=clip['sourcePtsTicks']
        require(type(base['numerator']) is int and type(base['denominator']) is int and min(base.values())>0 and
                len(ticks)==count and all(type(t) is int for t in ticks) and
                pts==[t*base['numerator']*1_000_000//base['denominator'] for t in ticks],f'Container ticks changed: {id}')
        require(summary['observations']==f'selected-pose-{id}-observations.jsonl',f'Unexpected trace path: {id}')
        trace=directory/summary['observations']
        require(sha(trace)==summary['observationsSha256'],f'Trace receipt changed: {id}')
        context=None;frames=0;single=0;world=0
        with trace.open() as stream:
            for i,line in enumerate(stream):
                row=json.loads(line)
                require(i<count and row['frameIndex']==i and row['timestamp']==dict(value=pts[i],timescale=1_000_000),f'Frame/PTS mismatch: {id}/{i}')
                require(row['imageSize']==decoded_size and receipt(row['pixelSha256']),f'Missing pixel context: {id}/{i}')
                current=(row['backend'],row['requestRevision'],row.get('estimatedWorldConvention'))
                require(current[0]=='MediaPipe Heavy CPU visibility/presence minimum' and current[1]==1,f'Changed provider: {id}/{i}')
                require(current[2] in (None,'cameraRightDownAway_hipOrigin_v1'),f'Unknown world convention: {id}/{i}')
                context=context or current[:2]
                require(current[:2]==context and math.isfinite(row['inferenceMillis']) and row['inferenceMillis']>=0,f'Invalid inference context: {id}/{i}')
                people=row['people'];single+=len(people)==1
                if people:
                    require(current[2]=='cameraRightDownAway_hipOrigin_v1',f'Missing world convention: {id}/{i}')
                    for person in people:
                        image=[p['joint'] for p in person['landmarks'] if p['joint'] not in ('neck','root')]
                        points=person['estimatedWorldLandmarks']
                        require(len(image)==len(set(image))==17 and image==[p['joint'] for p in points],f'World/body mismatch: {id}/{i}')
                        require(all(math.isfinite(p['position'][k]) for p in points for k in ('x','y','z')),f'Nonfinite world point: {id}/{i}')
                    world+=1
                frames+=1
        require(frames==count and single==summary['singlePersonFrames'] and world==summary['estimatedWorldFrames'],f'Incomplete trace: {id}')
        total+=frames
    return dict(clips=len(clips),frames=total,scope='Whole-source native SDK export integrity only; no movement accuracy verdict')

if __name__=='__main__':
    try:
        require(len(sys.argv)==2,'Usage: check-selected-pose-export.py output-directory')
        print(json.dumps(check(sys.argv[1]),sort_keys=True))
    except (ValueError,KeyError,TypeError,OSError,ZeroDivisionError) as error:
        raise SystemExit(f'Selected pose export failed: {error}')
