#!/usr/bin/env python3
"""Inventory official metadata only. This never downloads workout videos or approves media rights."""
from __future__ import annotations
import argparse
from collections import Counter
import csv
import hashlib
import io
import json
from pathlib import Path
import tarfile
import urllib.request

SOURCES = {
    "haa4d": "https://cse.hkust.edu.hk/haa4d/images/all_data.csv",
    "countix": "https://s3.amazonaws.com/kinetics/700_2020/annotations/countix.tar.gz",
}
LIMIT = 10 * 1024 * 1024


def category(name):
    key = name.strip().lower().replace("_", " ").replace("-", " ")
    return {"pull ups": "pull_up", "pull up": "pull_up", "pullups": "pull_up",
            "bench dip": "bench_dip", "bench dips": "bench_dip",
            "dips": "dip_unverified", "dip": "dip_unverified"}.get(key)


def parse_csv(dataset, payload, split=None):
    reader = csv.DictReader(io.StringIO(payload.decode("utf-8-sig")))
    required = {"class_name", "videoname", "length"} if dataset == "haa4d" else {
        "video_id", "kinetics_start", "kinetics_end", "repetition_start", "repetition_end", "count"}
    if not required.issubset(set(reader.fieldnames or [])):
        raise ValueError(f"Unrecognized {dataset} CSV headers: {reader.fieldnames}")
    counts, selected, total = Counter(), [], 0
    for row in reader:
        # The official Countix archive may omit action classes.
        # Preserve that missingness; do not infer labels from filenames or counts.
        label = row.get("class_name" if dataset == "haa4d" else "class") or "__unclassified__"
        counts[label] += 1
        total += 1
        exercise = category(label)
        if not exercise:
            continue
        item = {"exercise": exercise, "original_label": label, "official_split": split,
                "media_status": "not_acquired", "permissions": "pending_review"}
        if dataset == "haa4d":
            length = int(row["length"])
            if length <= 0:
                raise ValueError("Invalid sequence length")
            item.update(source_id=row["videoname"], frames=length, timebase="frame_index")
        else:
            # Retain authored intervals verbatim. They are not per-rep events or form labels.
            item.update(source_id="youtube:" + row["video_id"],
                        kinetics_start=row["kinetics_start"], kinetics_end=row["kinetics_end"],
                        repetition_start=row["repetition_start"], repetition_end=row["repetition_end"],
                        annotated_count=row["count"])
        selected.append(item)
    return {"metadata_rows": total, "unclassified_rows": counts["__unclassified__"],
            "class_counts": dict(counts), "selected": selected,
            "csv_sha256": hashlib.sha256(payload).hexdigest()}


def inventory(dataset, payload):
    if dataset == "haa4d":
        sections = {"all_data.csv": parse_csv(dataset, payload)}
    else:
        sections, expanded = {}, 0
        with tarfile.open(fileobj=io.BytesIO(payload), mode="r:gz") as archive:
            for member in archive:
                if not member.isfile() or not member.name.endswith(".csv"):
                    continue
                expanded += member.size
                if expanded > LIMIT or member.size > LIMIT:
                    raise ValueError("Metadata archive expansion exceeds limit")
                name = Path(member.name).name
                if name not in {"countix_train.csv", "countix_val.csv", "countix_test.csv"}:
                    raise ValueError(f"Unexpected Countix CSV: {name}")
                if name in sections:
                    raise ValueError("Duplicate metadata file")
                handle = archive.extractfile(member)
                if handle is None:
                    raise ValueError("Unreadable archive member")
                sections[name] = parse_csv(dataset, handle.read(LIMIT + 1), name.removesuffix(".csv").removeprefix("countix_"))
        if len(sections) != 3:
            raise ValueError("Missing Countix splits")
    selected = [x for section in sections.values() for x in section["selected"]]
    return {"schema_version": 1, "dataset": dataset, "source": SOURCES[dataset],
            "metadata_sha256": hashlib.sha256(payload).hexdigest(),
            "scope": "metadata inventory, not downloaded/rights-cleared/evaluated media",
            "metadata_rows": sum(x["metadata_rows"] for x in sections.values()),
            "unclassified_rows": sum(x["unclassified_rows"] for x in sections.values()),
            "selected_counts": dict(Counter(x["exercise"] for x in selected)),
            "selected_frames": {key: sum(x.get("frames", 0) for x in selected if x["exercise"] == key)
                                for key in sorted({x["exercise"] for x in selected if "frames" in x})},
            "sections": sections}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", choices=SOURCES)
    parser.add_argument("--input", type=Path, help="Local official metadata CSV/archive; no network needed")
    parser.add_argument("--fetch", action="store_true", help="Acquire official METADATA only")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if bool(args.input) == args.fetch:
        parser.error("Choose exactly one of --input or --fetch")
    try:
        if args.fetch:
            request = urllib.request.Request(SOURCES[args.dataset], headers={"User-Agent": "HangInThere-metadata-inventory/1.0"})
            with urllib.request.urlopen(request, timeout=45) as response:
                payload = response.read(LIMIT + 1)
        else:
            with args.input.open("rb") as stream:
                payload = stream.read(LIMIT + 1)
        if len(payload) > LIMIT:
            raise ValueError("Metadata exceeds limit")
        report = inventory(args.dataset, payload)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
        print(json.dumps({k: report[k] for k in ("dataset", "metadata_rows", "unclassified_rows", "selected_counts", "selected_frames", "metadata_sha256")}))
        return 0
    except (OSError, ValueError, tarfile.TarError, KeyError) as error:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps({"dataset": args.dataset, "status": "inventory_failed", "error": str(error)}) + "\n")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
