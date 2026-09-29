import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
import inventory


class CountixClassAvailabilityTests(unittest.TestCase):
    def test_missing_class_is_reported_not_inferred(self):
        section = inventory.parse_csv("countix", b"video_id,kinetics_start,kinetics_end,repetition_start,repetition_end,count\nabc,0,10,1,9,3\n", "test")
        self.assertEqual(section["metadata_rows"], 1)
        self.assertEqual(section["unclassified_rows"], 1)
        self.assertEqual(section["selected"], [])
