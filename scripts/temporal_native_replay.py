#!/usr/bin/env python3
"""Frozen temporal videos through selected-exercise production Apple replay.

Automatic contours are exercised; live Auto identity/readiness is not. Completion
is separate from event accuracy. --require-exact-events retains missed/extra events
as a fatal diagnostic. No labels, apparatus references or pixels are synthesized.
"""
from __future__ import annotations
import argparse
import hashlib
from pathlib import Path, PurePosixPath
import re
import shutil
import sys

try:
    from . import evaluation as ev, public_workout_datasets as public, temporal_references, temporal_events
    from .temporal_references import reference_check
    from .temporal_events import score_events
except ImportError:
    import evaluation as ev
    import public_workout_datasets as public
    import temporal_references
    import temporal_events
    from temporal_references import reference_check
    from temporal_events import score_events


REPLAY_SOURCE_FILES = (
    "ios/HangInThere/App/WorkoutAnalysisAdapter.swift",
    "ios/HangInThere/App/WorkoutProgression.swift",
    "ios/HangInThere/App/WorkoutGripReview.swift",
    "ios/HangInThere/App/ReplayController.swift",
    "ios/HangInThere/UI/QualificationReportDocument.swift",
    "ios/HangInThere/Pose/VisionPoseEstimator.swift",
    "Evaluation/PublicWorkoutReplay.swift",
    "Evaluation/PublicReplayMeasurements.swift",
    "scripts/evaluate-public-workouts-apple.sh",
    "scripts/temporal_native_replay.py",
    "scripts/build-workout-core.sh",
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    "shared/build.gradle.kts",
    "gradlew",
    "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties",
    "native/apple/Package.swift",
)
REPLAY_FRAMEWORK = "build/WorkoutCore/macosx/Debug/WorkoutCore.framework/WorkoutCore"
LEGACY_METHODS = {"pullUp": ("bothUsableArms", 8), "dip": ("selectedGripWithAvailableArms", 8)}
CANDIDATE_METHODS = {**LEGACY_METHODS, "dip": ("bilateralDipImageMotion", 1)}


def compiled_source_paths(root):
    """One inventory for native compilation and cross-host provenance checks."""
    paths = set(REPLAY_SOURCE_FILES)
    paths.update(str(p.relative_to(root)) for folder in ["Analysis", "Capture", "Pose"]
                 for p in (root / "ios/HangInThere" / folder).glob("*.swift"))
    paths.update(str(p.relative_to(root)) for p in (root / "shared/src").rglob("*") if p.is_file())
    paths.update(str(p.relative_to(root)) for p in (root / "native/apple").rglob("*")
                 if p.is_file() and not {".build", ".swiftpm", "__pycache__"}.intersection(p.parts)
                 and p.name != ".DS_Store")
    return sorted(paths)


def snapshot_compilation_sources(root, swift_directory):
    """Bind copied Swift and other build inputs to their exact captured bytes.

    Originals are checked again after framework build and replay. Reading once
    here prevents a change between copying and hashing from certifying different
    Swift bytes than the executable actually compiles.
    """
    paths = [root / name for name in compiled_source_paths(root)]
    swift_sources = [p for p in paths if p.suffix == ".swift" and root / "native/apple" not in p.parents]
    ev.require(len({p.name for p in swift_sources}) == len(swift_sources), "Duplicate Swift source names")
    swift_directory.mkdir(parents=True)
    swift_set = set(swift_sources)
    hashes = {}
    for path in paths:
        contents = path.read_bytes()
        hashes[str(path.relative_to(root))] = hashlib.sha256(contents).hexdigest()
        if path in swift_set:
            (swift_directory / path.name).write_bytes(contents)
    return hashes


def prepare(reference_path, manifest_path, root, output):
    manifest = ev.read_json(manifest_path)
    clips = ev.validate_manifest(manifest, root)
    reference = ev.read_json(reference_path)
    ev.require(reference.get("schema_version") == 1 and reference.get("manifest_sha256") == ev.digest(manifest_path),
               "Wrong frozen temporal reference")
    refs = reference.get("clips", [])
    ev.require(clips and [c["id"] for c in clips] == [r["id"] for r in refs], "Incomplete temporal corpus")
    for clip, ref in zip(clips, refs):
        reference_check(ref, clip)
        ev.require(re.fullmatch(r"[A-Za-z0-9_-]{1,64}", clip["id"]), "Unsafe clip identity")
        ev.require(ev.preflight(clip, root, True) == "ready", "Unapproved or changed public media")
    output.mkdir(parents=True, exist_ok=False)
    (output / "videos").mkdir()
    cases = []
    for clip, ref in zip(clips, refs):
        relative = "videos/" + clip["id"] + ".mp4"
        source = root / clip["media"]["files"][0]["path"]
        shutil.copyfile(source, output / relative)
        ev.require(ev.digest(source) == ev.digest(output / relative) == ref["media_sha256"], "Media changed during copy")
        cases.append(dict(id=clip["id"], media=dict(path=relative, sha256=ref["media_sha256"]),
            frames=len(ref["frame_pts_seconds"]), source_pts_seconds=ref["frame_pts_seconds"],
            source_span_seconds=ref["span_seconds"], selected_exercise=ref["exercise"], tracking_side=ref["side"]))
    result = dict(schema_version=1, scope="public_repetition_diagnostic", held_out_qualification=False,
        source_reference_sha256=ev.digest(reference_path), source_manifest_sha256=ev.digest(manifest_path), cases=cases)
    ev.write_json(output / "manifest.json", result)
    return result


