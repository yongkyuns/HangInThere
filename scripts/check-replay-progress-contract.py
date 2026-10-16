#!/usr/bin/env python3
"""Check authored Kotlin/JVM rows through the scorer; no native or accuracy qualification."""
import argparse
import hashlib
import json
import subprocess

import public_workout_datasets as public


def check(rows):
    assert len(rows) == 2 and {r["id"] for r in rows} == {"returned", "accepted_unreturned"}
    manifest = dict(schema_version=1, scope="public_repetition_diagnostic", held_out_qualification=False,
                    boundary_tolerance_seconds=.5, cases=[])
    cases = []
    for row in rows:
        ident, progress = row["id"], row["progress"]
        returned = ident == "returned"
        assert progress["status"] == "interrupted" and progress["frames"] == (6 if returned else 4)
        assert progress["acceptance_seconds"] == [12.45]
        assert progress["completed_seconds"] == ([12.75] if returned else [])
        manifest["cases"].append(dict(id=ident, dataset="authored_contract", published_split="synthetic",
            frames=7, source_pts_seconds=[12.0, 12.15, 12.3, 12.45, 12.6, 12.75, 12.9],
            source_span_seconds=[12.0, 13.0], media=dict(sha256="a" * 64),
            labels=dict(expected_count=1, count_window_seconds=[12.0, 13.0], cycle_end_seconds=None)))
        cases.append(dict(id=ident, sha256="a" * 64, selected_exercise="pullUp", **progress))
    digest = hashlib.sha256(json.dumps(manifest, sort_keys=True).encode()).hexdigest()
    result = dict(schema_version=1, manifest_sha256=digest, source_revision=subprocess.check_output(
        ["git", "rev-parse", "HEAD"], text=True).strip(), platform="android_emulator",
        backend="authored JVM wire-contract fixture; no Android or vision execution",
        exercise_selection="authored shared-runtime pullUp", progress_evidence_version=1, cases=cases)
    scored = public.score(manifest, result, digest)
    assert not scored["all_inputs_processed"] and not scored["all_exact_completed_counts"]
    assert scored["summary"]["completed_seconds"]["observed_events"] == 1
    return dict(scope="authored Kotlin/JVM-to-Python wire contract; no native accuracy qualification",
                cases=2, accepted=2, completed=1, all_inputs_processed=False, all_exact_completed_counts=False)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("rows")
    args = parser.parse_args()
    with open(args.rows) as f:
        print(json.dumps(check(json.load(f)), sort_keys=True))
