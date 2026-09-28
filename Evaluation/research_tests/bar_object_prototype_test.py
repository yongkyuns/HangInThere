#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/prepare-bar-object-prototype.py"
spec = importlib.util.spec_from_file_location("bar_object_prep", SCRIPT)
prep = importlib.util.module_from_spec(spec)
assert spec.loader
spec.loader.exec_module(prep)


class BarObjectPrototypeTests(unittest.TestCase):
    def test_fixture_is_bar_only_and_source_separated(self):
        row = prep.read_json(ROOT / "Evaluation/fixtures/bar-object-prototype.json")
        self.assertEqual(row["schema_version"], 1)
        self.assertEqual(row["label"], "grip_bar")
        self.assertEqual(set(row["training"]), {"pullup_dvids", "dip_rear", "dip_side"})
        self.assertEqual({x["id"] for x in row["testing"]},
                         {"pullup_back", "dip_front", "bench_control"})
        self.assertIn("No body landmarks", " ".join(row["notes"]))

    def test_center_anchor_annotation(self):
        got = prep.annotation("grip_bar", [10, 20, 50, 80])
        self.assertEqual(got["label"], "grip_bar")
        self.assertEqual(got["coordinates"], {
            "x": 30.0, "y": 50.0, "width": 40.0, "height": 60.0
        })

    def test_scale_box_preserves_geometry(self):
        self.assertEqual(prep.scale_box([0, 30, 960, 540], 2/3, 2/3),
                         [0.0, 20.0, 640.0, 360.0])

    def test_validate_box_rejects_out_of_bounds(self):
        prep.validate_box([0, 0, 640, 360], 640, 360)
        for bad in ([-1, 0, 10, 10], [0, 0, 641, 10], [4, 4, 4, 10], [0, 9, 10, 8]):
            with self.assertRaises(ValueError):
                prep.validate_box(bad, 640, 360)

    def test_clean_resize_strips_metadata_and_scales_uniformly(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            source = td / "source.png"
            output = td / "out.jpg"
            Image.new("RGB", (960, 480), "white").save(source)
            width, height, scale = prep.clean_resize(source, output, 640)
            self.assertEqual((width, height), (640, 320))
            self.assertAlmostEqual(scale, 2/3)
            with Image.open(output) as image:
                self.assertEqual(image.size, (640, 320))

    def test_all_frozen_boxes_fit_declared_source_geometry(self):
        fixture = prep.read_json(ROOT / "Evaluation/fixtures/bar-object-prototype.json")
        pullup = fixture["training"]["pullup_dvids"]
        for box in pullup["boxes"]:
            prep.validate_box(box, *pullup["output_size"])
        for name in ("dip_rear", "dip_side"):
            row = fixture["training"][name]
            for box in row["boxes"]:
                prep.validate_box(box, *row["input_size"])
        for row in fixture["testing"]:
            for box in row["boxes"]:
                prep.validate_box(box, *row["input_size"])


if __name__ == "__main__":
    unittest.main()