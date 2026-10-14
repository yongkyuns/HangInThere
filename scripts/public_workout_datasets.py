#!/usr/bin/env python3
"""Pinned public repetition diagnostics; never substitutes for field qualification.

Countix counts a periodic segment. RepCount annotates video-frame cycle bounds.
Neither contract certifies strict exercise form, athlete identity or hold phases.
"""
from __future__ import annotations

import argparse
import csv
from fractions import Fraction
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import urllib.parse
import urllib.request

try:
    from . import evaluation as ev, inventory, temporal_events
except ImportError:
    import evaluation as ev
    import inventory
    import temporal_events

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SPEC = ROOT / "Evaluation/fixtures/public-workout-pilot.json"
MAX_DOWNLOAD = 100_000_000


def write_new(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x") as stream:
        json.dump(value, stream, indent=2, sort_keys=True, allow_nan=False)
        stream.write("\n")


def countix_rows(payload):
    report = inventory.inventory("countix", payload)
    rows = []
    for section in report["sections"].values():
        for raw in section["selected"]:
            if raw["exercise"] != "pull_up":
                continue
            values = [float(raw[k]) for k in ("kinetics_start", "kinetics_end", "repetition_start", "repetition_end")]
            a, b, c, d = values
            count = int(raw["annotated_count"])
            ev.require(all(ev.number(v) for v in values) and 0 <= a <= c < d <= b and count > 0,
                       "Invalid Countix count/absolute segment")
            rows.append(dict(dataset="countix", video_id=raw["source_id"].removeprefix("youtube:"),
                             split=raw["official_split"], count=count,
                             kinetics_seconds=[a, b], repetition_seconds=[c, d]))
    ev.require(len({(r["split"], r["video_id"], *r["kinetics_seconds"], *r["repetition_seconds"]) for r in rows}) == len(rows),
               "Duplicate Countix target row")
    return report, rows


def repcount_rows(payload, split):
    """Author's OPEN-SET CSV, not the differently partitioned LLSP mirror."""
    reader = csv.DictReader(io.StringIO(payload.decode("utf-8-sig")))
    ev.require({"name", "type", "count", "L1", "L2"}.issubset(reader.fieldnames or []), "Invalid RepCount headers")
    rows = []
    for raw in reader:
        if raw["type"] != "pull_up":
            continue
        ev.require(re.fullmatch(r"[A-Za-z0-9_-]+\.mp4", raw["name"] or ""), "Unsafe RepCount source name")
        count = int(raw["count"])
        ev.require(0 < count <= 151, "Invalid RepCount count")
        values = [float(raw[f"L{i}"]) for i in range(1, count * 2 + 1)]
        ev.require(all(ev.number(v) and v >= 0 and v.is_integer() for v in values), "Invalid native frame marker")
        bounds = [[int(a), int(b)] for a, b in zip(values[::2], values[1::2])]
        ev.require(all(a < b for a, b in bounds) and all(a[1] <= b[0] for a, b in zip(bounds, bounds[1:])),
                   "Reordered/overlapping native cycles")
        ev.require(all(not raw.get(f"L{i}", "").strip() for i in range(count * 2 + 1, 303)),
                   "RepCount count disagrees with extra cycle markers")
        rows.append(dict(dataset="repcount_a", source_name=raw["name"], split=split,
                         split_scheme="author_open_set", count=count, cycle_frames=bounds))
    ev.require(len({r["source_name"] for r in rows}) == len(rows), "Duplicate RepCount target row")
    return rows


def catalog(countix, repcount):
    report, rows = countix_rows(countix)
    native = [r for split, data in repcount.items() for r in repcount_rows(data, split)]
    ev.require(len({r["source_name"] for r in native}) == len(native), "RepCount sources cross author splits")
    return dict(schema_version=1, scope="public metadata; no acquired-media, form or held-out qualification",
                countix_inventory=report, countix_pullups=rows, repcount_open_set_pullups=native,
                repcount_csv_sha256={s: hashlib.sha256(b).hexdigest() for s, b in repcount.items()})


def fetch(url, maximum):
    parsed = urllib.parse.urlparse(url)
    ev.require(parsed.scheme == "https" and parsed.username is None and parsed.password is None,
               "Only anonymous HTTPS publisher requests are supported")
    ev.require(parsed.hostname in {"s3.amazonaws.com", "raw.githubusercontent.com", "datasets-server.huggingface.co"},
               "Unsupported public-data provider")
    request = urllib.request.Request(url, headers={"User-Agent": "HangInThere-public-workout-evaluation/1.0"})
    with urllib.request.urlopen(request, timeout=45) as response:
        data = response.read(maximum + 1)
    ev.require(len(data) <= maximum, "Public input exceeds byte budget")
    return data


def pinned_source(spec, cache, allow_fetch):
    ev.require(re.fullmatch(r"[a-z0-9_-]+", spec["id"]), "Unsafe cache identifier")
    ev.require(ev.integer(spec["bytes"], 1) and spec["bytes"] <= MAX_DOWNLOAD, "Invalid source byte budget")
    ev.require(re.fullmatch(r"[a-f0-9]{64}", spec["sha256"]), "Missing public source pin")
    path = cache / spec["id"]
    if path.exists():
        ev.require(path.is_file() and not path.is_symlink() and path.stat().st_size == spec["bytes"], "Invalid source cache entry")
        data = path.read_bytes()
    else:
        ev.require(allow_fetch, f"Missing public source: {spec['id']}; supply cache or --fetch")
        data = fetch(spec["url"], spec["bytes"])
        ev.require(len(data) == spec["bytes"] and hashlib.sha256(data).hexdigest() == spec["sha256"], "Publisher input changed")
        with path.open("xb") as stream:
            stream.write(data)
    ev.require(len(data) == spec["bytes"] and hashlib.sha256(data).hexdigest() == spec["sha256"], "Cached publisher input changed")
    return data


def mirror_video(case, cache, allow_fetch):
    """Use the mirror as transport; compare its labels against author's CSV."""
    path = cache / (case["id"] + ".mp4")
    if path.exists():
        ev.require(path.is_file() and not path.is_symlink() and path.stat().st_size == case["bytes"], "Invalid mirror cache entry")
        data = path.read_bytes()
    else:
        ev.require(allow_fetch, "Missing RepCount media; supply cache or --fetch")
        mirror = case["mirror"]
        query = urllib.parse.urlencode(dict(dataset="lmms-lab-eval/repcounta-lance", config="default", split=mirror["split"]))
        preview = json.loads(fetch("https://datasets-server.huggingface.co/first-rows?" + query, 2_000_000))
        item = next((r for r in preview["rows"] if r["row_idx"] == mirror["row_index"]), None)
        ev.require(item is not None and not item["truncated_cells"], "Frozen mirror row is unavailable")
        row = item["row"]
        ev.require(row["source_name"] == case["source_name"] and row["action_type"] == "pull_up"
                   and row["split"] == mirror["split"] and row["count"] == case["count"]
                   and json.loads(row["cycle_bounds_json"]) == [v for pair in case["cycle_frames"] for v in pair]
                   and row["video_size_bytes"] == case["bytes"], "Mirror labels differ from frozen author labels")
        url = row["video_blob"]["src"]
        expected = f"/assets/lmms-lab-eval/repcounta-lance/--/{mirror['revision']}/--/default/{mirror['split']}/{mirror['row_index']}/video_blob/"
        ev.require(urllib.parse.urlparse(url).path.startswith(expected), "Mirror snapshot/row changed")
        data = fetch(url, case["bytes"])
        ev.require(len(data) == case["bytes"] and hashlib.sha256(data).hexdigest() == case["sha256"], "Mirror media changed")
        with path.open("xb") as stream:
            stream.write(data)
    ev.require(len(data) == case["bytes"] and hashlib.sha256(data).hexdigest() == case["sha256"], "Cached mirror media changed")
    return data


def source_clock(path):
    result = json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_frames", "-show_streams", "-show_entries",
        "stream=time_base,duration_ts:frame=best_effort_timestamp", "-of", "json", str(path)], text=True))
    base = Fraction(result["streams"][0]["time_base"])
    ticks = [int(f["best_effort_timestamp"]) for f in result["frames"]]
    ev.require(base > 0 and ticks and len(ticks) <= 20_000 and ticks[0] >= 0
               and all(a < b for a, b in zip(ticks, ticks[1:])), "Invalid actual video clock")
    pts = [float(t * base) for t in ticks]
    micros = [t * base.numerator * 1_000_000 // base.denominator for t in ticks]
    ev.require(all(a < b for a, b in zip(micros, micros[1:])), "Android microsecond clock loses ordering")
    end = pts[0] + float(int(result["streams"][0]["duration_ts"]) * base)
    ev.require(end > pts[-1], "Invalid container end clock")
    return pts, micros, end


def timed_labels(case, pts, end):
    if case["dataset"] == "countix":
        a, b = case["repetition_seconds"]
        origin = case["kinetics_seconds"][0]
        window = [pts[0] + a - origin, pts[0] + b - origin]
        ev.require(pts[0] <= window[0] < window[1] <= end + 1e-6, "Video truncates published Countix interval")
        return dict(expected_count=case["count"], count_window_seconds=window, cycle_end_seconds=None,
                    definition="published_periodic_segment_count; strict_form_unverified")
    bounds = case["cycle_frames"]
    ev.require(all(0 <= a < b < len(pts) for a, b in bounds), "Native RepCount markers exceed actual source frames")
    return dict(expected_count=case["count"], count_window_seconds=[pts[0], end],
                cycle_end_seconds=[pts[b] for a, b in bounds],
                definition="published_frame_cycles; start_extreme_return_semantics_unverified")


def prepare(spec_path, cache, output, allow_fetch):
    spec = ev.read_json(spec_path)
    ev.require(spec["schema_version"] == 1 and spec["scope"] == "public_repetition_diagnostic", "Wrong corpus contract")
    ev.require(spec["cases"] and len({c["id"] for c in spec["cases"]}) == len(spec["cases"]), "Empty/duplicate corpus")
    ev.require(not output.exists(), "Use a new preparation directory")
    cache.mkdir(parents=True, exist_ok=True)
    payloads = {s["id"]: pinned_source(s, cache, allow_fetch) for s in spec["sources"]}
    metadata = catalog(payloads["countix_metadata"], {s: payloads["repcount_" + s] for s in ["train", "valid", "test"]})
    output.mkdir(parents=True)
    (output / "videos").mkdir()
    cases = []
    for case in spec["cases"]:
        ev.require(re.fullmatch(r"[a-zA-Z0-9_-]+", case["id"]), "Unsafe case identifier")
        if case["dataset"] == "countix":
            ev.require(any(all(r[k] == case[k] for k in ["video_id", "split", "count", "kinetics_seconds", "repetition_seconds"])
                           for r in metadata["countix_pullups"]), "Frozen Countix row differs from publisher")
            with tarfile.open(fileobj=io.BytesIO(payloads["countix_video_archive"]), mode="r:gz") as archive:
                members = [m for m in archive if m.name == case["member"]]
                ev.require(len(members) == 1 and members[0].isfile() and members[0].size == case["bytes"], "Invalid public video member")
                data = archive.extractfile(members[0]).read(case["bytes"] + 1)
        else:
            ev.require(case["dataset"] == "repcount_a", "A mirror alias is not official Countix")
            ev.require(any(all(r[k] == case[k] for k in ["source_name", "split", "count", "cycle_frames"])
                           for r in metadata["repcount_open_set_pullups"]), "RepCount labels/split differ from author's snapshot")
            data = mirror_video(case, cache, allow_fetch)
        ev.require(len(data) == case["bytes"] and hashlib.sha256(data).hexdigest() == case["sha256"], "Exact public media changed")
        path = output / "videos" / (case["id"] + ".mp4")
        with path.open("xb") as stream:
            stream.write(data)
        pts, micros, end = source_clock(path)
        cases.append(dict(id=case["id"], dataset=case["dataset"], published_split=case["split"],
            published_split_scheme="author_open_set" if case["dataset"] == "repcount_a" else "countix_original",
            source_group="repcount-source:" + case["source_name"] if case["dataset"] == "repcount_a" else "youtube:" + case["video_id"],
            mirror_split=case.get("mirror", {}).get("split"), subject_group=None,
            media=dict(path="videos/" + path.name, sha256=case["sha256"]), frames=len(pts),
            source_pts_seconds=pts, source_pts_microseconds=micros, source_span_seconds=[pts[0], end],
            labels=timed_labels(case, pts, end)))
    manifest = dict(schema_version=1, scope="public_repetition_diagnostic", spec_sha256=ev.digest(spec_path),
                    held_out_qualification=False, source_video_redistribution=False,
                    boundary_tolerance_seconds=0.5, cases=cases)
    write_new(output / "manifest.json", manifest)
    write_new(output / "inventory.json", metadata)
    return manifest


def stage_android(manifest_path, root, output):
    manifest = ev.read_json(manifest_path)
    ev.require(manifest["schema_version"] == 1 and manifest["scope"] == "public_repetition_diagnostic", "Wrong Android corpus")
    ev.require(not output.exists(), "Use a new Android asset directory")
    for case in manifest["cases"]:
        source = ev.asset_path(root, case["media"])
        ev.require(source.is_file() and ev.digest(source) == case["media"]["sha256"], "Android staging input changed")
    output.mkdir(parents=True)
    for case in manifest["cases"]:
        source = ev.asset_path(root, case["media"])
        dest = output / case["media"]["path"]
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, dest)
    shutil.copyfile(manifest_path, output / "manifest.json")


