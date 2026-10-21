#!/usr/bin/env python3
"""Prepare only pinned public clips for native Android tests; never private phone data.

The test APK contains test-only media and must not be published as an artifact.
Source rights, actual decoded PTS and derivative hashes travel with each fixture.
"""
import importlib.util
import argparse
from fractions import Fraction
import json
from pathlib import Path
import shutil
import subprocess

ROOT=Path(__file__).resolve().parents[1]
def module(name):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/f'{name}.py')
    m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def timestamp_evidence(video):
    """Read container ticks; Android MediaExtractor truncates rational PTS to µs."""
    result=json.loads(subprocess.check_output(['ffprobe','-v','error','-select_streams','v:0',
        '-show_frames','-show_streams','-show_entries',
        'stream=time_base:frame=best_effort_timestamp','-of','json',str(video)],text=True))
    time_base=Fraction(result['streams'][0]['time_base'])
    ticks=[int(frame['best_effort_timestamp']) for frame in result['frames']]
    return timestamp_fields(ticks,time_base)

def timestamp_fields(ticks,time_base):
    assert time_base>0 and ticks and ticks[0]>=0 and all(a<b for a,b in zip(ticks,ticks[1:])), 'Invalid container PTS'
    micros=[tick*time_base.numerator*1_000_000//time_base.denominator for tick in ticks]
    assert all(a<b for a,b in zip(micros,micros[1:])), 'Microsecond timebase lost frame ordering'
    return dict(sourcePtsTicks=ticks,sourceTimeBase=dict(numerator=time_base.numerator,
        denominator=time_base.denominator),sourcePtsMicroseconds=micros)


def prepare_smoke(smoke,source,spec):
    """Keep Android derivatives separate from the Apple test bundle in this checkout."""
    destination=ROOT/'build/android-fixtures/smoke'
    smoke.acquire(spec,source)
    smoke.prepare(source,destination,spec,reproducible=True)
    return destination


def selected_pose_assets(prepared, destination):
    """Package every prepared source, without sending movement labels to inference."""
    source_tools=module('prepare-temporal-pilot')
    manifest=json.loads((prepared/'manifest.json').read_text())
    references=json.loads((prepared/'reference.json').read_text())
    source_tools.ev.validate_manifest(manifest,prepared)
    assert references['manifest_sha256']==source_tools.ev.digest(prepared/'manifest.json')
    clips=manifest['clips'];refs=references['clips']
    assert clips and len(clips)==len(refs) and [c['id'] for c in clips]==[r['id'] for r in refs]
    # This directory contains only assets owned by this optional public diagnostic.
    if destination.exists():shutil.rmtree(destination)
    destination.mkdir(parents=True)
    output=[]
    for clip,ref in zip(clips,refs):
        assert clip['rights']['status']=='approved' and clip['rights']['public_outputs'] is True
        assert clip['media']['kind']=='video' and len(clip['media']['files'])==1
        video=prepared/clip['media']['files'][0]['path']
        sha=source_tools.ev.digest(video)
        assert sha==clip['media']['files'][0]['sha256']==ref['media_sha256']
        pts=timestamp_evidence(video);_,size=source_tools.probe(video)
        assert len(pts['sourcePtsMicroseconds'])==clip['media']['expected_frames']==len(ref['frame_pts_seconds'])
        shutil.copyfile(video,destination/(clip['id']+'.mp4'))
        output.append(dict(id=clip['id'],video=clip['id']+'.mp4',sha256=sha,
            sourceSha256=ref['source_sha256'],expectedFrames=len(pts['sourcePtsMicroseconds']),
            imageSize=dict(width=size[0],height=size[1]),**pts))
    result=dict(schemaVersion=1,scope='Complete public source pose export only; no counting, form or accuracy qualification',
        preparedManifestSha256=source_tools.ev.digest(prepared/'manifest.json'),
        selectionRecipeSha256=refs[0]['recipe_sha256'],clips=output)
    assert all(r['recipe_sha256']==result['selectionRecipeSha256'] for r in refs)
    (destination/'manifest.json').write_text(json.dumps(result,indent=2)+'\n')
    shutil.copyfile(prepared/'ATTRIBUTION.txt',destination/'ATTRIBUTION.txt')
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--selected-pose-diagnostic',action='store_true')
    args=parser.parse_args()
    import fixture_encoding as encoding
    smoke=module('prepare-fixtures')
    source=ROOT/'Data/external/p0-pullup-source.webm'
    spec=json.loads(smoke.SPEC_PATH.read_text())
    smoke_dest=prepare_smoke(smoke,source,spec)
    temporal=ROOT/'build/android-fixtures/temporal'
    if temporal.exists():shutil.rmtree(temporal)
    subprocess.run(['python3',str(ROOT/'scripts/prepare-temporal-pilot.py'), '--cache',str(ROOT/'Data/external/android-ci'),
                    '--output',str(temporal),'--only','dip_controlled_pexels','--fetch','--reproducible'],check=True)
    dest=ROOT/'android/app/src/androidTest/assets/generated';dest.mkdir(parents=True,exist_ok=True)
    selected_assets=dest/'selected-poses'
    if selected_assets.exists():shutil.rmtree(selected_assets)
    if args.selected_pose_diagnostic:
        selected=ROOT/'build/android-fixtures/selected-poses'
        if selected.exists():shutil.rmtree(selected)
        source_tools=module('prepare-temporal-pilot')
        source_tools.prepare(ROOT/'Data/external/android-ci',selected,True,reproducible=True)
        selected_pose_assets(selected,selected_assets)
    refs=json.loads((temporal/'reference.json').read_text())['clips']
    source_tools=module('prepare-temporal-pilot')
    positive=ROOT/'build/android-fixtures/positive'
    if positive.exists():shutil.rmtree(positive)
    source_tools.prepare(ROOT/'Data/external/android-ci',positive,True,ROOT/'Evaluation/fixtures/android-positive.json',reproducible=True)
    positive_ref=json.loads((positive/'reference.json').read_text())['clips'][0]
    clips=[]
    for id,video,exercise,side,edge,expected,pts,source_hash,role in [
        ('pullup_margin',positive/'pullup_margin.mp4','pullUp','left',positive_ref['bar_reference_edge'],1,
         positive_ref['frame_pts_seconds'],positive_ref['source_sha256'],'positive'),
        ('pullup_smoke',smoke_dest/'pullup-smoke.mp4','pullUp','left',[280,4,395,8],1,
         json.loads((smoke_dest/'prepared.json').read_text())['frame_pts_seconds'],spec['source_sha1'],'diagnostic'),
        ('dip_controlled_pexels',temporal/'dip_controlled_pexels.mp4','dip','left',refs[0]['bar_reference_edge'],9,
         refs[0]['frame_pts_seconds'],refs[0]['source_sha256'],'diagnostic')]:
        shutil.copyfile(video,dest/video.name)
        clips.append(dict(id=id,video=video.name,exercise=exercise,side=side,
            referenceEdge=dict(a=dict(x=edge[0],y=edge[1]),b=dict(x=edge[2],y=edge[3])),
            qualificationRole=role,expectedMovements=expected,expectedFrames=len(pts),sourcePtsSeconds=pts,
            sha256=smoke.digest(video),sourceHash=source_hash,**timestamp_evidence(video)))
    setup_spec=json.loads((ROOT/'Evaluation/fixtures/android-setup.json').read_text())
    dip_source=source_tools.source_file(setup_spec['dip_source'],ROOT/'Data/external/android-ci',True)
    setup_clips=[]
    for id,video,exercise,first_frame in [('pullup_held_start',positive/'pullup_margin.mp4','pullUp',setup_spec['pullup_source_frame']-72),
                              ('dip_held_start',dip_source,'dip',setup_spec['source_frame'])]:
        target=dest/(id.replace('_','-')+'.mp4')
        subprocess.run(['ffmpeg','-v','error','-y',*encoding.input_options(True),'-i',str(video),'-an','-vf',
                        f'trim=start_frame={first_frame}:end_frame={first_frame+1},setpts=PTS-STARTPTS,scale=960:-2:flags={encoding.scale_flags(True,"bicubic")},tpad=stop_mode=clone:stop_duration=6,fps=12',
                        '-frames:v','72','-c:v','libx264','-pix_fmt','yuv420p',*encoding.output_options(True),str(target)],check=True)
        pts,size=source_tools.probe(target)
        assert len(pts)==72 and pts[0]==0, 'Authored setup timeline changed'
        setup_clips.append(dict(id=id,video=target.name,exercise=exercise,expectedFrames=72,
            sourcePtsSeconds=pts,sha256=smoke.digest(target),scope=setup_spec['scope'],**timestamp_evidence(target)))
    manifest=dict(schemaVersion=1,scope='Public development evidence; positive accuracy required, original capture failures retained as diagnostic. No form or held-out accuracy verdict',clips=clips,
                  setupClips=setup_clips)
    (dest/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    evidence=ROOT/'build/android-evaluation';evidence.mkdir(parents=True,exist_ok=True)
    (evidence/'fixture-preparation.json').write_text(json.dumps(dict(
        schemaVersion=1,encoding=encoding.provenance(True),
        ffmpeg=subprocess.check_output(['ffmpeg','-version'],text=True),
        manifestSha256=smoke.digest(dest/'manifest.json'),
        clips=[{key:clip[key] for key in ('id','sha256','sourceHash','expectedFrames')} for clip in clips],
        setupClips=[{key:clip[key] for key in ('id','sha256','expectedFrames')} for clip in setup_clips]
    ),indent=2)+'\n')
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
