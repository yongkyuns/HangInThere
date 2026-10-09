"""Protect native timestamp comparisons from decimal-rounding false failures."""
from fractions import Fraction
import importlib.util
from pathlib import Path
import unittest

SPEC=importlib.util.spec_from_file_location('prepare_android',Path(__file__).parents[1]/'prepare-android-fixtures.py')
PREPARE=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(PREPARE)

class AndroidTimestampTests(unittest.TestCase):
    def test_thirty_fps_container_ticks_are_truncated_not_decimal_rounded(self):
        report=PREPARE.timestamp_fields([0,3000,6000,9000],Fraction(1,90000))
        self.assertEqual([0,33333,66666,100000],report['sourcePtsMicroseconds'])
        self.assertEqual(6000,report['sourcePtsTicks'][2])

    def test_twelve_fps_held_timeline_uses_actual_ticks(self):
        self.assertEqual([0,83333,166666,250000],PREPARE.timestamp_fields(
            [0,1024,2048,3072],Fraction(1,12288))['sourcePtsMicroseconds'])

    def test_variable_timeline_is_not_inferred_from_frame_indices(self):
        self.assertEqual([0,33333,100011,233333],PREPARE.timestamp_fields(
            [0,3000,9001,21000],Fraction(1,90000))['sourcePtsMicroseconds'])

    def test_duplicate_or_reversed_pts_reject(self):
        for ticks in ([0,0],[2,1],[-1,1]):
            with self.assertRaises(AssertionError):PREPARE.timestamp_fields(ticks,Fraction(1,1000))

if __name__=='__main__':unittest.main()