def _source_header(reference, manifest, native, manifest_sha, reference_sha):
    ev.require(manifest.get("schema_version") == native.get("schema_version") == reference.get("schema_version") == 1
               and manifest.get("scope") == "public_repetition_diagnostic" and manifest.get("held_out_qualification") is False,
               "Wrong selected-replay diagnostic scope")
    ev.require(manifest.get("source_reference_sha256") == reference_sha
               and manifest.get("source_manifest_sha256") == reference.get("manifest_sha256")
               and native.get("manifest_sha256") == manifest_sha, "Reference/manifest binding changed")
    versions = {"request_revision": 1, "progress_evidence_version": 1}
    ev.require(native.get("platform") == "apple_host" and native.get("backend") == "Apple Vision 2D"
               and all(ev.integer(native.get(key), 1) and native[key] == value for key, value in versions.items()),
               "Wrong native pipeline or configuration contract")
    ev.require(re.fullmatch(r"[a-f0-9]{40}", native.get("source_revision", "")), "Missing native revision")


def score(reference, manifest, native, manifest_sha, reference_sha):
    """Original schema-1/v8 fatal qualification contract, unchanged."""
    _source_header(reference, manifest, native, manifest_sha, reference_sha)
    ev.require(type(native.get("counter_policy_version")) is int and native["counter_policy_version"] == 8
               and type(native.get("replay_configuration_version")) is int and native["replay_configuration_version"] == 1,
               "Wrong native pipeline or configuration contract")
    for row in native.get("cases", []):
        if "count_method" in row or "count_policy_version" in row:
            expected = LEGACY_METHODS.get(row.get("selected_exercise"))
            ev.require(expected is not None and row.get("count_method") == expected[0]
                       and type(row.get("count_policy_version")) is int and row["count_policy_version"] == expected[1],
                       "Candidate method cannot be labeled as legacy counter v8")
    return _score_cases(reference, manifest, native, manifest_sha, reference_sha)


def score_candidate(reference, manifest, native, manifest_sha, reference_sha):
    """Explicit bilateral candidate; identical source validation and matcher.

    Reference policy 8 remains annotation provenance. It cannot be relabeled as
    the candidate's counting method, nor can candidate results pass score().
    """
    _source_header(reference, manifest, native, manifest_sha, reference_sha)
    ev.require(type(native.get("replay_configuration_version")) is int and native["replay_configuration_version"] == 2
               and native.get("counting_method_configuration") == "bilateralDips_v1"
               and "counter_policy_version" not in native,
               "Wrong candidate pipeline or configuration contract")
    for row in native.get("cases", []):
        expected = CANDIDATE_METHODS.get(row.get("selected_exercise"))
        ev.require(expected is not None and row.get("count_method") == expected[0]
                   and type(row.get("count_policy_version")) is int and row["count_policy_version"] == expected[1]
                   and row.get("tracking_side") == "both", "Wrong candidate case method/version/arms")
    result = _score_cases(reference, manifest, native, manifest_sha, reference_sha, candidate=True)
    result.update(replay_configuration_version=2, counting_method_configuration="bilateralDips_v1",
                  scope="separately identified bilateral dip candidate; unchanged frozen labels and event matcher; no legacy-v8/live/device/form qualification")
    return result


