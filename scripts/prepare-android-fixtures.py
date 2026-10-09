#!/usr/bin/env python3
"""Prepare only pinned public clips for native Android tests; never private phone data.

The test APK contains test-only media and must not be published as an artifact.
Source rights, actual decoded PTS and derivative hashes travel with each fixture.
"""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess

ROOT=Path(__file__).resolve().parents[1]
def module(name):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/f'{name}.py')
    m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def main():
    smoke=module('prepare-fixtures')
    source=ROOT/'Data/external/p0-pullup-source.webm'
    spec=json.loads(smoke.SPEC_PATH.read_text());smoke.acquire(spec,source)
    smoke.prepare(source,smoke.DESTINATION,spec)
    temporal=ROOT/'build/android-fixtures/temporal'
    if temporal.exists():shutil.rmtree(temporal)
    subprocess.run(['python3',str(ROOT/'scripts/prepare-temporal-pilot.py'), '--cache',str(ROOT/'Data/external/android-ci'),
                    '--output',str(temporal),'--only','dip_controlled_pexels','--fetch'],check=True)
    dest=ROOT/'android/app/src/androidTest/assets/generated';dest.mkdir(parents=True,exist_ok=True)
    refs=json.loads((temporal/'reference.json').read_text())['clips']
    clips=[]
    for id,video,exercise,side,edge,expected,pts,source_hash in [
        ('pullup_smoke',smoke.DESTINATION/'pullup-smoke.mp4','pullUp','left',[280,4,395,8],1,
         json.loads((smoke.DESTINATION/'prepared.json').read_text())['frame_pts_seconds'],spec['source_sha1']),
        ('dip_controlled_pexels',temporal/'dip_controlled_pexels.mp4','dip','left',refs[0]['bar_reference_edge'],9,
         refs[0]['frame_pts_seconds'],refs[0]['source_sha256'])]:
        shutil.copyfile(video,dest/video.name)
        clips.append(dict(id=id,video=video.name,exercise=exercise,side=side,
            referenceEdge=dict(a=dict(x=edge[0],y=edge[1]),b=dict(x=edge[2],y=edge[3])),
            expectedMovements=expected,expectedFrames=len(pts),sourcePtsSeconds=pts,
            sha256=smoke.digest(video),sourceHash=source_hash))
    manifest=dict(schemaVersion=1,scope='public development smoke, movement only; no form verdict',clips=clips)
    (dest/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    # Authored negative, separate from public movement labels: no person/apparatus.
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i',
                    'color=c=black:s=320x240:r=12:d=2', '-an', '-c:v', 'libx264',
                    '-pix_fmt', 'yuv420p', str(dest/'no-person.mp4')], check=True)
    (dest/'ATTRIBUTION.txt').write_text(spec.get('attribution','U.S. Marines / Wikimedia Commons public-domain B-roll')+'\n'+(temporal/'ATTRIBUTION.txt').read_text())
    subprocess.run(['python3',str(ROOT/'scripts/prepare-mediapipe-model.py'),'--output',
                    str(ROOT/'android/app/src/main/assets/pose_landmarker_heavy.task'),'--metadata',
                    str(ROOT/'build/android-fixtures/model.json')],check=True)
    print(f'Prepared {len(clips)} public clips, {sum(c["expectedFrames"] for c in clips)} frames')
if __name__=='__main__':main()
