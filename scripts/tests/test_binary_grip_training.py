import importlib.util
from pathlib import Path
import unittest

PATH = Path(__file__).resolve().parents[1] / "train-binary-grip.py"
spec = importlib.util.spec_from_file_location("binary_grip_training", PATH)
trainer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(trainer)


def rows():
    return [dict(features=[float(sign)]+[0.0]*18, featureVersion=1, label=label,
        recordingId=str(i+1)*64, subjectId="person", split="train", seconds=float(t), side="left")
        for i, (label, sign) in enumerate([("overhand", -1), ("underhand", 1)]) for t in range(10)]


class BinaryGripTrainingTests(unittest.TestCase):
    def test_one_recording_per_class_is_development_only(self):
        trainer.validate(rows(), True)
        with self.assertRaises(ValueError):
            trainer.validate(rows(), False)

    def test_subject_and_recording_leakage_are_rejected(self):
        original = rows()
        for replacement in [dict(split="validation"), dict(split="validation", recordingId="a"*64)]:
            with self.assertRaises(ValueError):
                trainer.validate(original+[dict(original[0], seconds=30.0, **replacement)], False)

    def test_unknown_people_are_not_fabricated_or_accepted_as_person_holdouts(self):
        data = [dict(r, subjectId=None) for r in rows()]
        trainer.validate(data, True)
        with self.assertRaises(ValueError):
            trainer.validate(data, False)
        model = trainer.train(data, "0"*64)
        metrics = trainer.evaluate(model, data)["train"]
        self.assertEqual(metrics["subjects"], 0)
        self.assertEqual(metrics["unknownSubjectRecordings"], 2)

    def test_complete_independent_groups_are_required(self):
        data = []
        for i, split in enumerate(["train", "validation", "test"]):
            data.extend(dict(r, split=split, subjectId=f"person-{i}", recordingId=f"{i*2+j+1:064x}")
                        for j, r in enumerate([rows()[0], rows()[-1]]))
        trainer.validate(data, False)

    def test_invalid_data_and_duplicate_samples_fail(self):
        for data in [rows()+[rows()[0]], [dict(rows()[0], features=[float("nan")]*19)]+rows()[1:],
                     [dict(rows()[0], label="neutral")]+rows()[1:], [dict(rows()[0], featureVersion=2)]+rows()[1:]]:
            with self.assertRaises(ValueError):
                trainer.validate(data, True)

    def test_training_learns_from_targets_and_keeps_label_order(self):
        # Analytic learning mechanics only, not workout/grip accuracy evidence.
        data = rows()
        model = trainer.train(data, "0"*64)
        score, available = trainer.scores(model, [data[0]["features"], data[-1]["features"]])
        self.assertTrue(available.all())
        self.assertLess(score[0], .1); self.assertGreater(score[1], .9)
        self.assertEqual(model["labels"], ["overhand", "underhand"])
        self.assertEqual(model["qualification"], "developmentOnly")


if __name__ == "__main__":
    unittest.main()