def _score_cases(reference, manifest, native, manifest_sha, reference_sha, candidate=False):
    cases, refs, predictions = manifest.get("cases", []), reference.get("clips", []), native.get("cases", [])
    ids = [c["id"] for c in cases]
    ev.require(ids and len(set(ids)) == len(ids) and ids == [r["id"] for r in refs]
               and ids == [n["id"] for n in predictions], "Missing, extra or reordered native cases")
    rows = []
    for case, ref, row in zip(cases, refs, predictions):
        ev.require(case.get("selected_exercise") in {"pullUp", "dip"} and case.get("tracking_side") in {"left", "right"}
                   and ref.get("counter_policy_version") == 8, "Unknown selected exercise/side/counter policy")
        reference_check(ref, dict(id=case["id"], exercise={"pullUp": "pull_up", "dip": "parallel_bar_dip"}[case["selected_exercise"]],
            media=dict(kind="video", expected_frames=case["frames"], files=[case["media"]])))
        ev.require(case["source_pts_seconds"] == ref["frame_pts_seconds"]
                   and case["source_span_seconds"] == ref["span_seconds"]
                   and case["frames"] == len(ref["frame_pts_seconds"])
                   and case["media"]["sha256"] == ref["media_sha256"] == row["sha256"], "Source evidence changed")
        ev.require(case["selected_exercise"] == ref["exercise"] == row["selected_exercise"]
                   and case["tracking_side"] == ref["side"] == row.get("reference_side" if candidate else "tracking_side"), "Native exercise/side misrouting")
        ev.require(row["status"] in {"processed", "setupUnavailable", "interrupted", "failed"}, "Unknown replay outcome")
        public.validate_progress_evidence(case, row)
        if row["status"] in {"processed", "setupUnavailable"}:
            ev.require(row["frames"] == case["frames"] and row["source_clock_verified"] is True, "Incomplete source EOF")
        for field in ["acceptance_seconds", "completed_seconds"]:
            values = row[field]
            ev.require(all(ev.number(t) and ref["span_seconds"][0] <= t < ref["span_seconds"][1] for t in values)
                       and values == sorted(values), "Invalid event source clock")
            ev.require(row["status"] in {"processed", "interrupted"} or not values,
                       "Unavailable/failed replay cannot claim measurements")
        quality = row.get("pose_quality", {})
        ev.require(type(quality.get("schema_version")) is int and quality["schema_version"] == 1
                   and quality.get("exercise") == ref["exercise"], "Wrong pose diagnostic recipe")
        histogram = quality.get("people_count_histogram", {})
        count = quality.get("unique_source_frames")
        ev.require(ev.integer(count, 0) and isinstance(histogram, dict)
                   and all(re.fullmatch(r"0|[1-9][0-9]*", k) and ev.integer(v, 0) for k, v in histogram.items())
                   and sum(histogram.values()) == count and count <= case["frames"], "Invalid raw people evidence")
        ev.require(count >= row["frames"] and (row["status"] not in {"processed", "setupUnavailable"}
                   or count == case["frames"]), "Incomplete raw observation coverage")
        for field in ["single_person_frames", "both_arm_frames", "apparatus_search_frames", "starting_pose_candidate_frames"]:
            ev.require(ev.integer(quality.get(field), 0) and quality[field] <= count, "Invalid raw pose totals")
        ev.require(quality["single_person_frames"] == histogram.get("1", 0)
                   and quality["starting_pose_candidate_frames"] <= quality["apparatus_search_frames"]
                   <= quality["single_person_frames"]
                   and quality["both_arm_frames"] <= quality["single_person_frames"], "Contradictory raw pose totals")
        for field in ["left_arm_status_counts", "right_arm_status_counts"]:
            statuses = quality.get(field)
            ev.require(isinstance(statuses, dict) and all(ev.integer(v, 0) for v in statuses.values())
                       and sum(statuses.values()) == count, "Incomplete raw arm diagnostics")
        left = quality["left_arm_status_counts"].get("available", 0)
        right = quality["right_arm_status_counts"].get("available", 0)
        single = quality["single_person_frames"]
        ev.require(left <= single and right <= single
                   and max(0, left + right - single) <= quality["both_arm_frames"] <= min(left, right),
                   "Contradictory available-arm evidence")
        # Dip search can use exactly one measured contact. Inclusion/exclusion
        # bounds it by actual arm evidence; pull-up search remains bilateral.
        measurable = left + right - quality["both_arm_frames"] if ref["exercise"] == "dip" else quality["both_arm_frames"]
        ev.require(quality["apparatus_search_frames"] <= measurable, "Search exceeds measurable-arm evidence")
        longest = quality.get("longest_starting_pose_candidate_seconds")
        ev.require(ev.number(longest) and 0 <= longest <= ref["span_seconds"][1] - ref["span_seconds"][0],
                   "Invalid starting-pose source duration")
        ev.require(quality["starting_pose_candidate_frames"] >= 2 or longest == 0,
                   "Starting-pose duration needs consecutive observations")
        metrics = score_events(ref, row["acceptance_seconds"])
        rows.append(dict(id=case["id"], exercise=ref["exercise"], side="both" if candidate else ref["side"], status=row["status"],
            event_metrics=metrics, completed_cycle_seconds=row["completed_seconds"], pose_quality=quality,
            automatic_bar_state=row.get("automatic_bar_state")))
        if candidate:
            rows[-1].update(reference_side=ref["side"], count_method=row["count_method"], count_policy_version=row["count_policy_version"])
            if ref["exercise"] == "dip":
                rows[-1].pop("automatic_bar_state")
                rows[-1]["legacy_automatic_bar_state"] = row.get("legacy_automatic_bar_state")
    return dict(schema_version=1, scope="selected-exercise production replay diagnostic; automatic contours, not live Auto identity/readiness or held-out/form qualification",
        manifest_sha256=manifest_sha, reference_sha256=reference_sha, source_revision=native["source_revision"], clips=rows,
        all_inputs_processed=all(r["status"] in {"processed", "setupUnavailable"} for r in rows),
        all_events_matched=all(r["status"] == "processed" and not r["event_metrics"]["false_positives"]
            and not r["event_metrics"]["false_negatives"] for r in rows))


