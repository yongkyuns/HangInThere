#!/usr/bin/env python3
"""Score independently reviewed movement events, never strict-form acceptance.

Runs the exact Swift counter on a complete, hash-bound production pose stream.
Exit 0 means processing/scoring completed, not accuracy qualification. Optional
--require-exact-events returns 3 when this diagnostic contains missed or extra events.
"""
from __future__ import annotations
import argparse
from collections import Counter
import math
from pathlib import Path
import subprocess
import sys

import evaluation as ev

DEFINITION = {"pullUp": "observed_start_to_top", "dip": "observed_top_bottom_top"}
EXERCISE = {"pullUp": "pull_up", "dip": "parallel_bar_dip"}


def intervals(values, span, reason=False):
    ev.require(isinstance(values, list), "Intervals must be a list")
    result = []
    for value in values:
        pair = value.get("seconds") if reason else value
        ev.require(isinstance(pair, list) and len(pair) == 2 and all(ev.number(x) for x in pair), "Invalid interval")
        a, b = pair
        ev.require(span[0] <= a < b <= span[1], "Interval outside observation span")
        ev.require(not result or result[-1][1] < a, "Intervals overlap or are not strictly ordered")
        if reason:
            ev.require(isinstance(value.get("reason"), str) and value["reason"].strip(), "Ungradable interval needs reason")
        result.append((a, b))
    return result


def reference_check(ref, clip):
    ev.require(ref.get("schema_version") == 1 and ref.get("id") == clip["id"], "Reference identity mismatch")
    ev.require(ref.get("reviewed_without_counter_output") is True and ref.get("provenance"), "Independent temporal review required")
    ev.require(ref.get("form_verification") == "unverified", "Movement labels do not establish valid form")
    exercise = ref.get("exercise")
    ev.require(exercise in DEFINITION and EXERCISE[exercise] == clip["exercise"], "Exercise mismatch")
    ev.require(ref.get("event_definition") == DEFINITION[exercise] and ref.get("side") in ("left", "right"), "Event or arm policy missing")
    ev.require(clip["media"]["kind"] == "video" and ref.get("media_sha256") == clip["media"]["files"][0]["sha256"], "Wrong temporal media")
    pts = ref.get("frame_pts_seconds")
    ev.require(isinstance(pts, list) and len(pts) > 1 and all(ev.number(t) and t >= 0 for t in pts)
               and all(a < b for a, b in zip(pts, pts[1:])), "Full actual source PTS are required")
    ev.require(len(pts) == clip["media"].get("expected_frames"), "Reference frame count mismatch")
    span = ref.get("span_seconds")
    ev.require(isinstance(span, list) and len(span) == 2 and all(ev.number(t) for t in span)
               and abs(span[0] - pts[0]) < 1e-5 and pts[-1] < span[1] <= pts[-1] + 1, "Invalid full observation span")
    windows = intervals(ref.get("events"), span)
    excluded = intervals(ref.get("ungradable_intervals"), span, reason=True)
    edge = ref.get("bar_reference_edge")
    if edge is not None:
        ev.require(isinstance(edge, list) and len(edge) == 4 and all(ev.number(x) for x in edge),
                   "Invalid fixed apparatus reference")
        ev.require(math.hypot(edge[2]-edge[0], edge[3]-edge[1]) >= 2, "Degenerate apparatus reference")
        ev.require(isinstance(ref.get("bar_reference_provenance"), str) and ref["bar_reference_provenance"].strip(),
                   "Apparatus reference needs provenance")
    tolerance = ref.get("tolerance_seconds")
    ev.require(ev.number(tolerance) and 0 <= tolerance <= .5, "Invalid predeclared timing tolerance")
    for a, b in windows:
        ev.require(all(b + tolerance < x or a - tolerance >= y for x, y in excluded), "Event window overlaps ungradable time")
    return windows, excluded


