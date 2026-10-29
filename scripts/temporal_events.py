"""Source-time event matching shared by clip and whole-workout evaluation.

Reference windows represent independently reviewed timing uncertainty. Matching
is chronological, one-to-one and maximum-cardinality; duplicates remain extras.
"""
from __future__ import annotations

try:
    from . import evaluation as ev
except ImportError:  # Standalone CLI callers add scripts/ to sys.path.
    import evaluation as ev


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



def score_events(ref, events):
    """Legacy clip contract: positive-width, strictly disjoint windows."""
    span = ref["span_seconds"]
    return match_events(span, intervals(ref["events"], span),
                        intervals(ref["ungradable_intervals"], span, reason=True),
                        ref["tolerance_seconds"], events)


def match_events(span, windows, excluded, tolerance, events):
    """Chronological one-to-one maximum-cardinality matching of ordered windows.

    Each event can match once. Extra detections, including exact duplicates,
    remain false positives. Negative/no-event intervals remain in evaluation.
    """
    ev.require(len(span) == 2 and all(ev.number(x) for x in span) and span[0] < span[1], "Invalid source span")
    # Point windows support reviewed phase-transition timestamps. Monotone
    # endpoints (including overlapping uncertainty windows) retain the greedy
    # matcher's maximum-cardinality guarantee. Legacy validation stays stricter.
    ev.require(all(len(w) == 2 and all(ev.number(t) for t in w) and span[0] <= w[0] <= w[1] <= span[1] for w in windows)
               and all(a[0] <= b[0] and a[1] <= b[1] for a, b in zip(windows, windows[1:])), "Invalid ordered event windows")
    ev.require(all(len(w) == 2 and all(ev.number(t) for t in w) and span[0] <= w[0] < w[1] <= span[1] for w in excluded)
               and all(a[1] <= b[0] for a, b in zip(excluded, excluded[1:])), "Invalid excluded intervals")
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
