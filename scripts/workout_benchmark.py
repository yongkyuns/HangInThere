#!/usr/bin/env python3
"""Freeze and score source-time workout annotations, without a release verdict.

Only normalized labels/measurements are read. This tool never opens, fetches or
uploads media. Synthetic contracts do not establish population accuracy.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import sys

try:
    from . import session_qualification as session
    from . import temporal_events as temporal
except ImportError:
    import session_qualification as session
    import temporal_events as temporal

BenchmarkError = session.SessionError
require = session.require
EXERCISES = {"pullUp", "dip"}
PHASES = {"hang", "support", "pulling", "lowering", "rising", "peakHold", "bottomHold", "effortHold", "release", "acquiring"}
HOLDS = {"hang", "support", "peakHold", "bottomHold", "effortHold"}
SPLITS = {"development", "validation", "test"}
END_KINDS = {"disengaged", "trackingLost", "manual", "interruption", "sourceEnd"}
SCORER_SHA256 = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
MATCHER_SHA256 = hashlib.sha256(Path(temporal.__file__).read_bytes()).hexdigest()


def keys(value, required, optional=()):
    require(isinstance(value, dict), "Expected an object")
    require(set(required) <= value.keys() <= set(required) | set(optional), "Missing or unsupported fields")


def identifier(value):
    require(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,100}", value), "Invalid opaque identifier")


def sha(value):
    require(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value), "Invalid SHA256")


def number(value):
    return session.finite_number(value) is not None


def span(value, outer=None, point=False):
    require(isinstance(value, list) and len(value) == 2 and all(number(x) for x in value), "Invalid source interval")
    require(0 <= value[0] <= value[1] if point else 0 <= value[0] < value[1], "Invalid interval ordering")
    if outer is not None:
        require(outer[0] <= value[0] <= value[1] <= outer[1], "Interval outside source span")
    return tuple(value)


def ordered(values, outer, point=False):
    require(isinstance(values, list), "Expected an interval array")
    result = [span(x, outer, point) for x in values]
    require(all(a[1] <= b[0] for a, b in zip(result, result[1:])), "Intervals overlap or are reordered")
    return result


def event_windows(values, outer):
    result = ordered(values, outer, point=True)
    return result


def merge(values):
    result = []
    for a, b in sorted(values):
        if result and a <= result[-1][1]:
            result[-1] = (result[-1][0], max(b, result[-1][1]))
        else:
            result.append((a, b))
    return result


def intersect(left, right):
    result = []; i = j = 0
    while i < len(left) and j < len(right):
        a, b = max(left[i][0], right[j][0]), min(left[i][1], right[j][1])
        if a < b: result.append((a, b))
        if left[i][1] <= right[j][1]: i += 1
        else: j += 1
    return result


def subtract(source, excluded):
    result = []; cursor = source[0]
    for a, b in excluded:
        if cursor < a: result.append((cursor, a))
        cursor = b
    if cursor < source[1]: result.append((cursor, source[1]))
    return result


def seconds(values):
    return sum(b - a for a, b in values)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()


def fingerprint(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def phases(values, outer, exercise=None):
    require(isinstance(values, list), "Expected a phase array")
    intervals = []
    for item in values:
        keys(item, ("phase", "seconds"))
        require(item["phase"] in PHASES | {"unobserved"}, "Unknown motion phase")
        if exercise is not None:
            forbidden = {"support", "rising", "bottomHold", "unobserved"} if exercise == "pullUp" else {"hang", "pulling", "peakHold", "unobserved"}
            require(item["phase"] not in forbidden, "Reference phase disagrees with exercise")
        intervals.append(span(item["seconds"], outer))
    require(all(a[1] <= b[0] for a, b in zip(intervals, intervals[1:])), "Phases overlap or are reordered")


def validate_benchmark(value):
    keys(value, ("schema_version", "id", "evidence_class", "timebase", "completed_cycle_definition", "event_tolerance_seconds", "phase_tolerance_seconds", "automatic_end_grace_seconds", "sessions"))
    require(type(value["schema_version"]) is int and value["schema_version"] == 1, "Unsupported benchmark version")
    identifier(value["id"])
    require(value["evidence_class"] in {"synthetic_contract", "independently_reviewed_sessions"}, "Unknown evidence class")
    require(value["timebase"] == "source_pts_seconds", "Actual source timing is required; nominal FPS is unsupported")
    require(value["completed_cycle_definition"] == "start_extreme_return", "Acceptance milestones are not completed-cycle labels")
    for name in ("event_tolerance_seconds", "phase_tolerance_seconds"):
        require(number(value[name]) and 0 <= value[name] <= .5, "Invalid frozen timing tolerance")
    require(number(value["automatic_end_grace_seconds"]) and 0 <= value["automatic_end_grace_seconds"] <= 5, "Invalid frozen automatic-end response allowance")
    require(isinstance(value["sessions"], list) and value["sessions"], "Empty benchmark")
    seen = set(); groups = {name: {} for name in ("participant_group", "source_group", "capture_sha256")}
    for row in value["sessions"]:
        keys(row, ("id", "participant_group", "source_group", "capture_sha256", "split", "context", "review", "span_seconds", "ungradable_intervals", "sets"))
        identifier(row["id"]); require(row["id"] not in seen, "Duplicate session id"); seen.add(row["id"])
        sha(row["capture_sha256"]); span(row["span_seconds"])
        require(row["split"] in SPLITS, "Unknown split")
        for name, assignments in groups.items():
            if name != "capture_sha256": identifier(row[name])
            require(assignments.get(row[name], row["split"]) == row["split"], "Participant/source/capture leakage between splits")
            assignments[row[name]] = row["split"]
        keys(row["context"], ("device_family", "camera_view", "lighting", "assistance", "apparatus"))
        identifier(row["context"]["device_family"])
        for name, allowed in {"camera_view": {"front", "side", "oblique", "mixed"}, "lighting": {"bright", "dim", "mixed"},
                              "assistance": {"unassisted", "assisted", "negative", "mixed"}, "apparatus": {"pull_up_bar", "parallel_bars", "mixed"}}.items():
            require(row["context"][name] in allowed, "Unknown capture context")
        keys(row["review"], ("without_runtime_output", "reviewer_groups", "adjudicated"))
        review = row["review"]
        require(type(review["without_runtime_output"]) is bool and type(review["adjudicated"]) is bool, "Invalid review provenance")
        require(isinstance(review["reviewer_groups"], list), "Missing reviewers")
        for name in review["reviewer_groups"]: identifier(name)
        require(len(set(review["reviewer_groups"])) == len(review["reviewer_groups"]), "Duplicate reviewers")
        if value["evidence_class"] == "independently_reviewed_sessions":
            require(review["without_runtime_output"] and review["adjudicated"] and len(review["reviewer_groups"]) >= 2, "Independent adjudicated review is required")
        excluded = ordered(row["ungradable_intervals"], row["span_seconds"])
        require(isinstance(row["sets"], list), "Expected a set array")
        bounds = []; ids = set()
        for item in row["sets"]:
            keys(item, ("id", "exercise", "seconds", "start_window", "end_window", "completed_rep_windows", "phases"))
            identifier(item["id"]); require(item["id"] not in ids, "Duplicate reference set"); ids.add(item["id"])
            require(item["exercise"] in EXERCISES, "Unknown exercise")
            bounds.append(span(item["seconds"], row["span_seconds"]))
            for name, time in (("start_window", item["seconds"][0]), ("end_window", item["seconds"][1])):
                if name == "end_window" and item[name] is None: continue
                a, b = span(item[name], row["span_seconds"], point=True)
                require(a <= time <= b, "Boundary uncertainty window omits set boundary")
            windows = event_windows(item["completed_rep_windows"], item["seconds"])
            windows += [tuple(item["start_window"])] + ([tuple(item["end_window"])] if item["end_window"] is not None else [])
            tolerance = value["event_tolerance_seconds"]
            require(all(b + tolerance < x or a - tolerance >= y for a, b in windows for x, y in excluded), "Event label/tolerance overlaps ungradable time")
            phases(item["phases"], item["seconds"], item["exercise"])
        require(all(a[1] <= b[0] for a, b in zip(bounds, bounds[1:])), "Reference sets overlap or are reordered")


def freeze(value):
    validate_benchmark(value)
    return {"schema_version": 1, "benchmark_sha256": fingerprint(value), "benchmark": copy.deepcopy(value)}


def validate_lock(lock):
    keys(lock, ("schema_version", "benchmark_sha256", "benchmark"))
    require(type(lock["schema_version"]) is int and lock["schema_version"] == 1, "Unsupported lock version")
    validate_benchmark(lock["benchmark"]); sha(lock["benchmark_sha256"])
    require(fingerprint(lock["benchmark"]) == lock["benchmark_sha256"], "Frozen labels, groups or tolerances changed")


def validate_predictions(value, lock, split):
    keys(value, ("schema_version", "benchmark_sha256", "timebase", "completed_cycle_definition", "run", "sessions"))
    require(type(value["schema_version"]) is int and value["schema_version"] == 1, "Unsupported prediction version")
    require(value["benchmark_sha256"] == lock["benchmark_sha256"], "Run is bound to a different benchmark")
    require(value["timebase"] == "source_pts_seconds", "Wrong prediction timebase")
    require(value["completed_cycle_definition"] == lock["benchmark"]["completed_cycle_definition"], "Completed-cycle event definition differs")
    run = value["run"]
    keys(run, ("id", "platform", "backend", "source_revision", "counter_policy_version", "activity_metric_version", "rep_metric_version", "capture_mode"))
    identifier(run["id"]); identifier(run["backend"])
    require(run["platform"] in {"ios", "android"} and run["capture_mode"] == "automatic", "Benchmark requires a declared automatic run")
    require(isinstance(run["source_revision"], str) and re.fullmatch(r"[0-9a-f]{40}", run["source_revision"]), "Missing source revision")
    for name in ("counter_policy_version", "activity_metric_version", "rep_metric_version"):
        require(session.integer(run[name], 1), "Invalid analysis version")
    references = {r["id"]: r for r in lock["benchmark"]["sessions"] if split == "all" or r["split"] == split}
    require(references, "Selected split is empty")
    require(isinstance(value["sessions"], list), "Missing prediction sessions")
    seen = set()
    for row in value["sessions"]:
        keys(row, ("id", "capture_sha256", "span_seconds", "status", "observed_intervals", "sets"))
        require(row["id"] in references and row["id"] not in seen, "Unexpected or duplicate predicted session")
        seen.add(row["id"]); ref = references[row["id"]]
        require(row["capture_sha256"] == ref["capture_sha256"] and row["span_seconds"] == ref["span_seconds"], "Capture or full source span mismatch")
        require(row["status"] in {"processed", "failed"}, "Unknown run status")
        support = ordered(row["observed_intervals"], ref["span_seconds"], point=True)
        # Closed endpoints represent actual observed frames. Zero-width entries
        # support discrete events but never earn elapsed time or bridge a gap.
        def supported(time): return any(a <= time <= b for a, b in support)
        require(isinstance(row["sets"], list), "Expected predicted set array")
        if row["status"] == "failed": require(not row["sets"] and not row["observed_intervals"], "Failed processing cannot claim measurements")
        starts = []
        for item in row["sets"]:
            keys(item, ("exercise", "seconds", "end_kind", "completed_rep_seconds", "phases"))
            require(item["exercise"] in EXERCISES and item["end_kind"] in END_KINDS, "Unknown predicted exercise/end kind")
            bound = span(item["seconds"], ref["span_seconds"]); starts.append(bound[0])
            require(item["end_kind"] not in {"disengaged", "trackingLost"} or bound[1] < ref["span_seconds"][1], "Automatic end must be an observed source timestamp")
            require(isinstance(item["completed_rep_seconds"], list) and all(number(t) and bound[0] <= t <= bound[1] and t < ref["span_seconds"][1] for t in item["completed_rep_seconds"])
                    and item["completed_rep_seconds"] == sorted(item["completed_rep_seconds"]), "Invalid completed-cycle events")
            phases(item["phases"], bound)
            points = [bound[0]] + item["completed_rep_seconds"]
            if item["end_kind"] == "disengaged": points.append(bound[1])
            points += [t for values in transitions(item["phases"]).values() for t in values]
            require(all(supported(t) for t in points), "Discrete prediction lacks observed source support")
        require(starts == sorted(starts), "Predicted sets are reordered")
    require(seen == references.keys(), "Run omits benchmark sessions, including negatives or failures")
    return references


def transitions(items):
    result = {}
    for first, second in zip(items, items[1:]):
        if first["seconds"][1] == second["seconds"][0] and first["phase"] != second["phase"] and "unobserved" not in (first["phase"], second["phase"]):
            result.setdefault(first["phase"] + "->" + second["phase"], []).append(second["seconds"][0])
    return result


def pool_events(parts):
    counts = {key: sum(p[key] for p in parts.values()) for key in ("reference_events", "predicted_events_total", "predicted_events_scored", "true_positives", "false_positives", "false_negatives")}
    tp, fp, fn = (counts[k] for k in ("true_positives", "false_positives", "false_negatives"))
    return dict(counts, precision=session.fraction(tp, tp + fp), recall=session.fraction(tp, tp + fn),
                f1=session.fraction(2 * tp, 2 * tp + fp + fn), by_exercise=parts)


def score_ends(truth, detected, source, excluded, benchmark):
    original = sorted(r["end_window"] for r in truth if r["end_window"] is not None)
    outputs = sorted((r["seconds"][1], r["end_kind"]) for r in detected if r["end_kind"] in {"disengaged", "trackingLost"})
    positive_indices = [i for i, (_, kind) in enumerate(outputs) if kind == "disengaged"]
    response_windows = [[a, min(source[1], b + benchmark["automatic_end_grace_seconds"])] for a, b in original]
    metric = temporal.match_events(source, response_windows, excluded, benchmark["event_tolerance_seconds"],
                                   [outputs[i][0] for i in positive_indices])
    # Matching permits a frozen response delay. Report the distance to the
    # original physical-release label, so this cannot hide that delay.
    for match in metric["matches"]:
        a, b = original[match["reference_index"]]; time = match["source_seconds"]
        match["prediction_index"] = positive_indices[match["prediction_index"]]
        match["signed_distance_to_window_seconds"] = time - a if time < a else time - b if time > b else 0.0
    metric["matched_timing_error_seconds"] = temporal.ev.statistics([abs(m["signed_distance_to_window_seconds"]) for m in metric["matches"]], len(original))
    losses = [i for i, (_, kind) in enumerate(outputs) if kind == "trackingLost"]
    ignored_losses = [i for i in losses if any(a <= outputs[i][0] < b for a, b in excluded)]
    # A timeout on missing observations is an interruption, never positive
    # disengagement evidence even if its timestamp coincides with a true end.
    metric["predicted_end_events"] = [{"source_seconds": time, "kind": kind} for time, kind in outputs]
    metric["false_positive_indices"] = sorted([positive_indices[i] for i in metric["false_positive_indices"]] + [i for i in losses if i not in ignored_losses])
    metric["ignored_prediction_indices"] = sorted([positive_indices[i] for i in metric["ignored_prediction_indices"]] + ignored_losses)
    metric["false_positives"] = len(metric["false_positive_indices"])
    metric["predicted_events_total"] = len(outputs); metric["predicted_events_scored"] = len(outputs) - len(metric["ignored_prediction_indices"])
    tp, fp, fn = (metric[k] for k in ("true_positives", "false_positives", "false_negatives"))
    metric.update(precision=session.fraction(tp, tp + fp), recall=session.fraction(tp, tp + fn),
                  f1=session.fraction(2 * tp, 2 * tp + fp + fn), absolute_count_error=abs(metric["predicted_events_scored"] - len(original)),
                  exact_count=metric["predicted_events_scored"] == len(original))
    return metric


def score_session(ref, prediction, benchmark, exercise=None):
    truth = [r for r in ref["sets"] if exercise is None or r["exercise"] == exercise]
    detected = [r for r in prediction["sets"] if exercise is None or r["exercise"] == exercise]
    source = ref["span_seconds"]; excluded = ref["ungradable_intervals"]
    def events(windows, values, tolerance=None):
        return temporal.match_events(source, sorted(windows), excluded, benchmark["event_tolerance_seconds"] if tolerance is None else tolerance, sorted(values))
    completed = pool_events({kind: events([w for r in truth if r["exercise"] == kind for w in r["completed_rep_windows"]],
                            [t for r in detected if r["exercise"] == kind for t in r["completed_rep_seconds"]]) for kind in sorted(EXERCISES)})
    starts = events([r["start_window"] for r in truth], [r["seconds"][0] for r in detected])
    ends = score_ends(truth, detected, source, excluded, benchmark)
    # Match parents by start AND exercise. Counts with the same total but assigned
    # to a different set/exercise cannot become exact-set evidence.
    matched = {}; extra_sets = missed_sets = 0
    for kind in sorted(EXERCISES):
        refs = [(i, r) for i, r in enumerate(truth) if r["exercise"] == kind]
        preds = [(i, r) for i, r in enumerate(detected) if r["exercise"] == kind]
        match = events([r["start_window"] for _, r in refs], [r["seconds"][0] for _, r in preds])
        for pair in match["matches"]: matched[refs[pair["reference_index"]][0]] = preds[pair["prediction_index"]][1]
        extra_sets += match["false_positives"]; missed_sets += match["false_negatives"]
    count_gradable = {i for i, r in enumerate(truth) if not any(r["seconds"][0] < b and a < r["seconds"][1] for a, b in excluded)}
    exact_sets = sum(i in count_gradable and len(truth[i]["completed_rep_windows"]) == sum(not any(a <= t < b for a, b in excluded) for t in p["completed_rep_seconds"]) for i, p in matched.items())
    grade = subtract(source, excluded); observed = intersect(grade, merge(prediction["observed_intervals"]))
    known = merge([p["seconds"] for r in truth for p in r["phases"]]); labelled = intersect(grade, known)
    expected = {}; predicted = {}
    for phase in sorted(PHASES):
        expected[phase] = intersect(grade, merge([p["seconds"] for r in truth for p in r["phases"] if p["phase"] == phase]))
        claimed = []
        for i, row in matched.items():
            for p in row["phases"]:
                if p["phase"] == phase:
                    claimed.extend(intersect([tuple(p["seconds"])], [tuple(truth[i]["seconds"])]))
        predicted[phase] = intersect(observed, intersect(labelled, merge(claimed)))
    labelled_seconds = seconds(labelled); output_seconds = sum(seconds(v) for v in predicted.values())
    correct = sum(seconds(intersect(expected[p], predicted[p])) for p in sorted(PHASES))
    boundaries = {}
    # Exercise-specific diagnostics still know when another exercise was
    # independently labelled. Wrong-exercise boundaries there remain extras.
    boundary_truth = merge([p["seconds"] for row in ref["sets"] for p in row["phases"]])
    boundary_excluded = subtract(source, intersect(grade, boundary_truth))
    for kind in sorted(EXERCISES):
        reference_changes = {}; predicted_changes = {}
        for target, rows in ((reference_changes, truth), (predicted_changes, detected)):
            for row in rows:
                if row["exercise"] == kind:
                    for key, values in transitions(row["phases"]).items(): target.setdefault(key, []).extend(values)
        for key in sorted(reference_changes.keys() | predicted_changes.keys()):
            times = reference_changes.get(key, [])
            windows = [[t, t] for t in times if not any(a <= t < b for a, b in boundary_excluded)]
            boundaries[kind + ":" + key] = temporal.match_events(source, windows, boundary_excluded,
                benchmark["phase_tolerance_seconds"], sorted(predicted_changes.get(key, [])))
    return {"id": ref["id"], "split": ref["split"], "context": ref["context"], "status": prediction["status"],
            "completed_cycles": completed, "automatic_starts": starts, "automatic_ends": ends,
            "reference_sets": len(truth), "count_gradable_sets": len(count_gradable), "missed_sets": missed_sets, "extra_sets": extra_sets, "exact_count_sets": exact_sets,
            "source_gradable_seconds": seconds(grade), "observed_seconds": seconds(observed),
            "observation_coverage": session.fraction(seconds(observed), seconds(grade)),
            "labelled_phase_seconds": labelled_seconds, "phase_output_seconds": output_seconds, "correct_phase_seconds": correct,
            "phase_agreement_all_labelled": session.fraction(correct, labelled_seconds),
            "phase_agreement_when_reported": session.fraction(correct, output_seconds),
            "phase_output_coverage": session.fraction(output_seconds, labelled_seconds),
            "hold_duration": {p: {"reference_seconds": seconds(expected[p]), "predicted_credited_seconds": seconds(predicted[p]),
                                 "signed_error_seconds": seconds(predicted[p]) - seconds(expected[p]) if labelled_seconds else None} for p in sorted(HOLDS)},
            "phase_boundaries": boundaries}


def aggregate(rows):
    result = {"sessions": len(rows), "failed_sessions": sum(r["status"] == "failed" for r in rows)}
    for name in ("completed_cycles", "automatic_starts", "automatic_ends"):
        counts = {key: sum(r[name][key] for r in rows) for key in ("true_positives", "false_positives", "false_negatives")}
        tp, fp, fn = (counts[k] for k in ("true_positives", "false_positives", "false_negatives"))
        result[name] = dict(counts, precision=session.fraction(tp, tp + fp), recall=session.fraction(tp, tp + fn))
    for name in ("reference_sets", "count_gradable_sets", "missed_sets", "extra_sets", "exact_count_sets", "source_gradable_seconds", "observed_seconds", "labelled_phase_seconds", "phase_output_seconds", "correct_phase_seconds"):
        result[name] = sum(r[name] for r in rows)
    for name, a, b in (("exact_set_count_fraction", "exact_count_sets", "count_gradable_sets"), ("observation_coverage", "observed_seconds", "source_gradable_seconds"),
                       ("phase_agreement_all_labelled", "correct_phase_seconds", "labelled_phase_seconds"), ("phase_output_coverage", "phase_output_seconds", "labelled_phase_seconds")):
        result[name] = session.fraction(result[a], result[b])
    boundaries = [metric for row in rows for metric in row["phase_boundaries"].values()]
    result["phase_boundaries"] = {key: sum(b[key] for b in boundaries) for key in ("true_positives", "false_positives", "false_negatives")}
    errors = [abs(m["signed_distance_to_window_seconds"]) for b in boundaries for m in b["matches"]]
    result["phase_boundaries"]["matched_error_seconds"] = temporal.ev.statistics(errors, sum(b["reference_events"] for b in boundaries))
    result["hold_duration"] = {}
    for phase in sorted(HOLDS):
        measures = [r["hold_duration"][phase] for r in rows if r["labelled_phase_seconds"] and
                    (r["hold_duration"][phase]["reference_seconds"] or r["hold_duration"][phase]["predicted_credited_seconds"])]
        errors = [m["signed_error_seconds"] for m in measures]
        result["hold_duration"][phase] = {"scored_sessions": len(errors),
            "mean_signed_error_seconds": sum(errors) / len(errors) if errors else None,
            "absolute_error_seconds": temporal.ev.statistics([abs(e) for e in errors], len(measures))}
    return result


def score(lock, predictions, split="test"):
    validate_lock(lock); require(split in SPLITS | {"all"}, "Unknown split")
    refs = validate_predictions(predictions, lock, split); benchmark = lock["benchmark"]
    inputs = {r["id"]: r for r in predictions["sessions"]}
    rows = [score_session(r, inputs[r["id"]], benchmark) for r in refs.values()]
    return {"schema_version": 1, "scope": "source-time completed-cycle and automatic-set diagnostic; no strict-form or release verdict",
            "benchmark_sha256": lock["benchmark_sha256"], "predictions_sha256": fingerprint(predictions), "run": predictions["run"],
            "evidence_class": benchmark["evidence_class"], "split": split, "sessions": rows, "summary": aggregate(rows),
            "participant_groups": len({r["participant_group"] for r in refs.values()}), "source_groups": len({r["source_group"] for r in refs.values()}),
            "by_exercise": {kind: aggregate([score_session(r, inputs[r["id"]], benchmark, kind) for r in refs.values()]) for kind in sorted(EXERCISES)},
            "by_context": {key: {value: aggregate([r for r in rows if r["context"][key] == value]) for value in sorted({r["context"][key] for r in rows})} for key in next(iter(refs.values()))["context"]},
            "scorer_sha256": SCORER_SHA256, "event_matcher_sha256": MATCHER_SHA256}


def read(path):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "Duplicate JSON key")
            result[key] = value
        return result
    def constant(value): raise BenchmarkError("Non-finite JSON constant")
    return json.loads(path.read_text(), object_pairs_hook=pairs, parse_constant=constant)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    freeze_parser = commands.add_parser("freeze"); freeze_parser.add_argument("annotations", type=Path); freeze_parser.add_argument("--output", type=Path, required=True)
    score_parser = commands.add_parser("score"); score_parser.add_argument("lock", type=Path); score_parser.add_argument("predictions", type=Path)
    score_parser.add_argument("--split", choices=sorted(SPLITS | {"all"}), default="test"); score_parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        inputs = [args.annotations] if args.command == "freeze" else [args.lock, args.predictions]
        require(all(args.output.resolve() != p.resolve() and not (args.output.exists() and args.output.samefile(p)) for p in inputs), "Output would overwrite an immutable input")
        result = freeze(read(args.annotations)) if args.command == "freeze" else score(read(args.lock), read(args.predictions), args.split)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        # A lock is created once. Editing labels requires a distinct lock and
        # invalidates old run bindings; a digest alone cannot prove chronology.
        with args.output.open("x" if args.command == "freeze" else "w") as out:
            json.dump(result, out, indent=2, sort_keys=True, allow_nan=False); out.write("\n")
        return 0
    except (ValueError, OSError, KeyError, TypeError, OverflowError) as exc:
        print(f"Workout benchmark rejected: {exc}", file=sys.stderr); return 2


if __name__ == "__main__":
    raise SystemExit(main())
