"""Header discrepancies may be staged, but scoring requires explicit review."""
import unittest
import test_import_poses as imports
import test_stage_penn as intake


class NativeGeometryTests(unittest.TestCase):
    def setUp(self):
        self.fixture = imports.ImportTests()
        self.fixture.setUp()
        self.fixture.data['dimensions'] = [64, 97, 2]
        self.fixture.change_mat()

    def tearDown(self):
        self.fixture.tearDown()

    def resolution(self, **overrides):
        value = dict(native_size=[97, 64], image_size=[96, 64],
                     coordinate_mapping='identity', evidence='synthetic header mismatch')
        value.update(overrides)
        self.fixture.clip['annotation_review']['geometry_resolution'] = value
        return value

    def test_header_mismatch_without_review_is_rejected(self):
        self.assertEqual(self.fixture.run_import(), 2)
        self.assertFalse((self.fixture.out / 'manifest.json').exists())

    def test_exact_review_preserves_points_and_original_bytes(self):
        resolution = self.resolution()
        original = imports.ev.digest(self.fixture.labels)
        self.assertEqual(self.fixture.run_import(), 0)
        reference = self.fixture.reference()
        self.assertEqual(reference['frames'][0]['points']['leftWrist'], [15, 25])
        self.assertEqual(reference['frames'][0]['width'], 96)
        self.assertEqual(reference['conversion']['geometry_resolution'], resolution)
        self.assertEqual(imports.ev.digest(self.fixture.labels), original)

    def test_wrong_review_is_not_a_blanket_tolerance(self):
        self.resolution(native_size=[98, 64])
        self.assertEqual(self.fixture.run_import(), 2)

    def test_review_cannot_implicitly_rescale_labels(self):
        self.resolution(coordinate_mapping='scale')
        self.assertEqual(self.fixture.run_import(), 2)

    def test_empty_review_evidence_is_rejected(self):
        self.resolution(evidence='')
        self.assertEqual(self.fixture.run_import(), 2)

    def test_header_mismatch_stays_pending_after_original_byte_staging(self):
        fixture = intake.IntakeTests()
        fixture.setUp()
        try:
            for frame in (1, 2):
                fixture.files[f'Penn_Action/frames/0001/{frame:06d}.jpg'] = intake.jpeg(size=(95, 64), seed=frame)
            fixture.write_archive()
            report = intake.intake.stage(fixture.archive, fixture.output)
            self.assertEqual(report['geometry_review_required'], ['penn_0001'])
            clip = intake.ev.read_json(fixture.output / 'review.json')['clips'][0]
            self.assertEqual(clip['native_geometry'], dict(native_size=[96, 64], image_size=[95, 64], status='review_required'))
            self.assertEqual(clip['rights']['status'], 'pending')
            self.assertNotIn('geometry_resolution', clip['annotation_review'])
            for item in clip['media']['files']:
                self.assertEqual((fixture.output / item['path']).read_bytes(), fixture.files['Penn_Action/' + item['path']])
            clip['rights'] = dict(status='approved', evidence='original synthetic test bytes', public_outputs=False)
            clip['annotation_review'] = dict(independently_reviewed=True, pixel_origin=0, provenance='synthetic')
            intake.ev.write_json(fixture.output / 'synthetic-review.json', dict(schema_version=1, clips=[clip]))
            self.assertEqual(intake.imp.convert_manifest(fixture.output / 'synthetic-review.json', fixture.output, fixture.output / 'converted'), 2)
            self.assertFalse((fixture.output / 'converted/manifest.json').exists())
        finally:
            fixture.tearDown()


if __name__ == '__main__':
    unittest.main()