def score_events(ref, events):
    """Chronological one-to-one maximum-cardinality matching of ordered windows.

    Each event can match once. Extra detections, including exact duplicates,
    remain false positives. Negative/no-event intervals remain in evaluation.
    """
    span = ref["span_seconds"]
    windows = intervals(ref["events"], span)
    excluded = intervals(ref["ungradable_intervals"], span, reason=True)
    tolerance = ref["tolerance_seconds"]
    ev.require(ev.number(tolerance) and 0 <= tolerance <= .5, "Invalid tolerance")
    ev.require(all(ev.number(t) and span[0] <= t < span[1] for t in events)
               and events == sorted(events), "Invalid predicted event times")
    scored = [(i, t) for i, t in enumerate(events) if not any(a <= t < b for a, b in excluded)]
    ignored = [i for i, t in enumerate(events) if any(a <= t < b for a, b in excluded)]
    matches, false_positive, missed = [], [], []
    j = 0
    for r, (a, b) in enumerate(windows):
        while j < len(scored) and scored[j][1] < a - tolerance:
            false_positive.append(scored[j][0]); j += 1
        if j < len(scored) and scored[j][1] <= b + tolerance:
            p, t = scored[j]; j += 1
            # Distance to the reviewed uncertainty interval (zero inside it).
            error = t - a if t < a else t - b if t > b else 0.0
            matches.append({"reference_index": r, "prediction_index": p, "source_seconds": t,
                            "signed_distance_to_window_seconds": error})
        else:
            missed.append(r)
    false_positive.extend(i for i, _ in scored[j:])
    tp, fp, fn = len(matches), len(false_positive), len(missed)
    duration = span[1] - span[0]
    coverage = 1 - sum(b - a for a, b in excluded) / duration
    errors = [abs(m["signed_distance_to_window_seconds"]) for m in matches]
    return {"reference_events": len(windows), "predicted_events_total": len(events),
            "predicted_events_scored": len(scored), "true_positives": tp, "false_positives": fp,
            "false_negatives": fn, "precision": tp / (tp + fp) if tp + fp else None,
            "recall": tp / (tp + fn) if tp + fn else None,
            "f1": 2 * tp / (2 * tp + fp + fn) if 2 * tp + fp + fn else None,
            "absolute_count_error": abs(len(scored) - len(windows)), "exact_count": len(scored) == len(windows),
            "scored_time_fraction": coverage, "scored_seconds": duration * coverage,
            "matches": matches, "missed_reference_indices": missed,
            "false_positive_indices": false_positive, "ignored_prediction_indices": ignored,
            "matched_timing_error_seconds": ev.statistics(errors, len(windows))}


def validate_run(ref, clip, pose_report, counter, observations, completion):
    reference_check(ref, clip)
    ev.require(pose_report["status"] == "processed" and completion["status"] == "processed", "Incomplete pose run")
    ev.require(counter["summary"]["phase"] == "finished" and counter["summary"]["formVerification"] == "unverified", "Unfinished or unsupported count verdict")
    ev.require(counter["summary"]["exercise"] == ref["exercise"] and counter["summary"]["side"] == ref["side"], "Counter policy selection differs")
    ev.require(counter["summary"]["policyVersion"] == ref["counter_policy_version"], "Counter policy changed")
    expected_edge = ref.get("bar_reference_edge")
    actual_edge = counter.get("referenceEdge")
    if expected_edge is None:
        ev.require(actual_edge is None, "Counter used an unreviewed apparatus reference")
    else:
        ev.require(isinstance(actual_edge, dict), "Counter omitted the reviewed apparatus reference")
        actual_values = [actual_edge.get("a", {}).get("x"), actual_edge.get("a", {}).get("y"),
                         actual_edge.get("b", {}).get("x"), actual_edge.get("b", {}).get("y")]
        ev.require(all(ev.number(x) for x in actual_values) and
                   all(abs(a-b) < 1e-9 for a,b in zip(actual_values, expected_edge)),
                   "Counter apparatus reference differs from frozen review")
    ev.require(counter["frames"] == completion["frames"] == pose_report["frames"] == len(observations) == len(ref["frame_pts_seconds"]), "Partial/truncated temporal stream")
    pts = []
    for row in observations:
        stamp = row.get("timestamp") or {}
        ev.require(row.get("frameIndex") == len(pts) and row.get("timebase") == "source_pts"
                   and ev.integer(stamp.get("timescale"), 1) and ev.integer(stamp.get("value")), "Invalid source frame identity")
        now = stamp["value"] / stamp["timescale"]
        ev.require(abs(now - ref["frame_pts_seconds"][len(pts)]) < 1e-5, "Source timebase mismatch")
        pts.append(now)
    totals = Counter()
    previous = -math.inf
    for event in counter["events"]:
        t = event["sourceSeconds"]
        ev.require(event["outcome"] in ("movement", "partial", "interrupted") and event.get("reason"), "Unknown outcome")
        ev.require(ev.number(t) and t >= previous and any(abs(t - p) < 1e-5 for p in pts), "Event not bound to observed source time")
        totals[event["outcome"]] += 1; previous = t
    for outcome, key in (("movement", "observedMovements"), ("partial", "partialAttempts"), ("interrupted", "interruptedAttempts")):
        ev.require(counter["summary"][key] == totals[outcome], "Summary and events disagree")


