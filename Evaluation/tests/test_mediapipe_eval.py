import importlib.util
import math
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("mediapipe_eval", ROOT / "scripts/mediapipe_eval.py")
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

class Point:
    def __init__(self, x, y, visibility=1.0, presence=1.0):
        self.x, self.y = x, y
        self.visibility, self.presence = visibility, presence

class MediaPipeMappingTests(unittest.TestCase):
    def test_common_arm_points_map_to_pixels(self):
        points = [Point(0, 0) for _ in range(33)]
        points[11] = Point(.25, .50, .9, .8)
        points[13] = Point(.50, .25, .7, .95)
        points[15] = Point(.75, .10, .6, .4)
        person = mod.person_from_landmarks(points, 800, 400)
        by_joint = {x["joint"]: x for x in person["landmarks"]}
        self.assertEqual(by_joint["leftShoulder"]["position"], {"x": 200.0, "y": 200.0})
        self.assertEqual(by_joint["leftElbow"]["position"], {"x": 400.0, "y": 100.0})
        self.assertEqual(by_joint["leftWrist"]["position"], {"x": 600.0, "y": 40.0})
        self.assertAlmostEqual(by_joint["leftShoulder"]["confidence"], .8)
        self.assertAlmostEqual(by_joint["leftWrist"]["confidence"], .4)

    def test_confidence_is_conservative_and_finite(self):
        self.assertEqual(mod.usable_confidence(Point(0, 0, .9, .2)), .2)
        self.assertEqual(mod.usable_confidence(Point(0, 0, -1, 2)), 0.0)
        p = Point(0, 0); p.visibility = math.nan; p.presence = .7
        self.assertEqual(mod.usable_confidence(p), 0.0)

    def test_outside_points_are_not_clamped(self):
        points = [Point(0, 0) for _ in range(33)]
        points[15] = Point(1.2, -.1, .8, .8)
        person = mod.person_from_landmarks(points, 100, 200)
        wrist = next(x for x in person["landmarks"] if x["joint"] == "leftWrist")
        self.assertEqual(wrist["position"], {"x": 120.0, "y": -20.0})

    def test_mapping_does_not_invent_neck_or_root(self):
        points = [Point(.5, .5) for _ in range(33)]
        joints = {x["joint"] for x in mod.person_from_landmarks(points, 100, 100)["landmarks"]}
        self.assertNotIn("neck", joints)
        self.assertNotIn("root", joints)



    def test_invalid_signal_never_borrows_valid_other_signal(self):
        for value in (None, math.nan, math.inf, -0.1, 1.1, True, "0.7"):
            for field in ("visibility", "presence"):
                with self.subTest(value=value, field=field):
                    point = Point(0.1, 0.2, .9, .9)
                    setattr(point, field, value)
                    self.assertEqual(mod.usable_confidence(point), 0.0)

    def test_raw_scores_are_retained(self):
        points = [Point(.5, .5, .9, .2) for _ in range(33)]
        point = mod.person_from_landmarks(points, 100, 100)["landmarks"][0]
        self.assertEqual((point["visibility"], point["presence"]), (.9, .2))

from contextlib import ExitStack
import json
import sys
import tempfile
from types import SimpleNamespace as NS
from unittest.mock import patch
from PIL import Image
import numpy as np


class MediaPipeExecutionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.image = self.root / 'image.png'
        Image.new('RGB', (80, 40)).save(self.image)
        self.model = self.root / 'model.task'
        self.model.write_bytes(b'synthetic-model-not-for-inference')
        self.clip = dict(id='clip', dataset='contract', exercise='pull_up', split='smoke',
                         source_group='source', subject_group=None,
                         rights=dict(status='approved', evidence='Synthetic test', public_outputs=True),
                         media=dict(kind='images', expected_frames=1,
                                    files=[dict(path='image.png', sha256=mod.ev.digest(self.image))]))
        label = dict(schema_version=1, coordinates='upright_pixels_top_left', independently_reviewed=True,
                     provenance='Original synthetic contract reference', media_sha256=[mod.ev.digest(self.image)],
                     frames=[dict(frame_index=0, width=80, height=40, points={'leftElbow': [40, 20]})])
        self.label = self.root / 'label.json'
        mod.ev.write_json(self.label, label)
        self.clip['annotations'] = dict(path='label.json', sha256=mod.ev.digest(self.label))
        self.manifest = self.root / 'manifest.json'
        self.write_manifest()
        self.callback = lambda: NS(pose_landmarks=[[Point(.5, .5) for _ in range(33)]])
        self.options = None
        parent = self
        class Landmarker:
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def detect(self, image): return parent.callback()
        def create(options):
            parent.options = options
            return Landmarker()
        class BaseOptions:
            Delegate = NS(CPU='CPU')
            def __init__(self, **kwargs): self.__dict__.update(kwargs)
        def decode(path):
            with Image.open(path) as image: pixels = np.array(image.convert('RGB'))
            return NS(numpy_view=lambda: pixels)
        self.mp = NS(__version__='synthetic-test-runtime', Image=NS(create_from_file=decode),
                     tasks=NS(BaseOptions=BaseOptions, vision=NS(PoseLandmarkerOptions=lambda **x: NS(**x),
                     RunningMode=NS(IMAGE='IMAGE'), PoseLandmarker=NS(create_from_options=create))))

    def write_manifest(self):
        mod.ev.write_json(self.manifest, dict(schema_version=1, confidence_threshold=.3, clips=[self.clip]))

    def run_fixture(self, public=True):
        out = self.root / 'output'
        with ExitStack() as stack:
            stack.enter_context(patch.dict(sys.modules, mediapipe=self.mp))
            stack.enter_context(patch.object(mod, 'model_contract', return_value=NS(
                MODEL_ID=mod.MODEL_ID, SHA256=mod.ev.digest(self.model))))
            code = mod.run(self.manifest, self.root, out, self.model, public)
        return code, mod.ev.read_json(out / 'report.json')

    def test_two_people_remain_ambiguous(self):
        self.callback = lambda: NS(pose_landmarks=[[Point(.5, .5) for _ in range(33)]] * 2)
        code, report = self.run_fixture()
        self.assertEqual(code, 0)
        self.assertEqual(self.options.num_poses, 2)
        row = report['clips'][0]
        self.assertEqual(row['pose_metrics']['ambiguous_person_frames'], 1)
        self.assertEqual(row['pose_metrics']['joint_pixels']['leftElbow']['coverage'], 0)
        self.assertEqual(len(next(mod.read_observations(self.root / 'output/clip/observations.jsonl'))['people']), 2)

    def test_real_schema_and_hashes_no_invented_image_timestamp(self):
        code, report = self.run_fixture()
        self.assertEqual(code, 0)
        self.assertEqual(report['clips'][0]['pose_metrics']['joint_pixels']['leftElbow']['mean'], 0)
        self.assertEqual(report['clips'][0]['annotation_sha256'], mod.ev.digest(self.label))
        self.assertEqual(report['confidence_threshold'], .3)
        completion = mod.ev.read_json(self.root / 'output/clip/completion.json')
        self.assertEqual(completion, dict(status='processed', frames=1))
        self.assertIsNone(next(mod.read_observations(self.root / 'output/clip/observations.jsonl'))['timestamp'])

    def test_input_change_invalidates_score(self):
        def mutate():
            self.label.write_text('{}')
            return NS(pose_landmarks=[])
        self.callback = mutate
        code, report = self.run_fixture()
        self.assertEqual(code, 2)
        self.assertEqual(report['clips'][0]['pose_metrics']['status'], 'not_evaluated')

    def test_model_change_during_inference_invalidates_score(self):
        def mutate():
            self.model.write_bytes(b'changed model')
            return NS(pose_landmarks=[])
        self.callback = mutate
        code, report = self.run_fixture()
        self.assertEqual(code, 2)
        self.assertEqual(report['clips'][0]['pose_metrics']['status'], 'not_evaluated')

    def test_unknown_model_is_rejected_before_runtime_import(self):
        with self.assertRaisesRegex(ValueError, 'pinned Heavy'):
            mod.run(self.manifest, self.root, self.root / 'output', self.model)
        self.assertFalse((self.root / 'output').exists())

    def test_inference_error_retains_failed_completion_not_private_message(self):
        def fail(): raise RuntimeError('/private/recording/my-name.png')
        self.callback = fail
        code, report = self.run_fixture()
        self.assertEqual(code, 2)
        self.assertNotIn('/private', json.dumps(report))
        self.assertEqual(mod.ev.read_json(self.root / 'output/clip/completion.json'),
                         dict(status='engine_failure', frames=0))

    def test_nonpublic_data_requires_explicit_local_mode(self):
        self.clip['rights']['public_outputs'] = False
        self.write_manifest()
        code, report = self.run_fixture()
        self.assertEqual(code, 2)
        self.assertEqual(report['clips'][0]['status'], 'outputs_not_approved')

    def test_private_evaluation_does_not_require_public_permission(self):
        self.clip['rights']['public_outputs'] = False
        self.write_manifest()
        code, report = self.run_fixture(public=False)
        self.assertEqual(code, 0)

    def test_rotated_exif_is_rejected_instead_of_silent_misorientation(self):
        exif = Image.Exif(); exif[274] = 6
        Image.new('RGB', (80, 40)).save(self.image, exif=exif)
        with self.assertRaisesRegex(ValueError, 'pre-oriented'):
            mod.load_image_dimensions(self.mp, self.image)

    def test_truncated_stream_is_not_accepted(self):
        code, _ = self.run_fixture()
        path = self.root / 'output/clip/observations.jsonl'
        row = mod.ev.read_json(path)
        row['frameIndex'] = 1
        mod.ev.write_json(path, row)
        with self.assertRaises((ValueError, KeyError, json.JSONDecodeError)):
            list(mod.read_observations(path))


if __name__ == '__main__':
    unittest.main()