def score(manifest, result, manifest_sha):
    ev.require(result["schema_version"] == manifest["schema_version"] == 1
               and manifest["scope"] == "public_repetition_diagnostic" and not manifest["held_out_qualification"], "Wrong evaluation scope")
    ev.require(result["manifest_sha256"] == manifest_sha, "Native run used different corpus bytes")
    ev.require(result["platform"] in {"apple_host", "android_emulator"}, "Undeclared native platform")
    ev.require(manifest["cases"] and len({c["id"] for c in manifest["cases"]}) == len(manifest["cases"]), "Empty/duplicate corpus")
    ev.require(re.fullmatch(r"[a-f0-9]{40}", result["source_revision"]), "Missing app source revision")
    ev.require(len({r["id"] for r in result["cases"]}) == len(result["cases"]), "Duplicate native result")
    predictions = {r["id"]: r for r in result["cases"]}
    ev.require(predictions.keys() == {c["id"] for c in manifest["cases"]}, "Native run omitted or added corpus cases")
    reports = []
    for case in manifest["cases"]:
        native = predictions[case["id"]]
        ev.require(native["sha256"] == case["media"]["sha256"], "Native media hash mismatch")
        ev.require(native["status"] in {"processed", "setupUnavailable", "interrupted", "failed"}, "Unknown native outcome")
        valid = native["status"] == "processed"
        exercise_correct = native["selected_exercise"] == "pullUp"
        if native["status"] in {"processed", "setupUnavailable"}:
            ev.require(native["frames"] == case["frames"] and native["source_clock_verified"] is True,
                       "Native EOF/frame/source-clock verification failed")
        a, b = case["labels"]["count_window_seconds"]
        metrics = {}
        for name in ["acceptance_seconds", "completed_seconds"]:
            events = native[name]
            ev.require(isinstance(events, list) and all(ev.number(t) and case["source_span_seconds"][0] <= t < case["source_span_seconds"][1] for t in events)
                       and events == sorted(events), "Invalid source event clock")
            ev.require(native["status"] in {"processed", "interrupted"} or not events,
                       "Failed/setup-unavailable replay cannot claim completed measurements")
            selected = [t for t in events if a <= t <= b]
            expected = case["labels"]["expected_count"]
            metrics[name] = dict(observed=len(selected), expected=expected, signed_error=len(selected) - expected,
                                 absolute_error=abs(len(selected) - expected), exact=valid and exercise_correct and len(selected) == expected)
        ends = case["labels"]["cycle_end_seconds"]
        timing = temporal_events.match_events(case["source_span_seconds"], [[t, t] for t in ends], [],
            manifest["boundary_tolerance_seconds"], native["completed_seconds"]) if ends is not None else None
        reports.append(dict(id=case["id"], dataset=case["dataset"], published_split=case["published_split"],
                            status=native["status"], exercise_correct=exercise_correct, metrics=metrics, cycle_end_timing_diagnostic=timing))
    def summarize(rows):
        return {name: dict(exact_clips=sum(r["metrics"][name]["exact"] for r in rows), clips=len(rows),
            expected_repetitions=sum(r["metrics"][name]["expected"] for r in rows),
            observed_events=sum(r["metrics"][name]["observed"] for r in rows),
            mean_absolute_error=sum(r["metrics"][name]["absolute_error"] for r in rows) / len(rows))
            for name in ["acceptance_seconds", "completed_seconds"]}
    return dict(schema_version=1, scope="published repetition diagnostics; no strict-form, phase/hold, field or physical-device qualification",
                manifest_sha256=manifest_sha, run={k: result[k] for k in ["platform", "backend", "source_revision", "exercise_selection"]},
                all_inputs_processed=all(r["status"] in {"processed", "setupUnavailable"} for r in reports),
                all_exact_completed_counts=all(r["metrics"]["completed_seconds"]["exact"] for r in reports),
                summary=summarize(reports),
                by_dataset={d: summarize([r for r in reports if r["dataset"] == d]) for d in sorted({r["dataset"] for r in reports})},
                cases=reports)


