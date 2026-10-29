import importlib.util
from pathlib import Path
import unittest

s=importlib.util.spec_from_file_location('audit',Path(__file__).resolve().parents[1]/'audit-grip-fullsets-v30.py')
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)

class IntervalCompatibilityTests(unittest.TestCase):
    def test_uncertain_narrow_medium_does_not_excuse_wide(self):
        self.assertEqual(m.compatible_bands([.67,1.26]),['narrow','shoulderWidth'])
    def test_boundaries_use_nominal_category_inclusivity(self):
        self.assertEqual(m.compatible_bands([.85,1.30]),['shoulderWidth'])
        self.assertEqual(m.compatible_bands([.84,.85]),['narrow','shoulderWidth'])
        self.assertEqual(m.compatible_bands([1.30,1.31]),['shoulderWidth','wide'])
    def test_wide_only_and_full_uncertainty_are_distinct(self):
        self.assertEqual(m.compatible_bands([1.33,1.85]),['wide'])
        self.assertEqual(m.compatible_bands([.8,1.8]),['narrow','shoulderWidth','wide'])
    def test_invalid_intervals_are_not_silently_scored(self):
        for v in [[1,.5],[float('nan'),1],[0,1],[1,5]]:
            with self.assertRaises(ValueError):m.compatible_bands(v)
