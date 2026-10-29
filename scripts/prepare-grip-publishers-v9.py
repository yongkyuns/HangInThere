#!/usr/bin/env python3
"""Fixed publisher RGB sampling for development only; automatic SDK proposals.

GIF frames retain composited source RGB and original frame durations. Video
frames are ffmpeg RGB decodes at original frame indices/PTS. These are hand
component fitting inputs, not an Apple end-to-end localization benchmark.
"""
import os
os.environ.setdefault('OMP_NUM_THREADS','2')
import argparse,hashlib,json,subprocess
from pathlib import Path
import numpy as np
import mediapipe as mp
from PIL import Image
ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--mytrainingpro',action='store_true');args=parser.parse_args()
    root=ROOT/'build/grip-front-rear-v9'/('publishers-additional' if args.mytrainingpro else 'publishers');root.mkdir(exist_ok=False)
    data=ROOT/'Data/external/grip-front-rear-v9'
    cases=[]
    specs=[('tuscaloosa-neutral','mp4','neutral','rear','publisher:tuscaloosa:jake-barnett','https://www.youtube.com/watch?v=TXIhho2I1Zk'),
      ('strengthlog-neutral','gif','neutral','rear','publisher:strengthlog:female-rack','https://www.strengthlog.com/pull-ups-with-a-neutral-grip/'),
      ('strengthlog-chin','gif','underhand','rear','publisher:strengthlog:female-rack','https://www.strengthlog.com/chin-up/'),
      ('legion-neutral','gif','neutral','rear','publisher:legion:neutral-demo-2025','https://legionathletics.com/neutral-grip-pull-up/')]
    if args.mytrainingpro:
        specs=[('mytrainingpro-neutral','mp4','neutral','rear','publisher:mytrainingpro:blue-tank-female','https://www.youtube.com/watch?v=A9lX5OZ8Dz4')]
    for name,ext,grip,view,group,url in specs:
        p=data/(name+'.'+ext)
        cases.append(dict(id=name,path=str(p),sha256=sha(p),bytes=p.stat().st_size,sourceGroup=group,
                     split='validation',provisionalGrip=grip,provisionalView=view,sourceURL=url))
    # Freeze complete source groups and sampling before any hand detector runs.
    (root/'mining-intake.json').write_text(json.dumps(dict(role='validationDevelopmentOnly',
        sampling='Video: 2,6,...54 seconds; GIF: eight equally spaced original frame indices, including first and last',
        qualification=__doc__,cases=cases),indent=2)+'\n')
    options=mp.tasks.vision.PoseLandmarkerOptions(base_options=mp.tasks.BaseOptions(model_asset_path=str(ROOT/'models/grip/pose_landmarker_lite.task')),
        num_poses=2,min_pose_detection_confidence=.5,min_pose_presence_confidence=.5)
    native=root/'native';(native/'frames').mkdir(parents=True)
    prepared=[]
    with mp.tasks.vision.PoseLandmarker.create_from_options(options) as pose:
        for c in cases:
            p=Path(c['path']);folder=native/'frames'/c['id'];folder.mkdir();samples=[]
            if p.suffix=='.gif':
                im=Image.open(p);clock=[];ms=0
                for i in range(im.n_frames):
                    im.seek(i);clock.append(ms);ms+=im.info.get('duration',100)
                indices=sorted(set(round(i*(im.n_frames-1)/7) for i in range(8)))
                for i in indices:
                    im.seek(i);dest=folder/f'frame-{i:04d}.png';im.convert('RGB').save(dest)
                    samples.append((dest,clock[i],i,'Pillow composited GIF RGB'))
            else:
                probe=json.loads(subprocess.check_output(['ffprobe','-v','error','-select_streams','v:0','-show_frames','-show_entries','frame=best_effort_timestamp_time','-of','json',str(p)]))
                times=[float(x['best_effort_timestamp_time']) for x in probe['frames']]
                indices=sorted(set(min(range(len(times)),key=lambda i:abs(times[i]-t)) for t in range(2,55,4)))
                for i in indices:
                    dest=folder/f'frame-{i:04d}.png'
                    subprocess.run(['ffmpeg','-v','error','-i',str(p),'-vf',f'select=eq(n\\,{i})','-fps_mode','vfr','-frames:v','1',str(dest)],check=True)
                    samples.append((dest,round(times[i]*1e6),i,'ffmpeg original frame index to RGB'))
            rows=[]
            for dest,t,idx,decoder in samples:
                rgb=Image.open(dest).convert('RGB');w,h=rgb.size
                result=pose.detect(mp.Image(image_format=mp.ImageFormat.SRGB,data=np.asarray(rgb)))
                people=[]
                for person in result.pose_landmarks:
                    points=[]
                    for joint,j in [('leftWrist',15),('rightWrist',16)]:
                        x=person[j];points.append(dict(joint=joint,confidence=min(x.visibility,x.presence),position=dict(x=x.x*w,y=x.y*h)))
                    people.append(dict(landmarks=points))
                scale=1000 if p.suffix=='.gif' else 1000000
                view=('rear' if t/scale<16 else 'oblique' if t/scale<36 else 'front') if c['id']=='tuscaloosa-neutral' else c['provisionalView']
                rows.append(dict(image=dest.name,imageSHA256=sha(dest),sourceFrameIndex=idx,decoder=decoder,
                    originalSourceSHA256=c['sha256'],reviewedSourceView=view,
                    pose=dict(backend='MediaPipe Pose Lite (dataset ROI search only)',imageSize=dict(width=w,height=h),people=people,timestamp=dict(value=t,timescale=scale))))
            (folder/'frames.jsonl').write_text(''.join(json.dumps(x)+'\n' for x in rows))
            prepared.append(dict(c,status='ready',framesDirectory=str(folder),qualification=__doc__,samples=len(rows)))
            print(c['id'],len(rows),flush=True)
    (native/'prepared-threeway.json').write_text(json.dumps(dict(qualification=__doc__,cases=prepared),indent=2)+'\n')
if __name__=='__main__':main()