def merge_apple_shards(manifest, reports, manifest_sha, report_digests):
    """Union verified assignments without rebinding or changing measurements."""
    ev.require(reports and len(reports) == len(report_digests), "Missing Apple shards/digests")
    keys = ["schema_version", "manifest_sha256", "source_revision", "platform", "backend",
            "counter_policy_version", "request_revision", "exercise_selection"]
    first = reports[0]
    ev.require(first["platform"] == "apple_host" and first["manifest_sha256"] == manifest_sha,
               "Wrong Apple corpus/platform")
    count = len(reports)
    ev.require(all(ev.integer(r["shard_index"], 0) and ev.integer(r["shard_count"], 1) for r in reports)
               and all(re.fullmatch(r"[a-f0-9]{64}", d) for d in report_digests), "Invalid Apple shard provenance")
    ev.require({r["shard_index"] for r in reports} == set(range(count)), "Missing/duplicate Apple shard")
    cases = {}
    for report in reports:
        ev.require(all(report[k] == first[k] for k in keys) and report["shard_count"] == count,
                   "Apple source/backend/policy/shard contract differs")
        expected = {c["id"] for i, c in enumerate(manifest["cases"]) if i % count == report["shard_index"]}
        rows = report["cases"]
        ev.require(len(rows) == len(expected) and {r["id"] for r in rows} == expected,
                   "Apple shard omitted, duplicated or misassigned a case")
        cases.update({r["id"]: r for r in rows})
    merged = {k: first[k] for k in keys}
    merged["cases"] = [cases[c["id"]] for c in manifest["cases"]]
    merged["shards"] = sorted([dict(index=r["shard_index"], native_report_sha256=d)
                              for r, d in zip(reports, report_digests)], key=lambda s: s["index"])
    # Validate source/media/EOF/events before writing a combined audit artifact.
    # Failed cases remain failed; this never manufactures full-source success.
    score(manifest, merged, manifest_sha)
    return merged


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    intake = commands.add_parser("prepare")
    intake.add_argument("--spec", type=Path, default=DEFAULT_SPEC)
    intake.add_argument("--cache", type=Path, required=True)
    intake.add_argument("--output", type=Path, required=True)
    intake.add_argument("--fetch", action="store_true")
    grading = commands.add_parser("score")
    grading.add_argument("manifest", type=Path)
    grading.add_argument("native_report", type=Path)
    grading.add_argument("--output", type=Path, required=True)
    staging = commands.add_parser("android-assets")
    staging.add_argument("manifest", type=Path)
    staging.add_argument("--root", type=Path, required=True)
    staging.add_argument("--output", type=Path, required=True)
    merging = commands.add_parser("merge-apple")
    merging.add_argument("manifest", type=Path)
    merging.add_argument("native_reports", type=Path, nargs="+")
    merging.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "prepare":
            manifest = prepare(args.spec, args.cache, args.output, args.fetch)
            print(f"Prepared {len(manifest['cases'])} pinned original videos; held-out qualification is false.")
        elif args.command == "android-assets":
            stage_android(args.manifest, args.root, args.output)
        elif args.command == "merge-apple":
            report = merge_apple_shards(ev.read_json(args.manifest), [ev.read_json(p) for p in args.native_reports],
                                       ev.digest(args.manifest), [ev.digest(p) for p in args.native_reports])
            write_new(args.output, report)
        else:
            report = score(ev.read_json(args.manifest), ev.read_json(args.native_report), ev.digest(args.manifest))
            write_new(args.output, report)
            print(json.dumps(report["summary"]))
            if not report["all_inputs_processed"]:
                return 1
        return 0
    except (OSError, ValueError, KeyError, StopIteration, tarfile.TarError, subprocess.CalledProcessError) as error:
        print(f"Public workout diagnostic failed: {error}")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
