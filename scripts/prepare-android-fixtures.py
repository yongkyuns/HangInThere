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
    source_tools=module('prepare-temporal-pilot')
    positive=ROOT/'build/android-fixtures/positive'
    if positive.exists():shutil.rmtree(positive)
    source_tools.prepare(ROOT/'Data/external/android-ci',positive,True,ROOT/'Evaluation/fixtures/android-positive.json')
    positive_ref=json.loads((positive/'reference.json').read_text())['clips'][0]
    clips=[]
    for id,video,exercise,side,edge,expected,pts,source_hash,role in [
        ('pullup_margin',positive/'pullup_margin.mp4','pullUp','left',positive_ref['bar_reference_edge'],1,
         positive_ref['frame_pts_seconds'],positive_ref['source_sha256'],'positive'),
        ('pullup_smoke',smoke.DESTINATION/'pullup-smoke.mp4','pullUp','left',[280,4,395,8],1,
         json.loads((smoke.DESTINATION/'prepared.json').read_text())['frame_pts_seconds'],spec['source_sha1'],'diagnostic'),
        ('dip_controlled_pexels',temporal/'dip_controlled_pexels.mp4','dip','left',refs[0]['bar_reference_edge'],9,
         refs[0]['frame_pts_seconds'],refs[0]['source_sha256'],'diagnostic')]:
        shutil.copyfile(video,dest/video.name)
        clips.append(dict(id=id,video=video.name,exercise=exercise,side=side,
            referenceEdge=dict(a=dict(x=edge[0],y=edge[1]),b=dict(x=edge[2],y=edge[3])),
            qualificationRole=role,expectedMovements=expected,expectedFrames=len(pts),sourcePtsSeconds=pts,
            sha256=smoke.digest(video),sourceHash=source_hash))
    setup_spec=json.loads((ROOT/'Evaluation/fixtures/android-setup.json').read_text())
    dip_source=source_tools.source_file(setup_spec['dip_source'],ROOT/'Data/external/android-ci',True)
    setup_clips=[]
    for id,video,exercise,first_frame in [('pullup_held_start',positive/'pullup_margin.mp4','pullUp',setup_spec['pullup_source_frame']-72),
                              ('dip_held_start',dip_source,'dip',setup_spec['source_frame'])]:
        target=dest/(id.replace('_','-')+'.mp4')
        subprocess.run(['ffmpeg','-v','error','-y','-i',str(video),'-an','-vf',
                        f'trim=start_frame={first_frame}:end_frame={first_frame+1},setpts=PTS-STARTPTS,scale=960:-2,tpad=stop_mode=clone:stop_duration=6,fps=12',
                        '-frames:v','72','-c:v','libx264','-pix_fmt','yuv420p',str(target)],check=True)
        pts,size=source_tools.probe(target)
        assert len(pts)==72 and pts[0]==0, 'Authored setup timeline changed'
        setup_clips.append(dict(id=id,video=target.name,exercise=exercise,expectedFrames=72,
            sourcePtsSeconds=pts,sha256=smoke.digest(target),scope=setup_spec['scope']))
    manifest=dict(schemaVersion=1,scope='Public development evidence; positive accuracy required, original capture failures retained as diagnostic. No form or held-out accuracy verdict',clips=clips,
                  setupClips=setup_clips)
    (dest/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    # Authored negative, separate from public movement labels: no person/apparatus.
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i',
                    'color=c=black:s=320x240:r=12:d=2', '-an', '-c:v', 'libx264',
                    '-pix_fmt', 'yuv420p', str(dest/'no-person.mp4')], check=True)
    row=setup_spec['dip_source']
    setup_credit=f"\nAuthored setup fixtures: repeated reviewed pull-up hang frame (Commons source3.0s) and dip source frame zero, silent H264 resize, six seconds; no observed-duration or dynamic accuracy claim.\n{row['credit']}\n{row['page']}\n{row['license']}\n{row['license_url']}\n"
    (dest/'ATTRIBUTION.txt').write_text(spec.get('attribution','U.S. Marines / Wikimedia Commons public-domain B-roll')+'\n'+(temporal/'ATTRIBUTION.txt').read_text()+'\n'+(positive/'ATTRIBUTION.txt').read_text()+setup_credit)
    subprocess.run(['python3',str(ROOT/'scripts/prepare-mediapipe-model.py'),'--output',
                    str(ROOT/'android/app/src/main/assets/pose_landmarker_heavy.task'),'--metadata',
                    str(ROOT/'build/android-fixtures/model.json')],check=True)
    print(f'Prepared {len(clips)} public clips, {sum(c["expectedFrames"] for c in clips)} frames')
if __name__=='__main__':main()
