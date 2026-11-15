"""Keep the Apple host harness and dependency toolchains explicit.

These wiring checks complement, and do not replace, native macOS/iOS CI.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


def source(path):
    return (ROOT / path).read_text()


def job(path, name):
    workflow = source(path)
    match = re.search(rf"^  {re.escape(name)}:\n(.*?)(?=^  [A-Za-z0-9_-]+:|\Z)",
                      workflow, re.MULTILINE | re.DOTALL)
    if match is None:
        raise AssertionError(f"Missing job {name} in {path}")
    return match.group(1)


class AppleHostBuildContracts(unittest.TestCase):
    def test_batch_pins_the_same_python_as_host_regressions_before_install(self):
        path = '.github/workflows/evaluation.yml'
        for name in ('host-tools', 'batch'):
            with self.subTest(job=name):
                steps = job(path, name)
                setup = re.search(r'uses: actions/setup-python@[0-9a-f]{40}[^\n]*\n'
                                  r"\s+with:\n\s+python-version: '3\.12'", steps)
                self.assertIsNotNone(setup, 'Pinned wheels need an explicit compatible interpreter')
                self.assertLess(setup.start(), steps.index('python3 -m venv'))

    def test_shared_bridge_uses_the_same_xcode_as_native_evaluation(self):
        evaluation = job('.github/workflows/evaluation.yml', 'batch')
        bridge = job('.github/workflows/workout-analysis.yml', 'common-and-apple-bridge')
        pin = 'DEVELOPER_DIR: /Applications/Xcode_26.3.app/Contents/Developer'
        self.assertIn(pin, evaluation)
        self.assertIn(pin, bridge)

    def test_bridge_compiler_and_sdk_follow_xcode_selection(self):
        script = source('scripts/test-workout-bridge.sh')
        self.assertIn('xcrun --sdk macosx swiftc -sdk "$(xcrun --sdk macosx --show-sdk-path)"', script)
        self.assertIn('-framework WorkoutCore -lc++', script)
        self.assertIn('build/workout-analysis/apple-bridge-check "$@"', script)

    def test_core_package_compiler_follows_xcode_selection(self):
        script = source('scripts/test-core.sh')
        self.assertIn('xcrun --sdk macosx swift test --package-path "$WORK"', script)
        self.assertIn('WorkoutCoaching', script)

    def test_training_tip_bridge_is_in_the_core_harness_not_the_swiftui_view(self):
        app = source('ios/HangInThere/App/WorkoutCoaching.swift')
        view = source('ios/HangInThere/UI/WorkoutCoachView.swift')
        for declaration in ('struct TrainingTipSource:', 'struct TrainingTip:',
                            'struct TrainingTipCatalog:', 'enum TrainingTips {'):
            with self.subTest(declaration=declaration):
                self.assertEqual(app.count(declaration), 1)
                self.assertNotIn(declaration, view)
        self.assertIn('WorkoutTipsBridge().catalogJson()', app)
        self.assertNotIn('import SwiftUI', app)

    def test_overlay_imports_only_its_shared_presentation_bridge(self):
        overlay = source('ios/HangInThere/UI/PoseOverlay.swift')
        # WorkoutCore also exports PoseJoint. Importing the whole framework
        # shadows the app's observation joint type inside drawing helpers.
        imports = re.findall(r'^import[^\n]*WorkoutCore[^\n]*$', overlay, re.MULTILINE)
        self.assertEqual(imports, ['import class WorkoutCore.WorkoutSetPresentationBridge'])
        self.assertIn('WorkoutSetPresentationBridge().weight(', overlay)

    def test_ios_27_camera_api_is_guarded_for_the_pinned_older_sdk(self):
        camera = source('ios/HangInThere/Capture/LiveCameraPreview.swift')
        start = camera.index('    static func rotationAngle(')
        end = camera.index('    static func apply(', start)
        rotation = camera[start:end]
        guard = rotation.index('#if compiler(>=6.4)')
        runtime = rotation.index('if #available(iOS 27, *)')
        api = rotation.index('.videoRotationAngleRelative(')
        fallback = rotation.index('#endif')
        self.assertLess(guard, runtime)
        self.assertLess(runtime, api)
        self.assertLess(api, fallback)
        self.assertIn('case .landscapeRight: return camera == .front ? 180 : 0', rotation[fallback:])
        self.assertIn('case .landscapeLeft: return camera == .front ? 0 : 180', rotation[fallback:])
        self.assertIn('case .portraitUpsideDown: return 270', rotation[fallback:])
        self.assertIn('default: return 90', rotation[fallback:])


if __name__ == '__main__':
    unittest.main()