def run(reference_path, manifest_path, root, pose_output, output, public=False):
    manifest = ev.read_json(manifest_path)
    clips = ev.validate_manifest(manifest, root)
    refs = ev.read_json(reference_path)
    ev.require(refs.get("schema_version") == 1 and refs.get("manifest_sha256") == ev.digest(manifest_path), "Wrong temporal manifest")
    ev.require([r["id"] for r in refs["clips"]] == [c["id"] for c in clips], "Temporal corpus is incomplete/reordered")
    pose = ev.read_json(pose_output / "report.json")
    ev.require(pose["manifest_sha256"] == ev.digest(manifest_path) and [r["id"] for r in pose["clips"]] == [c["id"] for c in clips], "Wrong pose report")
    # Freeze all labels before executing the counter, not per-clip after results.
    for ref, clip in zip(refs["clips"], clips): reference_check(ref, clip)
    before = {reference_path: ev.digest(reference_path), manifest_path: ev.digest(manifest_path),
              pose_output / "report.json": ev.digest(pose_output / "report.json")}
    output.mkdir(parents=True, exist_ok=False)
    records = []
    for ref, clip, row in zip(refs["clips"], clips, pose["clips"]):
        ev.require(ev.preflight(clip, root, public) == "ready", "Temporal media permission/integrity failure")
        ev.require(row["media_sha256"] == [ref["media_sha256"]] and row["status"] == "processed", "Wrong/incomplete pose media")
        observation_path = pose_output / clip["id"] / "observations.jsonl"
        observation_hash = ev.digest(observation_path)
        ev.require(row["observations_sha256"] == observation_hash, "Pose observations changed")
        completion_path = observation_path.with_name("completion.json")
        completion_hash = ev.digest(completion_path)
        observations = list(ev.observations(observation_path, "video"))
        count_path = output / (clip["id"] + "-counter.json")
        count_command = [str(ev.ROOT / "scripts/count-replay.sh"), str(observation_path),
                         ref["exercise"], ref["side"], str(count_path)]
        if ref.get("bar_reference_edge") is not None:
            count_command.extend(str(x) for x in ref["bar_reference_edge"])
        subprocess.run(count_command, check=True)
        counter = ev.read_json(count_path)
        ev.require(counter["input_sha256"] == observation_hash and counter["source_revision"] == pose["source_commit"], "Counter/pose provenance differs")
        for path, sha in counter["source_sha256"].items():
            ev.require(ev.digest(ev.ROOT / path) == sha, "Counter source changed")
            if path.startswith("HangInThere/"):
                ev.require(pose["source_files_sha256"].get(path) == sha, "Pose/counter app source differs")
        validate_run(ref, clip, row, counter, observations, ev.read_json(completion_path))
        scores = score_events(ref, [e["sourceSeconds"] for e in counter["events"] if e["outcome"] == "movement"])
        hist = dict(Counter(len(x["people"]) for x in observations))
        records.append({"id": clip["id"], "exercise": clip["exercise"], "side": ref["side"], "status": "scored",
                        "form_verification": "unverified", "frames": len(observations), "person_count_histogram": hist,
                        "counter_summary": counter["summary"], "event_metrics": scores,
                        "counter_report_sha256": ev.digest(count_path), "observations_sha256": observation_hash,
                        "completion_sha256": completion_hash, "media_sha256": ref["media_sha256"]})
        ev.require(ev.digest(observation_path) == observation_hash and ev.digest(completion_path) == completion_hash
                   and ev.preflight(clip, root, public) == "ready", "Input changed while counting")
    ev.require(all(ev.digest(p) == h for p, h in before.items()), "Temporal labels/report changed during evaluation")
    report = {"schema_version": 1, "scope": "model-independent single-reviewer temporal diagnostic; not held-out or strict-form qualification",
              "reference_sha256": before[reference_path], "manifest_sha256": before[manifest_path],
              "pose_report_sha256": before[pose_output / "report.json"], "scorer_sha256": ev.digest(Path(__file__)),
              "source_commit": pose["source_commit"], "clips": records}
    # Report errors even if the aggregate count happens to match. No averaging of
    # distinct exercises, no counting a missing measurement as a correct event.
    report["all_events_matched"] = all(not r["event_metrics"]["false_positives"] and not r["event_metrics"]["false_negatives"] for r in records)
    ev.write_json(output / "report.json", report)
    return report


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("reference", type=Path); p.add_argument("--manifest", required=True, type=Path)
    p.add_argument("--root", required=True, type=Path); p.add_argument("--pose-output", required=True, type=Path)
    p.add_argument("--output", required=True, type=Path); p.add_argument("--public-output", action="store_true")
    p.add_argument("--require-exact-events", action="store_true")
    a = p.parse_args()
    try:
        report = run(a.reference.resolve(), a.manifest.resolve(), a.root.resolve(), a.pose_output.resolve(), a.output.resolve(), a.public_output)
        for row in report["clips"]:
            m = row["event_metrics"]
            print(f'{row["id"]}: TP={m["true_positives"]} FP={m["false_positives"]} FN={m["false_negatives"]}; scored time={m["scored_time_fraction"]:.1%}; form unverified')
        if not report["all_events_matched"]:
            print('Temporal pilot has missed/extra movement events; completed scoring is NOT passed accuracy qualification.')
            if a.require_exact_events: raise SystemExit(3)
    except (ValueError, KeyError, TypeError, OSError, subprocess.CalledProcessError) as error:
        print(f'Temporal evaluation incomplete: {error}', file=sys.stderr); raise SystemExit(2)