def compiled_provenance(native_path, native, repository_root=None):
    sidecar_path = Path(str(native_path) + ".provenance.json")
    source = ev.read_json(sidecar_path)
    ev.require(source.get("source_revision") == native["source_revision"]
               and source.get("native_report_sha256") == ev.digest(native_path)
               and type(source.get("source_dirty")) is bool, "Native report/source provenance mismatch")
    hashes = source.get("source_sha256")
    ev.require(isinstance(hashes, dict) and hashes and all(isinstance(k, str) and re.fullmatch(r"[a-f0-9]{64}", v)
               for k, v in hashes.items()) and re.fullmatch(r"[a-f0-9]{64}", source.get("executable_sha256", "")),
               "Missing compiled native source/executable hashes")
    root = (repository_root or Path(__file__).resolve().parents[1]).resolve()
    required = set(compiled_source_paths(root)) | {REPLAY_FRAMEWORK}
    ev.require(required <= hashes.keys(), "Incomplete compiled native inputs")
    for relative, sha in hashes.items():
        source_path = PurePosixPath(relative)
        ev.require(relative == str(source_path) and not source_path.is_absolute() and ".." not in source_path.parts
                   and "\\" not in relative, "Invalid compiled source path")
        # The accuracy job runs on Linux. Record the native framework hash but
        # verify repository sources against that job's checkout, not a rebuild.
        if relative == REPLAY_FRAMEWORK:
            continue
        local = (root / relative).resolve()
        ev.require(local.is_relative_to(root) and local.is_file() and ev.digest(local) == sha,
                   "Compiled source differs from scoring checkout")
    return dict(provenance_sha256=ev.digest(sidecar_path), source_dirty=source["source_dirty"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["prepare", "score", "score-candidate"])
    parser.add_argument("--reference", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--root", type=Path)
    parser.add_argument("--native", type=Path)
    parser.add_argument("--require-exact-events", action="store_true")
    args = parser.parse_args()
    try:
        if args.mode == "prepare":
            ev.require(args.root is not None and args.native is None and not args.require_exact_events, "Invalid preparation options")
            prepare(args.reference, args.manifest, args.root, args.output)
        else:
            ev.require(args.native is not None and args.root is None and not args.output.exists(), "Invalid scoring options/output")
            native = ev.read_json(args.native)
            provenance = compiled_provenance(args.native, native)
            scoring = score_candidate if args.mode == "score-candidate" else score
            result = scoring(ev.read_json(args.reference), ev.read_json(args.manifest), native,
                ev.digest(args.manifest), ev.digest(args.reference))
            result["native_report_sha256"] = ev.digest(args.native)
            result["scorer_sha256"] = ev.digest(Path(__file__))
            result["reference_validator_sha256"] = ev.digest(Path(temporal_references.__file__))
            result["event_matcher_sha256"] = ev.digest(Path(temporal_events.__file__))
            result["compiled_native_provenance"] = provenance
            ev.write_json(args.output, result)
            for row in result["clips"]:
                m = row["event_metrics"]
                print(f"{row['id']} ({row['exercise']}): {row['status']}, matched={m['true_positives']}, extra={m['false_positives']}, missed={m['false_negatives']}")
            if not result["all_events_matched"]:
                print("Selected-replay diagnostics contain misses/unavailable evidence; accuracy remains unqualified.")
                return 3 if args.require_exact_events else 0
        return 0
    except (ValueError, KeyError, TypeError, OSError) as failure:
        print(f"Selected-replay evaluation incomplete: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
