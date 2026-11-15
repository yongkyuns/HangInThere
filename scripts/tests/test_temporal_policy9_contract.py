"""Dependency-free policy identity checks; native/evidence tests are separate."""
import copy
import unittest

from scripts import temporal_policy9_replay as policy9


class TemporalPolicy9ContractTests(unittest.TestCase):
    def fixture(self):
        return dict(counter_policy_version=9, replay_configuration_version=1,
                    cases=[dict(selected_exercise="pullUp"), dict(selected_exercise="dip")])

    def test_current_production_identity_is_not_mutated(self):
        native = self.fixture()
        before = copy.deepcopy(native)
        policy9.validate_policy(native)
        self.assertEqual(native, before)

    def test_explicit_case_identities_are_supported_without_mutation(self):
        native = self.fixture()
        for row in native["cases"]:
            row.update(count_method=policy9.COUNT_METHODS[row["selected_exercise"]], count_policy_version=9)
        before = copy.deepcopy(native)
        policy9.validate_policy(native)
        self.assertEqual(native, before)

    def test_legacy_unknown_boolean_float_string_and_missing_policy_fail(self):
        for value in [8, 10, -1, 0, True, False, 9.0, "9", None]:
            with self.subTest(value=repr(value)):
                native = self.fixture()
                native["counter_policy_version"] = value
                with self.assertRaises(ValueError):
                    policy9.validate_policy(native)
        native = self.fixture()
        del native["counter_policy_version"]
        with self.assertRaises(ValueError):
            policy9.validate_policy(native)

    def test_candidate_unknown_and_malformed_configuration_fail(self):
        for value in [2, 0, True, 1.0, "1", None]:
            with self.subTest(value=repr(value)):
                native = self.fixture()
                native["replay_configuration_version"] = value
                with self.assertRaises(ValueError):
                    policy9.validate_policy(native)

    def test_candidate_marker_is_rejected_even_on_an_otherwise_valid_header(self):
        for value in ["bilateralDips_v1", None, ""]:
            native = self.fixture()
            native["counting_method_configuration"] = value
            with self.assertRaises(ValueError):
                policy9.validate_policy(native)

    def test_unknown_exercise_is_rejected(self):
        for value in ["pushUp", "sitUp", "", None]:
            native = self.fixture()
            native["cases"][0]["selected_exercise"] = value
            with self.assertRaises(ValueError):
                policy9.validate_policy(native)

    def test_legacy_candidate_wrong_and_incomplete_case_methods_fail(self):
        variants = [dict(count_method="bothUsableArms"), dict(count_policy_version=9),
                    dict(count_method="bothUsableArms", count_policy_version=8),
                    dict(count_method="bothUsableArms", count_policy_version=True),
                    dict(count_method="bothUsableArms", count_policy_version=9.0),
                    dict(count_method="bilateralDipImageMotion", count_policy_version=1),
                    dict(count_method="selectedGripWithAvailableArms", count_policy_version=9)]
        for variant in variants:
            with self.subTest(variant=variant):
                native = self.fixture()
                native["cases"][0].update(variant)
                before = copy.deepcopy(native)
                with self.assertRaises(ValueError):
                    policy9.validate_policy(native)
                self.assertEqual(native, before)


if __name__ == "__main__":
    unittest.main()
