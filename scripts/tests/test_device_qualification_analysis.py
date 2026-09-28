import copy
import importlib.util
import json
import pathlib
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "scripts" / "analyze_device_qualification.py"
SPEC = importlib.util.spec_from_file_location("device_qualification_analysis", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
analysis = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(analysis)


def sample_report():
    samples = []
    vision = [10.0, 20.0, 30.0, 40.0, 50.0]
    scene = [5.0, 10.0, 15.0, 20.0, 25.0]
    dropped = [0, 0, 1, 2, 4]
    for index in range(5):
        samples.append(
            {
                "elapsedSeconds": float(index),
                "visionProcessingMilliseconds": vision[index],
                "sceneRegistrationMilliseconds": scene[index],
                "analyzedFrames": index * 10,
                "droppedFrames": dropped[index],
                "analysisFailures": 0,
                "sceneRegistrationFailures": 1 if index >= 3 else 0,
                "orientationDeltaDegrees": 0.2 + 0.1 * index,
                "sceneShiftFraction": 0.001 + 0.0005 * index,
                "sceneScaleFraction": 0.002 + 0.0005 * index,
                "sceneTranslationConsensusPatches": 4 if index < 3 else 3,
                "sceneScaleMeasurementAvailable": True,
                "thermalLevel": "nominal" if index < 3 else "fair",
                "setPhase": "idle" if index < 2 else "running",
            }
        )

    return {
        "schemaVersion": 1,
        "runtime": {
            "durationSeconds": 4.0,
            "analyzedFrames": 40,
            "droppedFrames": 4,
            "analysisFailures": 0,
            "sceneRegistrationFailures": 1,
            "effectiveAnalyzedFPS": 10.0,
            "dropFraction": 4.0 / 44.0,
        },
        "visionLatency": {
            "sampleCount": 5,
            "medianMilliseconds": 30.0,
            "p95Milliseconds": 50.0,
            "maximumMilliseconds": 50.0,
        },
        "sceneRegistrationLatency": {
            "sampleCount": 5,
            "medianMilliseconds": 15.0,
            "p95Milliseconds": 25.0,
            "maximumMilliseconds": 25.0,
        },
        "stability": {
            "maximumOrientationDeltaDegrees": 0.6,
            "maximumSceneShiftFraction": 0.003,
            "maximumSceneScaleFraction": 0.004,
            "minimumTranslationConsensusPatches": 3,
            "sceneScaleMeasurementSamples": 5,
            "maximumThermalLevel": "fair",
        },
        "thresholds": {
            "orientationDegrees": 1.5,
            "orientationDwellSeconds": 0.25,
            "sceneTranslationFraction": 0.008,
            "sceneScaleFraction": 0.012,
            "sceneMovementDwellSeconds": 0.25,
            "minimumTranslationConsensusPatches": 2,
        },
        "observedMovements": 3,
        "trackingCoverage": 0.95,
        "setPhase": "finished",
        "setEndReason": "manual",
        "omittedSamples": 0,
        "samples": samples,
    }


class DeviceQualificationAnalysisTests(unittest.TestCase):
    def test_valid_report_passes_schema_and_privacy_validation(self):
        analysis.validate_report(sample_report())

    def test_forbidden_content_key_is_rejected(self):
        report = sample_report()
        report["location"] = "Oakville"
        with self.assertRaises(analysis.ReportError):
            analysis.validate_report(report)

    def test_nonmonotonic_runtime_counter_is_rejected(self):
        report = sample_report()
        report["samples"][3]["analyzedFrames"] = 5
        with self.assertRaises(analysis.ReportError):
            analysis.validate_report(report)

    def test_stationary_analysis_reports_threshold_usage_without_tuning(self):
        report_a = sample_report()
        report_b = sample_report()
        report_b["samples"][-1]["orientationDeltaDegrees"] = 0.75
        report_b["stability"]["maximumOrientationDeltaDegrees"] = 0.75

        result = analysis.stationary_analysis([report_a, report_b])

        self.assertEqual(result["reportCount"], 2)
        self.assertAlmostEqual(
            result["orientation"]["maximumThresholdUsage"],
            0.75 / 1.5,
        )
        self.assertAlmostEqual(
            result["translation"]["maximumThresholdUsage"],
            0.003 / 0.008,
        )
        self.assertAlmostEqual(
            result["scale"]["maximumThresholdUsage"],
            0.004 / 0.012,
        )
        self.assertEqual(result["totalSceneRegistrationFailures"], 2)
        self.assertEqual(result["highestThermalLevel"], "fair")

    def test_stationary_analysis_rejects_mixed_threshold_cohorts(self):
        report_a = sample_report()
        report_b = sample_report()
        report_b["thresholds"]["orientationDegrees"] = 2.0
        with self.assertRaises(analysis.ReportError):
            analysis.stationary_analysis([report_a, report_b])

    def test_thermal_analysis_compares_early_and_late_windows(self):
        result = analysis.thermal_analysis(sample_report())

        self.assertEqual(result["sampleCount"], 5)
        self.assertEqual(result["early"]["visionMedianMilliseconds"], 15.0)
        self.assertEqual(result["late"]["visionMedianMilliseconds"], 45.0)
        self.assertAlmostEqual(result["visionMedianChangeFraction"], 2.0)
        self.assertAlmostEqual(result["analysisFPSChangeFraction"], 0.0)
        self.assertEqual(result["maximumThermalLevel"], "fair")
        self.assertGreater(result["late"]["dropFraction"], result["early"]["dropFraction"])

    def test_cli_can_emit_machine_readable_stationary_analysis(self):
        report = sample_report()
        with tempfile.TemporaryDirectory() as directory:
            directory = pathlib.Path(directory)
            source = directory / "stationary.json"
            output = directory / "analysis.json"
            source.write_text(json.dumps(report), encoding="utf-8")

            status = analysis.main(
                [
                    "--profile",
                    "stationary",
                    "--json",
                    "--output",
                    str(output),
                    str(source),
                ]
            )

            self.assertEqual(status, 0)
            result = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(result["profile"], "stationary")
            self.assertEqual(result["stationary"]["reportCount"], 1)

    def test_markdown_summary_does_not_claim_pass_fail(self):
        result = analysis.analyze_reports_for_test(
            [("run.json", sample_report())],
            "summary",
        )
        markdown = analysis.render_markdown(result)
        self.assertIn("Device qualification summary", markdown)
        self.assertIn("not an automatic release verdict", markdown)


if __name__ == "__main__":
    unittest.main()
