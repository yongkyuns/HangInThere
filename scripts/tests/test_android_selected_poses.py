"""Authored export receipts protect source completeness, not model accuracy."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT=Path(__file__).resolve().parents[1]/'check-selected-pose-export.py'
SPEC=importlib.util.spec_from_file_location('selected_pose_check',SCRIPT)
CHECK=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(CHECK)

class SelectedPoseExportTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        recipe_path=CHECK.ROOT/'Evaluation/fixtures/temporal-pilot.json'
        recipe=json.loads(recipe_path.read_text());clips=[];summaries=[]
        for source in recipe['sources']:
            id=source['id'];count=source['frame_range'][1]-source['frame_range'][0]
            pts=[i*40_000 for i in range(count)]
            clip=dict(id=id,expectedFrames=count,sourceSha256=source['source_sha256'],sha256='a'*64,
                imageSize=dict(width=960,height=540),sourcePtsMicroseconds=pts,
                sourcePtsTicks=pts,sourceTimeBase=dict(numerator=1,denominator=1_000_000))
            rows=[dict(frameIndex=i,timestamp=dict(value=t,timescale=1_000_000),imageSize=clip['imageSize'],
                backend='MediaPipe Heavy CPU visibility/presence minimum',requestRevision=1,
                pixelSha256='b'*64,inferenceMillis=.1,people=[]) for i,t in enumerate(pts)]
            trace=self.root/f'selected-pose-{id}-observations.jsonl'
            trace.write_text(''.join(json.dumps(r)+'\n' for r in rows))
            clips.append(clip);summaries.append(dict(id=id,frames=count,singlePersonFrames=0,estimatedWorldFrames=0,
                sourceSha256='a'*64,observations=trace.name,observationsSha256=CHECK.sha(trace),decodedImageSize=clip['imageSize']))
        self.manifest=dict(schemaVersion=1,selectionRecipeSha256=CHECK.sha(recipe_path),clips=clips)
        self.report=dict(schemaVersion=1,complete=True,sdk=29,**CHECK.shipping_pins(),clips=summaries)
        (self.root/'report.json').write_text(json.dumps(dict(sdk=29,**CHECK.shipping_pins())))
        self.save()
    def save(self):
        path=self.root/'selected-pose-manifest.json';path.write_text(json.dumps(self.manifest))
        self.report['manifestSha256']=CHECK.sha(path)
        (self.root/'selected-pose-report.json').write_text(json.dumps(self.report))
    def mutate_trace(self,mutator,refresh_receipt=True):
        summary=self.report['clips'][0];path=self.root/summary['observations']
        rows=[json.loads(l) for l in path.read_text().splitlines()];mutator(rows)
        path.write_text(''.join(json.dumps(r)+'\n' for r in rows))
        if refresh_receipt:summary['observationsSha256']=CHECK.sha(path)
        self.save()
    def rejects(self):
        with self.assertRaises((ValueError,KeyError,TypeError)):CHECK.check(self.root)
    def test_complete_authored_export_has_exact_denominator(self):
        self.assertEqual((4,1866),(CHECK.check(self.root)['clips'],CHECK.check(self.root)['frames']))
    def test_missing_trailing_source_cannot_pass_zip(self):
        self.report['clips'].pop();self.save();self.rejects()
    def test_truncated_trace_fails_even_with_refreshed_trace_receipt(self):
        self.mutate_trace(lambda rows:rows.pop());self.rejects()
    def test_duplicate_source_time_fails_even_with_refreshed_trace_receipt(self):
        self.mutate_trace(lambda rows:rows[1].update(timestamp=rows[0]['timestamp']));self.rejects()
    def test_changed_native_backend_cannot_be_hidden_by_first_row_context(self):
        self.mutate_trace(lambda rows:rows[-1].update(backend='another model'));self.rejects()
    def test_missing_bitmap_receipt_fails(self):
        self.mutate_trace(lambda rows:rows[1].pop('pixelSha256'));self.rejects()
    def test_nonfinite_latency_fails(self):
        self.mutate_trace(lambda rows:rows[1].update(inferenceMillis=float('nan')));self.rejects()
    def test_observation_tamper_fails_its_published_receipt(self):
        self.mutate_trace(lambda rows:rows[1].update(inferenceMillis=2),refresh_receipt=False);self.rejects()
    def test_manifest_tamper_fails_without_updating_report(self):
        path=self.root/'selected-pose-manifest.json';path.write_text(path.read_text()+' ');self.rejects()
    def test_changed_original_source_cannot_become_a_new_manifest_pin(self):
        self.manifest['clips'][0]['sourceSha256']='e'*64;self.save();self.rejects()
    def test_incomplete_report_cannot_supply_prior_success(self):
        self.report['complete']=False;self.save();self.rejects()
    def test_changed_model_receipt_fails_even_if_native_report_agrees(self):
        self.report['modelSha256']='e'*64;self.save()
        (self.root/'report.json').write_text(json.dumps(self.report));self.rejects()
    def test_changed_runtime_receipt_fails_even_if_native_report_agrees(self):
        self.report['poseArtifactSha256']='e'*64;self.save()
        (self.root/'report.json').write_text(json.dumps(self.report));self.rejects()
    def test_changed_runtime_version_fails(self):
        self.report['poseArtifact']='com.google.mediapipe:tasks-vision:another';self.save();self.rejects()
    def test_mismatched_native_api_fails(self):
        self.report['sdk']=35;self.save();self.rejects()
    def test_pose_world_person_mapping_must_be_complete(self):
        def change(rows):
            rows[0].update(estimatedWorldConvention='cameraRightDownAway_hipOrigin_v1',
                people=[dict(landmarks=[dict(joint='leftShoulder')],estimatedWorldLandmarks=[])])
        self.mutate_trace(change);self.rejects()
    def test_portrait_source_retains_distinct_source_and_canonical_dimensions(self):
        self.manifest['clips'][0]['imageSize']=dict(width=960,height=1706)
        self.report['clips'][0]['decodedImageSize']=dict(width=720,height=1280)
        self.mutate_trace(lambda rows:[row.update(imageSize=dict(width=720,height=1280)) for row in rows])
        self.assertEqual(1866,CHECK.check(self.root)['frames'])
    def test_unscaled_portrait_rows_cannot_claim_shipping_decoder_geometry(self):
        self.manifest['clips'][0]['imageSize']=dict(width=960,height=1706)
        self.report['clips'][0]['decodedImageSize']=dict(width=960,height=1706)
        self.mutate_trace(lambda rows:[row.update(imageSize=dict(width=960,height=1706)) for row in rows]);self.rejects()

if __name__=='__main__':unittest.main()
