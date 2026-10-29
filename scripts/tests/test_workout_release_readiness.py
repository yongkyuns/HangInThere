import copy
import unittest
import json
import subprocess
import sys
import tempfile
from pathlib import Path
from scripts import workout_benchmark as benchmark
from scripts import workout_release_readiness as module


class WorkoutReleaseReadinessTests(unittest.TestCase):
    def policy(self):
        return dict(schema_version=1, status="predeclared", benchmark_sha256="a" * 64, minimum_participants=2, minimum_sessions=2,
            supported_contexts=[dict(camera_view="front")], limits=dict(rep_precision=.95, rep_recall=.95, exact_set_count_fraction=.9,
                observation_coverage=.8, phase_agreement_all_labelled=.8, false_starts=0, false_ends=0, phase_boundary_p95_seconds=.2, hold_error_p95_seconds=.5),
            performance=dict(minimum_duration_seconds=600, minimum_device_families_per_platform=1, maximum_pipeline_p95_ms=100,
                maximum_drop_fraction=.1, maximum_battery_fraction_per_hour=.2, maximum_thermal_level="fair"))
    def score(self):
        root = Path(__file__).resolve().parents[2]
        labels = json.loads((root / 'Evaluation/fixtures/workout-benchmark-synthetic.json').read_text())
        predictions = json.loads((root / 'Evaluation/fixtures/workout-predictions-synthetic.json').read_text())
        row = benchmark.score(benchmark.freeze(labels), predictions, 'all')['sessions'][0]
        rows = [copy.deepcopy(row) for _ in range(2)]
        for i, item in enumerate(rows):
            item.update(id=f'test_{i}', participant_group=f'actor_{i}', source_group=f'capture_{i}', split='test')
            item['context']['camera_view'] = 'front'
        return dict(schema_version=1, benchmark_sha256="a" * 64, evidence_class="independently_reviewed_sessions", split="test", participant_groups=2,
            source_groups=2, sessions=rows, summary=benchmark.aggregate(rows),
            run=dict(id="authored_ios", platform="ios", backend="authored_contract", source_revision="d" * 40,
                counter_policy_version=8, activity_metric_version=2, rep_metric_version=1, capture_mode="automatic"))
    def paired_scores(self, score=None):
        # Authored contract data only; identical measurements isolate provenance
        # and gate behavior. Platform-asymmetric tests mutate one report below.
        first = copy.deepcopy(score if score is not None else self.score())
        second = copy.deepcopy(first)
        second["run"].update(id="authored_android", platform="android")
        return [first, second]
    def assess(self, lock, score, devices, *args, **kwargs):
        return module.assess(lock, self.paired_scores(score), devices, *args, **kwargs)
    def devices(self):
        runs = {s["run"]["platform"]: s["run"] for s in self.paired_scores()}
        return [dict(report_sha256=c * 64, run_sha256=module.fingerprint(runs[p]), platform=p, device_family=f"{p}-family", model_family="pinned", scope="physical_live", duration_seconds=600,
            pipeline_p95_ms=80, drop_fraction=.05, battery_fraction_per_hour=.1, maximum_thermal_level="nominal", camera_sensor_time_aligned=True,
            consent_recorded=True) for c, p in [("b", "ios"), ("c", "android")]]
    def test_predeclared_all_evidence_passes_without_claiming_coach_or_usability_review(self):
        result=self.assess(module.freeze(self.policy()), self.score(), self.devices())
        self.assertTrue(result["measurement_and_device_gates_pass"])
        self.assertIn("qualified_coach_review", result["remaining_external_reviews"])
        self.assertFalse(result["reviewed_qualification_gates_pass"])
    def test_synthetic_data_cannot_qualify_regardless_of_scores(self):
        score=self.score(); score["evidence_class"]="synthetic_contract"
        self.assertFalse(self.assess(module.freeze(self.policy()), score, self.devices())["measurement_and_device_gates_pass"])
    def test_emulator_and_missing_battery_never_establish_physical_parity(self):
        runs = {s["run"]["platform"]: s["run"] for s in self.paired_scores()}
        rows=self.devices(); rows[1]["scope"]="emulator"
        result = module.device_matrix(rows, self.policy()["performance"], runs)
        self.assertFalse(result["qualified"])
        self.assertEqual(result["rows"][1]["reasons"], ["physical_capture_required"])
        rows=self.devices(); rows[0]["battery_fraction_per_hour"]=None
        result = module.device_matrix(rows, self.policy()["performance"], runs)
        self.assertFalse(result["qualified"])
        self.assertEqual(result["rows"][0]["reasons"], ["battery_fraction_per_hour_unqualified"])
    def test_lock_source_and_context_binding_cannot_be_changed_after_freeze(self):
        lock=module.freeze(self.policy()); lock["policy"]["limits"]["rep_recall"]=0
        with self.assertRaises(module.ReadinessError): self.assess(lock,self.score(),self.devices())
        score=self.score(); score["benchmark_sha256"]="d"*64
        with self.assertRaises(module.ReadinessError): self.assess(module.freeze(self.policy()),score,self.devices())
    def test_missing_metrics_and_unsupported_conditions_fail(self):
        score=self.score();
        for row in score["sessions"]:
            row["context"]["camera_view"] = "side"
            row["labelled_phase_seconds"] = 0
        score["summary"] = benchmark.aggregate(score["sessions"])
        result=self.assess(module.freeze(self.policy()),score,self.devices())
        self.assertIn("hold_error_p95_seconds",result["failures"]); self.assertIn("supported_context_coverage",result["failures"])
    def test_duplicate_or_content_bearing_reports_are_rejected(self):
        rows=self.devices(); rows[1]["report_sha256"]=rows[0]["report_sha256"]
        with self.assertRaises(module.ReadinessError): module.device_matrix(rows,self.policy()["performance"])
        rows=self.devices(); rows[0]["landmarks"]=[]
        with self.assertRaises(module.ReadinessError): module.device_matrix(rows,self.policy()["performance"])
    def test_draft_and_nonfinite_policies_cannot_be_frozen(self):
        policy=self.policy(); policy["status"]="draft"
        with self.assertRaises(module.ReadinessError): module.freeze(policy)
        policy=self.policy(); policy["schema_version"]=True
        with self.assertRaises(module.ReadinessError): module.freeze(policy)
    def test_inconsistent_or_failed_session_evidence_cannot_pass(self):
        score=self.score(); score["summary"]["sessions"]=3
        with self.assertRaises(module.ReadinessError): self.assess(module.freeze(self.policy()), score, self.devices())
        score=self.score(); score["sessions"][0]["status"]="failed"; score["summary"]=benchmark.aggregate(score["sessions"])
        self.assertIn("failed_sessions",self.assess(module.freeze(self.policy()),score,self.devices())["failures"])
        policy=self.policy(); policy["limits"]["rep_recall"]=float('nan')
        with self.assertRaises(module.ReadinessError): module.freeze(policy)
    def test_bad_supported_subgroup_cannot_hide_behind_good_pooled_metrics(self):
        score=self.score()
        good = copy.deepcopy(score['sessions'])
        rows = [copy.deepcopy(good[i % 2]) for i in range(100)]
        for i, row in enumerate(rows):
            row.update(id=f'row_{i}', participant_group=f'actor_{i}', source_group=f'capture_{i}')
        for row in rows[-2:]:
            row['context']['camera_view'] = 'side'
            row['completed_cycles'].update(true_positives=0, false_negatives=1)
        score.update(sessions=rows, summary=benchmark.aggregate(rows), participant_groups=100, source_groups=100)
        policy=self.policy(); policy['supported_contexts'].append(dict(camera_view='side'))
        result=self.assess(module.freeze(policy),score,self.devices())
        self.assertGreater(result['platforms'][0]['metrics']['rep_recall'], .95)
        self.assertFalse(result['measurement_and_device_gates_pass'])
        self.assertIn('rep_recall', result['platforms'][0]['supported_contexts'][1]['failures'])
    def test_cohort_counts_and_pooled_summary_must_match_rows(self):
        score=self.score(); score['participant_groups']=1
        with self.assertRaises(module.ReadinessError): self.assess(module.freeze(self.policy()),score,self.devices())
        score=self.score(); score['summary']['completed_cycles']['precision']=.9
        with self.assertRaises(module.ReadinessError): self.assess(module.freeze(self.policy()),score,self.devices())

    def approved_reviews(self, lock=None, score=None, devices=None):
        packet = module.prepare_reviews(lock or module.freeze(self.policy()), self.paired_scores(score),
            devices or self.devices(), "d" * 40, "e" * 64)
        contexts = [module.fingerprint(context) for context in self.policy()["supported_contexts"]]
        for index, row in enumerate(packet["reviews"]):
            row.update(status="approved", reviewer_id=f"reviewer_{index}", human_review_confirmed=True,
                independent_review_confirmed=True, reviewed_at_utc="2000-01-01T00:00:00Z", evidence_sha256=str(index + 1) * 64,
                reviewed_context_sha256s=contexts, checks=dict.fromkeys(row["checks"], True))
        return packet

    def assess_with_reviews(self, packet, lock=None, score=None, devices=None):
        return self.assess(lock or module.freeze(self.policy()), score or self.score(), devices or self.devices(),
            packet, "d" * 40, "e" * 64)

    def test_prepared_packet_is_pending_and_cannot_close_field_reviews(self):
        packet = module.prepare_reviews(module.freeze(self.policy()), self.paired_scores(), self.devices(), "d" * 40, "e" * 64)
        result = self.assess_with_reviews(packet)
        self.assertTrue(result["measurement_and_device_gates_pass"])
        self.assertFalse(result["reviewed_qualification_gates_pass"])
        self.assertEqual(set(result["remaining_external_reviews"]), set(module.EXTERNAL_REVIEW_CHECKS))

    def test_complete_artifact_bound_human_attestations_are_recorded(self):
        packet = self.approved_reviews()
        result = self.assess_with_reviews(packet)
        self.assertTrue(result["reviewed_qualification_gates_pass"])
        self.assertEqual(result["remaining_external_reviews"], [])
        self.assertEqual(result["field_reviews"]["reviews_sha256"], module.fingerprint(packet))
        self.assertNotIn("reviewer_0", json.dumps(result))
        self.assertIn("credentials", result["field_reviews"]["scope"])

    def test_field_approval_never_overrides_failed_accuracy_or_physical_evidence(self):
        for mode in ["synthetic", "missing_battery"]:
            score, devices = self.score(), self.devices()
            if mode == "synthetic": score["evidence_class"] = "synthetic_contract"
            else: devices[1]["battery_fraction_per_hour"] = None
            result = self.assess_with_reviews(self.approved_reviews(score=score, devices=devices), score=score, devices=devices)
            self.assertFalse(result["reviewed_qualification_gates_pass"])
            self.assertFalse(result["measurement_and_device_gates_pass"])

    def test_changed_score_devices_build_spec_or_policy_invalidates_old_reviews(self):
        packet = self.approved_reviews()
        score = self.score(); score["sessions"][0]["id"] = "different_session"
        devices = self.devices(); devices[0]["pipeline_p95_ms"] = 79
        policy = self.policy(); policy["limits"]["rep_recall"] = .94
        variants = [(module.freeze(self.policy()), score, self.devices(), "d" * 40, "e" * 64),
                    (module.freeze(self.policy()), self.score(), devices, "d" * 40, "e" * 64),
                    (module.freeze(self.policy()), self.score(), self.devices(), "f" * 40, "e" * 64),
                    (module.freeze(self.policy()), self.score(), self.devices(), "d" * 40, "f" * 64),
                    (module.freeze(policy), self.score(), self.devices(), "d" * 40, "e" * 64)]
        for lock, score, devices, revision, spec in variants:
            with self.assertRaises(module.ReadinessError): self.assess(lock, score, devices, packet, revision, spec)

    def test_rejected_missing_incomplete_or_nonhuman_reviews_remain_open(self):
        for change in [dict(status="rejected"), dict(human_review_confirmed=False), dict(independent_review_confirmed=False),
                       dict(evidence_sha256=None), dict(reviewed_at_utc=None), dict(reviewer_id=None), dict(reviewed_context_sha256s=[])]:
            packet = self.approved_reviews(); packet["reviews"][0].update(change)
            result = self.assess_with_reviews(packet)
            self.assertFalse(result["reviewed_qualification_gates_pass"])
            self.assertIn("qualified_coach_review", result["remaining_external_reviews"])
        packet = self.approved_reviews(); packet["reviews"][1]["checks"]["android_gym_noise_trials_completed"] = False
        self.assertIn("equipment_distance_and_gym_noise_usability", self.assess_with_reviews(packet)["remaining_external_reviews"])
        packet = self.approved_reviews(); packet["reviews"].pop()
        self.assertIn("consent_and_exposure_audit", self.assess_with_reviews(packet)["remaining_external_reviews"])

    def test_malformed_or_content_bearing_human_reports_are_rejected(self):
        mutations = [lambda p: p.update(video="private.mp4"), lambda p: p["reviews"][0].update(name="personal name"),
            lambda p: p["reviews"].append(copy.deepcopy(p["reviews"][0])),
            lambda p: p["reviews"][0].update(reviewer_role="automated_model"),
            lambda p: p["reviews"][0]["checks"].update(credentials_verified=1),
            lambda p: p["reviews"][0].update(reviewed_at_utc="9999-01-01T00:00:00Z"),
            lambda p: p["reviews"][0].update(reviewed_at_utc="2000-02-30T00:00:00Z"),
            lambda p: p["subject"].update(schema_version=True)]
        for mutate in mutations:
            packet = self.approved_reviews(); mutate(packet)
            with self.assertRaises(module.ReadinessError): self.assess_with_reviews(packet)

    def test_every_supported_condition_requires_explicit_review(self):
        policy = self.policy(); policy["supported_contexts"].append(dict(camera_view="side"))
        lock = module.freeze(policy)
        packet = self.approved_reviews(lock=lock)
        result = self.assess_with_reviews(packet, lock=lock)
        self.assertEqual(set(result["remaining_external_reviews"]), set(module.EXTERNAL_REVIEW_CHECKS))
        self.assertFalse(result["reviewed_qualification_gates_pass"])

    def test_cli_prepares_pending_reviews_and_required_missing_reviews_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, value in [("lock", module.freeze(self.policy())), ("score", self.score()), ("devices", self.devices())]:
                (root / f"{name}.json").write_text(json.dumps(value))
            (root / "coaching.md").write_text("Authored contract specification; not a coach review.")
            command = [sys.executable, str(Path(module.__file__)), "prepare-reviews", str(root / "lock.json"),
                       "--score", str(root / "score.json"), "--devices", str(root / "devices.json"),
                       "--app-revision", "d" * 40, "--coaching-spec", str(root / "coaching.md"), "--output", str(root / "reviews.json")]
            self.assertEqual(subprocess.run(command, capture_output=True).returncode, 0)
            self.assertTrue(all(row["status"] == "pending" for row in json.loads((root / "reviews.json").read_text())["reviews"]))
            command[2] = "assess"; command[-1] = str(root / "result.json")
            result = subprocess.run(command + ["--require-field-reviews"], capture_output=True)
            self.assertEqual(result.returncode, 1)
            self.assertFalse(json.loads((root / "result.json").read_text())["reviewed_qualification_gates_pass"])
            command[-1] = str(root / "result-with-reviews.json")
            result = subprocess.run(command + ["--reviews", str(root / "reviews.json")], capture_output=True)
            self.assertEqual(result.returncode, 1)

    def test_cli_never_overwrites_evidence_or_existing_decisions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            policy_path = root / "policy.json"
            policy_path.write_text(json.dumps(self.policy()))
            original = policy_path.read_bytes()
            self.assertEqual(module.main(["freeze", str(policy_path), "--output", str(policy_path)]), 2)
            self.assertEqual(policy_path.read_bytes(), original)
            output = root / "lock.json"
            self.assertEqual(module.main(["freeze", str(policy_path), "--output", str(output)]), 0)
            frozen = output.read_bytes()
            self.assertEqual(module.main(["freeze", str(policy_path), "--output", str(output)]), 2)
            self.assertEqual(output.read_bytes(), frozen)
            for name, value in [("score", self.score()), ("devices", self.devices())]:
                (root / f"{name}.json").write_text(json.dumps(value))
            (root / "coaching.md").write_text("Authored contract only.")
            packet = root / "reviews.json"
            command = ["prepare-reviews", str(output), "--score", str(root / "score.json"), "--devices", str(root / "devices.json"),
                "--app-revision", "d" * 40, "--coaching-spec", str(root / "coaching.md"), "--output", str(packet)]
            self.assertEqual(module.main(command), 0)
            retained = packet.read_bytes()
            self.assertEqual(module.main(command), 2)
            self.assertEqual(packet.read_bytes(), retained)
            # This packet is not an assessment input, but must still survive
            # an erroneous output path.
            command[0] = "assess"
            self.assertEqual(module.main(command), 2)
            self.assertEqual(packet.read_bytes(), retained)

    def test_cli_review_required_flags_cannot_be_ignored_by_other_actions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); policy = root / "policy.json"; output = root / "unused.json"
            policy.write_text(json.dumps(self.policy()))
            for action in ["freeze", "prepare-reviews"]:
                for flags in [["--require-field-reviews"], ["--reviews", str(root / "reviews.json")]]:
                    self.assertEqual(module.main([action, str(policy), "--output", str(output)] + flags), 2)
                    self.assertFalse(output.exists())

    def test_one_platform_and_unbound_performance_never_qualify(self):
        score = self.score()
        devices = self.devices()
        for row in devices:
            row.pop("run_sha256")
        result = module.assess(module.freeze(self.policy()), score, devices)
        self.assertEqual(result["missing_accuracy_platforms"], ["android"])
        self.assertIn("native_platform_accuracy_missing", result["failures"])
        self.assertFalse(result["measurement_and_device_gates_pass"])
        self.assertFalse(result["reviewed_qualification_gates_pass"])
        self.assertTrue(all("evaluated_run_binding_missing" in row["reasons"] for row in result["device_matrix"]["rows"]))

    def test_single_platform_approval_cannot_close_missing_platform_gate(self):
        lock, score, devices = module.freeze(self.policy()), self.score(), self.devices()
        packet = module.prepare_reviews(lock, score, devices, "d" * 40, "e" * 64)
        approved = self.approved_reviews()
        packet["reviews"] = approved["reviews"]
        result = module.assess(lock, score, devices, packet, "d" * 40, "e" * 64)
        self.assertEqual(result["remaining_external_reviews"], [])
        self.assertFalse(result["reviewed_qualification_gates_pass"])

    def test_each_platform_must_pass_without_pooling(self):
        scores = self.paired_scores()
        for row in scores[1]["sessions"]:
            row["completed_cycles"].update(true_positives=0, false_negatives=1)
        scores[1]["summary"] = benchmark.aggregate(scores[1]["sessions"])
        result = module.assess(module.freeze(self.policy()), scores, self.devices())
        platforms = {row["platform"]: row for row in result["platforms"]}
        self.assertTrue(platforms["ios"]["qualified"])
        self.assertIn("rep_recall", platforms["android"]["failures"])
        self.assertFalse(result["measurement_and_device_gates_pass"])

    def test_duplicate_or_invalid_native_run_provenance_is_rejected(self):
        for mutate in [lambda s: s[1]["run"].update(platform="ios"),
                       lambda s: s[1]["run"].update(source_revision="f" * 40),
                       lambda s: s[1]["run"].update(activity_metric_version=3),
                       lambda s: s[0]["run"].update(capture_mode="manual"),
                       lambda s: s[0].update(schema_version=2),
                       lambda s: s[0].update(schema_version=True),
                       lambda s: s[0].pop("run")]:
            scores = self.paired_scores(); mutate(scores)
            with self.assertRaises(module.ReadinessError):
                module.assess(module.freeze(self.policy()), scores, self.devices())

    def test_reviewed_revision_must_be_the_actual_evaluated_revision(self):
        scores = self.paired_scores()
        for score in scores:
            score["run"]["source_revision"] = "f" * 40
        with self.assertRaises(module.ReadinessError):
            module.prepare_reviews(module.freeze(self.policy()), scores, self.devices(), "d" * 40, "e" * 64)
        with self.assertRaises(module.ReadinessError):
            module.assess(module.freeze(self.policy()), scores, self.devices(), app_revision="d" * 40)

    def test_both_platforms_must_score_identical_frozen_reference_sessions(self):
        for field, value in [("id", "different"), ("participant_group", "different"),
                             ("source_group", "different"), ("context", dict(camera_view="side"))]:
            scores = self.paired_scores(); scores[1]["sessions"][0][field] = value
            with self.assertRaises(module.ReadinessError):
                module.assess(module.freeze(self.policy()), scores, self.devices())

    def test_device_reports_bind_platform_backend_revision_and_analysis_policy(self):
        for key, value in [("platform", "ios"), ("backend", "different_model"),
                           ("source_revision", "f" * 40), ("counter_policy_version", 9)]:
            scores = self.paired_scores(); devices = self.devices()
            wrong_run = dict(scores[1]["run"]); wrong_run[key] = value
            devices[1]["run_sha256"] = module.fingerprint(wrong_run)
            result = module.assess(module.freeze(self.policy()), scores, devices)
            self.assertFalse(result["measurement_and_device_gates_pass"])
            self.assertIn("evaluated_run_binding_mismatch", result["device_matrix"]["rows"][1]["reasons"])

    def test_legacy_unbound_rows_and_review_packets_cannot_silently_migrate(self):
        devices = self.devices()
        for row in devices: row.pop("run_sha256")
        result = module.assess(module.freeze(self.policy()), self.paired_scores(), devices)
        self.assertFalse(result["measurement_and_device_gates_pass"])
        packet = self.approved_reviews(); packet["schema_version"] = 1
        with self.assertRaises(module.ReadinessError): self.assess_with_reviews(packet)

    def test_report_order_does_not_change_human_review_binding(self):
        scores = self.paired_scores(); lock = module.freeze(self.policy()); devices = self.devices()
        self.assertEqual(module.review_subject(lock, scores, devices, "d" * 40, "e" * 64),
                         module.review_subject(lock, list(reversed(scores)), devices, "d" * 40, "e" * 64))

    def test_cli_accepts_two_score_files_and_binds_both_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            values = {"lock": module.freeze(self.policy()), "devices": self.devices(),
                      "ios": self.paired_scores()[0], "android": self.paired_scores()[1]}
            for name, value in values.items(): (root / f"{name}.json").write_text(json.dumps(value))
            base = ["assess", str(root / "lock.json"), "--score", str(root / "ios.json"),
                    "--score", str(root / "android.json"), "--devices", str(root / "devices.json")]
            self.assertEqual(module.main(base + ["--output", str(root / "result.json")]), 0)
            result = json.loads((root / "result.json").read_text())
            self.assertEqual({row["platform"] for row in result["platforms"]}, {"ios", "android"})
            original = (root / "android.json").read_bytes()
            self.assertEqual(module.main(base + ["--output", str(root / "android.json")]), 2)
            self.assertEqual((root / "android.json").read_bytes(), original)

    def test_actual_scorer_outputs_remain_synthetic_and_unqualified_on_both_platforms(self):
        root = Path(__file__).resolve().parents[2]
        labels = benchmark.read(root / "Evaluation/fixtures/workout-benchmark-synthetic.json")
        predictions = benchmark.read(root / "Evaluation/fixtures/workout-predictions-synthetic.json")
        frozen = benchmark.freeze(labels)
        policy = self.policy(); policy["benchmark_sha256"] = frozen["benchmark_sha256"]
        scores = []
        for platform in ["ios", "android"]:
            value = copy.deepcopy(predictions)
            value["run"].update(id=f"synthetic_{platform}", platform=platform)
            scores.append(benchmark.score(frozen, value, "all"))
        result = module.assess(module.freeze(policy), scores, [])
        self.assertEqual(len(result["platforms"]), 2)
        self.assertIn("fresh_independent_test_split_required", result["failures"])
        self.assertIn("physical_device_matrix", result["failures"])
        self.assertFalse(result["measurement_and_device_gates_pass"])
        self.assertFalse(result["reviewed_qualification_gates_pass"])

    def test_duplicate_sessions_and_mixed_reference_splits_are_rejected(self):
        for mutate in [lambda s: s["sessions"][1].update(id=s["sessions"][0]["id"]),
                       lambda s: s["sessions"][0].update(split="development")]:
            score = self.score(); mutate(score)
            with self.assertRaises(module.ReadinessError):
                self.assess(module.freeze(self.policy()), score, self.devices())

    def test_cli_rejects_duplicate_json_keys_without_producing_a_lock(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); policy = root / "policy.json"; output = root / "lock.json"
            policy.write_text('{"schema_version":1,"schema_version":1}')
            self.assertEqual(module.main(["freeze", str(policy), "--output", str(output)]), 2)
            self.assertFalse(output.exists())


if __name__ == '__main__': unittest.main()
