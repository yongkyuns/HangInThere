#!/usr/bin/env python3
"""Score policy-9 selected replay against unchanged policy-8 temporal labels.

This is an explicit contract, not a version alias for the legacy scorer. Source,
frame, clock, routing, visibility and event matching checks are shared with that
scorer. Completing replay is not movement accuracy or held-out/form validation.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import sys


COUNTER_POLICY_VERSION = 9
REFERENCE_POLICY_VERSION = 8
COUNT_METHODS = {"pullUp": "bothUsableArms", "dip": "selectedGripWithAvailableArms"}


def validate_policy(native):
    """Require the production v9 contract; never rewrite caller evidence."""
    if (type(native.get("counter_policy_version")) is not int
            or native["counter_policy_version"] != COUNTER_POLICY_VERSION
            or type(native.get("replay_configuration_version")) is not int
            or native["replay_configuration_version"] != 1
            or "counting_method_configuration" in native):
        raise ValueError("Expected policy-9 production replay configuration 1")
    for row in native.get("cases", []):
        expected = COUNT_METHODS.get(row.get("selected_exercise"))
        if expected is None:
            raise ValueError("Unsupported policy-9 selected exercise")
        # Current production reports omit per-case method fields. If present,
        # both must agree; candidate or legacy declarations cannot pass as v9.
        if "count_method" in row or "count_policy_version" in row:
            if (row.get("count_method") != expected
                    or type(row.get("count_policy_version")) is not int
                    or row["count_policy_version"] != COUNTER_POLICY_VERSION):
                raise ValueError("Wrong policy-9 case method/version")


def _shared():
    try:
        from . import temporal_native_replay as replay
    except ImportError:
        import temporal_native_replay as replay
    return replay


def score(reference, manifest, native, manifest_sha, reference_sha):
    replay = _shared()
    replay._source_header(reference, manifest, native, manifest_sha, reference_sha)
    validate_policy(native)
    # _score_cases still requires the original reference policy 8 and performs
    # every original evidence check and event match without changing its inputs.
    result = replay._score_cases(reference, manifest, native, manifest_sha, reference_sha)
    result.update(counter_policy_version=COUNTER_POLICY_VERSION,
                  reference_counter_policy_version=REFERENCE_POLICY_VERSION,
                  replay_configuration_version=1,
                  scope="policy-9 selected-exercise production replay against unchanged policy-8 annotations; automatic contours, not live Auto identity/readiness or held-out/form qualification")
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ["reference", "manifest", "native", "output"]:
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--require-exact-events", action="store_true")
    args = parser.parse_args(argv)
    replay = _shared()
    ev = replay.ev
    try:
        ev.require(not args.output.exists(), "Output already exists")
        native = ev.read_json(args.native)
        provenance = replay.compiled_provenance(args.native, native)
        result = score(ev.read_json(args.reference), ev.read_json(args.manifest), native,
                       ev.digest(args.manifest), ev.digest(args.reference))
        result.update(native_report_sha256=ev.digest(args.native),
                      scorer_sha256=ev.digest(Path(__file__)),
                      shared_scorer_sha256=ev.digest(Path(replay.__file__)),
                      reference_validator_sha256=ev.digest(Path(replay.temporal_references.__file__)),
                      event_matcher_sha256=ev.digest(Path(replay.temporal_events.__file__)),
                      compiled_native_provenance=provenance)
        ev.write_json(args.output, result)
        for row in result["clips"]:
            m = row["event_metrics"]
            print(f"{row['id']} ({row['exercise']}): {row['status']}, matched={m['true_positives']}, extra={m['false_positives']}, missed={m['false_negatives']}")
        if not result["all_events_matched"]:
            print("Selected-replay diagnostics contain misses/unavailable evidence; accuracy remains unqualified.")
            return 3 if args.require_exact_events else 0
        return 0
    except (ValueError, KeyError, TypeError, OSError) as failure:
        print(f"Policy-9 selected-replay evaluation incomplete: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
