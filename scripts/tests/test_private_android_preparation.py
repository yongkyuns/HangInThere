"""Private fixture preparation: strict labels, shared assets and actual VFR evidence."""
import copy
from fractions import Fraction
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location('private_android_preparation', Path(__file__).parents[1]/'prepare-private-android-evaluation.py')
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)


class PrivatePreparationTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.video = self.root/'personal-filename.mov'
        self.video.write_bytes(b'original-video-stand-in')
        self.row = dict(id='recording_a', videoPath=str(self.video), exercise='pullUp', expectedMovements=2,
            referenceImageSize=dict(width=1080, height=1920),
            referenceEdges={side:dict(a=dict(x=320,y=431),b=dict(x=737,y=454)) for side in ('left','right')})

    def manifest(self, row=None):
        return dict(schemaVersion=1, recordings=[copy.deepcopy(row or self.row)])

    def test_rejects_unsafe_asset_ids_and_duplicate_assets(self):
        for identifier in ('../original', 'recording_a/secret', 'original_filename', 'recording_A', 'recording_a.mp4'):
            row=copy.deepcopy(self.row);row['id']=identifier
            with self.assertRaises(ValueError):PREPARE.validate_manifest(self.manifest(row))
        manifest=self.manifest();manifest['recordings'].append(copy.deepcopy(self.row))
        with self.assertRaisesRegex(ValueError,'Duplicate'):PREPARE.validate_manifest(manifest)
        manifest['recordings'][1]['id']='recording_b'
        with self.assertRaisesRegex(ValueError,'Repeated source'):PREPARE.validate_manifest(manifest)

    def test_frozen_labels_are_exact_integers_and_concrete_exercises(self):
        for label in (True,2.0,-1,1001,'2'):
            row=copy.deepcopy(self.row);row['expectedMovements']=label
            with self.assertRaises(ValueError):PREPARE.validate_manifest(self.manifest(row))
        for exercise in ('automatic','pullup',None):
            row=copy.deepcopy(self.row);row['exercise']=exercise
            with self.assertRaises(ValueError):PREPARE.validate_manifest(self.manifest(row))
        self.assertEqual(2,PREPARE.validate_manifest(self.manifest())[0]['expectedMovements'])

    def test_both_references_required_and_bounds_verified_before_inference(self):
        for mutate in (lambda r:r['referenceEdges'].pop('right'),
                       lambda r:r['referenceEdges']['left']['a'].update(x=-1),
                       lambda r:r['referenceEdges']['left']['a'].update(x=float('nan')),
                       lambda r:r['referenceEdges']['left'].update(b=r['referenceEdges']['left']['a'])):
            row=copy.deepcopy(self.row);mutate(row)
            with self.assertRaises(ValueError):PREPARE.validate_manifest(self.manifest(row))

    def test_rotation_and_reference_conversion_match_actual_upright_pixels(self):
        size, rotation=PREPARE.upright_size(dict(width=1920,height=1080,side_data_list=[dict(rotation=-90)]))
        self.assertEqual(dict(width=1080,height=1920),size);self.assertEqual(-90,rotation)
        target=PREPARE.target_size(size,1280)
        self.assertEqual(dict(width=720,height=1280),target)
        edge=PREPARE.scaled_edge(self.row['referenceEdges']['left'],size,target)
        self.assertAlmostEqual(320*2/3,edge['a']['x']);self.assertAlmostEqual(431*2/3,edge['a']['y'])
        with self.assertRaises(ValueError):PREPARE.upright_size(dict(width=1080,height=1920,side_data_list=[dict(rotation=15)]))

    def test_exact_rational_pts_preserved_across_different_track_timebases(self):
        original=PREPARE.TIMESTAMPS.timestamp_fields([0,20,55,80],Fraction(1,600))
        derivative=PREPARE.TIMESTAMPS.timestamp_fields([0,3000,8250,12000],Fraction(1,90000))
        PREPARE.verify_timeline(original,derivative)
        self.assertEqual([0,33333,91666,133333],derivative['sourcePtsMicroseconds'])
        for ticks in ([0,3000,8250],[0,3000,8251,12000],[0,3000,8250,12000,15000]):
            with self.assertRaises(ValueError):PREPARE.verify_timeline(original,PREPARE.TIMESTAMPS.timestamp_fields(ticks,Fraction(1,90000)))

    def test_hash_pin_validation_and_digest_detect_replaced_bytes(self):
        before=PREPARE.digest(self.video);self.video.write_bytes(b'replaced-original')
        self.assertNotEqual(before,PREPARE.digest(self.video))
        row=copy.deepcopy(self.row);row['sourceSha256']='bad-pin'
        with self.assertRaises(ValueError):PREPARE.validate_manifest(self.manifest(row))

    def test_wrong_original_hash_rejects_before_video_decode_and_leaves_no_export(self):
        row=copy.deepcopy(self.row);row['sourceSha256']='0'*64
        manifest_path=self.root/'frozen-input.json';manifest_path.write_text(json.dumps(self.manifest(row)))
        output=self.root/'never-created'
        with mock.patch.object(PREPARE,'media_probe') as probe:
            with self.assertRaisesRegex(ValueError,'integrity pin'):PREPARE.prepare(manifest_path,output)
            probe.assert_not_called()
        self.assertFalse(output.exists())

    def test_hdr_pipeline_decodes_linear_light_before_tonemap_and_fails_without_zscale(self):
        stream=dict(color_transfer='arib-std-b67',color_primaries='bt2020')
        filters=PREPARE.pixel_filter(stream,dict(width=720,height=1280))
        self.assertLess(filters.index('transfer=linear'),filters.index('tonemap='))
        self.assertIn('primaries=bt709:transfer=bt709:matrix=bt709',filters)
        with mock.patch.object(PREPARE.subprocess,'check_output',return_value=' tonemap '):
            with self.assertRaisesRegex(ValueError,'zscale'):PREPARE.transcode(self.video,self.root/'derivative.mp4',dict(width=720,height=1280),PREPARE.TIMESTAMPS.timestamp_fields([0,20],Fraction(1,600)),stream)
        with self.assertRaises(ValueError):PREPARE.pixel_filter(dict(color_transfer='arib-std-b67'),dict(width=720,height=1280))

    @unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'),'FFmpeg required for real VFR preservation')
    def test_real_transcode_keeps_vfr_frames_labels_strips_metadata_and_shares_asset(self):
        source=self.root/'private-original.mp4'
        expression='if(eq(N,0),0,if(eq(N,1),20,if(eq(N,2),55,if(eq(N,3),80,if(eq(N,4),120,170)))))'
        subprocess.run(['ffmpeg','-v','error','-y','-f','lavfi','-i','testsrc2=size=160x240:rate=30:duration=0.2',
            '-vf',f"settb=1/600,setpts='{expression}'",'-fps_mode','passthrough','-enc_time_base','1:600',
            '-video_track_timescale','600','-c:v','libx264','-pix_fmt','yuv420p',
            '-metadata','title=private-original-label',str(source)],check=True)
        row=copy.deepcopy(self.row);row.update(videoPath=str(source),referenceImageSize=dict(width=160,height=240),expectedFrames=6)
        row['referenceEdges']={side:dict(a=dict(x=20,y=60),b=dict(x=120,y=65)) for side in ('left','right')}
        manifest_path=self.root/'local-input.json';manifest_path.write_text(json.dumps(self.manifest(row)))
        output=self.root/'anonymous-output';export=PREPARE.prepare(manifest_path,output)
        self.assertEqual(['recording_a_left','recording_a_right'],[c['id'] for c in export['cases']])
        self.assertEqual({'recording_a.mp4'},{c['asset'] for c in export['cases']})
        self.assertEqual([2,2],[c['expectedMovements'] for c in export['cases']])
        for case in export['cases']:
            self.assertEqual(6,case['frames'])
            self.assertEqual([0,33333,91666,133333,200000,283333],case['sourcePtsMicroseconds'])
            self.assertEqual(PREPARE.digest(output/case['asset']),case['sha256'])
        exported_text=(output/'manifest.json').read_text()+(output/'local-provenance.json').read_text()
        self.assertNotIn(str(self.root),exported_text);self.assertNotIn('private-original',exported_text)
        metadata=PREPARE.media_probe(output/'recording_a.mp4')
        self.assertNotIn('title',metadata['format'].get('tags',{}))
        with self.assertRaisesRegex(ValueError,'already exists'):PREPARE.prepare(manifest_path,output)

if __name__=='__main__':unittest.main()
