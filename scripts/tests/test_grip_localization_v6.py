"""Localization geometry, ownership uncertainty and bounded temporal selection."""
import importlib.util
from pathlib import Path
import unittest

import numpy as np

spec = importlib.util.spec_from_file_location('localization_test', Path(__file__).resolve().parents[1]/'grip-localization-v6.py')
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


class HandLocalizationTests(unittest.TestCase):
    def row(self, people=None, faces=None):
        return dict(pose=dict(people=people or [], imageSize=dict(width=640, height=360)),
                    localizationFaces=dict(faces=faces or []))

    def person(self, x):
        return dict(landmarks=[dict(joint=k, confidence=.95, position=dict(x=xx, y=y)) for k, xx, y in
             [('nose', x, 100), ('leftShoulder', x-25, 150), ('rightShoulder', x+25, 150),
              ('leftWrist', x-40, 40), ('rightWrist', x+40, 40)]])

    def test_hand_crop_contains_all_landmarks_and_short_wrist(self):
        points = np.array([[100+i%5*5, 100+i//5*5] for i in range(21)], dtype=float)
        points[0] = [110, 130]
        hand = policy.candidate(points, (640, 360), [0, 0, 640, 360])
        self.assertIsNotNone(hand)
        b = hand['box']
        self.assertEqual(b[2]-b[0], b[3]-b[1])
        self.assertTrue(np.all(points >= np.array(b[:2])))
        self.assertTrue(np.all(points <= np.array(b[2:])))

    def test_source_or_search_border_truncation_is_not_complete_hand(self):
        points = np.array([[i%5*5, 50+i//5*5] for i in range(21)], dtype=float)
        self.assertIsNone(policy.candidate(points, (640, 360), [0, 0, 640, 360]))
        points[:, 0] += 100
        self.assertIsNone(policy.candidate(points, (640, 360), [100, 0, 200, 200]))

    def test_bad_landmarks_do_not_create_wrist_only_fallback(self):
        self.assertIsNone(policy.candidate([[50, 50]], (640, 360), [0, 0, 640, 360]))
        points = np.ones((21, 2))*50
        self.assertIsNone(policy.candidate(points, (640, 360), [0, 0, 640, 360]))
        points[0, 0] = np.nan
        self.assertIsNone(policy.candidate(points, (640, 360), [0, 0, 640, 360]))

    def test_proposals_run_without_body_or_face_and_remain_bounded(self):
        requests = policy.regions((640, 360), None, [])
        self.assertEqual(requests[0], [0, 0, 640, 360])
        self.assertLessEqual(len(requests), 4)
        owner, reason = policy.primary(self.row())
        self.assertIsNone(owner)
        self.assertEqual(reason, 'ownershipUnavailable')

    def test_single_face_can_support_a_body_missing_hand_candidate(self):
        row = self.row(faces=[dict(confidence=.95, box=[260, 100, 340, 180])])
        owner, status = policy.primary(row)
        self.assertEqual(status, 'singleFaceProxy')
        hand = dict(wrist=[250, 60], points=[[250, 30]]*21, extent=30)
        self.assertIsNone(policy.ownership(hand, owner, row, []))
        owner['confirmed'] = True
        self.assertIsNotNone(policy.ownership(hand, owner, row, []))
        self.assertIsNone(policy.ownership(dict(hand, wrist=[600, 60]), owner, row, []))

    def test_missing_face_never_supplies_ownership(self):
        owner, _ = policy.primary(self.row())
        self.assertIsNone(policy.ownership(dict(wrist=[250, 60]), owner, self.row(), []))

    def test_competing_athletes_or_faces_block_spatial_proxy(self):
        self.assertIsNone(policy.primary(self.row(people=[self.person(100), self.person(400)]))[0])
        self.assertIsNone(policy.primary(self.row(faces=[dict(confidence=.9, box=[0, 0, 50, 50]), dict(confidence=.9, box=[100, 0, 150, 50])]))[0])

    def test_another_person_at_candidate_wrist_blocks_ownership(self):
        athlete = self.person(200)
        bystander = dict(landmarks=[dict(joint='leftWrist', confidence=.9, position=dict(x=160, y=40))])
        row = self.row(people=[athlete, bystander])
        owner, _ = policy.primary(row)
        self.assertIsNone(policy.ownership(dict(wrist=[160, 40]), owner, row, []))

    def test_hand_below_work_region_is_not_grip_evidence(self):
        row = self.row(people=[self.person(200)])
        owner, _ = policy.primary(row)
        self.assertIsNone(policy.ownership(dict(wrist=[160, 200]), owner, row, []))

    def test_duplicate_roi_detection_does_not_add_hands(self):
        c = dict(wrist=[100, 100], extent=30, box=[80, 80, 120, 120])
        self.assertEqual(len(policy.deduplicate([c, dict(c)])), 1)

    def test_clearer_selection_keeps_source_time_and_does_not_mix_tracks(self):
        rows = []
        for t, score, track in [(.25, 5, 1), (.5, 20, 1), (.75, 10, 1), (.8, 30, 2)]:
            rows.append(dict(recordingId='video', trackId=track, seconds=t, imageSHA256=str(t),
              patches=[dict(status='patchReady', owner=dict(slot='left'), candidateId=str(t), patch='image', patchSHA256='hash', sharpness=score)]))
        selected = policy.select_clearer(rows)
        self.assertEqual(len(selected), 2)
        self.assertEqual(selected[0]['seconds'], .5)
        self.assertEqual(selected[0]['availableAtSeconds'], .75)


if __name__ == '__main__':
    unittest.main()
